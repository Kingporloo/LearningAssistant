package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Agent 网关的 JSON、SSE 和 CORS 协议处理。 */
final class AgentGatewayHttp {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final String allowedOrigin;
    private final ObjectMapper mapper;

    AgentGatewayHttp(String allowedOrigin, ObjectMapper mapper) {
        this.allowedOrigin = allowedOrigin;
        this.mapper = mapper;
    }

    void applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !allowedOrigin.equals(origin)) {
            return;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers", "Authorization, Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    ObjectNode readObject(HttpExchange exchange, Set<String> fields) {
        byte[] bytes;
        try (var input = exchange.getRequestBody()) {
            bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
        } catch (IOException exception) {
            throw new AgentGatewayException(400, "无法读取请求正文");
        }
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new AgentGatewayException(413, "请求正文过大");
        }
        try {
            JsonNode value = bytes.length == 0
                    ? mapper.createObjectNode()
                    : mapper.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw new AgentGatewayException(400, "请求正文必须是 JSON 对象");
            }
            value.fieldNames().forEachRemaining(name -> {
                if (!fields.contains(name)) {
                    throw new AgentGatewayException(400, "请求包含未知字段: " + name);
                }
            });
            return (ObjectNode) value;
        } catch (AgentGatewayException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AgentGatewayException(400, "请求正文不是有效 JSON");
        }
    }

    void send(HttpExchange exchange, int status, JsonNode body) {
        byte[] bytes = body == null
                ? new byte[0]
                : body.toString().getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        }
        try {
            exchange.sendResponseHeaders(status, body == null ? -1 : bytes.length);
            if (body != null) {
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } else {
                exchange.close();
            }
        } catch (IOException exception) {
            exchange.close();
        }
    }

    ObjectNode error(String message) {
        return mapper.createObjectNode().put("message", message);
    }

    SseResponse sse(HttpExchange exchange) {
        return new SseResponse(exchange, mapper);
    }

    static final class SseResponse {
        private final HttpExchange exchange;
        private final ObjectMapper mapper;
        private boolean started;
        private boolean closed;

        private SseResponse(HttpExchange exchange, ObjectMapper mapper) {
            this.exchange = exchange;
            this.mapper = mapper;
        }

        boolean started() {
            return started;
        }

        void write(AgentEvent event) {
            if (closed) {
                throw new UncheckedIOException(new IOException("SSE 响应已关闭"));
            }
            try {
                if (!started) {
                    exchange.getResponseHeaders().set(
                            "Content-Type", "text/event-stream; charset=utf-8");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(200, 0);
                    started = true;
                }
                String block = "event: " + event.type() + "\n"
                        + "data: " + event.toJson(mapper) + "\n\n";
                exchange.getResponseBody().write(block.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        void close() {
            if (!closed) {
                closed = true;
                exchange.close();
            }
        }
    }
}
