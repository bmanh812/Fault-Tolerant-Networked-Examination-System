MERGE INTO candidate (candidate_id, full_name) KEY(candidate_id)
VALUES ('CAND-001', 'Thi sinh 01');

MERGE INTO exam (exam_id, title, start_time, end_time, status) KEY(exam_id)
VALUES ('EXAM-001', 'Network Programming MCQ Demo', 1790800000000, 1790803600000, 'ACTIVE');

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

