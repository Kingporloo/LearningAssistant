package com.pdflearning.backend.dataport;

import java.util.List;

public record RagChunk(
        String chunkId,
        String documentId,
        int chunkIndex,
        String text,
        String source,
        String fileType,
        Integer page,
        String h1,
        String h2,
        String h3,
        List<Double> vector) {
}
