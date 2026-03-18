package com.LingoCloudTranslate.lingocloud;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XposedBridge;
import org.json.JSONObject;

public class TranslationBridge {
    private WebView webView;
    private Handler mainHandler;

    public TranslationBridge(WebView webView) {
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public void requestTranslation(final String originalText, final String domNodeId) {
        if (originalText == null || originalText.trim().isEmpty()) return;

        // 1. Check Cache
        String cachedTranslation = HookMain.TranslationCache.get(originalText);
        if (cachedTranslation != null) {
            injectTranslationBackToDOM(domNodeId, cachedTranslation);
            return;
        }

        // 2. Fetch from Gemini API
        HookMain.GeminiTranslator.translate(originalText, new HookMain.TranslationCallback() {
            @Override
            public void onSuccess(String translatedText) {
                HookMain.TranslationCache.put(originalText, translatedText);
                injectTranslationBackToDOM(domNodeId, translatedText);
            }

            @Override
            public void onFailure(String error) {
                XposedBridge.log("WebView Translation Failed: " + error);
            }
        });
    }

    private void injectTranslationBackToDOM(final String domNodeId, final String translatedText) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String safeText = JSONObject.quote(translatedText);

                // Construct JS to update the specific node by its unique ID
                String jsUpdate = "javascript:(function() { " +
                        "  var el = document.getElementById('" + domNodeId + "'); " +
                        "  if (el) { el.innerText = " + safeText + "; } " +
                        "})();";

                webView.evaluateJavascript(jsUpdate, null);
            }
        });
    }
}
