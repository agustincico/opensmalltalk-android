package au.com.darkside.x11server;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * Tiny trampoline that runs in its OWN process (":restart", declared in the
 * manifest) whose only job is to restart the app cleanly.
 *
 * Why this exists: the native Stack VM can't re-initialise inside a live
 * process, so switching the running image means killing the app process and
 * booting a fresh one. The previous approach — send the app HOME, then relaunch
 * via an AlarmManager PendingIntent — fails on Android 10+ (API 29+): once the
 * app is in the background, Android blocks it from starting an activity
 * ("Background activity start ... isBgStartWhitelisted: false"), so the relaunch
 * never happened and the app just appeared to "close".
 *
 * The fix (the ProcessPhoenix technique, reduced to essentials):
 *   1. XServerActivity, while STILL in the foreground, starts this activity.
 *      A foreground app IS allowed to start an activity, and because we run in a
 *      separate process we survive the next step.
 *   2. Here we kill the old app process, wait briefly so the OS reaps it and
 *      frees the X server port (127.0.0.1:6000) and the display surface, then —
 *      as the now-foreground activity — start XServerActivity fresh. That start
 *      is a foreground start, so it is allowed.
 *   3. Finally we finish and kill our own helper process, leaving nothing behind.
 */
public class RestartActivity extends Activity {
    private static final String TAG = "Cuis";
    static final String EXTRA_PID  = "au.com.darkside.x11server.restart.pid";
    static final String EXTRA_NEXT = "au.com.darkside.x11server.restart.next";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent next = getIntent().getParcelableExtra(EXTRA_NEXT);
        int mainPid = getIntent().getIntExtra(EXTRA_PID, -1);

        if (mainPid > 0 && mainPid != Process.myPid()) {
            Log.i(TAG, "RestartActivity: killing old app process " + mainPid);
            Process.killProcess(mainPid);
        }

        // Give the OS a moment to reap the old process so its X server socket
        // (6000) and display surface are free before the fresh VM grabs them.
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        if (next == null)
            next = new Intent(this, XServerActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Log.i(TAG, "RestartActivity: relaunching main activity");
        startActivity(next);

        // Do NOT kill our own process now: killing the process that just issued
        // the start cancels the still-pending launch, so the fresh app process
        // never comes up (that's exactly what left the app "closed"). Finish
        // after a short delay — by then XServerActivity is up in the foreground —
        // and let Android reap this empty helper process on its own.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::finish, 2500);
    }
}
