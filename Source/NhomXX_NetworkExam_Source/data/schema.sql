CREATE TABLE IF NOT EXISTS candidate (
    candidate_id VARCHAR(64) PRIMARY KEY,
    full_name VARCHAR(200) NOT NULL
);

CREATE TABLE IF NOT EXISTS exam (
    exam_id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(250) NOT NULL,
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    CHECK (end_time > start_time)
);

CREATE TABLE IF NOT EXISTS question (
    question_id VARCHAR(64) PRIMARY KEY,
    exam_id VARCHAR(64) NOT NULL,
    position_no INTEGER NOT NULL,
    content VARCHAR(2000) NOT NULL,
    FOREIGN KEY (exam_id) REFERENCES exam(exam_id) ON DELETE CASCADE,
    UNIQUE (exam_id, position_no)
);

CREATE TABLE IF NOT EXISTS question_option (
    option_id VARCHAR(64) PRIMARY KEY,
    question_id VARCHAR(64) NOT NULL,
    option_label VARCHAR(8) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (question_id) REFERENCES question(question_id) ON DELETE CASCADE,
    UNIQUE (question_id, option_label)
);

CREATE TABLE IF NOT EXISTS exam_session (
    session_id VARCHAR(64) PRIMARY KEY,
    exam_id VARCHAR(64) NOT NULL,
    candidate_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at BIGINT NOT NULL,
    deadline BIGINT NOT NULL,
    submitted_at BIGINT,
    FOREIGN KEY (exam_id) REFERENCES exam(exam_id),
    FOREIGN KEY (candidate_id) REFERENCES candidate(candidate_id),
    UNIQUE (exam_id, candidate_id),
    CHECK (REGEXP_LIKE(status, '^(NEW|ACTIVE|SUBMITTED|AUTO_SUBMITTED)$'))
);

CREATE TABLE IF NOT EXISTS answer (
    session_id VARCHAR(64) NOT NULL,
    question_id VARCHAR(64) NOT NULL,
    answer_version INTEGER NOT NULL,
    selected_option_id VARCHAR(64) NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (session_id, question_id),
    FOREIGN KEY (session_id) REFERENCES exam_session(session_id) ON DELETE CASCADE,
    FOREIGN KEY (question_id) REFERENCES question(question_id),
    FOREIGN KEY (selected_option_id) REFERENCES question_option(option_id),
    CHECK (answer_version > 0)
);

CREATE TABLE IF NOT EXISTS submission (
    session_id VARCHAR(64) PRIMARY KEY,
    submitted_at BIGINT NOT NULL,
    submit_type VARCHAR(16) NOT NULL,
    score DOUBLE PRECISION,
    FOREIGN KEY (session_id) REFERENCES exam_session(session_id) ON DELETE CASCADE,
    CHECK (REGEXP_LIKE(submit_type, '^(MANUAL|AUTO)$'))
);

CREATE INDEX IF NOT EXISTS idx_question_exam ON question(exam_id, position_no);
CREATE INDEX IF NOT EXISTS idx_session_status_deadline ON exam_session(status, deadline);
CREATE INDEX IF NOT EXISTS idx_answer_session ON answer(session_id);

