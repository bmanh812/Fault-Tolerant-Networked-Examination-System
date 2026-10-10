# Architecture

## Scope

Hệ thống chỉ xử lý bài thi MCQ một lựa chọn. Server là nguồn sự thật cho session, answer version, deadline và trạng thái submit.

## Components

```text
Candidate Client A ---- TCP length-prefix JSON ----+
Candidate Client B ---- TCP length-prefix JSON ----+---- Exam Server
Candidate Client N ---- TCP length-prefix JSON ----+       |
                                                          +-- ClientHandler
                                                          +-- SessionService
                                                          +-- AnswerService
                                                          +-- DeadlineScheduler
                                                          +-- H2 Repository
```

## Client responsibilities

- Hiển thị MCQ, deadline và trạng thái chưa xác nhận/đã lưu.
- Duy trì `localVersion` và `confirmedVersion` theo từng câu hỏi.
- Chỉ đánh dấu đã lưu sau ACK hoặc RESYNC xác nhận.
- Giữ pending snapshot cho tới khi Server xác nhận.
- Reconnect và resync trước khi gửi tiếp dữ liệu.

## Server responsibilities

- Dùng bounded executor để xử lý nhiều connection.
- Cô lập dữ liệu theo `sessionId`.
- Persist answer mới hơn trước khi gửi ACK.
- Không overwrite answer bởi duplicate hoặc stale version.
- Dùng transaction hoặc per-session lock cho update/submit.
- Quản lý deadline và auto-submit độc lập với kết nối Client.

## Module boundaries

- `common.protocol`: framing, envelope, DTO và error code.
- `client`: socket, local state, ACK tracking và reconnect state machine.
- `server`: accept loop, message dispatch và error isolation.
- `server.service`: nghiệp vụ session/answer/submit.
- `server.persistence`: H2 connection, schema và repository implementation.
- `server.timer`: deadline scheduler.

