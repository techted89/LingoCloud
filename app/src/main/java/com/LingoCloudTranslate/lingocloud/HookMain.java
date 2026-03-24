package com.LingoCloudTranslate.lingocloud;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import android.view.accessibility.AccessibilityNodeInfo;
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
    private static final String TRANSLATED_TAG = "\u200B";

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
    private static LruCache<String, String> translationCache = new LruCache<>(500);
    private static final Object cacheLock = new Object();

    /**
     * Replaces the module's translation cache with the provided cache instance for testing.
     *
     * @param mockCache the LRU cache to use in place of the default translation cache; may be null to clear the cache reference
     */
    static void setTranslationCacheForTests(LruCache<String, String> mockCache) {
        translationCache = mockCache;
    }

    // Thread pool for async translation (prevents UI blocking)
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);

    private static final ThreadLocal<Boolean> isTranslatingStaticLayout = new ThreadLocal<>();

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
        add("com.android.systemui");
        add("com.android.settings");
        add("com.android.phone");
        add("com.android.launcher");
        add("de.robv.android.xposed.installer");
        add("org.lsposed.manager");
        add(PREFS_PKG); // Don't hook ourselves
    }};

    /**
     * Initializes module state for the given loaded package and installs translation hooks when the package is eligible.
     *
     * Performs a special-purpose system-server hook to bypass package visibility filtering, skips blacklisted or
     * framework/manager packages, loads and reloads user preferences, reads translation configuration and whitelist,
     * validates enablement and API key presence, configures the translator, and registers all package-specific hooks
     * (TextView, StaticLayout, WebView, Canvas, Toasts, tooltips, content descriptions, accessibility, input commits,
     * dialogs, menu items, and additional heuristic hooks).
     *
     * @param lpparam the Xposed LoadPackageParam representing the package being loaded and its class loader/context
     */
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // God Mode Hook: Bypass Package Visibility Filtering in the system server
        if (lpparam.packageName.equals("android")) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.pm.AppsFilter", // For Android 11-13+
                    lpparam.classLoader,
                    "shouldFilterApplication",
                    int.class, Object.class, "com.android.server.pm.PackageSetting", int.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return false; // Force visibility for all apps
                        }
                    }
                );
                XposedBridge.log(TAG + ": Successfully injected God Mode visibility bypass into system_server.");
            } catch (Throwable t) {
                // Not a fatal error if it fails (e.g. signature changed in newer Android)
                XposedBridge.log(TAG + ": Failed to inject AppsFilter hook: " + t.getMessage());
            }
            return; // Don't run translation hooks on system server
        }

        if (BLACKLISTED_PACKAGES.contains(lpparam.packageName) ||
            lpparam.packageName.startsWith("com.android.") ||
            lpparam.packageName.equals(PREFS_PKG)) {
            return; // Exclude system framework, system apps, manager apps, and the module itself
        }

        // 1. Initialize and reload XSharedPreferences
        if (prefs == null) {
            // Initialize with your EXACT module package name
            prefs = new XSharedPreferences(PREFS_PKG, PREFS_NAME);
            prefs.makeWorldReadable();
        } else {
            prefs.reload();
        }

        // 2. Fetch configurations
        String service = prefs.getString("service_provider", "Gemini");
        String apiKeyKey = service.equals("Gemini") ? "gemini_api_key" : "microsoft_api_key";
        String backupKeyKey = service.equals("Gemini") ? "gemini_api_key_backup" : "microsoft_api_key_backup";
        String apiKey = prefs.getString(apiKeyKey, "");
        String backupApiKey = prefs.getString(backupKeyKey, "");

        Set<String> enabledApps;
        try {
            enabledApps = prefs.getStringSet("app_whitelist", new HashSet<>());
        } catch (ClassCastException e) {
            XposedBridge.log(TAG + ": Legacy string-based app_whitelist found in HookMain. Clearing to prevent crash.");
            String legacy = prefs.getString("app_whitelist", null);
            if (legacy != null) {
                enabledApps = parseWhitelist(legacy);
            } else {
                enabledApps = new HashSet<>();
            }
            prefs.edit().remove("app_whitelist").apply();
        }

        String targetLanguage = prefs.getString("target_lang", "en");

        // 3. Dynamic Package Filtering
        if (apiKey.isEmpty()) {
            XposedBridge.log("LingoCloud: No API key configured. Bypassing.");
            return;
        }

        // Check if module is enabled
        if (!prefs.getBoolean("module_enabled", true)) {
            XposedBridge.log(TAG + ": Module disabled, skipping " + lpparam.packageName);
            return;
        }

        if (!enabledApps.contains(lpparam.packageName)) {
            return; // Target app is not in the user's whitelist. Bypass.
        }

        // 4. Pass configuration to the Translator
        GeminiTranslator.setConfiguration(service, apiKey, backupApiKey, targetLanguage);

        XposedBridge.log(TAG + ": Hooking package " + lpparam.packageName);

        // Hook 1: Standard TextView.setText() - Most common method
        hookTextViewSetText(lpparam);

        // Hook 2: StaticLayout.Builder - For custom drawn text
        hookStaticLayoutBuilder(lpparam);

        // Hook 3: Active View Scanner (For Pre-Loaded Layouts)
        hookActivityOnResume(lpparam);

        // Hook 4: Intercepting Transient UI: Toast Popups
        hookToastMakeText(lpparam);

        // Hook 5: Intercepting Action Bars and Context Menus: MenuItem
        hookMenuItemSetTitle(lpparam);

        // Hook 6: Intercepting Hybrid Apps: WebView (DOM Injection)
        hookWebViewClientOnPageFinished(lpparam);
        hookWebViewLoadUrl(lpparam);
        hookWebViewConstructor(lpparam);

        // Hook 7: Intercepting Custom Game Engines / Heavy UI: Canvas.drawText
        hookCanvasDrawText(lpparam);

        // Hook 8: Deep Discovery - Tooltips
        hookTooltipText(lpparam);

        // Hook 9: Deep Discovery - Content Description
        hookContentDescription(lpparam);

        // Hook 10: Deep Discovery - Accessibility Delegate for embedded/complex views
        hookAccessibilityNodeInfo(lpparam);

        // Hook 11: TextView.setHint()
        hookTextViewSetHint(lpparam);

        // Hook 12: TextView.append()
        hookTextViewAppend(lpparam);

        // Hook 13: AlertDialog.Builder setTitle/setMessage
        hookAlertDialogBuilder(lpparam);

        // Hook 14: Translating Outgoing Messages (Keyboard Input)
        hookInputConnectionCommitText(lpparam);

        // Hook 15: Heuristic Dynamic Discovery (Detect Compose, React Native, Obfuscated Standard)
        LingoHookManager.smartlyHookApp(lpparam);
    }

    /**
     * Installs a hook on android.view.inputmethod.InputConnectionWrapper.commitText to translate text
     * being committed by input methods and to warm the module's translation cache.
     *
     * If the committed argument is a non-empty CharSequence and passes translation checks, a cached
     * translation (if present) replaces the argument with the translation appended by the
     * TRANSLATED_TAG. If no cached translation exists, a translation request is made asynchronously
     * and the result is stored in the cache for future calls.
     *
     * @param lpparam the Xposed package load context used to locate and hook the InputConnectionWrapper
     *                class for the target package; installation failures are logged.
    private void hookInputConnectionCommitText(LoadPackageParam lpparam) {
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.view.inputmethod.InputConnectionWrapper", lpparam.classLoader),
                "commitText",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[0] == null || !(param.args[0] instanceof CharSequence)) return;

                        CharSequence originalText = (CharSequence) param.args[0];
                        if (originalText.length() == 0) return;

                        final String textStr = originalText.toString().trim();
                        if (!shouldTranslate(textStr)) return;

                        String cachedTranslation = TranslationCache.get(textStr);
                        if (cachedTranslation != null) {
                            param.args[0] = cachedTranslation + TRANSLATED_TAG;
                        } else {
                            // For commitText we often cannot hold the keyboard thread async.
                            // But doing this will at least trigger the cache for the next time, or we can wait if we restructure.
                            // The simplest approach is synchronous if cached, async pre-fetch otherwise.
                            // Alternatively, we inject it directly. Since this is an Xposed module, let's trigger it.
                            GeminiTranslator.translate(textStr, new TranslationCallback() {
                                @Override
                                public void onSuccess(String translatedText) {
                                    TranslationCache.put(textStr, translatedText);
                                }
                                @Override
                                public void onFailure(String error) {
                                    XposedBridge.log(TAG + ": Translation Failed: " + error);
                                }
                            });
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook InputConnection.commitText: " + e.getMessage());
        }
    }

    /**
     * Translates the first CharSequence argument of a hooked method when eligible and warms the cache.
     *
     * If the first argument is a non-empty CharSequence and passes translation heuristics, this method
     * replaces the argument with a cached translation (appended with the translation marker) when available.
     * If no cached translation exists, it initiates an asynchronous translation and stores the result in
     * the translation cache when successful; it does not modify the current method arguments in that case.
     *
     * @param param the XC_MethodHook.MethodHookParam whose first argument may be inspected and modified
     */
    private void translateCharSequenceArgument(XC_MethodHook.MethodHookParam param) {
        if (param.args[0] == null || !(param.args[0] instanceof CharSequence)) return;

        CharSequence originalText = (CharSequence) param.args[0];
        if (originalText.length() == 0) return;

        final String textStr = originalText.toString().trim();
        if (!shouldTranslate(textStr)) return;

        String cachedTranslation = TranslationCache.get(textStr);
        if (cachedTranslation != null) {
            param.args[0] = cachedTranslation + TRANSLATED_TAG;
        } else {
            GeminiTranslator.translate(textStr, new TranslationCallback() {
                @Override
                public void onSuccess(String translatedText) {
                    TranslationCache.put(textStr, translatedText);
                }
                @Override
                public void onFailure(String error) {
                    // Consider logging failures for debugging purposes
                    XposedBridge.log(TAG + ": Translation Failed: " + error);
                }
            });
        }
    }

    /**
     * Installs a hook on android.view.View.setTooltipText to translate tooltip text before it is applied.
     *
     * <p>If the provided argument is a non-empty CharSequence and meets translation criteria, the hook
     * substitutes a cached translation when available; otherwise it requests a translation asynchronously
     * and, on success, updates the view's tooltip on the UI thread and stores the result in the cache.
     * Inputs that are null, not a CharSequence, empty, or deemed ineligible by {@code shouldTranslate}
     * are left unchanged. Translation failures are logged.</p>
     *
     * @param lpparam the package load context used to resolve and hook the target class/method
     */
    private void hookTooltipText(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.View",
                lpparam.classLoader,
                "setTooltipText",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[0] == null || !(param.args[0] instanceof CharSequence)) return;

                        CharSequence originalText = (CharSequence) param.args[0];
                        if (originalText.length() == 0) return;

                        final String textStr = originalText.toString().trim();
                        if (!shouldTranslate(textStr)) return;

                        String cachedTranslation = TranslationCache.get(textStr);
                        if (cachedTranslation != null) {
                            param.args[0] = cachedTranslation + TRANSLATED_TAG;
                        } else {
                            final android.view.View view = (android.view.View) param.thisObject;
                            GeminiTranslator.translate(textStr, new TranslationCallback() {
                                @Override
                                public void onSuccess(final String translatedText) {
                                    TranslationCache.put(textStr, translatedText);
                                    if (view != null) {
                                        view.post(() -> {
                                            try {
                                                view.setTooltipText(translatedText);
                                            } catch (Exception ex) {
                                                XposedBridge.log(TAG + ": Async setTooltipText failed: " + ex);
                                            }
                                        });
                                    }
                                }
                                @Override
                                public void onFailure(String error) {
                                    XposedBridge.log(TAG + ": Translation Failed: " + error);
                                }
                            });
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook View.setTooltipText: " + e.getMessage());
        }
    }

    /**
     * Installs a hook for android.view.View.setContentDescription to translate and cache content descriptions
     * for the specified loaded package.
     *
     * The hook replaces the method argument with a cached translation (appending the module's translated marker)
     * when available. If no cached translation exists, it asynchronously requests a translation, stores the result
     * in the translation cache, and posts a main-thread update to call setContentDescription with the translated text.
     * Translation failures are logged; the hook silently leaves the original content description unchanged on failure.
     *
     * @param lpparam the package load parameter whose class loader is used to install the hook
     */
    private void hookContentDescription(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.View",
                lpparam.classLoader,
                "setContentDescription",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[0] == null || !(param.args[0] instanceof CharSequence)) return;

                        CharSequence originalText = (CharSequence) param.args[0];
                        if (originalText.length() == 0) return;

                        final String textStr = originalText.toString().trim();
                        if (!shouldTranslate(textStr)) return;

                        String cachedTranslation = TranslationCache.get(textStr);
                        if (cachedTranslation != null) {
                            param.args[0] = cachedTranslation + TRANSLATED_TAG;
                        } else {
                            final android.view.View view = (android.view.View) param.thisObject;
                            GeminiTranslator.translate(textStr, new TranslationCallback() {
                                @Override
                                public void onSuccess(final String translatedText) {
                                    TranslationCache.put(textStr, translatedText);
                                    if (view != null) {
                                        view.post(() -> {
                                            try {
                                                view.setContentDescription(translatedText);
                                            } catch (Exception ex) {
                                                XposedBridge.log(TAG + ": Async setContentDescription failed: " + ex);
                                            }
                                        });
                                    }
                                }
                                @Override
                                public void onFailure(String error) {
                                    XposedBridge.log(TAG + ": Translation Failed: " + error);
                                }
                            });
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook View.setContentDescription: " + e.getMessage());
        }
    }

    /**
     * Installs a hook on View.onInitializeAccessibilityNodeInfo to translate the node's
     * visible text and content description before they are exposed to accessibility services.
     *
     * For each non-empty text or content description that should be translated, the hook:
     * - uses a cached translation when available and writes it back with the module's
     *   translation marker; otherwise
     * - asynchronously requests a translation and stores the result in the translation cache.
     * Failed translations are logged but do not modify the AccessibilityNodeInfo.
     *
     * @param lpparam the Xposed load-package parameter for the package being hooked
     */
    private void hookAccessibilityNodeInfo(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.View",
                lpparam.classLoader,
                "onInitializeAccessibilityNodeInfo",
                AccessibilityNodeInfo.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        AccessibilityNodeInfo info = (AccessibilityNodeInfo) param.args[0];
                        if (info == null) return;

                        CharSequence text = info.getText();
                        if (text != null && text.length() > 0) {
                            String textStr = text.toString().trim();
                            if (shouldTranslate(textStr)) {
                                String translated = TranslationCache.get(textStr);
                                if (translated != null) {
                                    info.setText(translated + TRANSLATED_TAG);
                                } else {
                                    GeminiTranslator.translate(textStr, new TranslationCallback() {
                                        @Override
                                        public void onSuccess(String t) { TranslationCache.put(textStr, t); }
                                        @Override
                                        public void onFailure(String e) {
                                            XposedBridge.log(TAG + ": Translation Failed: " + e);
                                        }
                                    });
                                }
                            }
                        }

                        CharSequence contentDesc = info.getContentDescription();
                        if (contentDesc != null && contentDesc.length() > 0) {
                            String descStr = contentDesc.toString().trim();
                            if (shouldTranslate(descStr)) {
                                String translated = TranslationCache.get(descStr);
                                if (translated != null) {
                                    info.setContentDescription(translated + TRANSLATED_TAG);
                                } else {
                                    GeminiTranslator.translate(descStr, new TranslationCallback() {
                                        @Override
                                        public void onSuccess(String t) { TranslationCache.put(descStr, t); }
                                        @Override
                                        public void onFailure(String e) {
                                            XposedBridge.log(TAG + ": Translation Failed: " + e);
                                        }
                                    });
                                }
                            }
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook View.onInitializeAccessibilityNodeInfo: " + e.getMessage());
        }
    }

    /**
     * Installs an Xposed hook for android.widget.TextView.setHint(CharSequence) that
     * translates the hint text before it is applied.
     *
     * @param lpparam the LoadPackageParam used to locate the TextView class and register the hook
     */
    private void hookTextViewSetHint(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                lpparam.classLoader,
                "setHint",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        translateCharSequenceArgument(param);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook TextView.setHint: " + e.getMessage());
        }
    }

    /**
     * Installs a hook on android.widget.TextView.append(CharSequence) to translate the provided text argument before the original method runs.
     *
     * @param lpparam the Xposed LoadPackageParam used to locate the TextView class and its class loader for installing the hook
     */
    private void hookTextViewAppend(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                lpparam.classLoader,
                "append",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        translateCharSequenceArgument(param);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook TextView.append: " + e.getMessage());
        }
    }

    /**
     * Installs hooks on android.app.AlertDialog.Builder to translate title and message text before they are set.
     *
     * @param lpparam the Xposed package load context whose class loader is used to locate AlertDialog.Builder for hooking
     */
    private void hookAlertDialogBuilder(LoadPackageParam lpparam) {
        try {
            Class<?> builderClass = XposedHelpers.findClass("android.app.AlertDialog$Builder", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                builderClass,
                "setTitle",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        translateCharSequenceArgument(param);
                    }
                }
            );

            XposedHelpers.findAndHookMethod(
                builderClass,
                "setMessage",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        translateCharSequenceArgument(param);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook AlertDialog.Builder: " + e.getMessage());
        }
    }

    /**
     * Installs a hook for android.widget.Toast.makeText that substitutes cached translations and warms the cache.
     *
     * <p>If a cached translation exists for the toast text, the hook replaces the text argument synchronously
     * so the toast is shown translated. If no cached translation exists, the original text is allowed through
     * and a background translation is initiated to populate the cache for subsequent toasts.</p>
     *
     * @param lpparam the Xposed LoadPackageParam for the target package
     */
    private void hookToastMakeText(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.Toast",
                lpparam.classLoader,
                "makeText",
                android.content.Context.class,
                CharSequence.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        CharSequence originalText = (CharSequence) param.args[1];
                        if (originalText == null || originalText.length() == 0) return;

                        String textStr = originalText.toString();
                        String cachedTranslation = TranslationCache.get(textStr);

                        if (cachedTranslation != null) {
                            param.args[1] = cachedTranslation + TRANSLATED_TAG; // Instant synchronous swap
                        } else {
                            // For Toasts, we cannot easily wait for an async network call since the Toast
                            // is created and shown synchronously by the app.
                            // We trigger a background translation so the cache is hot for the next time,
                            // but we must let the original text through this first time.
                            GeminiTranslator.translate(textStr, new TranslationCallback() {
                                @Override
                                public void onSuccess(String translatedText) {
                                    TranslationCache.put(textStr, translatedText);
                                }
                                @Override
                                public void onFailure(String error) { }
                            });
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Toast.makeText: " + e.getMessage());
        }
    }

    /**
     * Installs a hook that translates menu item titles for the currently loaded package.
     *
     * <p>The hook intercepts calls to MenuItem.setTitle(CharSequence), substitutes a cached
     * translation when available, or requests an asynchronous translation and updates the
     * menu item's title on the main thread when the translation completes. If the internal
     * MenuItem implementation class is not found the hook is skipped. Translation failures
     * are logged.
     *
     * @param lpparam the Xposed LoadPackageParam for the package being loaded (provides the class loader and package context)
     */
    private void hookMenuItemSetTitle(LoadPackageParam lpparam) {
        try {
            Class<?> menuItemClass;
            try {
                menuItemClass = XposedHelpers.findClass("com.android.internal.view.menu.MenuItemImpl", lpparam.classLoader);
            } catch (XposedHelpers.ClassNotFoundError e) {
                XposedBridge.log("MenuItemImpl not found. Falling back or skipping.");
                return;
            }

            XposedHelpers.findAndHookMethod(
                menuItemClass,
                "setTitle",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final android.view.MenuItem menuItem = (android.view.MenuItem) param.thisObject;
                        final CharSequence originalCharSeq = (CharSequence) param.args[0];

                        if (originalCharSeq == null || originalCharSeq.length() == 0) return;
                        final String originalText = originalCharSeq.toString();

                        String cachedTranslation = TranslationCache.get(originalText);
                        if (cachedTranslation != null) {
                            param.args[0] = cachedTranslation + TRANSLATED_TAG;
                            return;
                        }

                        // Async fetch for menu items
                        GeminiTranslator.translate(originalText, new TranslationCallback() {
                            @Override
                            public void onSuccess(final String translatedText) {
                                TranslationCache.put(originalText, translatedText);
                                // MenuItems don't have a direct .post() method like Views,
                                // so we execute on the main thread via an Android Handler
                                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // Recursion is naturally prevented because we hit the cache
                                        // on the second pass when this setTitle runs.
                                        menuItem.setTitle(translatedText + TRANSLATED_TAG);
                                    }
                                });
                            }
                            @Override
                            public void onFailure(String error) { XposedBridge.log(TAG + ": MenuItem translation failed: " + error); }
                        });
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook MenuItemImpl.setTitle: " + e.getMessage());
        }
    }

    /**
     * Installs a hook into WebViewClient.onPageFinished to inject a script that extracts visible page text
     * and requests translations via the page's JavaScript bridge.
     *
     * <p>The injected script runs once per page load, walks page text nodes (skipping SCRIPT/STYLE),
     * filters strings by length and simple numeric/symbol patterns, wraps matched text nodes with spans
     * to assign IDs, groups identical strings, and calls window.LingoBridge.requestTranslation(text, ids)
     * for each unique string.</p>
     *
     * @param lpparam the package load context used to locate and hook WebViewClient in the target app
     */
    private void hookWebViewClientOnPageFinished(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.webkit.WebViewClient",
                lpparam.classLoader,
                "onPageFinished",
                "android.webkit.WebView",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        android.webkit.WebView webView = (android.webkit.WebView) param.args[0];

                        String jsPayload = "javascript:(function() { " +
                            "  if (window.lingoInjected) return; " + // Prevent multiple injections
                            "  window.lingoInjected = true; " +
                            "  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false); " +
                            "  var nodesToProcess = []; " +
                            "  var node; " +
                            "  while(node = walker.nextNode()) { nodesToProcess.push(node); } " +
                            "  var idCounter = 0; " +
                            "  var textToIds = {}; " +
                            "  nodesToProcess.forEach(function(n) { " +
                            "    var text = n.nodeValue.trim(); " +
                            "    if (text.length >= 2 && text.length <= 500 && !text.match(/^[0-9,.]+$/) && !text.match(/^[\\u2190-\\u2199\\u25A0-\\u25FF]+$/) && n.parentNode.nodeName !== 'SCRIPT' && n.parentNode.nodeName !== 'STYLE') { " +
                            "       idCounter++; " +
                            "       var uniqueId = 'lingo-node-' + idCounter; " +
                            "       /* Wrap the text node in a span so we have an ID to target later */ " +
                            "       var span = document.createElement('span'); " +
                            "       span.id = uniqueId; " +
                            "       span.innerText = text; " +
                            "       n.parentNode.replaceChild(span, n); " +
                            "       if (!textToIds[text]) { textToIds[text] = []; } " +
                            "       textToIds[text].push(uniqueId); " +
                            "    } " +
                            "  }); " +
                            "  if (window.LingoBridge) { " +
                            "      for (var text in textToIds) { " +
                            "          window.LingoBridge.requestTranslation(text, JSON.stringify(textToIds[text])); " +
                            "      } " +
                            "  } " +
                            "})();";

                        webView.evaluateJavascript(jsPayload, null);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook WebViewClient.onPageFinished: " + e.getMessage());
        }
    }

    /**
     * Hooks WebView.loadUrl(String, Map) and injects a MutationObserver script that detects newly added or changed text nodes and requests translations for them.
     *
     * <p>The injected script runs once per page (guarded by {@code window.lingoObserverInjected}), replaces eligible text nodes with uniquely identified <span> elements, groups identical text strings to arrays of element IDs, and calls {@code window.LingoBridge.requestTranslation(text, idsJson)} for each group so translated text can be applied back to the page.</p>
     *
     * @param lpparam the Xposed LoadPackageParam used to resolve the WebView class loader for hooking
     */
    private void hookWebViewLoadUrl(LoadPackageParam lpparam) {
        try {
            // Target the 2-parameter loadUrl method for dynamic interception
            XposedHelpers.findAndHookMethod(
                "android.webkit.WebView",
                lpparam.classLoader,
                "loadUrl",
                String.class,
                java.util.Map.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        android.webkit.WebView webView = (android.webkit.WebView) param.thisObject;

                        // Injecting a MutationObserver to catch dynamic content additions
                        String observerJs = "javascript:(function() { " +
                            "  if (window.lingoObserverInjected) return; " +
                            "  window.lingoObserverInjected = true; " +
                            "  var observer = new MutationObserver(function(mutations) { " +
                            "    var nodesToProcess = []; " +
                            "    var textToIds = {}; " +
                            "    var idCounter = window.lingoIdCounter || 0; " +
                            "    mutations.forEach(function(mutation) { " +
                            "      if (mutation.type === 'childList') { " +
                            "         mutation.addedNodes.forEach(function(node) { " +
                            "           if (node.nodeType === Node.TEXT_NODE) nodesToProcess.push(node); " +
                            "           else if (node.nodeType === Node.ELEMENT_NODE) { " +
                            "             var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null, false); " +
                            "             var n; while(n = walker.nextNode()) nodesToProcess.push(n); " +
                            "           } " +
                            "         }); " +
                            "      } else if (mutation.type === 'characterData') { " +
                            "         nodesToProcess.push(mutation.target); " +
                            "      } " +
                            "    }); " +
                            "    nodesToProcess.forEach(function(n) { " +
                            "      var text = n.nodeValue ? n.nodeValue.trim() : ''; " +
                            "      if (text.length >= 2 && text.length <= 500 && !text.match(/^[0-9,.]+$/) && !text.match(/^[\\u2190-\\u2199\\u25A0-\\u25FF]+$/) && n.parentNode && n.parentNode.nodeName !== 'SCRIPT' && n.parentNode.nodeName !== 'STYLE') { " +
                            "         idCounter++; " +
                            "         var uniqueId = 'lingo-dyn-' + idCounter; " +
                            "         var span = document.createElement('span'); " +
                            "         span.id = uniqueId; span.innerText = text; " +
                            "         n.parentNode.replaceChild(span, n); " +
                            "         if (!textToIds[text]) textToIds[text] = []; " +
                            "         textToIds[text].push(uniqueId); " +
                            "      } " +
                            "    }); " +
                            "    window.lingoIdCounter = idCounter; " +
                            "    if (window.LingoBridge && Object.keys(textToIds).length > 0) { " +
                            "        for (var text in textToIds) { " +
                            "            window.LingoBridge.requestTranslation(text, JSON.stringify(textToIds[text])); " +
                            "        } " +
                            "    } " +
                            "  }); " +
                            "  observer.observe(document.body, { childList: true, characterData: true, subtree: true }); " +
                            "})();";

                        webView.evaluateJavascript(observerJs, null);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook WebView.loadUrl: " + e.getMessage());
        }
    }

    /**
     * Installs a hook on WebView constructors to attach a JavaScript interface named "LingoBridge".
     *
     * @param lpparam Xposed load package parameter whose class loader is used to find and hook the WebView constructor.
     */
    private void hookWebViewConstructor(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookConstructor(
                "android.webkit.WebView",
                lpparam.classLoader,
                android.content.Context.class,
                android.util.AttributeSet.class,
                int.class,
                int.class,
                java.util.Map.class,
                boolean.class, // Target the deepest constructor to ensure it catches all instantiations
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        android.webkit.WebView webView = (android.webkit.WebView) param.thisObject;

                        // Add the JavascriptInterface. "LingoBridge" is the global JS object name.
                        webView.addJavascriptInterface(new TranslationBridge(webView), "LingoBridge");
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook WebView constructor: " + e.getMessage());
        }
    }

    /**
     * Hooks android.graphics.Canvas.drawText(...) to render cached translations immediately and to
     * asynchronously warm the cache for unseen text.
     *
     * When the method is called with a non-empty string, this hook checks TranslationCache:
     * - If a cached translation exists, it replaces the text argument with the cached translation
     *   appended with TRANSLATED_TAG so the translated text is drawn on the current frame.
     * - If no cached translation exists, it attempts to mark the text as "fetching" to avoid
     *   duplicate requests and submits an asynchronous translation request; on success the result
     *   is stored in TranslationCache and the fetching mark is cleared so subsequent frames can
     *   render the translated text.
     *
     * Empty or null text values are ignored and left unmodified.
     *
     * @param lpparam the LoadPackageParam providing the class loader used to install the hook
     */
    private void hookCanvasDrawText(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.graphics.Canvas",
                lpparam.classLoader,
                "drawText",
                String.class, float.class, float.class, android.graphics.Paint.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String originalText = (String) param.args[0];
                        if (originalText == null || originalText.trim().isEmpty()) return;

                        String cachedTranslation = TranslationCache.get(originalText);

                        if (cachedTranslation != null) {
                            param.args[0] = cachedTranslation + TRANSLATED_TAG; // Instant swap on canvas
                        } else {
                            // 1. Check if we are already fetching this exact string to prevent spam
                            if (TranslationCache.tryMarkFetching(originalText)) {
                                // 2. Queue in background. When it finishes, the NEXT frame drawn
                                // will hit the cache and render the translated text.
                                GeminiTranslator.translate(originalText, new TranslationCallback() {
                                    @Override
                                    public void onSuccess(String translatedText) {
                                        TranslationCache.put(originalText, translatedText);
                                        TranslationCache.unmarkFetching(originalText);
                                    }
                                    @Override
                                    public void onFailure(String error) {
                                        TranslationCache.unmarkFetching(originalText);
                                    }
                                });
                            }
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Canvas.drawText: " + e.getMessage());
        }
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
                        if (activity == null) return;
                        android.view.Window window = activity.getWindow();
                        if (window == null) return;
                        android.view.View decorView = window.getDecorView();
                        if (decorView == null) return;
                        android.view.View rootView = decorView.getRootView();
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
                            tv.post(() -> {
                                android.widget.TextView innerTv = weakTextView.get();
                                if (innerTv != null) {
                                    innerTv.setTag(STATE_TAG_ID, null);
                                }
                            });
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
     * Install hooks for android.text.StaticLayout and its Builder so text rendered via StaticLayout is translated or primed in the translation cache.
     *
     * @param lpparam parameters for the currently loaded package; provides the class loader used to find and hook StaticLayout classes
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
                    /**
                     * Intercepts StaticLayout.Builder.build invocation to replace the builder's `mText`
                     * with a cached translation or to enqueue an asynchronous translation request.
                     *
                     * If a cached translation for the builder's trimmed text exists, the method sets
                     * `mText` to the cached translation appended with the module's translation marker
                     * under a reentrancy guard. If no cached translation exists and the text qualifies
                     * for translation, an asynchronous request is started and the successful result is
                     * stored in the translation cache.
                     *
                     * @param param the Xposed method hook context for the intercepted `build` call;
                     *              `param.thisObject` is expected to be the StaticLayout.Builder instance
                     *              whose `mText` field may be read and updated
                     */
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Boolean.TRUE.equals(isTranslatingStaticLayout.get())) return;

                        CharSequence text = (CharSequence) XposedHelpers.getObjectField(
                            param.thisObject, "mText"
                        );

                        if (text == null || text.length() == 0) return;

                        String original = text.toString().trim();
                        if (!shouldTranslate(original)) return;

                        String cached = TranslationCache.get(original);
                        if (cached != null) {
                            isTranslatingStaticLayout.set(true);
                            try {
                                    XposedHelpers.setObjectField(param.thisObject, "mText", cached + TRANSLATED_TAG);
                            } finally {
                                isTranslatingStaticLayout.remove();
                            }
                        } else {
                            GeminiTranslator.translate(original, new TranslationCallback() {
                                @Override
                                public void onSuccess(String translatedText) {
                                    TranslationCache.put(original, translatedText);
                                }
                                @Override
                                public void onFailure(String error) {}
                            });
                        }
                    }
                }
            );

            Class<?> staticLayoutClass = XposedHelpers.findClass(
                "android.text.StaticLayout",
                lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                staticLayoutClass,
                "getText",
                new XC_MethodHook() {
                    /**
                     * Translates the CharSequence result of a StaticLayout text retrieval when the text is eligible and applies cached translations immediately.
                     *
                     * If the returned text is non-empty and passes translation rules, this hook replaces the method result with a cached translation suffixed by the module's translated marker; if no cached translation exists it asynchronously requests a translation and stores the result in the translation cache when it arrives.
                     *
                     * @param param the method hook parameter containing the original result and allowing the result to be replaced
                     */
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (Boolean.TRUE.equals(isTranslatingStaticLayout.get())) return;

                        CharSequence originalText = (CharSequence) param.getResult();
                        if (originalText == null || originalText.length() == 0) return;

                        String originalStr = originalText.toString().trim();
                        if (!shouldTranslate(originalStr)) return;

                        String translated = TranslationCache.get(originalStr);
                        if (translated != null) {
                            isTranslatingStaticLayout.set(true);
                            try {
                                param.setResult(translated + TRANSLATED_TAG);
                            } finally {
                                isTranslatingStaticLayout.remove();
                            }
                        } else {
                            // Async request to LingoCloud engine
                            GeminiTranslator.translate(originalStr, new TranslationCallback() {
                                @Override
                                public void onSuccess(String translatedText) {
                                    TranslationCache.put(originalStr, translatedText);
                                }
                                @Override
                                public void onFailure(String error) {}
                            });
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": StaticLayout hook failed (optional): " + e.getMessage());
        }
    }

    /**
     * Translate and apply text for a hooked TextView.setText call.
     *
     * Processes the incoming CharSequence argument: performs quick checks, uses a cached
     * translation when available (synchronously replacing the method argument), or
     * initiates an asynchronous translation that updates the TextView on the UI thread
     * if the view's text remains the same. The method also uses view tags to prevent
     * re-entrant translations and to record translation state and the original text.
     *
     * @param param the Xposed hook context for the intercepted TextView.setText invocation;
     *              expects param.thisObject to be the target TextView and param.args[0]
     *              to be the CharSequence being set
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
                param.args[0] = new android.text.SpannableStringBuilder(cachedTranslation + TRANSLATED_TAG);
            } else {
                param.args[0] = cachedTranslation + TRANSLATED_TAG;
            }
            textView.setTag(ORIGINAL_TEXT_TAG_ID, originalText);
            return;
        }

        // 4. Asynchronous Translation Initiation (Slow Path)
        textView.setTag(STATE_TAG_ID, "TRANSLATING"); // Lock the view from triggering more API calls

        final java.lang.ref.WeakReference<android.widget.TextView> weakTextView = new java.lang.ref.WeakReference<>(textView);

        GeminiTranslator.translate(originalText, new TranslationCallback() {
            /**
             * Handle a successful translation by caching the result and updating the original TextView when appropriate.
             *
             * Caches the provided translation for the original source text, then attempts to apply it to the target
             * TextView (referenced by the enclosing `weakTextView`). If the view is not present this method does nothing.
             * If the view is not currently attached to a window, a runnable is posted to clear the translation state tag
             * later. If the view is attached, a runnable is posted to the UI thread which applies the translation only
             * if the view's current trimmed text still equals the original source text; in that case the method sets the
             * state and original-text tags and replaces the view text with `translatedText + TRANSLATED_TAG`. If the view's
             * text changed in the meantime, the method clears the state tag and leaves the cached translation available for
             * future use.
             *
             * @param translatedText the translated text to cache and (if valid) set on the TextView
             */
            @Override
            public void onSuccess(final String translatedText) {
                TranslationCache.put(originalText, translatedText);

                android.widget.TextView tv = weakTextView.get();
                if (tv == null) return;
                if (!tv.isAttachedToWindow() || tv.getWindowToken() == null) {
                    tv.post(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.TextView innerTv = weakTextView.get();
                            if (innerTv != null) {
                                innerTv.setTag(STATE_TAG_ID, null);
                            }
                        }
                    });
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
                                innerTv.setText(translatedText + TRANSLATED_TAG);
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
     * Determines whether the provided text qualifies for translation.
     *
     * Evaluates null/empty, presence of the translation marker (`TRANSLATED_TAG`),
     * length bounds (`MIN_TEXT_LENGTH`..`MAX_TEXT_LENGTH`), numeric-only and
     * symbol-only patterns, and cache presence. If the text is already cached,
     * this method returns `true` so callers may apply the cached translation.
     *
     * @param text the text to evaluate
     * @return `true` if the text should be translated, `false` otherwise
     */
    private static boolean shouldTranslate(String text) {
        if (text == null || text.isEmpty()) return false;

        // Skip if it contains our translation marker
        if (text.contains(TRANSLATED_TAG)) return false;

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
    private static Set<String> parseWhitelist(String whitelist) {
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
    static class TranslationCache {
        private static final Set<String> fetchingSet = new HashSet<>();

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

        public static boolean isFetching(String key) {
            synchronized (fetchingSet) {
                return fetchingSet.contains(key);
            }
        }

        public static boolean tryMarkFetching(String key) {
            synchronized (fetchingSet) {
                if (fetchingSet.contains(key)) {
                    return false;
                }
                fetchingSet.add(key);
                return true;
            }
        }

        public static void markFetching(String key) {
            synchronized (fetchingSet) {
                fetchingSet.add(key);
            }
        }

        public static void unmarkFetching(String key) {
            synchronized (fetchingSet) {
                fetchingSet.remove(key);
            }
        }

        public static void clear() {
            synchronized (cacheLock) {
                translationCache.evictAll();
            }
            synchronized (fetchingSet) {
                fetchingSet.clear();
            }
        }
    }

    /**
     * Callback interface to match execution spec literally
     */
    interface TranslationCallback {
        void onSuccess(String translatedText);
        void onFailure(String error);
    }

    /**
     * Helper API Class to match execution spec literally
     */
    static class GeminiTranslator {
        private static String apiKey = "";
        private static String backupApiKey = "";
        public static String targetLanguage = "en";
        private static String service = "Gemini";
        private static boolean useBackup = false;
        private static long backupFallbackTime = 0;

        public static void setConfiguration(String svc, String key, String backupKey, String lang) {
            service = svc;
            apiKey = key;
            backupApiKey = backupKey;
            targetLanguage = lang;
        }

        public static void translate(String text, TranslationCallback callback) {
            if (apiKey.isEmpty()) {
                callback.onFailure("API key not configured");
                return;
            }

            // Reset backup flag if 1 hour has passed since fallback
            if (useBackup && (System.currentTimeMillis() - backupFallbackTime > 3600000)) {
                useBackup = false;
                client.resetClient();
            }

            String currentKey = useBackup && !backupApiKey.isEmpty() ? backupApiKey : apiKey;

            executor.execute(() -> {
                try {
                    client.translate(text, service, currentKey, targetLanguage, new TranslationClient.TranslationCallback() {
                        @Override
                        public void onResult(String result) {
                            if (result != null && result.startsWith("ERROR_LIMIT_EXCEEDED")) {
                                if (!useBackup && !backupApiKey.isEmpty()) {
                                    useBackup = true;
                                    backupFallbackTime = System.currentTimeMillis();
                                    client.resetClient();
                                    // Retry once with backup key
                                    translate(text, callback);
                                } else {
                                    callback.onFailure("API limits exceeded for both keys");
                                }
                            } else if (result != null && !result.isEmpty()) {
                                callback.onSuccess(result);
                            } else {
                                callback.onFailure("Translation result empty or null");
                            }
                        }
                    });
                } catch (Exception e) {
                    callback.onFailure("Exception during translation: " + e.getMessage());
                }
            });
        }
    }
}
