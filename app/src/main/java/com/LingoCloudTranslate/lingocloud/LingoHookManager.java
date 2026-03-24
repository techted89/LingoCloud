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

    /**
     * Installs method hooks for text-rendering components in the target package based on its detected UI framework.
     *
     * <p>If the framework is a standard Android view hierarchy, attempts to hook common TextView text setters;
     * if Jetpack Compose is detected, attempts to hook Compose text types. No action is taken for frameworks
     * that require native/JNI hooking (e.g., Flutter or React Native).</p>
     *
     * @param lpparam the Xposed load-package parameter for the target app; used to access the package name and class loader
     */
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

    /**
     * Installs Xposed hooks for text-setting methods discovered on the specified base UI class.
     *
     * Hooks invoke HookOrchestrator.executeTextHook before each discovered setter and will
     * attempt to derive an Android Context from the hooked object's `thisObject` (directly for
     * android.view.View instances or via a reflective `getContext()` if available).
     *
     * @param classLoader   the ClassLoader used to resolve the base class
     * @param baseClassName the fully-qualified name of the base UI class whose text setters should be scanned and hooked
     */
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
