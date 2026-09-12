CREATE TABLE IF NOT EXISTS chat_session (
    session_id VARCHAR(160) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_chat_session_user_status (user_id, status, updated_at),
    CONSTRAINT fk_chat_session_user
        FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT chk_chat_session_status CHECK (status IN ('active', 'deleted'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chat_message (
    message_id VARCHAR(160) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    request_id VARCHAR(160) NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    segments_json JSON NULL,
    usage_json JSON NULL,
    model_steps INT NULL,
    tool_rounds INT NULL,
    error_json JSON NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (message_id),
    UNIQUE KEY uq_chat_message_request_role (user_id, request_id, role),
    KEY idx_chat_message_session_time (user_id, session_id, created_at, message_id),
    CONSTRAINT fk_chat_message_user
        FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_chat_message_session
        FOREIGN KEY (session_id) REFERENCES chat_session (session_id),
    CONSTRAINT chk_chat_message_role CHECK (role IN ('user', 'assistant')),
    CONSTRAINT chk_chat_message_status
        CHECK (status IN ('completed', 'failed')),
    CONSTRAINT chk_chat_message_model_steps
        CHECK (model_steps IS NULL OR model_steps >= 0),
    CONSTRAINT chk_chat_message_tool_rounds
        CHECK (tool_rounds IS NULL OR tool_rounds >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
