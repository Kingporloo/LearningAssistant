CREATE TABLE IF NOT EXISTS agent_session (
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    active_request_id VARCHAR(160) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_run (
    user_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(160) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    message_id VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'running',
    last_event_seq BIGINT NOT NULL DEFAULT 0,
    interruption_reason TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at TIMESTAMP(6) NULL,
    PRIMARY KEY (user_id, request_id),
    KEY idx_agent_run_session (user_id, session_id, created_at),
    KEY idx_agent_run_status (status, created_at),
    CONSTRAINT fk_agent_run_session
        FOREIGN KEY (user_id, session_id)
        REFERENCES agent_session (user_id, session_id),
    CONSTRAINT chk_agent_run_status
        CHECK (status IN ('running', 'completed', 'failed', 'interrupted')),
    CONSTRAINT chk_agent_run_event_seq CHECK (last_event_seq >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_run_event (
    user_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(160) NOT NULL,
    event_seq BIGINT NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    event_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, request_id, event_seq),
    CONSTRAINT fk_agent_event_run
        FOREIGN KEY (user_id, request_id)
        REFERENCES agent_run (user_id, request_id),
    CONSTRAINT chk_agent_event_seq CHECK (event_seq > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
