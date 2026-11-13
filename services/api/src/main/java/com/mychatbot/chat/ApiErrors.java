package com.mychatbot.chat;

import com.mychatbot.ai.AiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(AiException.class)
    public ResponseEntity<StreamEvent.Failure> ai(AiException error) {
        HttpStatus status = switch (error.code()) {
            case "RATE_LIMITED", "LOCAL_RATE_LIMITED", "REQUEST_IN_PROGRESS" -> HttpStatus.TOO_MANY_REQUESTS;
            case "PROPOSAL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "PROPOSAL_CONFLICT" -> HttpStatus.CONFLICT;
            case "PROPOSAL_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "PROPOSAL_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (error.retryAfterSeconds() != null) response.header("Retry-After", String.valueOf(error.retryAfterSeconds()));
        return response.body(new StreamEvent.Failure(error.code(), error.getMessage(), error.retryAfterSeconds()));
    }
    @ExceptionHandler({WebExchangeBindException.class, ServerWebInputException.class})
    public ResponseEntity<StreamEvent.Failure> input(Exception ignored) {
        return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new StreamEvent.Failure("INVALID_INPUT", "질문은 1~4000자로 입력해 주세요. 모드를 지정할 경우 auto, work, general 중 하나를 사용해 주세요.", null));
    }
    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    public ResponseEntity<Void> unsupportedMediaType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<StreamEvent.Failure> unexpected(Exception ignored) {
        return ResponseEntity.internalServerError().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new StreamEvent.Failure("INTERNAL_ERROR", "요청 처리 중 오류가 발생했어요.", null));
    }
}
