MERGE INTO candidate (candidate_id, full_name) KEY(candidate_id)
VALUES ('CAND-001', 'Thi sinh 01');
MERGE INTO candidate (candidate_id, full_name) KEY(candidate_id)
VALUES ('CAND-002', 'Thi sinh 02');
MERGE INTO candidate (candidate_id, full_name) KEY(candidate_id)
VALUES ('CAND-003', 'Thi sinh 03');

MERGE INTO exam (exam_id, title, start_time, end_time, status) KEY(exam_id)
VALUES (
    'EXAM-001',
    'Network Programming MCQ Demo',
    CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP()) * 1000 AS BIGINT) - 60000,
    CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP()) * 1000 AS BIGINT) + 3600000,
    'ACTIVE'
);

MERGE INTO question (question_id, exam_id, position_no, content) KEY(question_id)
VALUES ('Q01', 'EXAM-001', 1, 'TCP cung cap loai dich vu nao?');

MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q01-A', 'Q01', 'A', 'Byte stream', TRUE);
MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q01-B', 'Q01', 'B', 'Message datagram', FALSE);
MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q01-C', 'Q01', 'C', 'Shared memory', FALSE);
MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q01-D', 'Q01', 'D', 'File transfer only', FALSE);

MERGE INTO question (question_id, exam_id, position_no, content) KEY(question_id)
VALUES ('Q02', 'EXAM-001', 2, 'TCP co giu nguyen ranh gio message cua ung dung khong?');

MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q02-A', 'Q02', 'A', 'Co', FALSE);
MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q02-B', 'Q02', 'B', 'Khong', TRUE);

MERGE INTO question (question_id, exam_id, position_no, content) KEY(question_id)
VALUES ('Q03', 'EXAM-001', 3, 'Khi nao Server duoc gui ANSWER_ACK?');

MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q03-A', 'Q03', 'A', 'Ngay sau khi nhan frame', FALSE);
MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q03-B', 'Q03', 'B', 'Sau khi ghi database thanh cong', TRUE);
MERGE INTO question_option (option_id, question_id, option_label, content, is_correct) KEY(option_id)
VALUES ('Q03-C', 'Q03', 'C', 'Khi Client dong ket noi', FALSE);

