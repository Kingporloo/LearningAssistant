package com.pdflearning.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VectorDimensionValidationTest {
    @Test
    void memoryRejectsUnexpectedDimensionBeforeAccessingStorage() {
        var memory = new MemoryDataPort(null, null, null, 768);

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> memory.query(new MemoryDataPort.Query(
                        "dev_user", "semantic", List.of(0.1, 0.2), 5)));

        assertTrue(error.getMessage().contains("768"));
    }

    @Test
    void ragRejectsUnexpectedDimensionBeforeAccessingStorage() {
        var rag = new RagDataPort(null, null, null, 768);

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> rag.search("dev_user", List.of(0.1, 0.2), 5));

        assertTrue(error.getMessage().contains("768"));
    }
}
