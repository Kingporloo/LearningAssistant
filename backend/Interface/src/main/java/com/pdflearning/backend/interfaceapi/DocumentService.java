package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdflearning.backend.dataport.DocumentDataPort;
import com.pdflearning.backend.dataport.RagDocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/** 用户文档落盘、异步 RAG 构建、状态查询和删除编排。 */
final class DocumentService {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".pdf", ".md", ".txt");

    private final DocumentDataPort documents;
    private final RagDocumentStore rag;
    private final RagBuildClient builder;
    private final Path uploadRoot;
    private final Executor executor;
    private final ObjectMapper mapper;

    DocumentService(
            DocumentDataPort documents,
            RagDocumentStore rag,
            RagBuildClient builder,
            Path uploadRoot,
            Executor executor,
            ObjectMapper mapper) {
        this.documents = documents;
        this.rag = rag;
        this.builder = builder;
        this.uploadRoot = uploadRoot.toAbsolutePath().normalize();
        this.executor = executor;
        this.mapper = mapper;
    }

    ArrayNode list(String userId) {
        var result = mapper.createArrayNode();
        documents.list(userId).forEach(document -> result.add(view(document)));
        return result;
    }

    ObjectNode upload(String userId, String fileName, byte[] content) {
        String extension = validateFileName(fileName);
        if (content.length == 0) {
            throw new AgentGatewayException(400, "上传文件不能为空");
        }
        String documentId = "doc_" + UUID.randomUUID();
        String requestId = "rag_build_" + UUID.randomUUID();
        Path directory = uploadRoot.resolve(userId).resolve(documentId);
        Path source = directory.resolve("source" + extension);
        Path markdown = directory.resolve("content.md");
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var document = new DocumentDataPort.DocumentData(
                documentId,
                userId,
                requestId,
                fileName,
                content.length,
                source.toString(),
                markdown.toString(),
                "converting",
                null,
                null,
                null,
                now,
                null);
        try {
            Files.createDirectories(directory);
            Path temporary = directory.resolve("upload.tmp");
            Files.write(temporary, content);
            Files.move(temporary, source, StandardCopyOption.ATOMIC_MOVE);
            documents.create(document);
        } catch (IOException exception) {
            deleteFiles(directory);
            throw new AgentGatewayException(500, "无法保存上传文件");
        } catch (RuntimeException exception) {
            deleteFiles(directory);
            throw exception;
        }
        try {
            executor.execute(() -> build(document));
            return view(document);
        } catch (RuntimeException exception) {
            documents.fail(userId, documentId, requestId, "文档构建任务无法启动");
            deleteFiles(directory);
            throw exception;
        }
    }

    void delete(String userId, String documentId) {
        var document = documents.find(userId, documentId)
                .orElseThrow(() -> new AgentGatewayException(404, "文档不存在"));
        var result = rag.deleteDocument(userId, documentId);
        if ("not_found".equals(result.get("status"))) {
            throw new AgentGatewayException(404, "文档不存在");
        }
        deleteFiles(Path.of(document.sourcePath()).getParent());
    }

    private void build(DocumentDataPort.DocumentData document) {
        try {
            var result = builder.build(
                    document.userId(),
                    document.documentId(),
                    document.requestId(),
                    document.sourcePath(),
                    document.markdownPath(),
                    document.fileName());
            if (!documents.isCurrent(
                    document.userId(), document.documentId(), document.requestId())) {
                return;
            }
            var stored = rag.replaceDocument(result.command());
            String status = String.valueOf(stored.get("status"));
            if (!"ready".equals(status) && !"empty".equals(status)) {
                if (!"cancelled".equals(status)) {
                    documents.fail(
                            document.userId(), document.documentId(), document.requestId(),
                            "RAG 索引写入失败");
                }
                return;
            }
            documents.complete(
                    document.userId(),
                    document.documentId(),
                    document.requestId(),
                    result.command().chunks().size(),
                    result.pageCount());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            documents.fail(
                    document.userId(), document.documentId(), document.requestId(),
                    "文档构建被中断");
        } catch (IOException | RuntimeException exception) {
            documents.fail(
                    document.userId(), document.documentId(), document.requestId(),
                    "文档转换或索引构建失败");
        }
    }

    private ObjectNode view(DocumentDataPort.DocumentData document) {
        var value = mapper.createObjectNode();
        value.put("id", document.documentId());
        value.put("name", document.fileName());
        value.put("size", document.fileSize());
        value.put("status", "empty".equals(document.status()) ? "ready" : document.status());
        value.put("createdAt", document.createdAt().toString());
        if (document.chunkCount() != null) {
            value.put("chunkCount", document.chunkCount());
        }
        if (document.pageCount() != null && document.pageCount() > 0) {
            value.put("pageCount", document.pageCount());
        }
        if ("failed".equals(document.status()) && document.error() != null) {
            value.put("error", document.error());
        }
        if (document.readyAt() != null) {
            value.put("readyAt", document.readyAt().toString());
        }
        return value;
    }

    private static String validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw new AgentGatewayException(400, "文件名无效");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream()
                .filter(lower::endsWith)
                .findFirst()
                .orElseThrow(() -> new AgentGatewayException(
                        400, "只支持 PDF、Markdown 和 TXT 文件"));
    }

    private static void deleteFiles(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 数据已不可见；遗留文件由运维清理。
                }
            });
        } catch (IOException ignored) {
            // 数据已不可见；遗留文件由运维清理。
        }
    }
}
