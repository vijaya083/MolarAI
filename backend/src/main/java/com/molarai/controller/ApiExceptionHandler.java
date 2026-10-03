package com.molarai.controller;

import com.molarai.ai.EmbeddingProviderException;
import com.molarai.ai.LlmProviderException;
import com.molarai.dto.ApiErrorResponse;
import com.molarai.service.AppointmentSlotNotFoundException;
import com.molarai.service.AppointmentSlotUnavailableException;
import com.molarai.service.InvalidAppointmentRequestException;
import com.molarai.service.CancellationOtpException;
import com.molarai.service.CancellationDisabledException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final String AI_UNAVAILABLE_MESSAGE =
            "The assistant could not complete that response. Please try again shortly.";

    @ExceptionHandler({LlmProviderException.class, EmbeddingProviderException.class})
    public ResponseEntity<ApiErrorResponse> handleAiProviderFailure(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse(AI_UNAVAILABLE_MESSAGE));
    }

    @ExceptionHandler(AppointmentSlotNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAppointmentSlotNotFound(AppointmentSlotNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(AppointmentSlotUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAppointmentSlotUnavailable(
            AppointmentSlotUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(InvalidAppointmentRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAppointmentRequest(
            InvalidAppointmentRequestException exception) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(CancellationOtpException.class)
    public ResponseEntity<ApiErrorResponse> handleCancellationOtp(CancellationOtpException exception) {
        HttpStatus status = switch (exception.reason()) {
            case COOLDOWN, INVALID_OTP, EXPIRED, TOO_MANY_ATTEMPTS, ALREADY_USED -> HttpStatus.CONFLICT;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(CancellationDisabledException.class)
    public ResponseEntity<ApiErrorResponse> handleCancellationDisabled(CancellationDisabledException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");
        return ResponseEntity.badRequest().body(new ApiErrorResponse(message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleParameterTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("Invalid value for request parameter: " + exception.getName()));
    }
}
