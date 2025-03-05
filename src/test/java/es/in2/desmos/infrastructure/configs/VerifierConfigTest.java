package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.VerifierProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerifierConfigTest {

    @Mock
    private VerifierProperties verifierProperties;

    @InjectMocks
    private VerifierConfig verifierConfig;

    @Test
    void getExternalDomain_shouldReturnExternalUrlFromProperties() {
        String externalDomain = "https://example.com";
        when(verifierProperties.url()).thenReturn(externalDomain);

        String result = verifierConfig.getExternalUrl();

        assertThat(result).isEqualTo(externalDomain);
    }

    @Test
    void getWellKnownPath_shouldReturnWellKnownPath() {
        String result = verifierConfig.getWellKnownPath();

        assertThat(result).isEqualTo(VerifierConfig.WELL_KNOWN_PATH);
    }

    @Test
    void getWellKnownContentType_shouldReturnWellKnownContentType() {
        String result = verifierConfig.getWellKnownContentType();

        assertThat(result).isEqualTo(VerifierConfig.WELL_KNOWN_CONTENT_TYPE);
    }

    @Test
    void getWellKnownContentTypeUrlEncodedForm_shouldReturnWellKnownContentTypeUrlEncodedForm() {
        String result = verifierConfig.getWellKnownContentTypeUrlEncodedForm();

        assertThat(result).isEqualTo(VerifierConfig.WELL_KNOWN_CONTENT_TYPE_URL_ENCODED_FORM);
    }
}