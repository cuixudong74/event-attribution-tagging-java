package com.github.cuixudong74.eventattributiontaggingjava.privacy;

/**
 * 越权读取用户级明细时抛出。
 */
public class UnauthorizedAccessException extends RuntimeException {

    public UnauthorizedAccessException(String message) {
        super(message);
    }
}
