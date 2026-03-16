package com.LingoCloudTranslate.lingocloud;

import org.junit.Test;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TranslationClientTest {

    @Test
    public void translate_emptyApiKey_returnsNullAndDoesNotCallService() throws InterruptedException {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        // Act
        client.translate("Hello World", "Gemini", "   ", "es", mockCallback);

        // Assert
        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult(null);
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }

    @Test
    public void translate_emptyText_returnsEmptyText() throws InterruptedException {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        // Act
        client.translate("   ", "Gemini", "VALID_KEY", "es", mockCallback);

        // Assert
        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult("   ");
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }

    @Test
    public void translate_unknownService_returnsNull() throws InterruptedException {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        // Act
        client.translate("Hello", "UnknownProvider", "VALID_KEY", "es", mockCallback);

        // Assert
        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult(null);
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }
}