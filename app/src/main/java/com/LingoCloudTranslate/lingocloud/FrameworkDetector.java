package com.LingoCloudTranslate.lingocloud;

import de.robv.android.xposed.XposedHelpers;

public class FrameworkDetector {
    public enum UIType { STANDARD_VIEW, JETPACK_COMPOSE, FLUTTER, REACT_NATIVE, UNKNOWN }

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
