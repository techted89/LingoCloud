package com.LingoCloudTranslate.lingocloud;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XposedBridge;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

public class TranslationBridge {
    private WebView webView;
    private Handler mainHandler;
    private final ConcurrentHashMap<String, List<String>> inFlightRequests = new ConcurrentHashMap<>();

    public TranslationBridge(WebView webView) {
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public void requestTranslation(final String originalText, final String domNodeIdsJson) {
        if (originalText == null || originalText.trim().isEmpty()) return;

        // 1. Check Cache
        String cachedTranslation = HookMain.TranslationCache.get(originalText);
        if (cachedTranslation != null) {
            injectTranslationBackToDOM(domNodeIdsJson, cachedTranslation);
            return;
        }

        // Check if request is in flight
        synchronized (inFlightRequests) {
            List<String> inFlightIds = inFlightRequests.get(originalText);
            if (inFlightIds != null) {
                // Request is already running, just add our node array JSON
                inFlightIds.add(domNodeIdsJson);
                return;
            }
            // Mark as in-flight
            List<String> newList = new ArrayList<>();
            newList.add(domNodeIdsJson);
            inFlightRequests.put(originalText, newList);
        }

        // 2. Fetch from Gemini API
        HookMain.GeminiTranslator.translate(originalText, new HookMain.TranslationCallback() {
            @Override
            public void onSuccess(String translatedText) {
                HookMain.TranslationCache.put(originalText, translatedText);
                List<String> pendingIds;
                synchronized (inFlightRequests) {
                    pendingIds = inFlightRequests.remove(originalText);
                }
                if (pendingIds != null) {
                    for (String idsJson : pendingIds) {
                        injectTranslationBackToDOM(idsJson, translatedText);
                    }
                }
            }

            @Override
            public void onFailure(String error) {
                synchronized (inFlightRequests) {
                    inFlightRequests.remove(originalText);
                }
                XposedBridge.log("WebView Translation Failed: " + error);
            }
        });
    }

    private void injectTranslationBackToDOM(final String domNodeIdsJson, final String translatedText) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String safeText = JSONObject.quote(translatedText);
                String safeIds = JSONObject.quote(domNodeIdsJson);

                // Construct JS to update the specific nodes by array
                String jsUpdate = "javascript:(function(idsJsonStr, text) { " +
                        "  try { " +
                        "    var ids = JSON.parse(idsJsonStr); " +
                        "    ids.forEach(function(id) { " +
                        "      var el = document.getElementById(id); " +
                        "      if (el) { el.innerText = text; } " +
                        "    }); " +
                        "  } catch(e) { console.error('LingoCloud JS Error: ' + e); } " +
                        "})(" + safeIds + ", " + safeText + ");";

                webView.evaluateJavascript(jsUpdate, null);
            }
        });
    }
}
