# Keep JavaScript interface methods
-keepclassmembers class com.novelreader.app.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
