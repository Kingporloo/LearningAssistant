package com.pdflearning.backend.dataport;

import java.time.OffsetDateTime;

public record MemoryRecord(
        String memoryId,
        String memoryType,
        String content,
        double importance,
        String status,
        String sourceSessionId,
        String sourceMessageId,
        OffsetDateTime createdAt,
        OffsetDateTime eventTime) {
}
