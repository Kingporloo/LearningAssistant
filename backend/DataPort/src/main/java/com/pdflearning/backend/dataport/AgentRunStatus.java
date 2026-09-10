package com.pdflearning.backend.dataport;

public enum AgentRunStatus {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    INTERRUPTED("interrupted");

    private final String databaseValue;

    AgentRunStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    static AgentRunStatus fromDatabase(String value) {
        for (var status : values()) {
            if (status.databaseValue.equals(value)) {
                return status;
            }
        }
        throw new DataPortException("数据库包含未知 Agent 运行状态: " + value);
    }
}
