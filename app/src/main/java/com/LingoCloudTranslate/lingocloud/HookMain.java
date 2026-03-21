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
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final ThreadLocal<Boolean> isTranslatingStaticLayout = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Boolean> staticLayoutEligibilityCache = new ConcurrentHashMap<>();

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
        Set<String> enabledApps = prefs.getStringSet("app_whitelist", new HashSet<>());
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
        hookWebViewConstructor(lpparam);

        // Hook 7: Intercepting Custom Game Engines / Heavy UI: Canvas.drawText
        hookCanvasDrawText(lpparam);

        // Hook 8: Deep Discovery - Tooltips
        hookTooltipText(lpparam);

        // Hook 9: Deep Discovery - Content Description
        hookContentDescription(lpparam);

        // Hook 10: Deep Discovery - Accessibility Delegate for embedded/complex views
        hookAccessibilityNodeInfo(lpparam);
    }

    private void translateCharSequenceArgument(XC_MethodHook.MethodHookParam param) {
        if (param.args[0] == null || !(param.args[0] instanceof CharSequence)) return;

        CharSequence originalText = (CharSequence) param.args[0];
        if (originalText.length() == 0) return;

        final String textStr = originalText.toString().trim();
        if (!shouldTranslate(textStr)) return;

        String cachedTranslation = TranslationCache.get(textStr);
        if (cachedTranslation != null) {
            param.args[0] = cachedTranslation;
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
                        translateCharSequenceArgument(param);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook View.setTooltipText: " + e.getMessage());
        }
    }

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
                        translateCharSequenceArgument(param);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook View.setContentDescription: " + e.getMessage());
        }
    }

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
                                    info.setText(translated);
                                } else {
                                    GeminiTranslator.translate(textStr, new TranslationCallback() {
                                        @Override
                                        public void onSuccess(String t) { TranslationCache.put(textStr, t); }
                                        @Override
                                        public void onFailure(String e) {}
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
                                    info.setContentDescription(translated);
                                } else {
                                    GeminiTranslator.translate(descStr, new TranslationCallback() {
                                        @Override
                                        public void onSuccess(String t) { TranslationCache.put(descStr, t); }
                                        @Override
                                        public void onFailure(String e) {}
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
                            param.args[1] = cachedTranslation; // Instant synchronous swap
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
                            param.args[0] = cachedTranslation;
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
                                        menuItem.setTitle(translatedText);
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
                            param.args[0] = cachedTranslation; // Instant swap on canvas
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
                        if (Boolean.TRUE.equals(isTranslatingStaticLayout.get())) return;

                        CharSequence text = (CharSequence) XposedHelpers.getObjectField(
                            param.thisObject, "mText"
                        );

                        if (text == null || text.length() == 0) return;

                        String original = text.toString().trim();

                        Boolean isEligible = staticLayoutEligibilityCache.get(original);
                        if (isEligible == null) {
                            isEligible = shouldTranslate(original);
                            staticLayoutEligibilityCache.put(original, isEligible);
                        }
                        if (!isEligible) return;

                        String cached = TranslationCache.get(original);
                        if (cached != null) {
                            isTranslatingStaticLayout.set(true);
                            try {
                                XposedHelpers.setObjectField(param.thisObject, "mText", cached);
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
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (Boolean.TRUE.equals(isTranslatingStaticLayout.get())) return;

                        CharSequence originalText = (CharSequence) param.getResult();
                        if (originalText == null || originalText.length() == 0) return;

                        String originalStr = originalText.toString().trim();

                        Boolean isEligible = staticLayoutEligibilityCache.get(originalStr);
                        if (isEligible == null) {
                            isEligible = shouldTranslate(originalStr);
                            staticLayoutEligibilityCache.put(originalStr, isEligible);
                        }
                        if (!isEligible) return;

                        String translated = TranslationCache.get(originalStr);
                        if (translated != null) {
                            isTranslatingStaticLayout.set(true);
                            try {
                                param.setResult(translated);
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
        private static String targetLanguage = "en";
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
