CREATE TABLE IF NOT EXISTS long_term_memory (
    memory_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    memory_type VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    importance DOUBLE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    index_status VARCHAR(24) NOT NULL DEFAULT 'pending',
    revision INT NOT NULL DEFAULT 1,
    graph_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    graph_error TEXT NULL,
    graph_updated_at TIMESTAMP(6) NULL,
    source_session_id VARCHAR(160) NOT NULL,
    source_message_id VARCHAR(160) NOT NULL,
    event_time TIMESTAMP(6) NULL,
    graph_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (memory_id),
    KEY idx_memory_user_type_status (user_id, memory_type, status),
    KEY idx_memory_graph_queue (graph_status, memory_type, status, index_status, updated_at),
    CONSTRAINT chk_memory_type CHECK (memory_type IN ('semantic', 'episodic')),
    CONSTRAINT chk_memory_importance CHECK (importance >= 0 AND importance <= 1),
    CONSTRAINT chk_memory_status CHECK (status IN ('active', 'deleted')),
    CONSTRAINT chk_memory_revision CHECK (revision > 0),
    CONSTRAINT chk_memory_graph_status
        CHECK (graph_status IN ('pending', 'processing', 'ok', 'error', 'skipped'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS storage_operation (
    user_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(160) NOT NULL,
    operation_id VARCHAR(200) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, request_id, operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS rag_document (
    user_id VARCHAR(128) NOT NULL,
    document_id VARCHAR(160) NOT NULL,
    file_name VARCHAR(255) NULL,
    file_size BIGINT NULL,
    source_path VARCHAR(1024) NULL,
    markdown_path VARCHAR(1024) NULL,
    build_request_id VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    status_message TEXT NULL,
    chunk_count INT NULL,
    page_count INT NULL,
    ready_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, document_id),
    KEY idx_rag_document_user_status (user_id, status),
    CONSTRAINT chk_rag_document_status
        CHECK (status IN ('converting', 'building', 'ready', 'empty', 'failed', 'deleted')),
    CONSTRAINT chk_rag_document_file_size
        CHECK (file_size IS NULL OR file_size >= 0),
    CONSTRAINT chk_rag_document_chunk_count
        CHECK (chunk_count IS NULL OR chunk_count >= 0),
    CONSTRAINT chk_rag_document_page_count
        CHECK (page_count IS NULL OR page_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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

CREATE TABLE IF NOT EXISTS session_summary (
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    version INT NOT NULL,
    text MEDIUMTEXT NOT NULL,
    through_message_id VARCHAR(160) NULL,
    history_cursor VARCHAR(160) NULL,
    source_refs_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, session_id),
    CONSTRAINT fk_summary_session
        FOREIGN KEY (user_id, session_id)
        REFERENCES agent_session (user_id, session_id),
    CONSTRAINT chk_summary_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS context_summary_operation (
    user_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(160) NOT NULL,
    operation_id VARCHAR(200) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, request_id, operation_id),
    KEY idx_summary_operation_session (user_id, session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS session_ledger (
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    version INT NOT NULL,
    compacted_through_message_id VARCHAR(160) NULL,
    entries_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, session_id),
    CONSTRAINT fk_ledger_session
        FOREIGN KEY (user_id, session_id)
        REFERENCES agent_session (user_id, session_id),
    CONSTRAINT chk_ledger_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(64) NOT NULL PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    nickname VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_login_token (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_user_login_token_user (user_id),
    INDEX idx_user_login_token_expires (expires_at),
    CONSTRAINT fk_user_login_token_user
        FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
