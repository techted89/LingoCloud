package com.LingoCloudTranslate.lingocloud;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for HookMain inner classes and static utility methods introduced in this PR:
 *   - HookMain.TranslationCache (fetching-set operations — the LruCache backing store is
 *     an Android class that returns default values in JVM unit tests, so put/get round-trip
 *     tests are omitted here and rely on device/instrumented tests instead)
 *   - HookMain.shouldTranslate() (via reflection)
 *   - HookMain.parseWhitelist() (via reflection)
 */
public class HookMainTest {

    // -------------------------------------------------------------------------
    // Test lifecycle
    // -------------------------------------------------------------------------

    @Before
    public void setUp() {
        // Reset shared static state before each test so tests are independent.
        HookMain.TranslationCache.clear();
    }

    @After
    public void tearDown() {
        HookMain.TranslationCache.clear();
    }

    // =========================================================================
    // TranslationCache – fetching-set operations
    // These use java.util.HashSet (pure Java) and work correctly in JVM tests.
    // =========================================================================

    @Test
    public void cache_isFetching_returnsFalseByDefault() {
        assertFalse(HookMain.TranslationCache.isFetching("someKey"));
    }

    @Test
    public void cache_markFetching_makesIsFetchingReturnTrue() {
        HookMain.TranslationCache.markFetching("someKey");
        assertTrue(HookMain.TranslationCache.isFetching("someKey"));
    }

    @Test
    public void cache_unmarkFetching_makesIsFetchingReturnFalse() {
        HookMain.TranslationCache.markFetching("someKey");
        HookMain.TranslationCache.unmarkFetching("someKey");
        assertFalse(HookMain.TranslationCache.isFetching("someKey"));
    }

    @Test
    public void cache_unmarkFetching_onNonExistentKey_doesNotThrow() {
        // Should be a no-op, not an exception
        HookMain.TranslationCache.unmarkFetching("key_that_was_never_marked");
        assertFalse(HookMain.TranslationCache.isFetching("key_that_was_never_marked"));
    }

    @Test
    public void cache_tryMarkFetching_returnsTrueFirstTime() {
        assertTrue(HookMain.TranslationCache.tryMarkFetching("key1"));
    }

    @Test
    public void cache_tryMarkFetching_returnsFalseIfAlreadyMarked() {
        HookMain.TranslationCache.tryMarkFetching("key1");
        assertFalse(HookMain.TranslationCache.tryMarkFetching("key1"));
    }

    @Test
    public void cache_tryMarkFetching_returnsTrueAfterUnmark() {
        HookMain.TranslationCache.tryMarkFetching("key1");
        HookMain.TranslationCache.unmarkFetching("key1");
        assertTrue(HookMain.TranslationCache.tryMarkFetching("key1"));
    }

    @Test
    public void cache_clear_clearsFetchingSet() {
        HookMain.TranslationCache.markFetching("pendingKey");
        HookMain.TranslationCache.clear();
        assertFalse(HookMain.TranslationCache.isFetching("pendingKey"));
    }

    @Test
    public void cache_clear_allowsReuseOfPreviouslyFetchedKey() {
        // After clear, a key that was in-flight should be claimable again
        HookMain.TranslationCache.tryMarkFetching("reuseKey");
        HookMain.TranslationCache.clear();
        assertTrue(HookMain.TranslationCache.tryMarkFetching("reuseKey"));
    }

    @Test
    public void cache_multipleDifferentKeys_areTrackedIndependently() {
        HookMain.TranslationCache.markFetching("keyA");
        assertFalse(HookMain.TranslationCache.isFetching("keyB"));
        assertTrue(HookMain.TranslationCache.isFetching("keyA"));
    }

    @Test
    public void cache_get_returnsNullForUnknownKey() {
        // LruCache stub returns null for all gets in JVM unit tests, which matches
        // the expected semantics of a cache miss.
        assertNull(HookMain.TranslationCache.get("unknown_key"));
    }

    // =========================================================================
    // shouldTranslate() – private static, accessed via reflection
    // =========================================================================

    private static boolean callShouldTranslate(String text) throws Exception {
        Method m = HookMain.class.getDeclaredMethod("shouldTranslate", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, text);
    }

    @Test
    public void shouldTranslate_null_returnsFalse() throws Exception {
        assertFalse(callShouldTranslate(null));
    }

    @Test
    public void shouldTranslate_emptyString_returnsFalse() throws Exception {
        assertFalse(callShouldTranslate(""));
    }

    @Test
    public void shouldTranslate_singleChar_returnsFalse() throws Exception {
        // MIN_TEXT_LENGTH = 2, so a 1-char string should be skipped
        assertFalse(callShouldTranslate("A"));
    }

    @Test
    public void shouldTranslate_twoCharString_returnsTrue() throws Exception {
        // Exactly at the minimum boundary
        assertTrue(callShouldTranslate("Hi"));
    }

    @Test
    public void shouldTranslate_normalText_returnsTrue() throws Exception {
        assertTrue(callShouldTranslate("Hello World"));
    }

    @Test
    public void shouldTranslate_numericOnly_returnsFalse() throws Exception {
        assertFalse(callShouldTranslate("12345"));
    }

    @Test
    public void shouldTranslate_numericWithCommaAndDot_returnsFalse() throws Exception {
        // Matches ^[0-9,.]+$
        assertFalse(callShouldTranslate("1,234.56"));
    }

    @Test
    public void shouldTranslate_arrowSymbols_returnsFalse() throws Exception {
        // Unicode range \u2190-\u2199 (arrows)
        assertFalse(callShouldTranslate("\u2190\u2191"));
    }

    @Test
    public void shouldTranslate_boxDrawingSymbols_returnsFalse() throws Exception {
        // Unicode range \u25A0-\u25FF (geometric shapes / box drawing)
        assertFalse(callShouldTranslate("\u25A0\u25FF"));
    }

    @Test
    public void shouldTranslate_tooLongText_returnsFalse() throws Exception {
        // MAX_TEXT_LENGTH = 500; build a 501-char string
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 501; i++) sb.append('a');
        assertFalse(callShouldTranslate(sb.toString()));
    }

    @Test
    public void shouldTranslate_exactlyMaxLength_returnsTrue() throws Exception {
        // MAX_TEXT_LENGTH = 500; a 500-char string of regular text should pass
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append('a');
        assertTrue(callShouldTranslate(sb.toString()));
    }

    @Test
    public void shouldTranslate_mixedAlphanumeric_returnsTrue() throws Exception {
        // Contains letters, so doesn't match numeric-only or symbol-only patterns
        assertTrue(callShouldTranslate("3 items"));
    }

    @Test
    public void shouldTranslate_singleDigit_returnsFalse() throws Exception {
        // Length = 1 < MIN_TEXT_LENGTH
        assertFalse(callShouldTranslate("5"));
    }

    // =========================================================================
    // parseWhitelist() – private static, accessed via reflection
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Set<String> callParseWhitelist(String input) throws Exception {
        Method m = HookMain.class.getDeclaredMethod("parseWhitelist", String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(null, input);
    }

    @Test
    public void parseWhitelist_singlePackage_returnsSetWithOneEntry() throws Exception {
        Set<String> result = callParseWhitelist("com.example.app");
        assertEquals(1, result.size());
        assertTrue(result.contains("com.example.app"));
    }

    @Test
    public void parseWhitelist_multiplePackages_returnsAllEntries() throws Exception {
        Set<String> result = callParseWhitelist("com.instagram.android,com.twitter.android");
        assertEquals(2, result.size());
        assertTrue(result.contains("com.instagram.android"));
        assertTrue(result.contains("com.twitter.android"));
    }

    @Test
    public void parseWhitelist_packagesWithSpaces_trimsAndReturnsCorrectly() throws Exception {
        Set<String> result = callParseWhitelist("  com.foo.app , com.bar.app  ");
        assertEquals(2, result.size());
        assertTrue(result.contains("com.foo.app"));
        assertTrue(result.contains("com.bar.app"));
    }

    @Test
    public void parseWhitelist_emptyString_returnsEmptySet() throws Exception {
        Set<String> result = callParseWhitelist("");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void parseWhitelist_onlyCommasAndSpaces_returnsEmptySet() throws Exception {
        Set<String> result = callParseWhitelist(" , , , ");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void parseWhitelist_duplicatePackage_isDeduplicatedBySet() throws Exception {
        Set<String> result = callParseWhitelist("com.example.app,com.example.app");
        assertEquals(1, result.size());
    }

    @Test
    public void parseWhitelist_threePackages_returnsAllThree() throws Exception {
        Set<String> result = callParseWhitelist("a.b.c,d.e.f,g.h.i");
        assertEquals(3, result.size());
        assertTrue(result.contains("a.b.c"));
        assertTrue(result.contains("d.e.f"));
        assertTrue(result.contains("g.h.i"));
    }

    @Test
    public void parseWhitelist_singlePackageWithTrailingComma_ignoresTrailingEmpty() throws Exception {
        // "com.foo," splits into ["com.foo", ""] – the empty entry should be dropped
        Set<String> result = callParseWhitelist("com.foo,");
        assertEquals(1, result.size());
        assertTrue(result.contains("com.foo"));
    }
}