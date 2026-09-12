package com.pdflearning.backend.interfaceapi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 尚未产生真实数据的会话只保存在当前 Java 进程。 */
final class PendingSessionRegistry {
    record PendingSession(
            String sessionId,
            String userId,
            String title,
            OffsetDateTime createdAt) {
    }

    private final ConcurrentHashMap<String, PendingSession> sessions = new ConcurrentHashMap<>();

    boolean add(PendingSession session) {
        return sessions.putIfAbsent(session.sessionId(), session) == null;
    }

    Optional<PendingSession> findOwned(String userId, String sessionId) {
        var session = sessions.get(sessionId);
        return session != null && session.userId().equals(userId)
                ? Optional.of(session)
                : Optional.empty();
    }

    List<PendingSession> listOwned(String userId) {
        return sessions.values().stream()
                .filter(session -> session.userId().equals(userId))
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .toList();
    }

    boolean removeOwned(String userId, String sessionId) {
        var session = sessions.get(sessionId);
        return session != null
                && session.userId().equals(userId)
                && sessions.remove(sessionId, session);
    }
}
