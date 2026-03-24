package com.LingoCloudTranslate.lingocloud;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class LingoHookManager {
    private static final String TAG = "LingoHookManager";
    public static final String TRANSLATED_TAG = "\u200B";

    public static void smartlyHookApp(XC_LoadPackage.LoadPackageParam lpparam) {
        FrameworkDetector.UIType uiType = FrameworkDetector.detect(lpparam.classLoader);
        XposedBridge.log(TAG + ": Detected UI Type for " + lpparam.packageName + ": " + uiType);

        if (uiType == FrameworkDetector.UIType.STANDARD_VIEW) {
            // Fallback to searching all loaded views if standard fails
            hookDiscoveredMethods(lpparam.classLoader, "android.widget.TextView");
        } else if (uiType == FrameworkDetector.UIType.JETPACK_COMPOSE) {
            // Compose uses TextLayoutResult and AnnotatedString.
            // We hook the TextAnnotator directly.
            hookDiscoveredMethods(lpparam.classLoader, "androidx.compose.ui.text.AnnotatedString");
        }
        // Flutter/React Native require native C++ hooking via JNI, which must be routed to a separate Native Hook Engine.
    }

    private static void hookDiscoveredMethods(ClassLoader classLoader, String baseClassName) {
        Class<?> baseClass = XposedHelpers.findClassIfExists(baseClassName, classLoader);
        if (baseClass == null) return;

        List<Method> hookPoints = HeuristicScanner.findTextSetters(baseClass);

        for (Method targetMethod : hookPoints) {
            XposedBridge.log(TAG + ": Hooked method: " + targetMethod.getDeclaringClass().getName() + "." + targetMethod.getName() + "()");
            try {
                XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        android.content.Context context = null;
                        if (param.thisObject instanceof android.view.View) {
                            context = ((android.view.View) param.thisObject).getContext();
                        } else if (param.thisObject != null) {
                            // Attempt to find context via reflection if not a View (e.g. AccessibilityNodeInfo)
                            try {
                                java.lang.reflect.Method getContextMethod = param.thisObject.getClass().getMethod("getContext");
                                Object ctxObj = getContextMethod.invoke(param.thisObject);
                                if (ctxObj instanceof android.content.Context) {
                                    context = (android.content.Context) ctxObj;
                                }
                            } catch (Throwable t) {
                                // Ignore
                            }
                        }

                        HookOrchestrator.executeTextHook(param, context, 0);
                    }
                });
            } catch (Exception e) {
                XposedBridge.log(TAG + ": Failed to attach dynamic hook: " + e.getMessage());
            }
        }
    }
}
