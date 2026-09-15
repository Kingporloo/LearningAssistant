package com.pdflearning.backend.dataport;

import java.time.OffsetDateTime;

public record MemoryRecord(
        String memoryId,
        String memoryType,
        String content,
        double importance,
        String status,
        int revision,
        String sourceSessionId,
        String sourceMessageId,
        OffsetDateTime createdAt,
        OffsetDateTime eventTime) {
}
