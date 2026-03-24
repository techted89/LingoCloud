package com.LingoCloudTranslate.lingocloud;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class HeuristicScanner {
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
