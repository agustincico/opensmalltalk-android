/* sqUnixAndroidDisplay.c -- Squeak/OpenSmalltalk display driver for Android.
 *
 * Renders the image's Display straight into an ANativeWindow (the buffer behind
 * a SurfaceView) and feeds it input posted from the Android UI thread. No X
 * server, no SDL, no support libraries: the only NEEDED beyond libc are
 * libandroid.so and liblog.so, both part of the platform.
 *
 * It is an ordinary Unix display module -- `SqDisplayDefine` + `SqModuleDefine`,
 * built as vm-display-android.so and selected with `-vm-display-android`, exactly
 * like vm-display-X11 -- so it needs no change to the VM itself.
 *
 * THREADS.  Everything named display_* runs on the VM thread. Everything named
 * sqAndroid* is called from the Android UI thread by the app's JNI glue. The two
 * meet in exactly two places, each guarded by its own mutex:
 *
 *   - the window pointer (winMutex): surfaceDestroyed must not pull the window
 *     out from under a blit in progress, so it blocks until the blit finishes.
 *   - the event ring (queueMutex): the UI thread appends raw events; the VM
 *     thread drains them in ioProcessEvents and only there turns them into
 *     Squeak events, so sqUnixEvent.c's buffer stays single-threaded as designed.
 *
 * A self-pipe registered with aio lets a touch cut short the VM's idle sleep.
 *
 * Author: Agustin Martinez.  Licence: MIT, as the rest of this repository.
 */

#include "sq.h"
#include "sqMemoryAccess.h"
#include "sqUnixMain.h"
#include "sqUnixGlobals.h"
#include "sqaio.h"
#include "SqDisplay.h"

#include <android/native_window.h>
#include <android/log.h>

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "sqUnixEvent.c"

#define LOG_TAG "SQUEAK_VM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

/* Android's WINDOW_FORMAT_RGBX_8888; the alpha byte is ignored, which is what we
   want -- Squeak Forms routinely carry a zero alpha in an opaque display. */
#define SQ_WINDOW_FORMAT_RGBX_8888 2

/*** state shared with the UI thread ***/

static pthread_mutex_t winMutex   = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t queueMutex = PTHREAD_MUTEX_INITIALIZER;

static ANativeWindow *nativeWindow = 0;
static int windowW = 0, windowH = 0;	/* what the image sees as the screen  */
static int surfaceW = 0, surfaceH = 0;	/* what the surface physically is     */
static int geometryDirty = 0;		/* setBuffersGeometry pending          */

static int wakePipe[2] = { -1, -1 };

/* raw events, produced by the UI thread, consumed in display_ioProcessEvents */
enum { AEV_MOUSE = 1, AEV_KEY, AEV_WHEEL, AEV_RESIZE, AEV_DROP };

typedef struct {
  int  type;
  int  a, b, c, d;
  char *text;				/* AEV_DROP: strdup'ed path, we free it */
} AndroidEvent;

#define AEQ_SIZE 256			/* must be a power of two */
static AndroidEvent aeq[AEQ_SIZE];
static int aeqIn = 0, aeqOut = 0;
static int aeqDropped = 0;

/* clipboard: the app pushes the system clipboard in, and we hand writes back
   out through a callback it registers, so this file stays free of JNI. */
static char *clipboardText = 0;
static void (*clipboardWriter)(const char *utf8) = 0;

/* Coalesced damage. Posting a buffer presents a frame and blocks until the
   compositor frees one, so a post per damage rectangle throttles the image to
   one rectangle per refresh -- measured at 29 ms for a 200x40 blit. Instead the
   damage is unioned here and posted once per turn of the VM's event loop, which
   is one frame's worth of drawing. (Holding the Display's bits pointer between
   calls is what the X11 driver does too: it keeps stDisplayBits to repaint on
   expose.) */
static const uint32_t *pendingBits = 0;
static int pendingW = 0, pendingH = 0;
static int dirtyL = 0, dirtyR = 0, dirtyT = 0, dirtyB = 0;
static int havePending = 0;
static unsigned lastPostMSecs = 0;

/* Never let drawing that does not return to the event loop hold the screen. */
#define MAX_UNPOSTED_MSECS 16

static void postPendingFrame(void);	/* defined with the drawing code below */


/* dropped files, read by the VM through these two globals (see SqDisplay.h) */
static char *dropFileName = 0;
static char *dropFileNames[1];

/*** helpers ***/

static void
wakeVM(void)
{
  if (wakePipe[1] >= 0) {
    char byte = 'x';
    ssize_t ignored = write(wakePipe[1], &byte, 1);
    (void)ignored;
  }
}

static void
enqueue(int type, int a, int b, int c, int d, char *text)
{
  pthread_mutex_lock(&queueMutex);
  {
    int next = (aeqIn + 1) & (AEQ_SIZE - 1);
    if (next == aeqOut) {		/* full: drop, but say so once in a while */
      if ((aeqDropped++ % 64) == 0)
	LOGW("android display: event queue full, %d dropped", aeqDropped);
      free(text);
    }
    else {
      aeq[aeqIn].type = type;
      aeq[aeqIn].a = a;  aeq[aeqIn].b = b;  aeq[aeqIn].c = c;  aeq[aeqIn].d = d;
      aeq[aeqIn].text = text;
      aeqIn = next;
    }
  }
  pthread_mutex_unlock(&queueMutex);
  wakeVM();
}

/*** the API the app's JNI glue calls (UI thread) ***/

/* Hand over the ANativeWindow for the SurfaceView, or 0 when it is destroyed.
   Blocks while a blit is in progress, so the VM never draws into a dead buffer. */
void
sqAndroidSetNativeWindow(void *window, int width, int height)
{
  pthread_mutex_lock(&winMutex);
  if (nativeWindow && nativeWindow != (ANativeWindow *)window)
    ANativeWindow_release(nativeWindow);
  nativeWindow = (ANativeWindow *)window;
  if (nativeWindow) {
    ANativeWindow_acquire(nativeWindow);
    surfaceW = width;
    surfaceH = height;
    if (!windowW || !windowH) { windowW = width; windowH = height; }
    geometryDirty = 1;
  }
  pthread_mutex_unlock(&winMutex);
  LOGI("android display: surface %s %dx%d", window ? "attached" : "detached", width, height);
  if (window)
    enqueue(AEV_RESIZE, windowW, windowH, 0, 0, 0);
}

/* The size the IMAGE should believe the screen is. Pass the physical size for
   1:1, or a smaller one to have SurfaceFlinger scale the buffer up in hardware
   -- which is a zoom that costs the VM fewer pixels instead of more. */
void
sqAndroidSetLogicalSize(int width, int height)
{
  if (width < 1 || height < 1) return;
  pthread_mutex_lock(&winMutex);
  if (windowW != width || windowH != height) {
    windowW = width;
    windowH = height;
    geometryDirty = 1;
  }
  pthread_mutex_unlock(&winMutex);
  enqueue(AEV_RESIZE, width, height, 0, 0, 0);
}

void sqAndroidPostMouseEvent(int x, int y, int buttons, int modifiers)
{ enqueue(AEV_MOUSE, x, y, buttons, modifiers, 0); }

void sqAndroidPostKeyEvent(int keyCode, int pressCode, int modifiers, int ucs4)
{ enqueue(AEV_KEY, keyCode, pressCode, modifiers, ucs4, 0); }

void sqAndroidPostWheelEvent(int dx, int dy)
{ enqueue(AEV_WHEEL, dx, dy, 0, 0, 0); }

void sqAndroidPostDropFile(const char *path)
{ if (path) enqueue(AEV_DROP, 0, 0, 0, 0, strdup(path)); }

void
sqAndroidSetClipboardText(const char *utf8)
{
  pthread_mutex_lock(&queueMutex);
  free(clipboardText);
  clipboardText = utf8 ? strdup(utf8) : 0;
  pthread_mutex_unlock(&queueMutex);
}

void sqAndroidSetClipboardWriter(void (*writer)(const char *utf8))
{ clipboardWriter = writer; }

/* Where the image thinks the screen is, so the app can map touches to it. */
void
sqAndroidGetLogicalSize(int *width, int *height)
{
  pthread_mutex_lock(&winMutex);
  if (width)  *width  = windowW;
  if (height) *height = windowH;
  pthread_mutex_unlock(&winMutex);
}

/*** draining the queue (VM thread) ***/

static void
deliver(AndroidEvent *e)
{
  switch (e->type) {

  case AEV_MOUSE:
    mousePosition.x = e->a;
    mousePosition.y = e->b;
    buttonState     = e->c;
    modifierState   = e->d;
    recordMouseEvent();
    break;

  case AEV_KEY:
    modifierState = e->c;
    recordKeyboardEvent(e->a, e->b, e->c, e->d);
    break;

  case AEV_WHEEL:
    recordMouseWheelEvent(e->a, e->b);
    break;

  case AEV_RESIZE:
    recordWindowEvent(WindowEventMetricChange, 0, 0, e->a, e->b, 1);
    break;

  case AEV_DROP:
    free(dropFileName);
    dropFileName     = e->text;		/* ownership moves here */
    e->text          = 0;
    dropFileNames[0] = dropFileName;
    uxDropFileNames  = dropFileNames;
    uxDropFileCount  = 1;
    recordDragEvent(SQDragDrop, 1);
    break;
  }
}

static void
drainQueue(void)
{
  for (;;) {
    AndroidEvent e;
    pthread_mutex_lock(&queueMutex);
    if (aeqIn == aeqOut) { pthread_mutex_unlock(&queueMutex); return; }
    e = aeq[aeqOut];
    aeq[aeqOut].text = 0;
    aeqOut = (aeqOut + 1) & (AEQ_SIZE - 1);
    pthread_mutex_unlock(&queueMutex);
    deliver(&e);
    free(e.text);
  }
}

static void
wakeHandler(int fd, void *data, int flags)
{
  char buf[64];
  while (read(fd, buf, sizeof(buf)) > 0)
    ;
  aioHandle(fd, wakeHandler, AIO_RX);	/* aio disarms after each call */
}

/*** display primitives ***/

static char *display_winSystemName(void) { return "Android"; }

static void
display_winInit(void)
{
  if (pipe(wakePipe) == 0) {
    fcntl(wakePipe[0], F_SETFL, O_NONBLOCK);
    fcntl(wakePipe[1], F_SETFL, O_NONBLOCK);
  }
  else {
    wakePipe[0] = wakePipe[1] = -1;
    LOGW("android display: no wake pipe (%s); input will wait for the poll", strerror(errno));
  }
}

static void
display_winOpen(int argc, char *dropFiles[])
{
  if (wakePipe[0] >= 0) {
    aioEnable(wakePipe[0], 0, AIO_EXT);
    aioHandle(wakePipe[0], wakeHandler, AIO_RX);
  }
  LOGI("android display: open, screen %dx%d", windowW, windowH);
}

static void
display_winExit(void)
{
  pthread_mutex_lock(&winMutex);
  if (nativeWindow) { ANativeWindow_release(nativeWindow); nativeWindow = 0; }
  pthread_mutex_unlock(&winMutex);
}

static void  display_winSetName(char *title)			{ }
static long  display_winImageFind(char *imageName, int size)	{ return 0; }
static void  display_winImageNotFound(void)			{ }

static sqInt display_ioBeep(void)				{ return 0; }

static sqInt
display_ioFormPrint(sqInt b, sqInt w, sqInt h, sqInt d, double hs, double vs, sqInt ls)
{ return 0; }

static sqInt
display_ioRelinquishProcessorForMicroseconds(sqInt microSeconds)
{
  postPendingFrame();		/* about to idle: never sleep on a stale screen */
  aioSleepForUsecs(microSeconds);
  return 0;
}

static sqInt
display_ioProcessEvents(void)
{
  postPendingFrame();		/* one frame's worth of damage has accumulated */
  drainQueue();
  aioPoll(0);
  return 0;
}

static double display_ioScreenScaleFactor(void) { return nan("MISS"); }
static sqInt  display_ioScreenDepth(void)	{ return 32; }

static sqInt
display_ioScreenSize(void)
{
  int w, h;
  pthread_mutex_lock(&winMutex);
  w = windowW;  h = windowH;
  pthread_mutex_unlock(&winMutex);
  if (w < 1 || h < 1) {
    int sws = getSavedWindowSize();
    return sws ? sws : ((640 << 16) | 480);
  }
  {  /* What the image is told the screen is; logged once per change. This is how
        a zoom is confirmed to have reached the image rather than just the buffer. */
    static int lastW = 0, lastH = 0;
    if (w != lastW || h != lastH) { lastW = w; lastH = h;
      LOGI("android display: ioScreenSize -> %dx%d", w, h); }
  }
  return (w << 16) | (h & 0xFFFF);
}

/* We render 32bpp only. Reporting just that keeps the image from picking a depth
   we would have to expand by hand on every frame; every modern Cuis/Squeak image
   is happy at 32. */
static sqInt display_ioHasDisplayDepth(sqInt depth)	{ return depth == 32; }

static sqInt
display_ioSetDisplayMode(sqInt w, sqInt h, sqInt d, sqInt fullscreenFlag)
{
  setSavedWindowSize((w << 16) + (h & 0xFFFF));
  return 1;
}

static sqInt display_ioSetFullScreen(sqInt fullScreen)	{ return 1; }	/* always is */
static sqInt display_ioForceDisplayUpdate(void)		{ postPendingFrame(); return 0; }

static sqInt display_ioSetCursorWithMask(sqInt bits, sqInt mask, sqInt x, sqInt y)  { return 0; }
static sqInt display_ioSetCursorARGB(sqInt bits, sqInt w, sqInt h, sqInt x, sqInt y) { return 0; }

/* 0xAARRGGBB (Squeak, little-endian memory B G R A) -> R G B X, which is what
   ANativeWindow's RGBX_8888 buffer wants: swap the red and blue bytes. */
static inline void
copyRowSwizzled(const uint32_t *src, uint32_t *dst, int n)
{
  int i;
  for (i = 0; i < n; ++i) {
    uint32_t p = src[i];
    dst[i] = (p & 0xFF00FF00u) | ((p >> 16) & 0x000000FFu) | ((p & 0x000000FFu) << 16);
  }
}

/* Copy the accumulated damage into the window and present it. VM thread only. */
static void
postPendingFrame(void)
{
  ANativeWindow_Buffer buf;
  ARect dirty;
  const uint32_t *bits;
  int l, r, t, b, y, w, h;

  pthread_mutex_lock(&winMutex);
  if (!havePending || !nativeWindow) { pthread_mutex_unlock(&winMutex); return; }

  bits = pendingBits;  w = pendingW;  h = pendingH;
  l = dirtyL;  r = dirtyR;  t = dirtyT;  b = dirtyB;
  havePending = 0;

  if (geometryDirty) {
    ANativeWindow_setBuffersGeometry(nativeWindow, windowW, windowH,
				     SQ_WINDOW_FORMAT_RGBX_8888);
    geometryDirty = 0;
  }

  /* Counter-intuitive but measured: ANativeWindow_lock preserves everything
     OUTSIDE the dirty rectangle by copying it from the previously posted buffer,
     so a small damage rectangle makes the system copy nearly the whole screen --
     a 200x40 update cost MORE than a full-screen one (28 ms vs 21 ms). Since the
     complete Display bits are in hand anyway, claim the whole surface (nothing to
     preserve, no copy-back) and repaint it ourselves. The damage rectangle above is
     still worth accumulating: it is what tells us there is anything to post at all. */
  l = 0; t = 0; r = w; b = h;

  dirty.left = l;  dirty.right = r;  dirty.top = t;  dirty.bottom = b;
  if (ANativeWindow_lock(nativeWindow, &buf, &dirty) != 0) {
    pthread_mutex_unlock(&winMutex);
    return;
  }

  /* lock() may widen the damage -- honour what it gives back, clipped to both
     the buffer and the Display we were handed (either can be a frame ahead). */
  l = dirty.left < 0 ? 0 : dirty.left;
  t = dirty.top  < 0 ? 0 : dirty.top;
  r = dirty.right  > buf.width  ? buf.width  : dirty.right;
  b = dirty.bottom > buf.height ? buf.height : dirty.bottom;
  if (r > w) r = w;
  if (b > h) b = h;

  for (y = t; y < b; ++y)
    copyRowSwizzled(bits + (size_t)y * w + l,
		    (uint32_t *)buf.bits + (size_t)y * buf.stride + l,
		    r - l);

  /* Whatever the Display does not cover is cleared rather than left alone.
     Buffers come back recycled, so an uncovered strip would show a piece of some
     earlier frame, and it would not repair itself: the next post writes the same
     rectangle and leaves the same strip. The surface is bigger than the Display
     whenever the two disagree about size, which is what a rotation or a zoom
     change looks like until the image adopts the new one. Defensive — that window
     has not been caught showing garbage, it is just too cheap not to close. */
  if (b < buf.height || r < buf.width) {
    int cw = buf.width - r;
    for (y = 0; y < buf.height; ++y) {
      uint32_t *row = (uint32_t *)buf.bits + (size_t)y * buf.stride;
      if (y < t || y >= b)
	memset(row, 0, (size_t)buf.width * 4);	/* whole row is outside */
      else if (cw > 0)
	memset(row + r, 0, (size_t)cw * 4);	/* just the right-hand strip */
    }
  }

  ANativeWindow_unlockAndPost(nativeWindow);
  lastPostMSecs = (unsigned)ioMSecs();
  pthread_mutex_unlock(&winMutex);
}

static sqInt
display_ioShowDisplay(sqInt dispBitsIndex, sqInt width, sqInt height, sqInt depth,
		      sqInt affectedL, sqInt affectedR, sqInt affectedT, sqInt affectedB)
{
  const uint32_t *bits = (const uint32_t *)pointerForOop(dispBitsIndex);
  int l, r, t, b;

  {  /* And what it actually hands back: the two agreeing means the image relaid
        out; disagreeing means the compositor is scaling a stale Display. */
    static sqInt lastW = 0, lastH = 0, lastD = 0;
    if (width != lastW || height != lastH || depth != lastD) {
      lastW = width; lastH = height; lastD = depth;
      LOGI("android display: image Display is %ldx%ld depth %ld",
           (long)width, (long)height, (long)depth);
    }
  }
  if (width < 1 || height < 1 || depth != 32 || !bits)
    return 0;

  l = affectedL < 0 ? 0 : affectedL;   r = affectedR > width  ? (int)width  : affectedR;
  t = affectedT < 0 ? 0 : affectedT;   b = affectedB > height ? (int)height : affectedB;
  if (l >= r || t >= b)
    return 0;

  pthread_mutex_lock(&winMutex);
  if (havePending && bits == pendingBits && width == pendingW && height == pendingH) {
    if (l < dirtyL) dirtyL = l;
    if (r > dirtyR) dirtyR = r;
    if (t < dirtyT) dirtyT = t;
    if (b > dirtyB) dirtyB = b;
  }
  else {
    /* a different Form (or the first damage): start a fresh region */
    pendingBits = bits;  pendingW = width;  pendingH = height;
    dirtyL = l;  dirtyR = r;  dirtyT = t;  dirtyB = b;
    havePending = 1;
  }
  {
    unsigned now = (unsigned)ioMSecs();
    int overdue = (now - lastPostMSecs) >= MAX_UNPOSTED_MSECS;
    pthread_mutex_unlock(&winMutex);
    if (overdue) postPendingFrame();
  }
  return 0;
}

/*** clipboard ***/

static sqInt
display_clipboardSize(void)
{
  sqInt n;
  pthread_mutex_lock(&queueMutex);
  n = clipboardText ? (sqInt)strlen(clipboardText) : 0;
  pthread_mutex_unlock(&queueMutex);
  return n;
}

static sqInt
display_clipboardReadIntoAt(sqInt count, sqInt byteArrayIndex, sqInt startIndex)
{
  char *dst = (char *)pointerForOop(byteArrayIndex) + startIndex;
  sqInt n = 0;
  pthread_mutex_lock(&queueMutex);
  if (clipboardText) {
    n = (sqInt)strlen(clipboardText);
    if (n > count) n = count;
    memcpy(dst, clipboardText, n);
  }
  pthread_mutex_unlock(&queueMutex);
  return n;
}

static sqInt
display_clipboardWriteFromAt(sqInt count, sqInt byteArrayIndex, sqInt startIndex)
{
  const char *src = (const char *)pointerForOop(byteArrayIndex) + startIndex;
  char *copy = (char *)malloc(count + 1);
  if (!copy) return 0;
  memcpy(copy, src, count);
  copy[count] = '\0';

  pthread_mutex_lock(&queueMutex);
  free(clipboardText);
  clipboardText = copy;
  pthread_mutex_unlock(&queueMutex);

  if (clipboardWriter)			/* let the app mirror it to the system */
    clipboardWriter(copy);
  return count;
}

static char **display_clipboardGetTypeNames(void)			{ return 0; }
static sqInt  display_clipboardSizeWithType(char *t, int nt)		{ return 0; }
static void   display_clipboardWriteWithType(char *data, size_t n, char *t,
					     size_t nt, int isDnd, int isClaiming) { }

static sqInt display_dndOutStart(char *types, int ntypes)	{ return 0; }
static sqInt display_dndOutAcceptedType(char *type, int ntype)	{ return 0; }
static void  display_dndOutSend(char *bytes, int nbytes)	{ }
static sqInt display_dndReceived(char *fileName)		{ return 0; }

/*** OpenGL: not offered (B3D falls back to software) ***/

static void  *display_ioGetDisplay(void)	{ return 0; }
static void  *display_ioGetWindow(void)		{ return (void *)nativeWindow; }
static sqInt  display_ioGLinitialise(void)	{ return 0; }
static sqInt  display_ioGLcreateRenderer(glRenderer *r, sqInt x, sqInt y, sqInt w, sqInt h, sqInt f) { return 0; }
static sqInt  display_ioGLmakeCurrentRenderer(glRenderer *r)	{ return 0; }
static void   display_ioGLdestroyRenderer(glRenderer *r)	{ }
static void   display_ioGLswapBuffers(glRenderer *r)		{ }
static void   display_ioGLsetBufferRect(glRenderer *r, sqInt x, sqInt y, sqInt w, sqInt h) { }

static sqInt display_primitivePluginBrowserReady(void)		{ return primitiveFail(); }
static sqInt display_primitivePluginRequestURLStream(void)	{ return primitiveFail(); }
static sqInt display_primitivePluginRequestURL(void)		{ return primitiveFail(); }
static sqInt display_primitivePluginPostURL(void)		{ return primitiveFail(); }
static sqInt display_primitivePluginRequestFileHandle(void)	{ return primitiveFail(); }
static sqInt display_primitivePluginDestroyRequest(void)	{ return primitiveFail(); }
static sqInt display_primitivePluginRequestState(void)		{ return primitiveFail(); }

/*** host windows: one screen, no secondary windows ***/

static long display_hostWindowClose(long index)			{ return 0; }
static long display_hostWindowCreate(long w, long h, long x, long y, char *l, long n) { return 0; }
static long display_hostWindowShowDisplay(unsigned char *bits, long w, long h, long d,
					  long al, long ar, long at, long ab, sqIntptr_t idx) { return 0; }
static long display_hostWindowGetSize(long idx)			{ return -1; }
static long display_hostWindowSetSize(long idx, long w, long h)	{ return -1; }
static long display_hostWindowGetPosition(long idx)		{ return -1; }
static long display_hostWindowSetPosition(long idx, long x, long y) { return -1; }
static long display_hostWindowSetTitle(long idx, char *t, long n)   { return -1; }
static long display_hostWindowCloseAll(void)			{ return 0; }

static long display_ioPositionOfScreenWorkArea(long idx)	{ return 0; }
static long display_ioSizeOfScreenWorkArea(long idx)		{ return display_ioScreenSize(); }
static long display_ioSetCursorPositionXY(long x, long y)	{ return -1; }

static void *display_ioGetWindowHandle(void)			{ return (void *)nativeWindow; }
static long  display_ioPositionOfNativeDisplay(void *h)		{ return 0; }
static long  display_ioSizeOfNativeDisplay(void *h)		{ return display_ioScreenSize(); }
static long  display_ioPositionOfNativeWindow(void *h)		{ return 0; }
static long  display_ioSizeOfNativeWindow(void *h)		{ return display_ioScreenSize(); }
static sqInt display_ioScreenRectangles(void)			{ return 0; }

SqDisplayDefine(android);

/*** module ***/

#include "SqModule.h"

static void display_parseEnvironment(void) { }

static int
display_parseArgument(int argc, char **argv)
{
  /* Accept -headless/-nodisplay so a command line written for the X11 driver
     still parses; there is nothing to do about them here. */
  if (!strcmp(argv[0], "-headless") || !strcmp(argv[0], "-nodisplay"))
    return 1;
  return 0;
}

static void display_printUsage(void)      { printf("\nAndroid <option>s: (none)\n"); }
static void display_printUsageNotes(void) { }

static void *display_makeInterface(void)  { return &display_android_itf; }

SqModuleDefine(display, android);
