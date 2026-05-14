package my.side.trading.core.adapter.in.web.common;

import my.side.trading.core.application.execution.ExecutionBlockedException;
import my.side.trading.core.application.operation.ParameterRegistryConflictException;
import my.side.trading.shared.security.SensitiveDataSanitizer;
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
        log.warn("실행이 차단되었습니다: {}", ex.getReason().code());
        return new ResponseEntity<>(ApiResponse.error(ex.getReason().code()), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ParameterRegistryConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterRegistryConflict(ParameterRegistryConflictException ex) {
        String message = SensitiveDataSanitizer.sanitize(ex.getMessage());
        log.warn("파라미터 레지스트리 충돌: {}", message);
        return new ResponseEntity<>(ApiResponse.error(message), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = SensitiveDataSanitizer.sanitize(error.getDefaultMessage());
            errors.put(fieldName, errorMessage);
        });
        log.warn("검증에 실패했습니다: {}", errors);
        return new ResponseEntity<>(ApiResponse.error("Validation failed", errors), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("인자 타입이 일치하지 않습니다: {}", SensitiveDataSanitizer.sanitize(ex.getMessage()));
        return new ResponseEntity<>(ApiResponse.error("Invalid request parameter"), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        String message = SensitiveDataSanitizer.sanitize(ex.getMessage());
        log.warn("잘못된 인자입니다: {}", message);
        return new ResponseEntity<>(ApiResponse.error(message), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("예상하지 못한 오류가 발생했습니다: {}", SensitiveDataSanitizer.sanitizeThrowable(ex));
        return new ResponseEntity<>(ApiResponse.error("An unexpected error occurred"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
