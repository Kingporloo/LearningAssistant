package com.pdflearning.backend.user;

/**
 * 用户服务业务异常，携带面向 HTTP 的状态码。
 */
public final class UserServiceException extends RuntimeException {
    private final int statusCode;

    public UserServiceException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
