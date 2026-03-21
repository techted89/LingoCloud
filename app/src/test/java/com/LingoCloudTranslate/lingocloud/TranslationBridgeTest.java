package com.LingoCloudTranslate.lingocloud;

import android.os.Handler;
import android.webkit.WebView;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;

public class TranslationBridgeTest {

    private WebView mockWebView;
    private TranslationBridge bridge;
    private TranslationBridge.JavascriptExecutor mockJsExecutor;
    private Handler mockHandler;

    @Before
    public void setup() throws Exception {
        mockWebView = mock(WebView.class);
        mockJsExecutor = mock(TranslationBridge.JavascriptExecutor.class);
        mockHandler = mock(Handler.class);

        // Force the Handler to execute Runnables immediately and synchronously
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return true;
        }).when(mockHandler).post(any(Runnable.class));

        bridge = new TranslationBridge(mockWebView, mockJsExecutor, mockHandler);
    }

    @Test
    public void testRequestTranslation_EmptyOrNullInput() {
        bridge.requestTranslation(null, "[\"node1\"]");
        verify(mockHandler, never()).post(any());

        bridge.requestTranslation("", "[\"node2\"]");
        verify(mockHandler, never()).post(any());

        bridge.requestTranslation("   ", "[\"node3\"]");
        verify(mockHandler, never()).post(any());
    }

    @Test
    public void testRequestTranslation_PrimedCache() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn("Hola");

            bridge.requestTranslation("Hello", "[\"node1\"]");

            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor).evaluateJavascript(jsCaptor.capture());

            String js = jsCaptor.getValue();

            assertTrue(js.contains("JSON.parse"));
            assertTrue(js.contains("document.getElementById"));
            assertTrue(js.contains("innerText = text"));
            assertTrue(js.contains("\"Hola\""));
            assertTrue(js.contains("\"[\\\"node1\\\"]\""));
        }
    }
}
