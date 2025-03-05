package es.in2.desmos.infrastructure.security;

import es.in2.desmos.domain.exceptions.JWTVerificationException;
import es.in2.desmos.domain.exceptions.TokenFetchException;
import es.in2.desmos.domain.exceptions.WellKnownInfoFetchException;
import es.in2.desmos.domain.models.VerifierOauth2AccessToken;
import es.in2.desmos.infrastructure.configs.VerifierConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerifierServiceTest {
    private MockWebServer mockWebServer;

    @Mock
    private VerifierConfig verifierConfig;

    private VerifierService verifierService;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        verifierService = new VerifierServiceImpl(WebClient.builder().build(), verifierConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void itShouldPerformTokenRequest() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                         "token_endpoint":""" + "\"" + mockWebServer.url("/token") + "\"" + """
                            }
                        """)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                            {
                                "access_token": "your_access_token_value",
                                "token_type": "Bearer",
                                "expires_in": "3600"
                              }
                        """)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        when(verifierConfig.getExternalUrl())
                .thenReturn(mockWebServer.url("/").toString());

        when(verifierConfig.getWellKnownPath())
                .thenReturn("/.well-known/openid-configuration");

        when(verifierConfig.getWellKnownContentType())
                .thenReturn("Content-Type");

        when(verifierConfig.getWellKnownContentTypeUrlEncodedForm())
                .thenReturn("application/x-www-form-urlencoded");
        StepVerifier
                .create(verifierService.performTokenRequest("{\"hello\":\"world\"}"))
                .expectNext(new VerifierOauth2AccessToken("your_access_token_value", "Bearer", "3600"))
                .verifyComplete();
    }

    @Test
    void itShouldThrowExceptionWhenErrorFetchingTheToken() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                         "token_endpoint":""" + "\"" + mockWebServer.url("/token") + "\"" + """
                            }
                        """)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                            {
                                "access_token": "your_access_token_value",
                                "token_ty",
                                "expires_in": "3600"
                              }
                        """)
                .setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()));

        when(verifierConfig.getExternalUrl())
                .thenReturn(mockWebServer.url("/").toString());

        when(verifierConfig.getWellKnownPath())
                .thenReturn("/.well-known/openid-configuration");

        when(verifierConfig.getWellKnownContentType())
                .thenReturn("Content-Type");

        when(verifierConfig.getWellKnownContentTypeUrlEncodedForm())
                .thenReturn("application/x-www-form-urlencoded");
        StepVerifier
                .create(verifierService.performTokenRequest("{\"hello\":\"world\"}"))
                .expectError(TokenFetchException.class)
                .verify();
    }

    @Test
    void itShouldThrowTokenFetchExceptionWhenPerformingTokenRequest() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                            {
                                "access_token": "your_access_token_value",
                                "token_type": "Bearer",
                                "expires_in": "3600"
                              }
                        """)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        when(verifierConfig.getExternalUrl())
                .thenReturn(mockWebServer.url("/").toString());

        when(verifierConfig.getWellKnownPath())
                .thenReturn("/.well-known/openid-configuration");

        when(verifierConfig.getWellKnownContentType())
                .thenReturn("Content-Type");

        when(verifierConfig.getWellKnownContentTypeUrlEncodedForm())
                .thenReturn("application/x-www-form-urlencoded");
        StepVerifier
                .create(verifierService.performTokenRequest("{\"hello\":\"world\"}"))
                .expectError(TokenFetchException.class)
                .verify();
    }

    @Test
    void itShouldThrowJwtVerificationExceptionIfInvalidIssuer() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                        "jwks_uri":""" + "\"" + mockWebServer.url("/jwks") + "\"" + """
                        }
                        """)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                            "keys" : [ { "kty" : "EC",
                                         "crv" : "P-256",
                                         "x"   : "MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4",
                                         "y"   : "4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM",
                                         "use" : "enc",
                                         "kid" : "1" },
                                       { "kty" : "RSA",
                                         "n"   : "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx
                                                  4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMs
                                                  tn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2
                                                  QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbI
                                                  SD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqb
                                                  w0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw",
                                         "e"   : "AQAB",
                                         "alg" : "RS256",
                                         "kid" : "2011-04-29" } ]
                          }""")
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        when(verifierConfig.getExternalUrl())
                .thenReturn(mockWebServer.url("/").toString());

        when(verifierConfig.getWellKnownPath())
                .thenReturn("/.well-known/openid-configuration");
        StepVerifier
                .create(verifierService.verifyToken("eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjMyNDczOTk4NTY0LCJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6Ijg3NWU4YTQyZjJhNTFlNTVkMGNhN2MwMDg4ZjZjZTU1In0.eyJuYW1lIjoiSm9obiBEb2UiLCJpYXQiOjE1MTYyMzkwMjIsImV4cCI6MzI0NzM5OTg1NjR9.ARzDuqn_wjRSrdsMeT-oSEm9GMD5u6oh8iouNjKwHvcDQbfgLveYU9Y9TxxiZ2d4Sh_AGRE4JSikUrPbuiX55g"))
                .expectError(JWTVerificationException.class)
                .verify();
    }

    @Test
    void itShouldThrowExceptionWhenJwkIsNotCorrect() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                        "jwks_uri":""" + "\"" + mockWebServer.url("/jwks") + "\"" + """
                        }
                        """)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {"hello":"world"}""")
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        when(verifierConfig.getExternalUrl())
                .thenReturn(mockWebServer.url("/").toString());

        when(verifierConfig.getWellKnownPath())
                .thenReturn("/.well-known/openid-configuration");
        StepVerifier
                .create(verifierService.verifyToken("valid.jwt.token"))
                .expectError(JWTVerificationException.class)
                .verify();
    }

    @Test
    void getWellKnownInfo_errorFetching_shouldThrowWellKnownInfoFetchException() {
        // Arrange
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR.value()) // Simulate an error fetching metadata
                .setBody(""));

        when(verifierConfig.getExternalUrl()).thenReturn(mockWebServer.url("/").toString());
        when(verifierConfig.getWellKnownPath()).thenReturn("/.well-known/openid-configuration");

        StepVerifier
                .create(verifierService.getWellKnownInfo())
                .expectError(WellKnownInfoFetchException.class)
                .verify();
    }
}