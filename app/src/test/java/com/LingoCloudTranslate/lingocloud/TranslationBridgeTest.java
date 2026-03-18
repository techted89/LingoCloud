package com.LingoCloudTranslate.lingocloud;

import android.os.Handler;
import android.webkit.WebView;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

public class TranslationBridgeTest {

    private WebView mockWebView;
    private TranslationBridge bridge;
    private Handler mockHandler;

    @Before
    public void setup() throws Exception {
        mockWebView = mock(WebView.class);
        bridge = new TranslationBridge(mockWebView);

        // Inject a mock handler to intercept post() calls
        mockHandler = mock(Handler.class);
        Field handlerField = TranslationBridge.class.getDeclaredField("mainHandler");
        handlerField.setAccessible(true);
        handlerField.set(bridge, mockHandler);

        // Capture runnables posted to handler and execute them synchronously
        when(mockHandler.post(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return true;
        });

        // Clear cache
        HookMain.TranslationCache.clear();
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
    public void testRequestTranslation_PrimedCache() {
        // Prime the cache
        HookMain.TranslationCache.put("Hello", "Hola");

        bridge.requestTranslation("Hello", "[\"node1\"]");

        // Verify Javascript injection format
        ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockWebView).evaluateJavascript(jsCaptor.capture(), any());

        String js = jsCaptor.getValue();
        // Ensure standard string quote escaping and JS payload format
        assertEquals("javascript:(function(idsJsonStr, text) {   try {     var ids = JSON.parse(idsJsonStr);     ids.forEach(function(id) {       var el = document.getElementById(id);       if (el) { el.innerText = text; }     });   } catch(e) { console.error('LingoCloud JS Error: ' + e); } })(\"[\\\"node1\\\"]\", \"Hola\");", js);
    }
}
