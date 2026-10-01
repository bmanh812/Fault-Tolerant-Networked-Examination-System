# Kiến trúc phiên bản MVP

```text
Exam Client CLI
  - ProtocolIO
  - Local pending request
  - Reconnect controller
          |
          | TCP persistent connection
          | 4-byte length + UTF-8 JSON
          v
Exam Server
  - Connection handler pool
  - Protocol dispatcher
  - Session service
  - Answer synchronization service
  - Deadline scheduler
          |
          | JDBC transaction
          v
SQLite
```

## Nguyên tắc

- Server là nguồn chân lý cho deadline, session state, committed sequence và câu trả lời.
- ACK chỉ được gửi sau khi database commit thành công.
- Mỗi session chỉ có một connection generation hợp lệ tại một thời điểm.
- Ghi dữ liệu ra cùng một socket phải được tuần tự hóa.
- `ANSWER_UPDATE`, `SUBMIT` và `AUTO_SUBMIT` của cùng session dùng chung per-session lock.

