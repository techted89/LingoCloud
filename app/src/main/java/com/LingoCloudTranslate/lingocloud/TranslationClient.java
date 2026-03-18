package com.LingoCloudTranslate.lingocloud;

import android.util.Log;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TranslationClient - Cloud API Bridge for LingoCloud
 * Supports: Google Gemini 2.5 Flash & Microsoft Azure Translator
 * Android 15 (SDK 35) Compatible
 */
public class TranslationClient implements AutoCloseable {
    private static final String TAG = "LingoCloud";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // API Endpoints
    private static final String MICROSOFT_ENDPOINT =
        "https://api.cognitive.microsofttranslator.com/translate";

    // OkHttp Client with optimized timeouts for UI translation
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private volatile Client genaiClient;
    private String cachedApiKey;

    public interface TranslationCallback {
        void onResult(String translatedText);
    }

    private void deliverResult(TranslationCallback callback, String result) {
        callback.onResult(result);
    }

    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void resetClient() {
        synchronized (this) {
            genaiClient = null;
            cachedApiKey = null;
        }
    }

    /**
     * Main translation entry point
     * @param text Text to translate
     * @param service "Gemini" or "Microsoft"
     * @param apiKey API key for selected service
     * @param targetLang Target language code (e.g., "en", "es", "ja")
     * @param callback Async result callback
     */
    public void translate(@NonNull String text, @NonNull String service,
                         @NonNull String apiKey, @NonNull String targetLang,
                         @NonNull TranslationCallback callback) {

        if (text.trim().isEmpty()) {
            deliverResult(callback, text);
            return;
        }

        if (apiKey.trim().isEmpty()) {
            Log.w(TAG, "API key is empty, skipping translation");
            deliverResult(callback, null);
            return;
        }

        switch (service) {
            case "Gemini":
                callGemini(text, apiKey, targetLang, callback);
                break;
            case "Microsoft":
                callMicrosoft(text, apiKey, targetLang, callback);
                break;
            default:
                Log.e(TAG, "Unknown service: " + service);
                deliverResult(callback, null);
        }
    }

    /**
     * Google GenAI API Call
     * Uses the official GenAI SDK to replace legacy REST API
     */
    private void callGemini(@NonNull String text, @NonNull String apiKey,
                           @NonNull String targetLang, @NonNull TranslationCallback callback) {

        // Construct prompt for UI translation
        String prompt = String.format(
            "Translate the following UI text to %s. " +
            "Return ONLY the translated text without quotes, explanations, or formatting: %s",
            targetLang, text
        );

        executor.execute(() -> {
            try {
                if (genaiClient == null || !apiKey.equals(cachedApiKey)) {
                    synchronized (this) {
                        if (genaiClient == null || !apiKey.equals(cachedApiKey)) {
                            genaiClient = Client.builder().apiKey(apiKey).build();
                            cachedApiKey = apiKey;
                        }
                    }
                }

                GenerateContentConfig config = GenerateContentConfig.builder()
                    .temperature(0.1f)
                    .maxOutputTokens(256)
                    .build();

                GenerateContentResponse response = genaiClient.models.generateContent(
                    "gemini-2.5-flash",
                    prompt,
                    config
                );

                if (response != null && response.text() != null) {
                    deliverResult(callback, response.text().trim());
                } else {
                    Log.e(TAG, "Gemini API returned empty response");
                    deliverResult(callback, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Gemini API request failed via GenAI SDK", e);
                if (e.getMessage() != null && e.getMessage().contains("429")) {
                    deliverResult(callback, "ERROR_LIMIT_EXCEEDED");
                } else {
                    deliverResult(callback, null);
                }
            }
        });
    }

    /**
     * Microsoft Azure Translator API Call
     * Uses v3.0 API with proper authentication
     */
    private void callMicrosoft(@NonNull String text, @NonNull String apiKey,
                              @NonNull String targetLang, @NonNull TranslationCallback callback) {

        String url = MICROSOFT_ENDPOINT + "?api-version=3.0&to=" + targetLang;

        JSONArray payload = new JSONArray();
        try {
            JSONObject textObj = new JSONObject();
            textObj.put("Text", text);
            payload.put(textObj);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build Microsoft request", e);
            deliverResult(callback, null);
            return;
        }

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .header("Content-Type", "application/json")
            .header("Ocp-Apim-Subscription-Region", "global")
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Microsoft API request failed", e);
                deliverResult(callback, null);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Microsoft API error: " + response.code());
                    if (response.code() == 429) {
                        deliverResult(callback, "ERROR_LIMIT_EXCEEDED");
                    } else {
                        deliverResult(callback, null);
                    }
                    return;
                }

                try {
                    String responseBody = response.body().string();
                    JSONArray json = new JSONArray(responseBody);

                    String result = json
                        .getJSONObject(0)
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("text");

                    deliverResult(callback, result);

                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse Microsoft response", e);
                    deliverResult(callback, null);
                }
            }
        });
    }
}
