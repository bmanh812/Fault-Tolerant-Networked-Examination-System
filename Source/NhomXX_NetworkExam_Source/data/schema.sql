PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS candidate (
    candidate_id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    full_name TEXT NOT NULL,
    email TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS exam (
    exam_id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT,
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds > 0),
    start_at TEXT,
    deadline_at TEXT,
    status TEXT NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'FINISHED', 'CANCELLED')),
    shuffle_questions INTEGER NOT NULL DEFAULT 0 CHECK (shuffle_questions IN (0, 1)),
    max_clients INTEGER NOT NULL DEFAULT 20 CHECK (max_clients > 0),
    protocol_version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (deadline_at IS NULL OR start_at IS NULL OR deadline_at > start_at)
);

CREATE TABLE IF NOT EXISTS question (
    question_id TEXT PRIMARY KEY,
    exam_id TEXT NOT NULL,
    position INTEGER NOT NULL CHECK (position > 0),
    question_type TEXT NOT NULL DEFAULT 'SINGLE_CHOICE'
        CHECK (question_type IN ('SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'TEXT')),
    content TEXT NOT NULL,
    options_json TEXT,
    correct_answer_json TEXT,
    points REAL NOT NULL DEFAULT 1.0 CHECK (points >= 0),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (exam_id) REFERENCES exam(exam_id) ON DELETE CASCADE,
    UNIQUE (exam_id, position)
);

CREATE TABLE IF NOT EXISTS exam_candidate (
    exam_id TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    eligible INTEGER NOT NULL DEFAULT 1 CHECK (eligible IN (0, 1)),
    extra_time_seconds INTEGER NOT NULL DEFAULT 0 CHECK (extra_time_seconds >= 0),
    assigned_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (exam_id, candidate_id),
    FOREIGN KEY (exam_id) REFERENCES exam(exam_id) ON DELETE CASCADE,
    FOREIGN KEY (candidate_id) REFERENCES candidate(candidate_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS exam_session (
    session_id TEXT PRIMARY KEY,
    exam_id TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL DEFAULT 'CREATED'
        CHECK (state IN ('CREATED', 'ACTIVE', 'DISCONNECTED', 'SUBMITTED', 'CLOSED')),
    last_committed_seq INTEGER NOT NULL DEFAULT 0 CHECK (last_committed_seq >= 0),
    connection_generation INTEGER NOT NULL DEFAULT 0 CHECK (connection_generation >= 0),
    started_at TEXT,
    last_seen_at TEXT,
    disconnected_at TEXT,
    submitted_at TEXT,
    submit_reason TEXT CHECK (submit_reason IS NULL OR submit_reason IN ('MANUAL', 'DEADLINE', 'ADMIN')),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (exam_id) REFERENCES exam(exam_id),
    FOREIGN KEY (candidate_id) REFERENCES candidate(candidate_id),
    UNIQUE (exam_id, candidate_id)
);

CREATE TABLE IF NOT EXISTS processed_request (
    session_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    message_type TEXT NOT NULL,
    seq INTEGER,
    payload_hash TEXT NOT NULL,
    result_code TEXT NOT NULL,
    response_json TEXT,
    completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, request_id),
    FOREIGN KEY (session_id) REFERENCES exam_session(session_id) ON DELETE CASCADE,
    UNIQUE (session_id, message_type, seq)
);

CREATE TABLE IF NOT EXISTS answer_event (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    question_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    seq INTEGER NOT NULL CHECK (seq > 0),
    answer_json TEXT NOT NULL,
    committed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES exam_session(session_id) ON DELETE CASCADE,
    FOREIGN KEY (question_id) REFERENCES question(question_id),
    UNIQUE (session_id, seq),
    UNIQUE (session_id, request_id)
);

CREATE TABLE IF NOT EXISTS answer_current (
    session_id TEXT NOT NULL,
    question_id TEXT NOT NULL,
    answer_json TEXT NOT NULL,
    latest_seq INTEGER NOT NULL CHECK (latest_seq > 0),
    latest_request_id TEXT NOT NULL,
    answered_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, question_id),
    FOREIGN KEY (session_id) REFERENCES exam_session(session_id) ON DELETE CASCADE,
    FOREIGN KEY (question_id) REFERENCES question(question_id),
    UNIQUE (session_id, latest_request_id)
);

CREATE TABLE IF NOT EXISTS submission (
    submission_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL UNIQUE,
    submitted_at TEXT NOT NULL,
    submit_reason TEXT NOT NULL CHECK (submit_reason IN ('MANUAL', 'DEADLINE', 'ADMIN')),
    final_seq INTEGER NOT NULL CHECK (final_seq >= 0),
    answered_count INTEGER NOT NULL DEFAULT 0 CHECK (answered_count >= 0),
    score REAL,
    result_json TEXT,
    FOREIGN KEY (session_id) REFERENCES exam_session(session_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS session_event (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    event_type TEXT NOT NULL
        CHECK (event_type IN ('CREATED', 'CONNECTED', 'DISCONNECTED', 'RESUMED', 'ANSWER_COMMITTED', 'SUBMITTED', 'CLOSED', 'ERROR')),
    connection_generation INTEGER,
    request_id TEXT,
    details_json TEXT,
    occurred_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES exam_session(session_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_question_exam ON question(exam_id, position);
CREATE INDEX IF NOT EXISTS idx_session_candidate ON exam_session(candidate_id, state);
CREATE INDEX IF NOT EXISTS idx_session_exam_state ON exam_session(exam_id, state);
CREATE INDEX IF NOT EXISTS idx_session_last_seen ON exam_session(state, last_seen_at);
CREATE INDEX IF NOT EXISTS idx_answer_event_session_seq ON answer_event(session_id, seq);
CREATE INDEX IF NOT EXISTS idx_session_event_time ON session_event(session_id, occurred_at);

CREATE VIEW IF NOT EXISTS v_session_progress AS
SELECT
    s.session_id,
    s.exam_id,
    s.candidate_id,
    s.state,
    s.last_committed_seq,
    COUNT(a.question_id) AS answered_count,
    s.last_seen_at,
    s.submitted_at
FROM exam_session s
LEFT JOIN answer_current a ON a.session_id = s.session_id
GROUP BY s.session_id;

INSERT OR IGNORE INTO schema_version(version, description)
VALUES (1, 'Initial fault-tolerant network exam schema');

