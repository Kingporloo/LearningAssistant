package com.pdflearning.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VectorIndexScopeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void qdrantQueryAndDeleteAlwaysContainUserScope() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var headers = new ArrayList<String>();
        start(exchange -> {
            requests.add(read(exchange));
            headers.add(exchange.getRequestHeaders().getFirst("api-key"));
            if (exchange.getRequestURI().getPath().endsWith("/query")) {
                respond(exchange, """
                        {"status":"ok","result":{"points":[{"id":"1c193731-86d6-4ae7-9797-f25173401d03","score":0.8}]}}
                        """);
            } else {
                respond(exchange, "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}");
            }
        });
        var index = new QdrantMemoryIndex(
                address(), "agent_memory_dev", "secret", Duration.ofSeconds(2));

        var result = index.query("dev_user", "semantic", List.of(0.1, 0.2), 5);
        index.delete("dev_user", "1c193731-86d6-4ae7-9797-f25173401d03");

        assertEquals(1, result.size());
        assertEquals(List.of("secret", "secret"), headers);
        assertEquals(
                List.of("dev_user", "active", "semantic"),
                matchValues(requests.get(0).path("filter").path("must")));
        assertEquals(
                List.of("dev_user", "active", "1c193731-86d6-4ae7-9797-f25173401d03"),
                matchValues(requests.get(1).path("filter").path("must")));
    }

    @Test
    void milvusSearchAndBodyReadKeepTheSameTenantScope() throws Exception {
        var requests = new ArrayList<JsonNode>();
        start(exchange -> {
            requests.add(read(exchange));
            if (exchange.getRequestURI().getPath().endsWith("/search")) {
                respond(exchange, """
                        {"code":0,"data":[{"id":"doc-a:000000","distance":0.91}]}
                        """);
            } else {
                respond(exchange, """
                        {"code":0,"data":[
                          {"user_id":"other","chunk_id":"doc-a:000000","document_id":"doc-a","chunk_index":0,"text":"wrong","source":"a.md","file_type":"md"},
                          {"user_id":"dev_user","chunk_id":"doc-a:000000","document_id":"doc-a","chunk_index":0,"text":"right","source":"a.md","file_type":"md"}
                        ]}
                        """);
            }
        });
        var index = new MilvusRagIndex(
                address(), "rag_chunks_dev", "default", "root:Milvus", Duration.ofSeconds(2));

        var scored = index.search("dev_user", List.of("doc-a"), List.of(0.1, 0.2), null, 5);
        var chunks = index.readChunks("dev_user", List.of("doc-a"), List.of("doc-a:000000"));

        assertEquals(1, scored.size());
        assertEquals("right", chunks.get("doc-a:000000").text());
        assertTrue(requests.get(0).path("filter").asText().contains("user_id == \"dev_user\""));
        assertTrue(requests.get(1).path("filter").asText().contains("chunk_id in [\"doc-a:000000\"]"));
    }

    @Test
    void milvusLiteralEscapesFilterSyntaxCharacters() {
        assertEquals("\"user\\\"\\\\name\"", MilvusRagIndex.literal("user\"\\name"));
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private JsonNode read(HttpExchange exchange) throws IOException {
        return mapper.readTree(exchange.getRequestBody());
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static List<String> matchValues(JsonNode must) {
        var values = new ArrayList<String>();
        for (var condition : must) {
            values.add(condition.path("match").path("value").asText());
        }
        return values;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
