package com.LingoCloudTranslate.lingocloud;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;

public class HookOrchestrator {
    // ThreadLocal ensures thread-safety across the target app's UI rendering pipeline
    private static final ThreadLocal<Boolean> isCurrentlyTranslating = ThreadLocal.withInitial(() -> false);

    public static void executeTextHook(MethodHookParam param, Context context, int argIndex) {
        if (isCurrentlyTranslating.get()) return; // Prevent recursive loops
        if (param.args == null || param.args.length <= argIndex || param.args[argIndex] == null) return;

        Object arg = param.args[argIndex];
        if (!(arg instanceof CharSequence)) return;

        if (!PayloadValidator.textShouldTranslate((CharSequence) arg, HookMain.GeminiTranslator.targetLanguage)) return;

        String originalText = arg.toString().trim();

        isCurrentlyTranslating.set(true);
        try {
            // 1. Ask IPC for cached translation (Fast, Blocking)
            String translated = null;
            if (context != null) {
                translated = IPCClient.getTranslationFast(context, originalText, "en");
            } else {
                // Fallback if Context is null (e.g., inside deep hook before context is ready)
                translated = HookMain.TranslationCache.get(originalText);
            }

            if (translated != null) {
                // 2a. Cache Hit: Modify text immediately
                param.args[argIndex] = translated + LingoHookManager.TRANSLATED_TAG;
            } else {
                // 2b. Cache Miss: Let original text render, trigger async background request via Intent
                if (context != null) {
                    Intent requestIntent = new Intent("com.LingoCloudTranslate.lingocloud.ACTION_REQUEST_TRANSLATION");
                    requestIntent.setPackage("com.LingoCloudTranslate.lingocloud");
                    requestIntent.putExtra("text", originalText);

                    // We don't have the original target package inside the pure UI hook unless we pass it down
                    String pkg = context.getPackageName();
                    requestIntent.putExtra("package", pkg);

                    if (param.thisObject != null) {
                        requestIntent.putExtra("viewId", System.identityHashCode(param.thisObject));
                    }

                    context.sendBroadcast(requestIntent);
                } else {
                    // Fallback to direct thread invocation if no Context is available
                    HookMain.GeminiTranslator.translate(originalText, new HookMain.TranslationCallback() {
                        @Override
                        public void onSuccess(String translatedText) {
                            HookMain.TranslationCache.put(originalText, translatedText);
                        }
                        @Override
                        public void onFailure(String error) { }
                    });
                }
            }
        } finally {
            isCurrentlyTranslating.set(false); // Always release the lock
        }
    }
}
