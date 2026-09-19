package com.learningassistant.backend.dataport;

import java.util.Map;

/** RAG 文档构建结果写入和按用户删除能力。 */
public interface RagDocumentStore {
    Map<String, Object> replaceDocument(RagDataPort.BuildCommand command);

    Map<String, Object> deleteDocument(String userId, String documentId);
}
