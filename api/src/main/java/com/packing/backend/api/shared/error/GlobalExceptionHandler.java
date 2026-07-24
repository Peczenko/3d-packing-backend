package com.packing.backend.api.shared.error;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.shared.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps exceptions to RFC 9457 problem details.
 *
 * <p>Handlers are keyed on the shared base types from {@code domain.shared}, so a new
 * aggregate's exceptions get the right status without touching this class. Nothing here
 * leaks a stack trace or an internal message to the client.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} is load-bearing, not decoration.
 * {@code ExceptionHandlerExceptionResolver} runs <em>before</em> Spring's
 * {@code DefaultHandlerExceptionResolver}, so the catch-all at the bottom of this class
 * would otherwise intercept the framework's own client errors — malformed JSON, an
 * unparseable UUID path variable, an unknown route, an unsupported method — and report
 * every one of them as 500. Inheriting the framework's handlers gives those exceptions a
 * more specific match, so they keep their proper 400/404/405.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", e.getMessage(), request);
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ProblemDetail handleConflict(ResourceConflictException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), request);
    }

    @ExceptionHandler(DomainRuleViolationException.class)
    public ProblemDetail handleRuleViolation(DomainRuleViolationException e, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Request rejected", e.getMessage(), request);
    }

    /**
     * The optimistic lock rejected a write built on a stale read. Retryable, so the client
     * is told as much rather than being handed an opaque failure.
     */
    @ExceptionHandler(ConcurrentUpdateException.class)
    public ProblemDetail handleConcurrentUpdate(ConcurrentUpdateException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflicting update",
                "The resource was modified by another request. Re-read it and try again.", request);
    }

    /**
     * {@code @PreAuthorize} throws this from inside the controller invocation, which is
     * downstream of {@code ExceptionTranslationFilter}. Without an explicit handler the
     * catch-all below would swallow it and return 500 instead of 403.
     *
     * <p>401 is not a risk here: the filter chain rejects unauthenticated requests before
     * they ever reach a handler method, so anything getting this far is authenticated but
     * insufficiently privileged.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ProblemDetail handleExternalService(ExternalServiceException e, HttpServletRequest request) {
        log.error("Call to external service '{}' failed", e.service(), e);
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Dependency unavailable",
                "The '" + e.service() + "' service is currently unavailable. Please retry.",
                request);
    }

    /**
     * Last resort. Only reached by exceptions no more specific handler matched — the
     * framework's own client errors are handled by the inherited methods. The real message
     * is logged, never returned: an unexpected exception's message can carry SQL
     * fragments, file paths or credentials.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred.",
                request);
    }

    /**
     * Overrides the inherited handler rather than declaring a second one for the same
     * exception type, which would make the mapping ambiguous and fail at startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", fieldErrors);
        problem.setProperty("path", pathOf(request));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Gives the inherited framework handlers the same {@code path} property the handlers
     * above set, so every problem response has a consistent shape.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> response =
                super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("path", pathOf(request));
        }
        return response;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    private String pathOf(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : description;
    }
}
