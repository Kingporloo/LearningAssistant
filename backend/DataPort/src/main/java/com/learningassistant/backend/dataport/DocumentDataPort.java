package com.learningassistant.backend.dataport;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** 用户上传文档的元数据、构建状态和受控文件引用。 */
public final class DocumentDataPort {
    public record DocumentData(
            String documentId,
            String userId,
            String requestId,
            String fileName,
            long fileSize,
            String sourcePath,
            String markdownPath,
            String status,
            String error,
            Integer chunkCount,
            Integer pageCount,
            OffsetDateTime createdAt,
            OffsetDateTime readyAt) {
    }

    private final DataSource dataSource;

    public DocumentDataPort(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DocumentData create(DocumentData document) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO rag_document
                            (user_id, document_id, file_name, file_size, source_path,
                             markdown_path, build_request_id, status, status_message,
                             chunk_count, page_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'converting', NULL, NULL, NULL, ?, ?)
                        """)) {
            statement.setString(1, document.userId());
            statement.setString(2, document.documentId());
            statement.setString(3, document.fileName());
            statement.setLong(4, document.fileSize());
            statement.setString(5, document.sourcePath());
            statement.setString(6, document.markdownPath());
            statement.setString(7, document.requestId());
            statement.setTimestamp(8, timestamp(document.createdAt()));
            statement.setTimestamp(9, timestamp(document.createdAt()));
            statement.executeUpdate();
            return document;
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 创建 RAG 文档记录失败", exception);
        }
    }

    public List<DocumentData> list(String userId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT * FROM rag_document
                        WHERE user_id = ? AND status <> 'deleted' AND file_name IS NOT NULL
                        ORDER BY created_at DESC, document_id DESC
                        """)) {
            statement.setString(1, userId);
            try (var result = statement.executeQuery()) {
                var documents = new ArrayList<DocumentData>();
                while (result.next()) {
                    documents.add(read(result));
                }
                return List.copyOf(documents);
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取 RAG 文档列表失败", exception);
        }
    }

    public Optional<DocumentData> find(String userId, String documentId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT * FROM rag_document
                        WHERE user_id = ? AND document_id = ? AND status <> 'deleted'
                          AND file_name IS NOT NULL
                        """)) {
            statement.setString(1, userId);
            statement.setString(2, documentId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取 RAG 文档失败", exception);
        }
    }

    public boolean isCurrent(String userId, String documentId, String requestId) {
        return find(userId, documentId)
                .filter(document -> requestId.equals(document.requestId()))
                .isPresent();
    }

    public boolean startReplacement(DocumentData document) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE rag_document
                        SET file_name = ?, file_size = ?, source_path = ?, markdown_path = ?,
                            build_request_id = ?, status = 'converting', status_message = NULL,
                            chunk_count = NULL, page_count = NULL, ready_at = NULL,
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE user_id = ? AND document_id = ?
                          AND status NOT IN ('converting', 'building', 'deleted')
                        """)) {
            statement.setString(1, document.fileName());
            statement.setLong(2, document.fileSize());
            statement.setString(3, document.sourcePath());
            statement.setString(4, document.markdownPath());
            statement.setString(5, document.requestId());
            statement.setString(6, document.userId());
            statement.setString(7, document.documentId());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 更新 RAG 文档源文件失败", exception);
        }
    }

    public boolean restartBuild(String userId, String documentId, String requestId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE rag_document
                        SET build_request_id = ?, status = 'converting', status_message = NULL,
                            chunk_count = NULL, page_count = NULL, ready_at = NULL,
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE user_id = ? AND document_id = ?
                          AND status NOT IN ('converting', 'building', 'deleted')
                        """)) {
            statement.setString(1, requestId);
            statement.setString(2, userId);
            statement.setString(3, documentId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 重新启动 RAG 文档构建失败", exception);
        }
    }

    public void complete(
            String userId,
            String documentId,
            String requestId,
            int chunkCount,
            int pageCount) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE rag_document
                        SET chunk_count = ?, page_count = ?, ready_at = CURRENT_TIMESTAMP(6),
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE user_id = ? AND document_id = ? AND build_request_id = ?
                          AND status IN ('ready', 'empty')
                        """)) {
            statement.setInt(1, chunkCount);
            statement.setInt(2, pageCount);
            statement.setString(3, userId);
            statement.setString(4, documentId);
            statement.setString(5, requestId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 更新 RAG 文档统计失败", exception);
        }
    }

    public void fail(String userId, String documentId, String requestId, String message) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE rag_document
                        SET status = 'failed', status_message = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE user_id = ? AND document_id = ? AND build_request_id = ?
                          AND status <> 'deleted'
                        """)) {
            statement.setString(1, message);
            statement.setString(2, userId);
            statement.setString(3, documentId);
            statement.setString(4, requestId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 更新 RAG 文档失败状态失败", exception);
        }
    }

    public int recoverInterrupted() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE rag_document
                        SET status = 'failed', status_message = '服务重启，文档构建已中断',
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE status IN ('converting', 'building') AND file_name IS NOT NULL
                        """)) {
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 恢复中断的 RAG 文档失败", exception);
        }
    }

    private static DocumentData read(java.sql.ResultSet result) throws SQLException {
        return new DocumentData(
                result.getString("document_id"),
                result.getString("user_id"),
                result.getString("build_request_id"),
                result.getString("file_name"),
                result.getLong("file_size"),
                result.getString("source_path"),
                result.getString("markdown_path"),
                result.getString("status"),
                result.getString("status_message"),
                nullableInt(result, "chunk_count"),
                nullableInt(result, "page_count"),
                time(result.getTimestamp("created_at")),
                time(result.getTimestamp("ready_at")));
    }

    private static Integer nullableInt(java.sql.ResultSet result, String name) throws SQLException {
        int value = result.getInt(name);
        return result.wasNull() ? null : value;
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime time(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
