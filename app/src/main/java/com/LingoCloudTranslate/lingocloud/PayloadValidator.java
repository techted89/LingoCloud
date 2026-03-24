package com.LingoCloudTranslate.lingocloud;

public class PayloadValidator {
    public static boolean textShouldTranslate(CharSequence originalText, String userTargetLang) {
        if (originalText == null || originalText.length() < 2) return false;

        String text = originalText.toString();

        // 1. Check for infinite loop marker (already translated)
        if (text.contains(LingoHookManager.TRANSLATED_TAG)) return false;

        // 2. Ignore URLs and file paths (prevent translating system intents)
        if (text.startsWith("http") || text.startsWith("www.") || text.startsWith("/") || text.startsWith("content://")) {
            return false;
        }

        // 3. Ignore JSON or XML snippets
        if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("<") && text.endsWith(">"))) {
            return false;
        }

        // 4. SMART DETECTION: Check if the text actually contains foreign characters
        return UnicodeAnalyzer.requiresTranslation(text, userTargetLang);
    }
}
