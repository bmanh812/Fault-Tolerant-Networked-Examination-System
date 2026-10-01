INSERT OR IGNORE INTO candidate(candidate_id, username, password_hash, full_name, email)
VALUES
    ('CAND-001', 'candidate01', 'demo-sha256-change-before-production', 'Thi sinh 01', 'candidate01@example.local'),
    ('CAND-002', 'candidate02', 'demo-sha256-change-before-production', 'Thi sinh 02', 'candidate02@example.local'),
    ('CAND-003', 'candidate03', 'demo-sha256-change-before-production', 'Thi sinh 03', 'candidate03@example.local');

INSERT OR IGNORE INTO exam(
    exam_id, title, description, duration_seconds, status, shuffle_questions, max_clients, protocol_version
)
VALUES (
    'EXAM-DEMO-001',
    'Network Programming Demo Exam',
    'Ky thi mau dung de phat trien va kiem thu Client Server.',
    600,
    'DRAFT',
    0,
    20,
    1
);

INSERT OR IGNORE INTO question(
    question_id, exam_id, position, question_type, content, options_json, correct_answer_json, points
)
VALUES
    ('Q01', 'EXAM-DEMO-001', 1, 'SINGLE_CHOICE', 'TCP la giao thuc thuoc tang nao?', '["Transport","Network","Application","Physical"]', '"Transport"', 1.0),
    ('Q02', 'EXAM-DEMO-001', 2, 'SINGLE_CHOICE', 'TCP co bao toan ranh gio message cua ung dung khong?', '["Co","Khong"]', '"Khong"', 1.0),
    ('Q03', 'EXAM-DEMO-001', 3, 'SINGLE_CHOICE', 'ACK cua ung dung nen gui khi nao?', '["Truoc khi luu","Sau khi database commit","Ngay khi accept socket","Sau khi client dong ket noi"]', '"Sau khi database commit"', 1.0),
    ('Q04', 'EXAM-DEMO-001', 4, 'TEXT', 'Mo ta vai tro cua sequence number trong dong bo cau tra loi.', NULL, NULL, 2.0),
    ('Q05', 'EXAM-DEMO-001', 5, 'TEXT', 'Giai thich tinh huong server commit nhung ACK bi mat.', NULL, NULL, 2.0);

INSERT OR IGNORE INTO exam_candidate(exam_id, candidate_id)
VALUES
    ('EXAM-DEMO-001', 'CAND-001'),
    ('EXAM-DEMO-001', 'CAND-002'),
    ('EXAM-DEMO-001', 'CAND-003');

