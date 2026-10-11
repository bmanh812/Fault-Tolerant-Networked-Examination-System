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

## Submission và deadline hiện tại

- `ANSWER_UPDATE`, manual `SUBMIT` và `AUTO_SUBMIT` khóa cùng hàng `exam_session` trong H2 transaction. Deadline được kiểm tra sau khi lấy khóa; chỉ một thao tác có thể thay đổi state tại một thời điểm.
- Manual submit cập nhật `exam_session` và thêm đúng một hàng `submission` trong cùng transaction. Request lặp trả `SUBMIT_ACK` với `submittedAt` ban đầu. Nếu đã hết giờ, Server ghi auto-submit và trả lỗi `EXAM_ENDED` cho `SUBMIT`.
- Khi Server khởi động, nó quét các session `ACTIVE` đã quá hạn. Sau đó quét mỗi giây; session vừa JOIN còn được hẹn chạy tại deadline. Cả hai đường đều dùng cùng thao tác auto-submit idempotent.
- Sau commit auto-submit, Server gửi `AUTO_SUBMIT` tới các connection đang gắn với session. Việc gửi thông báo qua hàng đợi riêng để một socket chậm không chặn timer và các session khác. Client offline lấy trạng thái cuối qua luồng resync khi được triển khai.

## Module boundaries

- `common.protocol`: framing, envelope, DTO và error code.
- `client`: socket, local state, ACK tracking và reconnect state machine.
- `server`: accept loop, message dispatch và error isolation.
- `server.service`: nghiệp vụ session/answer/submit.
- `server.persistence`: H2 connection, schema và repository implementation.
- `server.timer`: deadline scheduler.

