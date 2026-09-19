package com.learningassistant.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.learningassistant.backend.dataport.RagChunk;
import com.learningassistant.backend.dataport.RagDataPort;
import com.learningassistant.backend.dataport.RagEdge;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 将 Python RAG 构建 JSON 转为 DataPort 命令。 */
final class RagBuildResultParser {
    private RagBuildResultParser() {
    }

    static RagBuildClient.BuildResult parse(
            JsonNode body,
            String userId,
            String documentId,
            String requestId) throws IOException {
        if (body == null || !body.isObject()) {
            throw new IOException("Python RAG 没有返回 JSON 对象");
        }
        requireEqual(body, "user_id", userId);
        requireEqual(body, "document_id", documentId);
        requireEqual(body, "request_id", requestId);
        String status = text(body, "status");
        if (!"ok".equals(status) && !"empty".equals(status)) {
            throw new IOException("Python RAG 返回了不可入库状态: " + status);
        }
        var chunks = chunks(body.path("chunks"), documentId);
        var graph = body.path("graph");
        var command = new RagDataPort.BuildCommand(
                userId,
                requestId,
                documentId,
                status,
                optionalText(body, "message"),
                chunks,
                edges(graph.path("next_chunk")),
                edges(graph.path("similar_to")));
        int pageCount = chunks.stream()
                .map(RagChunk::page)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return new RagBuildClient.BuildResult(command, pageCount);
    }

    private static List<RagChunk> chunks(JsonNode values, String documentId) throws IOException {
        if (!values.isArray()) {
            throw new IOException("Python RAG chunks 必须是数组");
        }
        var result = new ArrayList<RagChunk>();
        for (var value : values) {
            requireEqual(value, "document_id", documentId);
            result.add(new RagChunk(
                    text(value, "chunk_id"),
                    documentId,
                    integer(value, "chunk_index"),
                    text(value, "text"),
                    text(value, "source"),
                    text(value, "file_type"),
                    optionalInteger(value, "page"),
                    optionalText(value, "h1"),
                    optionalText(value, "h2"),
                    optionalText(value, "h3"),
                    numbers(value.path("vector"))));
        }
        return List.copyOf(result);
    }

    private static List<RagEdge> edges(JsonNode values) throws IOException {
        if (values.isMissingNode() || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw new IOException("Python RAG 图关系必须是数组");
        }
        var result = new ArrayList<RagEdge>();
        for (var value : values) {
            var score = value.get("score");
            result.add(new RagEdge(
                    text(value, "from"),
                    text(value, "to"),
                    score == null || score.isNull() ? null : number(score)));
        }
        return List.copyOf(result);
    }

    private static List<Double> numbers(JsonNode values) throws IOException {
        if (!values.isArray() || values.isEmpty()) {
            throw new IOException("Python RAG vector 必须是非空数组");
        }
        var result = new ArrayList<Double>();
        for (var value : values) {
            result.add(number(value));
        }
        return List.copyOf(result);
    }

    private static double number(JsonNode value) throws IOException {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IOException("Python RAG 数值字段无效");
        }
        return value.doubleValue();
    }

    private static int integer(JsonNode body, String name) throws IOException {
        var value = body.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IOException("Python RAG 字段 " + name + " 必须是整数");
        }
        return value.intValue();
    }

    private static Integer optionalInteger(JsonNode body, String name) throws IOException {
        var value = body.get(name);
        return value == null || value.isNull() ? null : integer(body, name);
    }

    private static String text(JsonNode body, String name) throws IOException {
        var value = optionalText(body, name);
        if (value == null) {
            throw new IOException("Python RAG 响应缺少 " + name);
        }
        return value;
    }

    private static String optionalText(JsonNode body, String name) throws IOException {
        var value = body.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("Python RAG 字段 " + name + " 必须是非空字符串");
        }
        return value.textValue();
    }

    private static void requireEqual(JsonNode body, String name, String expected) throws IOException {
        if (!expected.equals(text(body, name))) {
            throw new IOException("Python RAG 返回了不匹配的 " + name);
        }
    }
}
