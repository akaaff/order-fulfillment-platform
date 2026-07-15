package com.orderplatform.aiagent.api;

import com.orderplatform.aiagent.chat.AiAgentUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalExceptionHandler}, the single
 * {@code @RestControllerAdvice} translating exceptions into response bodies
 * for this service (see the "Conventions to keep consistent" section of the
 * repo's CLAUDE.md). No Spring context is needed - each handler method is a
 * plain method on a no-arg-constructed instance, so these are pure unit tests.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private MethodParameter methodParameter;

    @Mock
    private BindingResult bindingResult;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidation_returns400WithFieldErrors() {
        FieldError fieldError = new FieldError("askRequest", "question", "must not be blank");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.get("timestamp")).isNotNull();
        assertThat(body.get("errors")).isEqualTo(Map.of("question", "must not be blank"));
    }

    @Test
    void handleValidation_multipleFieldErrors_areAllIncluded() {
        FieldError first = new FieldError("askRequest", "question", "must not be blank");
        FieldError second = new FieldError("askRequest", "otherField", "must be positive");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(first, second));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) response.getBody().get("errors");
        assertThat(errors)
                .containsEntry("question", "must not be blank")
                .containsEntry("otherField", "must be positive");
    }

    @Test
    void handleUnavailable_returns503WithExceptionMessageAsErrorBody() {
        AiAgentUnavailableException ex = new AiAgentUnavailableException(
                "Ollama call timed out or circuit breaker is open", new RuntimeException("upstream cause"));

        ResponseEntity<Map<String, Object>> response = handler.handleUnavailable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(body.get("timestamp")).isNotNull();
        assertThat(body.get("error")).isEqualTo("Ollama call timed out or circuit breaker is open");
    }

    /**
     * The catch-all handler must not leak the raw exception's message into
     * the response body - only a generic, fixed string. The message here
     * stands in for something that could be a sensitive internal detail
     * (a stack-trace fragment, a class name, a SQL error) that should stay
     * server-side (it's still logged via `log.error`, just not returned).
     */
    @Test
    void handleUnexpected_returns500WithGenericMessage_andDoesNotLeakRawExceptionMessage() {
        RuntimeException ex = new RuntimeException("sensitive internal detail: connection string leaked here");

        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(body.get("timestamp")).isNotNull();
        assertThat(body.get("error")).isEqualTo("Internal server error");
        assertThat(body.get("error")).asString().doesNotContain("sensitive internal detail");
        assertThat(body.values()).noneMatch(value ->
                value instanceof String s && s.contains("connection string leaked here"));
    }
}
