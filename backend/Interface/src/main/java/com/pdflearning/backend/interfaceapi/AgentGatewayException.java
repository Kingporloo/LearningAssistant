package com.pdflearning.backend.interfaceapi;

/** 公共 Agent HTTP 边界可安全返回给前端的失败。 */
final class AgentGatewayException extends RuntimeException {
    private final int status;

    AgentGatewayException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
