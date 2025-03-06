package es.in2.desmos.domain.exceptions.handler;

import es.in2.desmos.domain.exceptions.*;
import es.in2.desmos.domain.models.GlobalErrorMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SubscriptionCreationException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleBlockchainNodeSubscriptionException(SubscriptionCreationException subscriptionCreationException, ServerHttpRequest request) {
        log.error("SubscriptionCreationException: {}", subscriptionCreationException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("SubscriptionCreationException").message(subscriptionCreationException.getMessage()).path(path).build());
    }

    @ExceptionHandler(BrokerNotificationParserException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleBrokerNotificationParserException(BrokerNotificationParserException brokerNotificationParserException, ServerHttpRequest request) {
        log.error("BrokerNotificationParserException: {}", brokerNotificationParserException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("BrokerNotificationParserException").message(brokerNotificationParserException.getMessage()).path(path).build());
    }

    @ExceptionHandler(BrokerNotificationSelfGeneratedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleBrokerNotificationSelfGeneratedException(BrokerNotificationSelfGeneratedException ex, ServerHttpRequest request) {
        log.debug("BrokerNotificationSelfGeneratedException: {}", ex.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("BrokerNotificationSelfGeneratedException").message(ex.getMessage()).path(path).build());
    }

    @ExceptionHandler(HashCreationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleHashCreationException(HashCreationException hashCreationException, ServerHttpRequest request) {
        log.error("HashCreationException: {}", hashCreationException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("HashCreationException").message(hashCreationException.getMessage()).path(path).build());
    }

    @ExceptionHandler(HashLinkException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleHashLinkException(HashLinkException hashLinkException, ServerHttpRequest request) {
        log.error("HashLinkException: {}", hashLinkException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("HashLinkException").message(hashLinkException.getMessage()).path(path).build());
    }

    @ExceptionHandler(JsonReadingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleJsonReadingException(JsonReadingException jsonReadingException, ServerHttpRequest request) {
        log.error("JsonReadingException: {}", jsonReadingException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("JsonReadingException").message(jsonReadingException.getMessage()).path(path).build());
    }

    @ExceptionHandler(AuditRecordCreationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleAuditRecordCreationException(AuditRecordCreationException auditRecordCreationException, ServerHttpRequest request) {
        log.error("AuditRecordCreationException: {}", auditRecordCreationException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("AuditRecordCreationException").message(auditRecordCreationException.getMessage()).path(path).build());
    }

    @ExceptionHandler(RequestErrorException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleRequestErrorException(RequestErrorException requestErrorException, ServerHttpRequest request) {
        log.error("RequestErrorException: {}", requestErrorException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("RequestErrorException").message(requestErrorException.getMessage()).path(path).build());
    }

    @ExceptionHandler(BrokerEntityRetrievalException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleBrokerEntityRetrievalException(BrokerEntityRetrievalException brokerEntityRetrievalException, ServerHttpRequest request) {
        log.error("BrokerEntityRetrievalException: {}", brokerEntityRetrievalException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("BrokerEntityRetrievalException").message(brokerEntityRetrievalException.getMessage()).path(path).build());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleWebExchangeBindException(WebExchangeBindException webExchangeBindException, ServerHttpRequest request) {
        String path = String.valueOf(request.getPath());
        Map<String, String> errorMap = new HashMap<>();
        webExchangeBindException.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((org.springframework.validation.FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errorMap.put(fieldName, errorMessage);
        });
        String globalErrorMessage = errorMap.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining(", "));
        return Mono.just(GlobalErrorMessage.builder()
                .title("WebExchangeBindException").message(globalErrorMessage).path(path).build());
    }

    @ExceptionHandler(UnauthorizedDomeParticipantException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleUnauthorizedDomeParticipantException(UnauthorizedDomeParticipantException unauthorizedDomeParticipantException, ServerHttpRequest request) {
        log.error("UnauthorizedDomeParticipantException: {}", unauthorizedDomeParticipantException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("UnauthorizedDomeParticipantException").message(unauthorizedDomeParticipantException.getMessage()).path(path).build());
    }

    @ExceptionHandler(UnauthorizedBrokerSubscriptionException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleUnauthorizedBrokerSubscriptionException(UnauthorizedBrokerSubscriptionException unauthorizedBrokerSubscriptionException, ServerHttpRequest request) {
        log.error("UnauthorizedBrokerSubscriptionException: {}", unauthorizedBrokerSubscriptionException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("UnauthorizedBrokerSubscriptionException").message(unauthorizedBrokerSubscriptionException.getMessage()).path(path).build());
    }

    @ExceptionHandler(InvalidProfileException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleInvalidProfileException(InvalidProfileException invalidProfileException, ServerHttpRequest request) {
        log.error("InvalidProfileException: {}", invalidProfileException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("InvalidProfileException").message(invalidProfileException.getMessage()).path(path).build());
    }

    @ExceptionHandler(InvalidTokenException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleInvalidTokenException(InvalidTokenException invalidTokenException, ServerHttpRequest request) {
        log.error("InvalidTokenException: {}", invalidTokenException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("InvalidTokenException").message(invalidTokenException.getMessage()).path(path).build());
    }

    @ExceptionHandler(EntitySyncException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleEntitySyncException(EntitySyncException entitySyncException, ServerHttpRequest request) {
        log.error("EntitySyncException: {}", entitySyncException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("EntitySyncException").message(entitySyncException.getMessage()).path(path).build());
    }

    @ExceptionHandler(DiscoverySyncException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public Mono<GlobalErrorMessage> handleDiscoverySyncException(DiscoverySyncException discoverySyncException, ServerHttpRequest request) {
        log.error("DiscoverySyncException: {}", discoverySyncException.getMessage());
        String path = String.valueOf(request.getPath());
        return Mono.just(GlobalErrorMessage.builder().title("DiscoverySyncException").message(discoverySyncException.getMessage()).path(path).build());
    }
}
