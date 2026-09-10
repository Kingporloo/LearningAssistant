package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** 可关闭的 Python Agent SSE 响应；关闭会取消对应 HTTP 响应读取。 */
public final class AgentEventStream implements AutoCloseable {
    private final BufferedReader reader;
    private final ObjectMapper mapper;
    private final String requestId;
    private final String sessionId;
    private long nextSequence = 1;
    private boolean terminalSeen;
    private volatile boolean closed;

    AgentEventStream(
            InputStream input,
            ObjectMapper mapper,
            String requestId,
            String sessionId) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.mapper = mapper;
        this.requestId = requestId;
        this.sessionId = sessionId;
    }

    /** 读取下一条事件；收到 run_finished 或主动关闭后返回 null。 */
    public AgentEvent readNext() throws IOException {
        if (closed || terminalSeen) {
            return null;
        }

        String eventType = null;
        var data = new StringBuilder();
        while (true) {
            String line;
            try {
                line = reader.readLine();
            } catch (IOException exception) {
                if (closed) {
                    return null;
                }
                throw exception;
            }
            if (line == null) {
                if (closed) {
                    return null;
                }
                if (data.isEmpty()) {
                    throw new EOFException("Python Agent SSE 在 run_finished 前结束");
                }
                return parse(eventType, data.toString());
            }
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    return parse(eventType, data.toString());
                }
                eventType = null;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }

            int separator = line.indexOf(':');
            String field = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0 ? "" : line.substring(separator + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                eventType = value;
            } else if ("data".equals(field)) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
    }

    /** 在当前线程持续消费事件；适合由 Java 虚拟线程执行。 */
    public void consume(Consumer<AgentEvent> consumer) throws IOException {
        if (consumer == null) {
            throw new IllegalArgumentException("consumer 不能为空");
        }
        AgentEvent event;
        while ((event = readNext()) != null) {
            consumer.accept(event);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }

    private AgentEvent parse(String eventType, String data) throws IOException {
        if (eventType == null || eventType.isBlank()) {
            throw new IOException("Python Agent SSE 事件缺少 event 字段");
        }
        JsonNode root = mapper.readTree(data);
        if (root == null || !root.isObject()) {
            throw new IOException("Python Agent SSE data 必须是 JSON 对象");
        }
        String dataType = requiredText(root, "type");
        String dataRequestId = requiredText(root, "request_id");
        String dataSessionId = requiredText(root, "session_id");
        JsonNode sequence = root.get("event_seq");
        JsonNode payload = root.get("payload");
        if (!eventType.equals(dataType)) {
            throw new IOException("Python Agent SSE 的 event 与 data.type 不一致");
        }
        if (!requestId.equals(dataRequestId) || !sessionId.equals(dataSessionId)) {
            throw new IOException("Python Agent SSE 事件不属于当前运行");
        }
        if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()) {
            throw new IOException("Python Agent SSE event_seq 必须是整数");
        }
        long eventSequence = sequence.longValue();
        if (eventSequence != nextSequence) {
            throw new IOException(
                    "Python Agent SSE event_seq 不连续，期望 "
                            + nextSequence + "，实际 " + eventSequence);
        }
        if (payload == null || !payload.isObject()) {
            throw new IOException("Python Agent SSE payload 必须是 JSON 对象");
        }

        AgentEvent event;
        try {
            event = new AgentEvent(
                    dataType,
                    dataRequestId,
                    dataSessionId,
                    eventSequence,
                    payload);
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Python Agent SSE 事件无效: " + exception.getMessage(),
                    exception);
        }
        nextSequence++;
        terminalSeen = "run_finished".equals(event.type());
        return event;
    }

    private static String requiredText(JsonNode root, String name) throws IOException {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("Python Agent SSE " + name + " 必须是非空字符串");
        }
        return value.textValue();
    }
}
