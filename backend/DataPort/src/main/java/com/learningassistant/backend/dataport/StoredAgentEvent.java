package com.learningassistant.backend.dataport;

import java.time.OffsetDateTime;

public record StoredAgentEvent(
        long eventSeq,
        String eventType,
        String eventJson,
        OffsetDateTime createdAt) {
}
