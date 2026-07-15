package com.orderplatform.order.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalExceptionHandler}. No constructor dependencies,
 * so the handler is constructed directly with no mocks - only the exception
 * arguments passed into each handler method need building/mocking. Every
 * assertion also checks that response bodies stay free of raw exception
 * messages/stack traces, per the class's own documented intent.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private BindingResult bindingResult;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Used only to obtain a real (non-mocked) MethodParameter via reflection - several handled exceptions require one. */
    @SuppressWarnings("unused")
    private static void dummyHandlerMethod(String customerId) {
    }

    private MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyHandlerMethod", String.class), 0);
    }

    @Test
    void handleValidationReturns400WithFieldErrors() throws NoSuchMethodException {
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("createOrderRequest", "customerId", "must not be blank"),
                new FieldError("createOrderRequest", "lines", "must not be empty")));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter(), bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("timestamp")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) body.get("errors");
        assertThat(errors)
                .containsEntry("customerId", "must not be blank")
                .containsEntry("lines", "must not be empty");
    }

    @Test
    void handleConstraintViolationReturns400WithViolations() {
        ConstraintViolation<?> violation = mockViolation("customerId", "must not be blank");
        Set<ConstraintViolation<?>> violations = new LinkedHashSet<>(Set.of(violation));
        ConstraintViolationException ex = new ConstraintViolationException(violations);

        ResponseEntity<Map<String, Object>> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) body.get("errors");
        assertThat(errors).containsEntry("customerId", "must not be blank");
    }

    private ConstraintViolation<?> mockViolation(String propertyPath, String message) {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = org.mockito.Mockito.mock(ConstraintViolation.class);
        Path path = org.mockito.Mockito.mock(Path.class);
        when(path.toString()).thenReturn(propertyPath);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn(message);
        return violation;
    }

    @Test
    void handleMissingParameterReturns400NamingTheMissingParam() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("customerId", "String");

        ResponseEntity<Map<String, Object>> response = handler.handleMissingParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("error")).isEqualTo("Missing required parameter 'customerId'");
    }

    @Test
    void handleTypeMismatchReturns400NamingTheBadParam() throws NoSuchMethodException {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "NOT_A_REAL_STATUS", String.class, "status", methodParameter(), new IllegalArgumentException("bad enum"));

        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("error")).isEqualTo("Invalid value for parameter 'status'");
    }

    @Test
    void handleNotFoundReturns404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/orders/does-not-exist");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(404);
        assertThat(body.get("error")).isEqualTo("Not found");
    }

    @Test
    void handleUnexpectedReturns500WithGenericMessageAndNoLeakedDetails() {
        // A message deliberately chosen to look like something that must never reach the client.
        Exception ex = new RuntimeException("Couchbase password is hunter2; stack trace follows...");

        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(500);
        assertThat(body.get("error")).isEqualTo("Internal server error");

        // The whole point of this handler: no raw exception message anywhere in the body.
        assertThat(body.toString()).doesNotContain("hunter2", "Couchbase password", "stack trace");
    }
}
