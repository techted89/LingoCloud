package com.LingoCloudTranslate.lingocloud;

import de.robv.android.xposed.XposedHelpers;

public class FrameworkDetector {
    public enum UIType { STANDARD_VIEW, JETPACK_COMPOSE, FLUTTER, REACT_NATIVE, UNKNOWN }

    /**
     * Detects the UI framework by probing the provided ClassLoader for well-known framework classes.
     *
     * @param classLoader the ClassLoader to inspect for framework-specific classes
     * @return the detected {@code UIType}: {@code JETPACK_COMPOSE}, {@code FLUTTER}, {@code REACT_NATIVE}, or {@code STANDARD_VIEW} if no known framework classes are found
     */
    public static UIType detect(ClassLoader classLoader) {
        if (XposedHelpers.findClassIfExists("androidx.compose.ui.node.LayoutNode", classLoader) != null) {
            return UIType.JETPACK_COMPOSE;
        }
        if (XposedHelpers.findClassIfExists("io.flutter.embedding.engine.FlutterEngine", classLoader) != null) {
            return UIType.FLUTTER;
        }
        if (XposedHelpers.findClassIfExists("com.facebook.react.ReactApplication", classLoader) != null) {
            return UIType.REACT_NATIVE;
        }
        return UIType.STANDARD_VIEW;
    }
}
