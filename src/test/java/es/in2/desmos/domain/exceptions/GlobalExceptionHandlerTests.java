package es.in2.desmos.domain.exceptions;

import es.in2.desmos.domain.exceptions.handler.GlobalExceptionHandler;
import es.in2.desmos.domain.models.GlobalErrorMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTests {

    private static GlobalExceptionHandler globalExceptionHandler;
    private ServerHttpRequest request;
    private RequestPath requestPath;

    static Stream<Arguments> provideData() {
        List<Class<?>> classes = new ArrayList<>(List.of(
                SubscriptionCreationException.class,
                BrokerNotificationParserException.class,
                HashCreationException.class,
                HashLinkException.class,
                JsonReadingException.class,
                AuditRecordCreationException.class,
                RequestErrorException.class,
                BrokerEntityRetrievalException.class,
                BrokerNotificationSelfGeneratedException.class,
                UnauthorizedDomeParticipantException.class,
                UnauthorizedBrokerSubscriptionException.class,
                EntitySyncException.class,
                DiscoverySyncException.class
        ));
        List<String> messages = new ArrayList<>(List.of(
                "SubscriptionCreationException",
                "BrokerNotificationParserException",
                "HashCreationException",
                "HashLinkException",
                "JsonReadingException",
                "AuditRecordCreation",
                "RequestErrorException",
                "BrokerEntityRetrievalException",
                "BrokerNotificationSelfGeneratedException",
                "UnauthorizedDomeParticipantException",
                "UnauthorizedBrokerSubscriptionException",
                "EntitySyncException",
                "DiscoverySyncException"
        ));
        List<Throwable> nullCauseThrowableList = new ArrayList<>();
        List<Throwable> exceptionCauseThrowableList = new ArrayList<>();
        for (int i = 0; i < classes.size() / 2; i++) {
            nullCauseThrowableList.add(null);
            exceptionCauseThrowableList.add(new RuntimeException("cause"));
        }
        List<Throwable> causes = new ArrayList<>();
        for (int i = 0; i < classes.size() / 2; i++) {
            causes.add(nullCauseThrowableList.get(i));
            causes.add(exceptionCauseThrowableList.get(i));
        }
        List<BiFunction<RuntimeException, ServerHttpRequest, Mono<GlobalErrorMessage>>> methods = new ArrayList<>(Arrays.asList(
                (ex, req) -> globalExceptionHandler.handleBlockchainNodeSubscriptionException((SubscriptionCreationException) ex, req),
                (ex, req) -> globalExceptionHandler.handleBrokerNotificationParserException((BrokerNotificationParserException) ex, req),
                (ex, req) -> globalExceptionHandler.handleHashCreationException((HashCreationException) ex, req),
                (ex, req) -> globalExceptionHandler.handleHashLinkException((HashLinkException) ex, req),
                (ex, req) -> globalExceptionHandler.handleJsonReadingException((JsonReadingException) ex, req),
                (ex, req) -> globalExceptionHandler.handleAuditRecordCreationException((AuditRecordCreationException) ex, req),
                (ex, req) -> globalExceptionHandler.handleRequestErrorException((RequestErrorException) ex, req),
                (ex, req) -> globalExceptionHandler.handleBrokerEntityRetrievalException((BrokerEntityRetrievalException) ex, req),
                (ex, req) -> globalExceptionHandler.handleBrokerNotificationSelfGeneratedException((BrokerNotificationSelfGeneratedException) ex, req),
                (ex, req) -> globalExceptionHandler.handleUnauthorizedDomeParticipantException((UnauthorizedDomeParticipantException) ex, req),
                (ex, req) -> globalExceptionHandler.handleUnauthorizedBrokerSubscriptionException((UnauthorizedBrokerSubscriptionException) ex, req),
                (ex, req) -> globalExceptionHandler.handleEntitySyncException((EntitySyncException) ex, req),
                (ex, req) -> globalExceptionHandler.handleDiscoverySyncException((DiscoverySyncException) ex, req)
        ));
        classes.addAll(new ArrayList<>(classes));
        messages.addAll(new ArrayList<>(messages));
        methods.addAll(new ArrayList<>(methods));
        return IntStream.range(0, classes.size())
                .mapToObj(i -> Arguments.of(classes.get(i), messages.get(i), causes.get(i % causes.size()), methods.get(i)));
    }

    @BeforeEach
    void setup() {
        request = mock(ServerHttpRequest.class);
        requestPath = mock(RequestPath.class);
        globalExceptionHandler = new GlobalExceptionHandler();
    }

    @ParameterizedTest
    @MethodSource("provideData")
    void testExceptions(Class<?> exceptionClass, String message, Throwable cause,
                        BiFunction<RuntimeException, ServerHttpRequest, Mono<GlobalErrorMessage>> method) {
        // Mock
        when(request.getPath()).thenReturn(requestPath);
        // Act
        Object exception;
        try {
            exception = exceptionClass.getConstructor(String.class).newInstance(message);
        } catch (Exception e) {
            throw new RuntimeException("Error instantiating exception", e);
        }
        GlobalErrorMessage globalErrorMessage =
                GlobalErrorMessage.builder()
                        .title(exceptionClass.getSimpleName())
                        .message(message)
                        .path(String.valueOf(requestPath))
                        .build();
        //Assert
        StepVerifier.create(method.apply((RuntimeException) exception, request))
                .expectNext(globalErrorMessage)
                .verifyComplete();
    }

    @Test
    void testHandleWebExchangeBindException() {
        // Arrange
        WebExchangeBindException webExchangeBindException = mock(WebExchangeBindException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("objectName", "fieldName", "error message");
        // Mock
        when(webExchangeBindException.getBindingResult()).thenReturn(bindingResult);
        when(request.getPath()).thenReturn(requestPath);
        when(bindingResult.getAllErrors()).thenReturn(List.of(fieldError));
        // Act
        GlobalErrorMessage globalErrorMessage =
                GlobalErrorMessage.builder()
                        .title(webExchangeBindException.getClass().getSimpleName())
                        .message("fieldName: error message")
                        .path(String.valueOf(requestPath))
                        .build();
        Mono<GlobalErrorMessage> result = globalExceptionHandler.handleWebExchangeBindException(webExchangeBindException, request);
        // Assert
        StepVerifier.create(result)
                .expectNext(globalErrorMessage)
                .verifyComplete();
    }

}
