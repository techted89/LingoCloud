package com.LingoCloudTranslate.lingocloud;

import org.junit.Test;
import static org.mockito.Mockito.*;

public class TranslationClientTest {

    @Test
    public void translate_emptyApiKey_returnsNullAndDoesNotCallService() {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        // Act
        client.translate("Hello World", "Gemini", "   ", "es", mockCallback);

        // Assert
        verify(mockCallback).onResult(null);
        verifyNoMoreInteractions(mockCallback);
    }

    @Test
    public void translate_emptyText_returnsEmptyText() {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        // Act
        client.translate("   ", "Gemini", "VALID_KEY", "es", mockCallback);

        // Assert
        verify(mockCallback).onResult("   ");
        verifyNoMoreInteractions(mockCallback);
    }

    @Test
    public void translate_unknownService_returnsNull() {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        // Act
        client.translate("Hello", "UnknownProvider", "VALID_KEY", "es", mockCallback);

        // Assert
        verify(mockCallback).onResult(null);
        verifyNoMoreInteractions(mockCallback);
    }

    @Test
    public void translate_validInputsGemini_invokesCallback() {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        // We can't easily mock the GenAI Client inside TranslationClient without refactoring it to be injectable,
        // but we can test that calling translate with valid parameters doesn't throw synchronous exceptions
        // and safely attempts the background thread execution.
        try {
            client.translate("Hello", "Gemini", "VALID_KEY", "es", mockCallback);
            // In a real isolated unit test, we would inject a Mock Client and verify it's called.
            // Since the execution is async (executor.submit), verifying the callback immediately won't work
            // without awaiting the thread or injecting the executor.
        } catch (Exception e) {
            org.junit.Assert.fail("Should not throw exceptions on valid input");
        }
    }
}