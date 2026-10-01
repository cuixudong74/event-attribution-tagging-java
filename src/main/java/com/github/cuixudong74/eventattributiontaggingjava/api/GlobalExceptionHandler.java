package com.github.cuixudong74.eventattributiontaggingjava.api;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfigException;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.UnauthorizedAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：非法配置返回 400 并附逐项原因；越权返回 403；非法参数返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AttributionConfigException.class)
    public ResponseEntity<Map<String, Object>> handleConfig(AttributionConfigException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVALID_CONFIG");
        body.put("reasons", ex.getReasons());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedAccessException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "FORBIDDEN");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
