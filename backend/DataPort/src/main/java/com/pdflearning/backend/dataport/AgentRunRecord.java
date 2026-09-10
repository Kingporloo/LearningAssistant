package com.pdflearning.backend.dataport;

import java.time.OffsetDateTime;

public record AgentRunRecord(
        String userId,
        String sessionId,
        String requestId,
        String messageId,
        String requestHash,
        AgentRunStatus status,
        long lastEventSeq,
        String interruptionReason,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {
}
