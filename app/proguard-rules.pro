# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- JNI reaches into Java by NAME ------------------------------------------
# The default rules already keep `native <methods>` and their classes, which
# covers startVMNative/getLastError and the NativeDisplay entry points. They do
# NOT cover the other direction: squeak_jni.c looks up
# NativeDisplay.onImageWroteClipboard(String) with FindClass/GetStaticMethodID,
# and R8 cannot see that call site — so in a minified build it would be renamed
# or dropped and the shared clipboard would fail silently (only in release).
-keep class au.com.darkside.x11server.NativeDisplay {
    static void onImageWroteClipboard(java.lang.String);
    static void onVmQuit();
}
