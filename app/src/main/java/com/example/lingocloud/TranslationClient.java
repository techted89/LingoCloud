package com.example.lingocloud;

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

/**
 * TranslationClient - Cloud API Bridge for LingoCloud
 * Supports: Google Gemini 1.5 Flash & Microsoft Azure Translator
 * Android 15 (SDK 35) Compatible
 */
public class TranslationClient {
    private static final String TAG = "LingoCloud";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // API Endpoints
    private static final String GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final String MICROSOFT_ENDPOINT =
        "https://api.cognitive.microsofttranslator.com/translate";

    // OkHttp Client with optimized timeouts for UI translation
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();

    public interface TranslationCallback {
        void onResult(String translatedText);
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
            callback.onResult(text);
            return;
        }

        if (apiKey.trim().isEmpty()) {
            Log.w(TAG, "API key is empty, skipping translation");
            callback.onResult(null);
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
                callback.onResult(null);
        }
    }

    /**
     * Google Gemini 1.5 Flash API Call
     * Optimized for low-latency UI translation
     */
    private void callGemini(@NonNull String text, @NonNull String apiKey,
                           @NonNull String targetLang, @NonNull TranslationCallback callback) {

        String url = GEMINI_ENDPOINT + "?key=" + apiKey;

        // Construct prompt for UI translation
        String prompt = String.format(
            "Translate the following UI text to %s. " +
            "Return ONLY the translated text without quotes, explanations, or formatting: %s",
            targetLang, escapeJson(text)
        );

        JSONObject part = new JSONObject();
        JSONObject content = new JSONObject();
        JSONObject payload = new JSONObject();

        try {
            part.put("text", prompt);
            content.put("parts", new JSONArray().put(part));
            payload.put("contents", new JSONArray().put(content));

            // Configure for faster response
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 0.1);
            generationConfig.put("maxOutputTokens", 256);
            payload.put("generationConfig", generationConfig);

        } catch (JSONException e) {
            Log.e(TAG, "Failed to build Gemini request", e);
            callback.onResult(null);
            return;
        }

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Gemini API request failed", e);
                callback.onResult(null);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Gemini API error: " + response.code());
                    callback.onResult(null);
                    return;
                }

                try {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);

                    String result = json
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text");

                    callback.onResult(result.trim());

                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse Gemini response", e);
                    callback.onResult(null);
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
            callback.onResult(null);
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
                callback.onResult(null);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Microsoft API error: " + response.code());
                    callback.onResult(null);
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

                    callback.onResult(result);

                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse Microsoft response", e);
                    callback.onResult(null);
                }
            }
        });
    }

    /**
     * Escape special characters for JSON strings
     */
    private String escapeJson(String input) {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
