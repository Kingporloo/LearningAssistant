package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;

/** Python 手动 Compact 接口返回的结果。 */
public record AgentCompactResult(
        String status,
        String trigger,
        String reason,
        int beforeTokens,
        int afterTokens,
        int compactTriggerTokens,
        boolean belowTrigger,
        String summarySaveStatus,
        JsonNode sessionSummary,
        JsonNode compact) {
}
