package com.LingoCloudTranslate.lingocloud;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for HookMain inner classes and static methods added/changed in this PR:
 *  - TranslationCache (get, put, isFetching, tryMarkFetching, markFetching, unmarkFetching, clear)
 *  - GeminiTranslator.translate (empty-key fast path, no-backup-key exhaustion path)
 *  - shouldTranslate (via reflection)
 *  - parseWhitelist (via reflection)
 */
public class HookMainTest {

    // -------------------------------------------------------------------------
    // Helpers to reset static state between tests
    // -------------------------------------------------------------------------

    @Before
    public void resetCacheAndTranslator() throws Exception {
        // Clear the LruCache wrapper and the fetching set
        HookMain.TranslationCache.clear();

        // Reset GeminiTranslator static fields to a safe default
        setGeminiField("apiKey", "");
        setGeminiField("backupApiKey", "");
        setGeminiField("targetLanguage", "en");
        setGeminiField("service", "Gemini");
        setGeminiField("useBackup", false);
        setGeminiField("backupFallbackTime", 0L);
    }

    @After
    public void tearDown() throws Exception {
        HookMain.TranslationCache.clear();
    }

    private static void setGeminiField(String name, Object value) throws Exception {
        Field f = HookMain.GeminiTranslator.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Object getGeminiField(String name) throws Exception {
        Field f = HookMain.GeminiTranslator.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    // =========================================================================
    // TranslationCache – fetchingSet operations
    // These work in unit tests because the fetchingSet is a plain HashSet.
    // =========================================================================

    @Test
    public void translationCache_isFetching_returnsFalseForUnknownKey() {
        assertFalse(HookMain.TranslationCache.isFetching("unknownKey"));
    }

    @Test
    public void translationCache_markFetching_makesIsFetchingReturnTrue() {
        HookMain.TranslationCache.markFetching("hello");
        assertTrue(HookMain.TranslationCache.isFetching("hello"));
    }

    @Test
    public void translationCache_unmarkFetching_clearsMarkedKey() {
        HookMain.TranslationCache.markFetching("hello");
        HookMain.TranslationCache.unmarkFetching("hello");
        assertFalse(HookMain.TranslationCache.isFetching("hello"));
    }

    @Test
    public void translationCache_tryMarkFetching_returnsTrueOnFirstCall() {
        boolean result = HookMain.TranslationCache.tryMarkFetching("world");
        assertTrue(result);
        assertTrue(HookMain.TranslationCache.isFetching("world"));
    }

    @Test
    public void translationCache_tryMarkFetching_returnsFalseWhenAlreadyMarked() {
        HookMain.TranslationCache.tryMarkFetching("dup");
        boolean second = HookMain.TranslationCache.tryMarkFetching("dup");
        assertFalse("Second tryMarkFetching should return false", second);
    }

    @Test
    public void translationCache_clear_removesFetchingEntries() {
        HookMain.TranslationCache.markFetching("a");
        HookMain.TranslationCache.markFetching("b");
        HookMain.TranslationCache.clear();
        assertFalse(HookMain.TranslationCache.isFetching("a"));
        assertFalse(HookMain.TranslationCache.isFetching("b"));
    }

    @Test
    public void translationCache_tryMarkFetching_worksForMultipleDistinctKeys() {
        assertTrue(HookMain.TranslationCache.tryMarkFetching("key1"));
        assertTrue(HookMain.TranslationCache.tryMarkFetching("key2"));
        // key1 should still be marked
        assertFalse(HookMain.TranslationCache.tryMarkFetching("key1"));
        // key2 should still be marked
        assertFalse(HookMain.TranslationCache.tryMarkFetching("key2"));
    }

    @Test
    public void translationCache_unmarkFetchingOnNonMarkedKey_doesNotThrow() {
        // Should be a no-op
        HookMain.TranslationCache.unmarkFetching("notMarked");
        assertFalse(HookMain.TranslationCache.isFetching("notMarked"));
    }

    // =========================================================================
    // GeminiTranslator – fast-path tests (no network calls required)
    // =========================================================================

    @Test
    public void geminiTranslator_emptyApiKey_callsOnFailureImmediately() {
        // apiKey is "" by default after resetCacheAndTranslator()
        final String[] errorOut = {null};
        final boolean[] successCalled = {false};

        HookMain.GeminiTranslator.translate("Hello", new HookMain.TranslationCallback() {
            @Override
            public void onSuccess(String translatedText) {
                successCalled[0] = true;
            }

            @Override
            public void onFailure(String error) {
                errorOut[0] = error;
            }
        });

        // The empty-key branch is synchronous
        assertFalse("onSuccess should NOT be called", successCalled[0]);
        assertNotNull("onFailure should be called", errorOut[0]);
        assertEquals("API key not configured", errorOut[0]);
    }

    @Test
    public void geminiTranslator_setConfiguration_persistsValues() throws Exception {
        HookMain.GeminiTranslator.setConfiguration("Microsoft", "testKey", "backupKey", "fr");

        assertEquals("Microsoft", getGeminiField("service"));
        assertEquals("testKey", getGeminiField("apiKey"));
        assertEquals("backupKey", getGeminiField("backupApiKey"));
        assertEquals("fr", getGeminiField("targetLanguage"));
    }

    @Test
    public void geminiTranslator_setConfiguration_emptyApiKey_translateFails() {
        HookMain.GeminiTranslator.setConfiguration("Gemini", "", "", "en");

        final String[] errorOut = {null};
        HookMain.GeminiTranslator.translate("text", new HookMain.TranslationCallback() {
            @Override
            public void onSuccess(String t) {}

            @Override
            public void onFailure(String error) {
                errorOut[0] = error;
            }
        });

        assertEquals("API key not configured", errorOut[0]);
    }

    // =========================================================================
    // shouldTranslate – tested via reflection (private static method)
    // =========================================================================

    private static boolean invokeShouldTranslate(String text) throws Exception {
        Method m = HookMain.class.getDeclaredMethod("shouldTranslate", String.class);
        m.setAccessible(true);
        // Pass as Object array to avoid ambiguity when text is null
        return (Boolean) m.invoke(null, new Object[]{text});
    }

    @Test
    public void shouldTranslate_nullText_returnsFalse() throws Exception {
        assertFalse(invokeShouldTranslate(null));
    }

    @Test
    public void shouldTranslate_emptyText_returnsFalse() throws Exception {
        assertFalse(invokeShouldTranslate(""));
    }

    @Test
    public void shouldTranslate_singleCharText_returnsFalse() throws Exception {
        // MIN_TEXT_LENGTH = 2, length 1 < 2
        assertFalse(invokeShouldTranslate("A"));
    }

    @Test
    public void shouldTranslate_twoCharText_returnsTrue() throws Exception {
        // Exactly at MIN_TEXT_LENGTH boundary
        assertTrue(invokeShouldTranslate("Hi"));
    }

    @Test
    public void shouldTranslate_textExactlyAtMaxLength_returnsTrue() throws Exception {
        // MAX_TEXT_LENGTH = 500; a string of 500 'a' chars should be translatable
        String text = repeat("a", 500);
        assertTrue(invokeShouldTranslate(text));
    }

    @Test
    public void shouldTranslate_textExceedingMaxLength_returnsFalse() throws Exception {
        // 501 chars > MAX_TEXT_LENGTH
        String text = repeat("a", 501);
        assertFalse(invokeShouldTranslate(text));
    }

    @Test
    public void shouldTranslate_numericOnlyText_returnsFalse() throws Exception {
        assertFalse(invokeShouldTranslate("12345"));
        assertFalse(invokeShouldTranslate("3.14"));
        assertFalse(invokeShouldTranslate("1,000"));
    }

    @Test
    public void shouldTranslate_arrowSymbolsOnly_returnsFalse() throws Exception {
        // Unicode arrows \u2190-\u2199
        assertFalse(invokeShouldTranslate("\u2190\u2192"));
    }

    @Test
    public void shouldTranslate_normalText_returnsTrue() throws Exception {
        assertTrue(invokeShouldTranslate("Hello World"));
        assertTrue(invokeShouldTranslate("Click here"));
    }

    @Test
    public void shouldTranslate_mixedAlphanumeric_returnsTrue() throws Exception {
        // Contains letters, so not purely numeric
        assertTrue(invokeShouldTranslate("Version 1.0"));
    }

    // =========================================================================
    // parseWhitelist – tested via reflection (private static method)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Set<String> invokeParseWhitelist(String input) throws Exception {
        Method m = HookMain.class.getDeclaredMethod("parseWhitelist", String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(null, input);
    }

    @Test
    public void parseWhitelist_singlePackage_returnsSetWithOneEntry() throws Exception {
        Set<String> result = invokeParseWhitelist("com.example.app");
        assertEquals(1, result.size());
        assertTrue(result.contains("com.example.app"));
    }

    @Test
    public void parseWhitelist_multiplePackages_returnsAllEntries() throws Exception {
        Set<String> result = invokeParseWhitelist("com.instagram.android, com.twitter.android");
        assertEquals(2, result.size());
        assertTrue(result.contains("com.instagram.android"));
        assertTrue(result.contains("com.twitter.android"));
    }

    @Test
    public void parseWhitelist_emptyString_returnsEmptySet() throws Exception {
        Set<String> result = invokeParseWhitelist("");
        assertTrue(result.isEmpty());
    }

    @Test
    public void parseWhitelist_trailingComma_ignoresEmptyEntry() throws Exception {
        Set<String> result = invokeParseWhitelist("com.example.app,");
        assertEquals(1, result.size());
        assertTrue(result.contains("com.example.app"));
    }

    @Test
    public void parseWhitelist_extraWhitespace_trimsEntries() throws Exception {
        Set<String> result = invokeParseWhitelist("  com.foo.bar  ,  com.baz.qux  ");
        assertEquals(2, result.size());
        assertTrue(result.contains("com.foo.bar"));
        assertTrue(result.contains("com.baz.qux"));
    }

    @Test
    public void parseWhitelist_duplicatePackages_deduplicates() throws Exception {
        Set<String> result = invokeParseWhitelist("com.dup.app,com.dup.app");
        assertEquals(1, result.size());
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }
}