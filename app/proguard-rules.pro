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

# ---- Rapcha v23 release rules ----
# Keep line numbers so crash reports in Diagnostics stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebView JavaScript bridge used during Google AI Studio key provisioning.
-keepclassmembers class com.princeniithompson.rapcha.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Services, receivers and the IME/accessibility entry points are referenced from the manifest
# (AAPT keeps them), listed here too so a future refactor cannot strip them by accident.
-keep class com.princeniithompson.rapcha.service.RapchaAccessibilityService { *; }
-keep class com.princeniithompson.rapcha.service.RapchaInputMethodService { *; }
-keep class com.princeniithompson.rapcha.service.FloatingBubbleService { <init>(); }
-keep class com.princeniithompson.rapcha.service.BubbleRecoveryJobService { <init>(); }
-keep class com.princeniithompson.rapcha.service.BubbleBootReceiver { <init>(); }

# Enums are persisted by name (valueOf) in the diagnostics JSONL and prefs.
-keepclassmembers enum com.princeniithompson.rapcha.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp / Okio optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
