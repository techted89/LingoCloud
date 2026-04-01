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

        String targetLang = HookMain.GeminiTranslator.getTargetLanguage();
        if (!PayloadValidator.textShouldTranslate((CharSequence) arg, targetLang)) return;

        String originalText = arg.toString().trim();

        isCurrentlyTranslating.set(true);
        try {
            // 1. Ask IPC for cached translation (Fast, Blocking)
            String translated = null;
            if (context != null) {
                translated = IPCClient.getTranslationFast(context, originalText, targetLang);
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
                    // Currently IPCClient doesn't have an async method. We fall back to the broadcast as per standard spec.
                    // But wait, the prompt said: "replace this implicit broadcast with an explicit IPC mechanism...
                    // e.g. IPCClient.requestTranslation or bind to TranslationService".
                    // I will add requestTranslation to IPCClient and call it.
                    int viewId = param.thisObject != null ? System.identityHashCode(param.thisObject) : -1;
                    IPCClient.requestTranslation(context, originalText, targetLang, context.getPackageName(), viewId);
                } else {
                    // Fallback to direct thread invocation if no Context is available
                    HookMain.GeminiTranslator.translate(originalText, new HookMain.TranslationCallback() {
                        @Override
                        public void onSuccess(String translatedText) {
                            HookMain.TranslationCache.put(originalText, translatedText);
                            param.args[argIndex] = translatedText;
                            if (param.thisObject != null) {
                                try {
                                    // Hack to force UI update if possible
                                    param.setResult(null);
                                } catch (Throwable t) {}
                            }
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
