package com.LingoCloudTranslate.lingocloud;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class HeuristicScanner {
    /**
     * Finds candidate "text setter" methods on the given class using simple heuristics.
     *
     * <p>Heuristics: the method must declare exactly one parameter (compatible with
     * `CharSequence` or `String`), the declaring class must be a subclass of
     * `android.view.View` or `android.view.accessibility.AccessibilityNodeInfo`, and
     * both the class and method names must not match common logging/builder/buffer or
     * print/write/append patterns.</p>
     *
     * @param targetClass the class to scan for candidate text-setting methods
     * @return a list of reflected `Method` instances that match the heuristics for text setters
     */
    public static List<Method> findTextSetters(Class<?> targetClass) {
        List<Method> validHooks = new ArrayList<>();
        Method[] methods = targetClass.getDeclaredMethods();

        for (Method method : methods) {
            // Rule 1: Method must not be abstract
            if (Modifier.isAbstract(method.getModifiers())) continue;

            // Rule 2: Must accept exactly one parameter
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) continue;

            String methodName = method.getName().toLowerCase();
            String className = targetClass.getName().toLowerCase();

            // RULE A: Ignore Logging and Builders
            if (className.contains("log") || className.contains("builder") || className.contains("buffer")) continue;

            // RULE B: Ignore common non-UI methods
            if (methodName.contains("log") || methodName.contains("print") || methodName.contains("write") || methodName.contains("append")) continue;

            // RULE C: Ensure it belongs to a View or Node hierarchy
            if (!android.view.View.class.isAssignableFrom(targetClass) && !android.view.accessibility.AccessibilityNodeInfo.class.isAssignableFrom(targetClass)) {
                continue;
            }

            // Rule 3: The parameter must be CharSequence or String
            if (params[0].isAssignableFrom(CharSequence.class) || params[0].isAssignableFrom(String.class)) {
                validHooks.add(method);
            }
        }
        return validHooks;
    }
}
