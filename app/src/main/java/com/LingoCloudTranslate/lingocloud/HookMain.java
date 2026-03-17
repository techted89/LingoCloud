package com.LingoCloudTranslate.lingocloud;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HookMain - Xposed Module Entry Point for LingoCloud
 * Android 15 (SDK 35) Compatible
 *
 * Intercepts TextView.setText() calls and translates UI text in real-time
 * using cloud translation APIs (Gemini / Microsoft).
 */
public class HookMain implements IXposedHookLoadPackage {
    private static final String TAG = "LingoCloud";
    private static final String PREFS_PKG = "com.LingoCloudTranslate.lingocloud";
    private static final String PREFS_NAME = "settings";

    // Recursion guard tag key - prevents infinite translation loops
    private static final String TRANSLATED_FIELD = "lingocloud_translated";

    private static final int STATE_TAG_ID = android.R.id.accessibilityActionContextClick;
    private static final int ORIGINAL_TEXT_TAG_ID = android.R.id.accessibilityActionScrollDown;

    // Minimum text length to translate (skip single chars/icons)
    private static final int MIN_TEXT_LENGTH = 2;

    // Maximum text length to prevent API abuse
    private static final int MAX_TEXT_LENGTH = 500;

    // Shared preferences for cross-process settings access
    private static XSharedPreferences prefs;

    // HTTP client for translation API calls
    private static final TranslationClient client = new TranslationClient();

    // Memory cache to avoid repeated API calls (500 entries)
    private static final LruCache<String, String> translationCache = new LruCache<>(500);
    private static final Object cacheLock = new Object();

    // Thread pool for async translation (prevents UI blocking)
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);

    // Main thread handler for UI updates
    private static Handler mainHandler;

    private static synchronized Handler getMainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    // Blacklisted packages (system apps that shouldn't be hooked)
    private static final Set<String> BLACKLISTED_PACKAGES = new HashSet<String>() {{
        add("android");
        add("com.android.systemui");
        add("com.android.settings");
        add("com.android.phone");
        add("com.android.launcher");
        add("de.robv.android.xposed.installer");
        add("org.lsposed.manager");
        add(PREFS_PKG); // Don't hook ourselves
    }};

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (BLACKLISTED_PACKAGES.contains(lpparam.packageName) ||
            lpparam.packageName.equals("android") ||
            lpparam.packageName.startsWith("com.android.") ||
            lpparam.packageName.equals(PREFS_PKG)) {
            return; // Exclude system framework, system apps, manager apps, and the module itself
        }

        // Initialize shared preferences (world-readable for LSPosed)
        if (prefs == null) {
            prefs = new XSharedPreferences(PREFS_PKG, PREFS_NAME);
            prefs.makeWorldReadable();
        }

        // Reload preferences to get latest settings
        prefs.reload();

        // Check if module is enabled
        if (!prefs.getBoolean("module_enabled", true)) {
            XposedBridge.log(TAG + ": Module disabled, skipping " + lpparam.packageName);
            return;
        }

        // Check if this package is in the whitelist (if whitelist is enabled)
        String whitelistStr = prefs.getString("app_whitelist", "");
        if (!whitelistStr.isEmpty()) {
            Set<String> whitelist = parseWhitelist(whitelistStr);
            if (!whitelist.contains(lpparam.packageName)) {
                return; // Not in whitelist, skip
            }
        }

        XposedBridge.log(TAG + ": Hooking package " + lpparam.packageName);

        // Hook 1: Standard TextView.setText() - Most common method
        hookTextViewSetText(lpparam);

        // Hook 2: StaticLayout.Builder - For custom drawn text
        hookStaticLayoutBuilder(lpparam);

        // Hook 3: Active View Scanner (For Pre-Loaded Layouts)
        hookActivityOnResume(lpparam);
    }

    /**
     * The Active View Scanner (For Pre-Loaded Layouts)
     */
    private void hookActivityOnResume(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        android.app.Activity activity = (android.app.Activity) param.thisObject;
                        android.view.View rootView = activity.getWindow().getDecorView().getRootView();
                        scanAndTranslateViewGroup(rootView);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Activity.onResume: " + e.getMessage());
        }
    }

    /**
     * Recursively scans view hierarchy and manually triggers translation logic for pre-loaded text
     */
    private void scanAndTranslateViewGroup(android.view.View rootView) {
        if (rootView == null) return;

        java.util.Deque<android.view.View> queue = new java.util.ArrayDeque<>();
        queue.add(rootView);

        while (!queue.isEmpty()) {
            final android.view.View view = queue.poll();

            if (view instanceof android.widget.TextView) {
                final android.widget.TextView textView = (android.widget.TextView) view;
                CharSequence originalCharSeq = textView.getText();

                if (originalCharSeq == null || originalCharSeq.length() == 0) {
                    continue;
                }
                final String originalText = originalCharSeq.toString().trim();

                Object currentState = textView.getTag(STATE_TAG_ID);

                if ("IGNORE".equals(currentState) || "TRANSLATING".equals(currentState) || "TRANSLATED".equals(currentState)) {
                    continue;
                }

                Object storedOriginal = textView.getTag(ORIGINAL_TEXT_TAG_ID);
                if (storedOriginal != null && storedOriginal instanceof String) {
                    String cached = TranslationCache.get((String) storedOriginal);
                    if (cached != null && cached.equals(originalText)) {
                        continue;
                    }
                }

                if (!shouldTranslate(originalText)) {
                    continue;
                }

                String cachedTranslation = TranslationCache.get(originalText);
                if (cachedTranslation != null) {
                    textView.setTag(STATE_TAG_ID, "TRANSLATED");
                    textView.setTag(ORIGINAL_TEXT_TAG_ID, originalText);
                    textView.setText(cachedTranslation);
                    continue;
                }

                textView.setTag(STATE_TAG_ID, "TRANSLATING");

                final java.lang.ref.WeakReference<android.widget.TextView> weakTextView = new java.lang.ref.WeakReference<>(textView);

                GeminiTranslator.translate(originalText, new TranslationCallback() {
                    @Override
                    public void onSuccess(final String translatedText) {
                        TranslationCache.put(originalText, translatedText);

                        android.widget.TextView tv = weakTextView.get();
                        if (tv == null) return;
                        if (!tv.isAttachedToWindow() || tv.getWindowToken() == null) {
                            tv.setTag(STATE_TAG_ID, null);
                            return;
                        }

                        tv.post(() -> {
                            android.widget.TextView innerTv = weakTextView.get();
                            if (innerTv == null) return;

                            if (innerTv.getText().toString().trim().equals(originalText)) {
                                innerTv.setTag(STATE_TAG_ID, "TRANSLATED");
                                innerTv.setTag(ORIGINAL_TEXT_TAG_ID, originalText);
                                innerTv.setText(translatedText);
                            } else {
                                innerTv.setTag(STATE_TAG_ID, null);
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        android.widget.TextView tv = weakTextView.get();
                        if (tv != null) {
                            tv.post(() -> {
                                android.widget.TextView innerTv = weakTextView.get();
                                if (innerTv != null) {
                                    innerTv.setTag(STATE_TAG_ID, null);
                                }
                            });
                        }
                        XposedBridge.log("Translation Scanner Error: " + error);
                    }
                });

            } else if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup viewGroup = (android.view.ViewGroup) view;
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    android.view.View child = viewGroup.getChildAt(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        }
    }

    /**
     * Hook TextView.setText(CharSequence, BufferType, boolean, int)
     * This is the most comprehensive setText signature
     */
    private void hookTextViewSetText(LoadPackageParam lpparam) {
        Class<?> textViewClass = XposedHelpers.findClass("android.widget.TextView", lpparam.classLoader);

        try {
            XposedHelpers.findAndHookMethod(
                textViewClass,
                "setText",
                CharSequence.class,
                android.widget.TextView.BufferType.class,
                boolean.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        executeTextTransformation(param);
                    }
                }
            );
        } catch (NoSuchMethodError e) {
            XposedBridge.log(TAG + ": 4-parameter setText not found, falling back to 2-parameter: " + e.getMessage());
            try {
                XposedHelpers.findAndHookMethod(
                    textViewClass,
                    "setText",
                    CharSequence.class,
                    android.widget.TextView.BufferType.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            executeTextTransformation(param);
                        }
                    }
                );
            } catch (Exception e2) {
                XposedBridge.log(TAG + ": Failed to hook TextView.setText fallback: " + e2.getMessage());
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook TextView.setText: " + e.getMessage());
        }
    }

    /**
     * Hook StaticLayout.Builder for apps that draw text directly to canvas
     * (e.g., custom browsers, complex RecyclerViews)
     */
    private void hookStaticLayoutBuilder(LoadPackageParam lpparam) {
        try {
            Class<?> builderClass = XposedHelpers.findClass(
                "android.text.StaticLayout$Builder",
                lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                builderClass,
                "build",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // Access the mText field of the Builder
                        CharSequence text = (CharSequence) XposedHelpers.getObjectField(
                            param.thisObject, "mText"
                        );

                        if (text == null) return;

                        String original = text.toString().trim();
                        if (!shouldTranslate(original)) return;

                        // Check cache first
                        String cached;
                        synchronized (cacheLock) {
                            cached = translationCache.get(original);
                        }
                        if (cached != null) {
                            XposedHelpers.setObjectField(param.thisObject, "mText", cached);
                        }
                        // Note: Async translation not supported here (build() is synchronous)
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": StaticLayout.Builder hook failed (optional): " + e.getMessage());
        }
    }

    /**
     * Main text update handler - processes translation logic
     */
    private void executeTextTransformation(XC_MethodHook.MethodHookParam param) {
        final android.widget.TextView textView = (android.widget.TextView) param.thisObject;
        final CharSequence originalCharSeq = (CharSequence) param.args[0];

        // 1. Null and Empty checks
        if (originalCharSeq == null || originalCharSeq.length() == 0) return;
        final String originalText = originalCharSeq.toString().trim();

        // Skip if text doesn't meet criteria (min/max length, symbols, etc)
        if (!shouldTranslate(originalText)) return;

        // 2. Recursion and State Guard
        // We use an Android framework-defined ID that is rarely used to attach our state.
        Object currentState = textView.getTag(STATE_TAG_ID);

        if ("IGNORE".equals(currentState) || "TRANSLATING".equals(currentState)) {
            return; // Already in progress or explicitly ignored
        }

        if ("TRANSLATED".equals(currentState)) {
            // Text was set by our async callback. Reset state so future organic app changes are caught.
            textView.setTag(STATE_TAG_ID, null);
            return;
        }

        Object storedOriginal = textView.getTag(ORIGINAL_TEXT_TAG_ID);
        if (storedOriginal != null && storedOriginal instanceof String) {
            String cached = TranslationCache.get((String) storedOriginal);
            if (cached != null && cached.equals(originalText)) {
                return;
            }
        }

        // 3. Synchronous Cache Check (Fast Path for RecyclerViews/Scrolling)
        String cachedTranslation = TranslationCache.get(originalText);
        if (cachedTranslation != null) {
            // Inject instantly before the method executes.
            // Crucial: Preserve the original sequence type if possible (String vs Spannable)
            if (originalCharSeq instanceof android.text.Spanned || originalCharSeq instanceof android.text.Spannable) {
                param.args[0] = new android.text.SpannableStringBuilder(cachedTranslation);
            } else {
                param.args[0] = cachedTranslation;
            }
            textView.setTag(ORIGINAL_TEXT_TAG_ID, originalText);
            return;
        }

        // 4. Asynchronous Translation Initiation (Slow Path)
        textView.setTag(STATE_TAG_ID, "TRANSLATING"); // Lock the view from triggering more API calls

        final java.lang.ref.WeakReference<android.widget.TextView> weakTextView = new java.lang.ref.WeakReference<>(textView);

        GeminiTranslator.translate(originalText, new TranslationCallback() {
            @Override
            public void onSuccess(final String translatedText) {
                TranslationCache.put(originalText, translatedText);

                android.widget.TextView tv = weakTextView.get();
                if (tv == null) return;
                if (!tv.isAttachedToWindow() || tv.getWindowToken() == null) {
                    tv.setTag(STATE_TAG_ID, null);
                    return;
                }

                // We MUST post back to the UI thread to update the view
                tv.post(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.TextView innerTv = weakTextView.get();
                        if (innerTv == null) return;

                        // Double-check if the view's text changed while we were fetching the translation
                        if (innerTv.getText().toString().trim().equals(originalText)) {
                            innerTv.setTag(STATE_TAG_ID, "TRANSLATED"); // Set guard before calling setText
                            innerTv.setTag(ORIGINAL_TEXT_TAG_ID, originalText);
                            innerTv.setText(translatedText);
                        } else {
                            // The app changed the text while we were waiting (e.g., fast scrolling).
                            // Discard this translation update, but keep it in the cache.
                            innerTv.setTag(STATE_TAG_ID, null);
                        }
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                android.widget.TextView tv = weakTextView.get();
                if (tv != null) {
                    tv.post(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.TextView innerTv = weakTextView.get();
                            if (innerTv != null) {
                                innerTv.setTag(STATE_TAG_ID, null); // Unlock on failure
                            }
                        }
                    });
                }
                XposedBridge.log("Translation Hook Error: " + error);
            }
        });
    }

    /**
     * Determine if text should be translated based on various criteria
     */
    private static boolean shouldTranslate(String text) {
        if (text == null || text.isEmpty()) return false;

        // Skip short text (likely icons/symbols)
        if (text.length() < MIN_TEXT_LENGTH) return false;

        // Skip very long text (prevent API abuse)
        if (text.length() > MAX_TEXT_LENGTH) return false;

        // Allow cached entry: return true so caller can use cached result
        boolean inCache;
        synchronized (cacheLock) {
            inCache = translationCache.get(text) != null;
        }
        if (inCache) return true;

        // Skip numeric-only text
        if (text.matches("^[0-9,.]+$")) return false;

        // Skip common non-translatable patterns
        if (text.matches("^[\\u2190-\\u2199\\u25A0-\\u25FF]+$")) return false; // Arrows & symbols

        return true;
    }

    /**
     * Parse comma-separated whitelist string into Set
     */
    private Set<String> parseWhitelist(String whitelist) {
        Set<String> set = new HashSet<>();
        for (String pkg : whitelist.split(",")) {
            pkg = pkg.trim();
            if (!pkg.isEmpty()) {
                set.add(pkg);
            }
        }
        return set;
    }

    /**
     * Helper Cache Class to match execution spec literally
     */
    private static class TranslationCache {
        public static String get(String key) {
            synchronized (cacheLock) {
                return translationCache.get(key);
            }
        }

        public static void put(String key, String value) {
            synchronized (cacheLock) {
                translationCache.put(key, value);
            }
        }
    }

    /**
     * Callback interface to match execution spec literally
     */
    private interface TranslationCallback {
        void onSuccess(String translatedText);
        void onFailure(String error);
    }

    /**
     * Helper API Class to match execution spec literally
     */
    private static class GeminiTranslator {
        private static long lastReloadTime = 0;

        public static void translate(String text, TranslationCallback callback) {
            long now = System.currentTimeMillis();
            if (now - lastReloadTime > 1000) {
                prefs.reload();
                lastReloadTime = now;
            }
            if (!prefs.getBoolean("module_enabled", true)) {
                callback.onFailure("Module disabled");
                return;
            }

            String service = prefs.getString("service_provider", "Gemini");
            String apiKeyKey = service.equals("Gemini") ? "gemini_api_key" : "microsoft_api_key";
            String apiKey = prefs.getString(apiKeyKey, "");
            String targetLang = prefs.getString("target_lang", "en");

            if (apiKey.isEmpty()) {
                callback.onFailure("API key not configured");
                return;
            }

            executor.execute(() -> {
                try {
                    client.translate(text, service, apiKey, targetLang, result -> {
                        if (result != null && !result.isEmpty()) {
                            callback.onSuccess(result);
                        } else {
                            callback.onFailure("Translation result empty or null");
                        }
                    });
                } catch (Exception e) {
                    callback.onFailure("Exception during translation: " + e.getMessage());
                }
            });
        }
    }
}
