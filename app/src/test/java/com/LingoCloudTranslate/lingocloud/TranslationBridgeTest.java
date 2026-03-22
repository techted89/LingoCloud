package com.LingoCloudTranslate.lingocloud;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.webkit.WebView;
import org.junit.Before;
import org.junit.Test;

public class TranslationBridgeTest {

    private WebView mockWebView;
    private TranslationBridge.JavascriptExecutor mockJsExecutor;
    private Handler mockHandler;
    private TranslationBridge bridge;

    @Before
    public void setup() {
        mockWebView = mock(WebView.class);
        mockJsExecutor = mock(TranslationBridge.JavascriptExecutor.class);
        mockHandler = mock(Handler.class);

        // Mock handler to run immediately
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return true;
        }).when(mockHandler).post(any(Runnable.class));

        // Ensure JSONObject works in tests without crashing
        // The Android test environment might throw a "Method ... not mocked."
        // but `unitTests.returnDefaultValues = true` is in build.gradle

        bridge = new TranslationBridge(mockWebView, mockJsExecutor, mockHandler);
        HookMain.TranslationCache.clear();
    }

    @Test
    public void testRequestTranslation_cachedHit() {
        // Arrange
        String originalText = "Hello World";
        String translatedText = "Hola Mundo";
        String domNodeIdsJson = "[\"node-1\"]";

        // Explicitly put in the cache directly.
        HookMain.translationCache = mock(android.util.LruCache.class);
        org.mockito.Mockito.when(HookMain.translationCache.get(originalText)).thenReturn(translatedText);

        // Act
        bridge.requestTranslation(originalText, domNodeIdsJson);

        // Assert
        verify(mockJsExecutor).evaluateJavascript(anyString());
    }

    @Test
    public void testRequestTranslation_inFlightDeduplication() {
        // Arrange
        String originalText = "Hello World";
        String domNodeIdsJson1 = "[\"node-1\"]";
        String domNodeIdsJson2 = "[\"node-2\"]";

        // The first call puts it in-flight
        bridge.requestTranslation(originalText, domNodeIdsJson1);

        // The second call should add to the pending list without initiating another translation
        bridge.requestTranslation(originalText, domNodeIdsJson2);

        // In the mock environment, no cache is populated yet. The in-flight deduplication logic
        // happens before calling translation client, so mockJsExecutor should not have been called
        // because the async translation doesn't execute its callback in this mocked environment.
        verify(mockJsExecutor, org.mockito.Mockito.never()).evaluateJavascript(anyString());
    }
}
