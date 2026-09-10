package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.AgentRunDataPort;
import com.pdflearning.backend.dataport.AgentRunRecord;
import com.pdflearning.backend.dataport.AgentRunStatus;
import com.pdflearning.backend.dataport.StoredAgentEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/** 登记一次运行、持久化 Python SSE 事件并执行 request_id 幂等判断。 */
public final class AgentRunService {
    public enum Outcome {
        EXECUTED,
        REPLAYED,
        IN_PROGRESS,
        SESSION_BUSY,
        TERMINAL
    }

    public record RunResult(
            Outcome outcome,
            AgentRunRecord run,
            String activeRequestId) {
    }

    private final AgentSseClient agentClient;
    private final AgentRunDataPort dataPort;
    private final ObjectMapper mapper;

    public AgentRunService(
            AgentSseClient agentClient,
            AgentRunDataPort dataPort) {
        this(agentClient, dataPort, new ObjectMapper());
    }

    AgentRunService(
            AgentSseClient agentClient,
            AgentRunDataPort dataPort,
            ObjectMapper mapper) {
        if (agentClient == null || dataPort == null || mapper == null) {
            throw new IllegalArgumentException("AgentRunService 依赖不能为空");
        }
        this.agentClient = agentClient;
        this.dataPort = dataPort;
        this.mapper = mapper;
    }

    /**
     * 执行或复用一次运行。事件总是在持久化成功后才交给上层 consumer。
     */
    public RunResult execute(
            AgentRunRequest request,
            Consumer<AgentEvent> consumer) throws IOException, InterruptedException {
        if (request == null || consumer == null) {
            throw new IllegalArgumentException("request 和 consumer 不能为空");
        }
        String requestHash = sha256(request.message().getBytes(StandardCharsets.UTF_8));
        var claim = dataPort.beginRun(
                request.userId(),
                request.sessionId(),
                request.requestId(),
                request.messageId(),
                requestHash);

        return switch (claim.decision()) {
            case IN_PROGRESS -> result(Outcome.IN_PROGRESS, claim);
            case SESSION_BUSY -> result(Outcome.SESSION_BUSY, claim);
            case TERMINAL -> result(Outcome.TERMINAL, claim);
            case REPLAY -> replay(request, claim, consumer);
            case STARTED -> executeClaimed(request, claim, consumer);
        };
    }

    private RunResult executeClaimed(
            AgentRunRequest request,
            AgentRunDataPort.RunClaim claim,
            Consumer<AgentEvent> consumer) throws IOException, InterruptedException {
        try (var stream = agentClient.openRun(request)) {
            stream.consume(event -> persistAndForward(request, event, consumer));
        } catch (InterruptedException exception) {
            markInterrupted(request, exception);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (IOException | RuntimeException exception) {
            markInterrupted(request, exception);
            throw exception;
        }

        var finished = dataPort.findRun(request.userId(), request.requestId())
                .orElseThrow(() -> new IllegalStateException("已执行的 Agent 运行不存在"));
        if (finished.status() == AgentRunStatus.RUNNING) {
            var exception = new IllegalStateException(
                    "Python SSE 正常结束但 Agent 运行仍未完成");
            markInterrupted(request, exception);
            throw exception;
        }
        return new RunResult(Outcome.EXECUTED, finished, claim.activeRequestId());
    }

    private RunResult replay(
            AgentRunRequest request,
            AgentRunDataPort.RunClaim claim,
            Consumer<AgentEvent> consumer) throws IOException {
        for (var stored : dataPort.readEvents(request.userId(), request.requestId())) {
            consumer.accept(readStoredEvent(request, stored));
        }
        return new RunResult(Outcome.REPLAYED, claim.run(), claim.activeRequestId());
    }

    private void persistAndForward(
            AgentRunRequest request,
            AgentEvent event,
            Consumer<AgentEvent> consumer) {
        var root = event.toJson(mapper);
        String eventJson = root.toString();
        var appendResult = dataPort.appendEvent(
                request.userId(),
                request.sessionId(),
                request.requestId(),
                event.eventSeq(),
                event.type(),
                sha256(eventJson.getBytes(StandardCharsets.UTF_8)),
                eventJson,
                terminalStatus(event));
        if (appendResult == AgentRunDataPort.AppendResult.INSERTED) {
            consumer.accept(event);
        }
    }

    private AgentEvent readStoredEvent(
            AgentRunRequest request,
            StoredAgentEvent stored) throws IOException {
        AgentEvent event;
        try {
            event = AgentEvent.fromJson(mapper.readTree(stored.eventJson()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("数据库中的 Agent 事件无效", exception);
        }
        if (event.eventSeq() != stored.eventSeq()
                || !event.type().equals(stored.eventType())
                || !event.requestId().equals(request.requestId())
                || !event.sessionId().equals(request.sessionId())) {
            throw new IOException("数据库中的 Agent 事件与运行索引不一致");
        }
        return event;
    }

    private static AgentRunStatus terminalStatus(AgentEvent event) {
        if (!"run_finished".equals(event.type())) {
            return null;
        }
        String status = event.payload().path("status").asText("");
        return switch (status) {
            case "completed" -> AgentRunStatus.COMPLETED;
            case "failed" -> AgentRunStatus.FAILED;
            default -> throw new IllegalArgumentException(
                    "run_finished.payload.status 必须是 completed 或 failed");
        };
    }

    private void markInterrupted(AgentRunRequest request, Exception cause) {
        String detail = cause.getMessage();
        String reason = cause instanceof InterruptedException
                ? "Java 运行线程被中断。"
                : "Python Agent 事件流中断: "
                        + (detail == null || detail.isBlank() ? cause.getClass().getSimpleName() : detail);
        try {
            dataPort.markInterrupted(request.userId(), request.requestId(), reason);
        } catch (RuntimeException persistenceFailure) {
            cause.addSuppressed(persistenceFailure);
        }
    }

    private static RunResult result(
            Outcome outcome,
            AgentRunDataPort.RunClaim claim) {
        return new RunResult(outcome, claim.run(), claim.activeRequestId());
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }
}
