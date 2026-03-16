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

    // Thread pool for async translation (prevents UI blocking)
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);

    // Main thread handler for UI updates
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        // Skip blacklisted packages
        if (BLACKLISTED_PACKAGES.contains(lpparam.packageName)) {
            return;
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
    }

    /**
     * Hook TextView.setText(CharSequence, BufferType, boolean, int)
     * This is the most comprehensive setText signature
     */
    private void hookTextViewSetText(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                TextView.class,
                "setText",
                CharSequence.class,
                TextView.BufferType.class,
                boolean.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleTextUpdate(param);
                    }
                }
            );
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
                        String cached = translationCache.get(original);
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
    private void handleTextUpdate(XC_MethodHook.MethodHookParam param) {
        // Reload preferences for latest settings
        prefs.reload();

        if (!prefs.getBoolean("module_enabled", true)) return;

        // Get the TextView instance
        TextView textView = (TextView) param.thisObject;

        // Validate input
        if (param.args[0] == null) return;

        String original = param.args[0].toString().trim();

        // Skip if text doesn't meet criteria
        if (!shouldTranslate(original)) return;

        // Check local cache first
        String cachedResult = translationCache.get(original);
        if (cachedResult != null) {
            param.args[0] = cachedResult;
            XposedHelpers.setAdditionalInstanceField(textView, TRANSLATED_FIELD, cachedResult);
            return;
        }

        // Recursion Guard: Don't re-translate our own work
        Object lastTranslated = XposedHelpers.getAdditionalInstanceField(textView, TRANSLATED_FIELD);
        if (lastTranslated != null && lastTranslated.equals(original)) {
            return;
        }

        // Get translation settings
        String service = prefs.getString("service_provider", "Gemini");
        String apiKeyKey = service.equals("Gemini") ? "gemini_api_key" : "microsoft_api_key";
        String apiKey = prefs.getString(apiKeyKey, "");
        String targetLang = prefs.getString("target_lang", "en");

        if (apiKey.isEmpty()) {
            Log.w(TAG, "API key for " + service + " not configured");
            return;
        }

        // Async translation - don't block the UI thread
        final String textToTranslate = original;
        executor.execute(() -> {
            client.translate(textToTranslate, service, apiKey, targetLang, result -> {
                if (result != null && !result.isEmpty()) {
                    // Cache the result
                    translationCache.put(textToTranslate, result);

                    // Update UI on main thread
                    mainHandler.post(() -> {
                        XposedHelpers.setAdditionalInstanceField(textView, TRANSLATED_FIELD, result);
                        textView.setText(result);
                    });
                }
            });
        });
    }

    /**
     * Determine if text should be translated based on various criteria
     */
    private boolean shouldTranslate(String text) {
        if (text == null || text.isEmpty()) return false;

        // Skip short text (likely icons/symbols)
        if (text.length() < MIN_TEXT_LENGTH) return false;

        // Skip very long text (prevent API abuse)
        if (text.length() > MAX_TEXT_LENGTH) return false;

        // Skip if already in cache (handled separately)
        if (translationCache.get(text) != null) return true;

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
}
