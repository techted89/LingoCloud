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
}