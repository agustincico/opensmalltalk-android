package au.com.darkside.x11server;

/**
 * The app with the X-server-free display forced on, for testing it side by side with the
 * shipping one without touching the marker file:
 *
 * <pre>adb shell am start -n ar.com.opensmalltalk/au.com.darkside.x11server.NativeDisplayActivity</pre>
 *
 * <p>Everything else — the pill, the options dialog, the image chooser, the crash-loop
 * guard — is inherited, because both backends are two modes of the same activity. Users
 * switch engines from ☰ → <i>Display engine</i>, which writes the marker and restarts
 * through {@link XServerActivity}; this class exists only so a shell command can override
 * that choice.
 *
 * <p>It must NOT redeclare {@code startVMNative}: that native method binds by its declaring
 * class name, so it has to keep resolving through {@link XServerActivity}.
 */
public class NativeDisplayActivity extends XServerActivity {

    @Override
    protected boolean useNativeDisplay() { return true; }
}
