package com.pdflearning.backend.user;

import java.time.OffsetDateTime;

/**
 * 用户数据。passwordHash 仅在服务内部流转，不会出现在任何 API 响应中。
 */
public record UserRecord(
        String userId,
        String username,
        String nickname,
        String passwordHash,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** API 响应用的无凭据视图。 */
    public UserRecord withoutSecrets() {
        return new UserRecord(userId, username, nickname, null, createdAt, updatedAt);
    }
}
