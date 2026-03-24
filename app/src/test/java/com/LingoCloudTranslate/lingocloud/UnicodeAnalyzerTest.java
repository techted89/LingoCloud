package com.LingoCloudTranslate.lingocloud;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnicodeAnalyzerTest {

    @Test
    public void testRequiresTranslation_pureTargetLanguage() {
        assertFalse(UnicodeAnalyzer.requiresTranslation("Hello world! 123 😊", "en"));
        assertFalse(UnicodeAnalyzer.requiresTranslation("Bonjour le monde", "fr"));
    }

    @Test
    public void testRequiresTranslation_pureForeignLanguage() {
        assertTrue(UnicodeAnalyzer.requiresTranslation("こんにちは", "en"));
        assertTrue(UnicodeAnalyzer.requiresTranslation("Привет", "en"));
    }

    @Test
    public void testRequiresTranslation_mixedLanguage() {
        assertTrue(UnicodeAnalyzer.requiresTranslation("My friend said こんにちは", "en"));
        assertTrue(UnicodeAnalyzer.requiresTranslation("Order status: 準備中", "en"));
    }

    @Test
    public void testRequiresTranslation_cjkBlock() {
        // Should return false because Japanese is fully in CJK
        assertFalse(UnicodeAnalyzer.requiresTranslation("こんにちは", "ja"));
        // Should return false because Chinese is fully in CJK
        assertFalse(UnicodeAnalyzer.requiresTranslation("你好 123", "zh"));
    }

    @Test
    public void testRequiresTranslation_universalCharactersOnly() {
        // Only numbers, punctuation, spaces, emojis
        assertFalse(UnicodeAnalyzer.requiresTranslation("123 456!!! 😊", "en"));
        assertFalse(UnicodeAnalyzer.requiresTranslation("123 456!!! 😊", "zh"));
    }
}
