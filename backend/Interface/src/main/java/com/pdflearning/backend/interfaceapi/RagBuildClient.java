package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.RagDataPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Java 文档流程调用 Python 转换和 RAG 构建接口。 */
final class RagBuildClient {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    record BuildResult(RagDataPort.BuildCommand command, int pageCount) {
    }

    private final URI buildUri;
    private final String internalToken;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper mapper;

    RagBuildClient(URI baseUri, String internalToken, Duration timeout, ObjectMapper mapper) {
        if (baseUri == null || baseUri.getScheme() == null || baseUri.getHost() == null) {
            throw new IllegalArgumentException("Python RAG 地址无效");
        }
        this.buildUri = baseUri.resolve("/internal/rag/build");
        this.internalToken = internalToken;
        this.timeout = timeout;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    BuildResult build(
            String userId,
            String documentId,
            String requestId,
            String sourcePath,
            String markdownPath,
            String sourceName) throws IOException, InterruptedException {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        body.put("document_id", documentId);
        body.put("request_id", requestId);
        body.put("file_ref", sourcePath);
        body.put("markdown_ref", markdownPath);
        body.put("source_name", sourceName);
        var request = HttpRequest.newBuilder(buildUri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + internalToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Python RAG 构建结果超过 256 MiB");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = new String(
                        bytes, 0, Math.min(bytes.length, MAX_ERROR_BYTES), StandardCharsets.UTF_8);
                throw new IOException("Python RAG HTTP " + response.statusCode() + ": " + detail);
            }
            return RagBuildResultParser.parse(
                    mapper.readTree(bytes), userId, documentId, requestId);
        }
    }
}
