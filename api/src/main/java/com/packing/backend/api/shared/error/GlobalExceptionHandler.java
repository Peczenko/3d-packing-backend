package com.packing.backend.api.shared.error;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.core.notification.ServerErrorReport;
import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.shared.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;


@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorAlerter alerter;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", e.getMessage(), request);
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ProblemDetail handleConflict(ResourceConflictException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), request);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ProblemDetail handlePermissionDenied(PermissionDeniedException e,
                                                HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage(), request);
    }

    @ExceptionHandler(DomainRuleViolationException.class)
    public ProblemDetail handleRuleViolation(DomainRuleViolationException e, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Request rejected", e.getMessage(), request);
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    public ProblemDetail handleConcurrentUpdate(ConcurrentUpdateException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflicting update",
                "The resource was modified by another request. Re-read it and try again.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ProblemDetail handleExternalService(ExternalServiceException e, HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("[{}] Call to external service '{}' failed", traceId, e.service(), e);
        return serverError(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Dependency unavailable",
                "The '" + e.service() + "' service is currently unavailable. Please retry.",
                traceId, e, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("[{}] Unhandled exception for {} {}",
                traceId, request.getMethod(), request.getRequestURI(), e);
        return serverError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred.",
                traceId, e, request);
    }

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

    private ProblemDetail serverError(HttpStatus status, String title, String detail,
                                      String traceId, Exception cause, HttpServletRequest request) {
        ProblemDetail problem = problem(status, title, detail, request);
        problem.setProperty("traceId", traceId);
        try {
            alerter.alert(reportOf(status, traceId, cause, request));
        } catch (RuntimeException | LinkageError e) {
            log.error("[{}] Alerting itself failed", traceId, e);
        }
        return problem;
    }

    private ServerErrorReport reportOf(HttpStatus status, String traceId, Exception cause,
                                       HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser caller = callerOf(authentication);

        return new ServerErrorReport(
                traceId,
                Instant.now(),
                status.value(),
                request.getMethod(),
                fullPath(request),
                uriTemplate(request),
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT),
                caller == null ? null : caller.firebaseUid(),
                caller == null ? null : caller.email(),
                rolesOf(authentication),
                cause);
    }

    private AuthenticatedUser callerOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                ? AuthenticatedUser.from(jwt)
                : null;
    }

    private String rolesOf(Authentication authentication) {
        return authentication == null ? null : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
    }

    private String uriTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }

    private String fullPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        return forwarded.split(",")[0].strip();
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
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
