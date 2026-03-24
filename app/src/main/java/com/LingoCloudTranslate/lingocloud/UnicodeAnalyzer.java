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
     * Determines whether UI text contains characters outside the expected Unicode script for a target language.
     *
     * <p>The method first removes "universal" characters (digits, whitespace, punctuation, symbol and certain emoji ranges)
     * before checking whether the remaining characters belong entirely to the target language's primary Unicode block.
     * If the stripped text is empty, the method returns `false`. If `targetLanguageCode` is `null` or not recognized, the
     * method returns `true`.</p>
     *
     * @param text the raw UI text to analyze; universal characters (digits, whitespace, punctuation, symbols, some emoji) are ignored
     * @param targetLanguageCode the target language code (case-insensitive). Supported codes: "en", "es", "fr", "de", "it", "pt" (Latin),
     *                           "ru" (Cyrillic), "zh","ja","ko" (CJK/Han/Hiragana/Katakana/Hangul), and "ar" (Arabic)
     * @return `true` if one or more characters outside the target language's primary Unicode script remain after stripping universal characters, `false` otherwise
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
