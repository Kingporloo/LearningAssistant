package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.ChatDataPort;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 组装可信上下文、执行 Python SSE 并保存前端可见结果。 */
final class AgentRunCoordinator {
    private final ChatDataPort chats;
    private final AgentControlClient controlClient;
    private final AgentRunService runService;
    private final AgentRunRequest.AgentConfig agentConfig;
    private final AgentSessionService sessions;
    private final AgentContextSnapshotFactory snapshots;
    private final AgentGatewayHttp http;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    AgentRunCoordinator(
            ChatDataPort chats,
            AgentControlClient controlClient,
            AgentRunService runService,
            AgentRunRequest.AgentConfig agentConfig,
            AgentSessionService sessions,
            AgentContextSnapshotFactory snapshots,
            AgentGatewayHttp http,
            ObjectMapper mapper) {
        this.chats = chats;
        this.controlClient = controlClient;
        this.runService = runService;
        this.agentConfig = agentConfig;
        this.sessions = sessions;
        this.snapshots = snapshots;
        this.http = http;
        this.mapper = mapper;
    }

    void run(
            HttpExchange exchange,
            String userId,
            String sessionId,
            String requestId,
            String messageId,
            String message) {
        String title = sessions.title(userId, sessionId);
        var lock = lock(sessionId);
        try {
            var snapshot = snapshots.load(userId, sessionId);
            try {
                chats.saveUserMessage(
                        userId, sessionId, title, messageId, requestId, message, now());
            } catch (IllegalArgumentException exception) {
                throw new AgentGatewayException(409, "会话归属或消息标识冲突");
            }
            sessions.markPersisted(userId, sessionId);
            var request = snapshots.runRequest(
                    snapshot,
                    userId,
                    sessionId,
                    requestId,
                    messageId,
                    message,
                    agentConfig);
            execute(exchange, request);
        } finally {
            lock.unlock();
        }
    }

    void compact(
            HttpExchange exchange,
            String userId,
            String sessionId,
            String requestId) {
        sessions.requireStored(userId, sessionId);
        var lock = lock(sessionId);
        try {
            var snapshot = snapshots.load(userId, sessionId);
            var request = snapshots.compactRequest(
                    snapshot, userId, sessionId, requestId, agentConfig);
            var result = controlClient.compactContext(request);
            http.send(exchange, 200, mapper.valueToTree(result));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentGatewayException(503, "上下文压缩被中断");
        } catch (IOException exception) {
            throw new AgentGatewayException(502, "Python Agent 暂时不可用");
        } finally {
            lock.unlock();
        }
    }

    private void execute(HttpExchange exchange, AgentRunRequest request) {
        var transcript = new AgentRunTranscript(mapper);
        var response = http.sse(exchange);
        try {
            var result = runService.execute(request, event -> {
                transcript.accept(event);
                if ("run_finished".equals(event.type())) {
                    chats.saveAssistantMessage(transcript.toMessage(
                            request.userId(), request.sessionId(), request.requestId(), now()));
                }
                response.write(event);
            });
            if (result.outcome() == AgentRunService.Outcome.EXECUTED
                    || result.outcome() == AgentRunService.Outcome.REPLAYED) {
                chats.saveAssistantMessage(transcript.toMessage(
                        request.userId(), request.sessionId(), request.requestId(), now()));
                response.close();
                return;
            }
            if (response.started()) {
                response.close();
                return;
            }
            String message = switch (result.outcome()) {
                case IN_PROGRESS -> "相同请求正在执行";
                case SESSION_BUSY -> "该会话已有一次运行正在进行";
                case TERMINAL -> "该请求已中断，不能自动重放";
                default -> "Agent 运行状态冲突";
            };
            http.send(exchange, 409, http.error(message));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            persistFailure(transcript, request, "gateway_interrupted", "Agent 运行被中断");
            failResponse(response, exchange, 503, "Agent 运行被中断");
        } catch (IOException | UncheckedIOException exception) {
            persistFailure(transcript, request, "agent_stream_interrupted", "Agent 事件流中断");
            failResponse(response, exchange, 502, "Python Agent 事件流中断");
        } catch (RuntimeException exception) {
            persistFailure(transcript, request, "agent_run_failed", "Agent 运行失败");
            failResponse(response, exchange, 500, "Agent 运行失败");
        }
    }

    private ReentrantLock lock(String sessionId) {
        var lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new AgentGatewayException(409, "该会话已有一次运行正在进行");
        }
        return lock;
    }

    private void persistFailure(
            AgentRunTranscript transcript,
            AgentRunRequest request,
            String code,
            String message) {
        if (!transcript.terminal()) {
            transcript.fail(code, message, true);
        }
        try {
            chats.saveAssistantMessage(transcript.toMessage(
                    request.userId(), request.sessionId(), request.requestId(), now()));
        } catch (RuntimeException ignored) {
            // 原始运行事件仍由 AgentRunService 保存；这里不能覆盖原异常。
        }
    }

    private void failResponse(
            AgentGatewayHttp.SseResponse response,
            HttpExchange exchange,
            int status,
            String message) {
        if (response.started()) {
            response.close();
        } else {
            http.send(exchange, status, http.error(message));
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
