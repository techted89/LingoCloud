package com.LingoCloudTranslate.lingocloud;

public class PayloadValidator {
    /**
     * Decides whether a given text payload should be translated.
     *
     * The method rejects null or very short inputs, strings that contain the translation marker,
     * strings that look like URLs or file/content paths, and strings that appear to be whole
     * JSON or XML snippets. For other inputs, it uses Unicode/character analysis relative to
     * the specified target language to determine whether translation is necessary.
     *
     * @param originalText the text to evaluate for translation eligibility
     * @param userTargetLang the target language code used to assess whether translation is needed
     * @return `true` if the text should be translated, `false` otherwise
     */
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
