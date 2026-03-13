# ProGuard rules for LingoCloud Xposed Module
# These rules prevent obfuscation of Xposed-related classes

# Keep Xposed API classes
-keep class de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }

# Keep hook entry point
# Keep the module entry point so Xposed can find it
-keep class com.LingoCloudTranslate.lingocloud.HookMain {
    public void handleLoadPackage(...);
}

# Keep Xposed interface
-keep interface de.robv.android.xposed.IXposedHookLoadPackage { *; }

# Keep Android UI classes that we hook
-keep class android.widget.TextView { *; }
-keep class android.text.StaticLayout$Builder { *; }

# Keep OkHttp (used for API calls)
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep JSON parsing
-keep class org.json.** { *; }

# Keep preferences for XSharedPreferences
-keep class android.content.SharedPreferences { *; }

# General Android keep rules
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
