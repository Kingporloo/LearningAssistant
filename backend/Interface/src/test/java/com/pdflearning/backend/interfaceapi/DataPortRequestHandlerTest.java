package com.pdflearning.backend.interfaceapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DataPortRequestHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InternalRequestContext context = new InternalRequestContext(
            "dev_user",
            "session_20260908_101112_dev_user",
            "request-1",
            "message-1");

    @Test
    void mapsMemoryStoreAndBindsTrustedSource() throws Exception {
        var command = DataPortRequestHandler.memoryStore(context, mapper.readTree("""
                {
                  "operation_id":"operation-1",
                  "memory_id":null,
                  "memory_type":"semantic",
                  "content":"用户偏好简洁回答",
                  "importance":0.2,
                  "vector":[0.1,0.2],
                  "source":{
                    "session_id":"session_20260908_101112_dev_user",
                    "message_id":"message-1"
                  },
                  "graph":{
                    "entities":[{"name":"用户","type":"人物"}],
                    "relations":[]
                  },
                  "graph_status":"ok"
                }
                """));

        assertEquals("dev_user", command.userId());
        assertEquals("request-1", command.requestId());
        assertEquals(0.2, command.importance());
        assertEquals(context.sessionId(), command.source().sessionId());
        assertEquals(context.messageId(), command.source().messageId());
        assertEquals("用户", command.graph().entities().getFirst().name());
        assertNull(command.memoryId());
    }

    @Test
    void rejectsSpoofedMemorySource() throws Exception {
        var body = mapper.readTree("""
                {
                  "operation_id":"operation-1",
                  "memory_type":"episodic",
                  "content":"一次经历",
                  "importance":0.5,
                  "vector":[0.1,0.2],
                  "source":{"session_id":"other-session","message_id":"message-1"}
                }
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> DataPortRequestHandler.memoryStore(context, body));
    }

    @Test
    void memoryQueryOnlyAcceptsUserScope() throws Exception {
        var body = mapper.readTree("""
                {"memory_type":"all","scope":"session","query_vector":[0.1],"limit":5}
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> DataPortRequestHandler.memoryQuery(context, body));
    }

    @Test
    void mapsContextSummaryWithoutRequiringMessageId() throws Exception {
        var compactContext = new InternalRequestContext(
                "dev_user",
                "session_20260908_101112_dev_user",
                "compact-request-1",
                null);
        var command = DataPortRequestHandler.contextSummary(
                compactContext,
                mapper.readTree("""
                        {
                          "operation_id":"compact:compact-request-1",
                          "base_version":2,
                          "history_cursor":"cursor-1",
                          "through_message_id":"assistant-5",
                          "source_refs":[{"kind":"message","ref_id":"assistant-5"}],
                          "text":"压缩后的会话摘要"
                        }
                        """));

        assertEquals("dev_user", command.userId());
        assertEquals("compact-request-1", command.requestId());
        assertEquals(2, command.baseVersion());
        assertEquals("assistant-5", command.throughMessageId());
        assertEquals(1, command.sourceRefs().size());
    }
}
