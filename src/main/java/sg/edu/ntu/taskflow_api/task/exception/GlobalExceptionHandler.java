package sg.edu.ntu.taskflow_api.task.exception;

import java.time.Instant;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolationException;

import sg.edu.ntu.taskflow_api.task.dto.ErrorResponseDto;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({ TaskNotFoundException.class })
    public ResponseEntity<ErrorResponseDto> handleEntityNotFoundException(EntityNotFoundException ex) {
        ErrorResponseDto errorResponse = new ErrorResponseDto(ex.getMessage(), Instant.now());
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /** A token was sent but rejected (expired, bad signature, malformed, empty). */
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalidToken(InvalidTokenException ex) {
        return new ResponseEntity<>(new ErrorResponseDto(ex.getMessage(), Instant.now()),
                HttpStatus.UNAUTHORIZED);
    }

    /**
     * Authenticated but not permitted. Covers @PreAuthorize failures and
     * RestAccessDeniedHandler.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponseDto errorResponse = new ErrorResponseDto(
                "You do not have permission to access this resource", Instant.now());
        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    /** No token at all, or any other authentication failure. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponseDto> handleAuthentication(AuthenticationException ex) {
        return new ResponseEntity<>(new ErrorResponseDto("Authentication failed", Instant.now()),
                HttpStatus.UNAUTHORIZED);
    }

    /**
     * A request body failed @Valid. One "field: message" entry per problem, sorted
     * for a stable message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return new ResponseEntity<>(new ErrorResponseDto(message, Instant.now()), HttpStatus.BAD_REQUEST);
    }

    /** A path variable or parameter has the wrong type, e.g. /api/tasks/abc. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return new ResponseEntity<>(
                new ErrorResponseDto("Invalid value for '" + ex.getName() + "'", Instant.now()),
                HttpStatus.BAD_REQUEST);
    }

    /**
     * Unparseable JSON, or a value that does not fit the type, e.g. an unknown
     * priority.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadable(HttpMessageNotReadableException ex) {
        return new ResponseEntity<>(new ErrorResponseDto("Malformed request body", Instant.now()),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return new ResponseEntity<>(new ErrorResponseDto(message, Instant.now()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleException(Exception ex) {
        // Spring's own exceptions (unknown route, wrong method, ...) keep their real
        // status.
        if (ex instanceof ErrorResponse springError) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            return new ResponseEntity<>(new ErrorResponseDto(status.getReasonPhrase(), Instant.now()),
                    status);
        }
        logger.error("Unhandled exception", ex);
        return new ResponseEntity<>(new ErrorResponseDto("Something went wrong", Instant.now()),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}