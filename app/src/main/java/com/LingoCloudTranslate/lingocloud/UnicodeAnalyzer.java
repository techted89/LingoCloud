package com.LingoCloudTranslate.lingocloud;

import java.util.regex.Pattern;

public class UnicodeAnalyzer {
    // Strips out things that don't need translation:
    // Numbers, standard punctuation, whitespace, and Emojis (Surrogate pairs & Misc Symbols)
    private static final Pattern UNIVERSAL_CHARS_PATTERN = Pattern.compile(
            "[\\d\\s\\p{Punct}\\p{So}\\p{C}\ud83c\udf00-\ud83d\ude4f\ud83d\ude80-\ud83d\udeff]+",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    // Unicode block definitions for common target languages
    private static final Pattern BLOCK_LATIN = Pattern.compile("^[\\p{IsLatin}]+$");
    private static final Pattern BLOCK_CYRILLIC = Pattern.compile("^[\\p{IsCyrillic}]+$");
    private static final Pattern BLOCK_CJK = Pattern.compile("^[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+$");
    private static final Pattern BLOCK_ARABIC = Pattern.compile("^[\\p{IsArabic}]+$");

    /**
     * Analyzes if the text contains characters foreign to the target language.
     * @param text The raw text from the UI hook.
     * @param targetLanguageCode The user's selected language (e.g., "en", "ru", "zh").
     * @return true if foreign characters are detected, requiring translation.
     */
    public static boolean requiresTranslation(String text, String targetLanguageCode) {
        // 1. Strip all universal characters (emojis, numbers, spaces, punctuation)
        String strippedText = UNIVERSAL_CHARS_PATTERN.matcher(text).replaceAll("");

        // 2. If nothing is left (e.g., text was just "123 😊"), no translation needed
        if (strippedText.isEmpty()) {
            return false;
        }

        if (targetLanguageCode == null) return true;

        // 3. Match the remaining characters against the user's known language block.
        // If the stripped text DOES NOT fully match the target block, it means foreign text is present.
        switch (targetLanguageCode.toLowerCase()) {
            case "en":
            case "es":
            case "fr":
            case "de":
            case "it":
            case "pt":
                // If it contains ANYTHING outside the Latin block (e.g., Japanese characters), this returns false,
                // which we invert to true (requires translation).
                return !BLOCK_LATIN.matcher(strippedText).matches();

            case "ru":
                return !BLOCK_CYRILLIC.matcher(strippedText).matches();

            case "zh":
            case "ja":
            case "ko":
                return !BLOCK_CJK.matcher(strippedText).matches();

            case "ar":
                return !BLOCK_ARABIC.matcher(strippedText).matches();

            default:
                // Fallback: If we don't have a strict block, assume it needs an API check
                return true;
        }
    }
}
