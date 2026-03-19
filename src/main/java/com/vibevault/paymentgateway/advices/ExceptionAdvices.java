package com.vibevault.paymentgateway.advices;

import com.vibevault.paymentgateway.dtos.exceptions.ExceptionDto;
import com.vibevault.paymentgateway.exceptions.InvalidPaymentStateException;
import com.vibevault.paymentgateway.exceptions.PaymentGatewayException;
import com.vibevault.paymentgateway.exceptions.PaymentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.owasp.encoder.Encode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class ExceptionAdvices {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ExceptionDto> handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), "PAYMENT_NOT_FOUND");
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ExceptionDto> handleInvalidState(InvalidPaymentStateException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI(), "INVALID_PAYMENT_STATE");
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ExceptionDto> handleGatewayError(PaymentGatewayException ex, HttpServletRequest request) {
        log.error("Payment gateway error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.BAD_GATEWAY, "Payment gateway unavailable", request.getRequestURI(), "GATEWAY_ERROR");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExceptionDto> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, message, request.getRequestURI(), "VALIDATION_ERROR");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ExceptionDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Invalid parameter: " + ex.getName(), request.getRequestURI(), "BAD_REQUEST");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ExceptionDto> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), "NOT_FOUND");
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ExceptionDto> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "Payment was modified concurrently, please retry", request.getRequestURI(), "CONCURRENT_MODIFICATION");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ExceptionDto> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request.getRequestURI(), "INTERNAL_ERROR");
    }

    private ResponseEntity<ExceptionDto> buildResponse(HttpStatus status, String message, String path, String errorCode) {
        ExceptionDto dto = new ExceptionDto(
                status.toString(),
                Encode.forHtml(message),
                Encode.forHtml(path),
                errorCode,
                LocalDateTime.now()
        );
        return new ResponseEntity<>(dto, status);
    }
}
