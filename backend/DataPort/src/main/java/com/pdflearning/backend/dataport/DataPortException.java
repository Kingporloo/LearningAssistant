package com.pdflearning.backend.dataport;

public final class DataPortException extends RuntimeException {
    public DataPortException(String message) {
        super(message);
    }

    public DataPortException(String message, Throwable cause) {
        super(message, cause);
    }
}
