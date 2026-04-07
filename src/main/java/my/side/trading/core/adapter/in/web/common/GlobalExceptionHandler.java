package my.side.trading.core.adapter.in.web.common;

import my.side.trading.core.application.execution.ExecutionBlockedException;
import my.side.trading.core.application.operation.ParameterRegistryConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ExecutionBlockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleExecutionBlockedException(ExecutionBlockedException ex) {
        log.warn("Execution blocked: {}", ex.getReason().code());
        return new ResponseEntity<>(ApiResponse.error(ex.getReason().code()), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ParameterRegistryConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterRegistryConflict(ParameterRegistryConflictException ex) {
        log.warn("Parameter registry conflict: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error(ex.getMessage()), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("Validation failed: {}", errors);
        return new ResponseEntity<>(ApiResponse.error("Validation failed", errors), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Argument type mismatch: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error("Invalid request parameter"), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return new ResponseEntity<>(ApiResponse.error(ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return new ResponseEntity<>(ApiResponse.error("An unexpected error occurred"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
