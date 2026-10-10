package sg.edu.ntu.taskflow_api.task.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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