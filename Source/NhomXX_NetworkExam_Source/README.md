# Networked MCQ Examination System

Bộ khung mới cho đề tài hệ thống thi trắc nghiệm qua mạng có autosave và phục hồi phiên sau khi mất kết nối.

## Phạm vi đã khóa

- Chỉ hỗ trợ câu hỏi trắc nghiệm một lựa chọn.
- TCP connection dài hạn với framing `4-byte big-endian length + UTF-8 JSON`.
- Mỗi câu trả lời có `version` riêng và chỉ được coi là đã lưu sau `ANSWER_ACK` hoặc `RESYNC_STATE`.
- Server giữ authoritative state, deadline và thực hiện manual/auto-submit.
- Reconnect lấy lại state từ Server; Client không tự suy đoán trạng thái đã lưu.
- Nhiều Client được phục vụ bằng bounded thread pool và state được cô lập theo session.
- Heartbeat, dashboard và phục hồi sau Server restart là P1, không chặn P0.

## Trạng thái hiện tại

Protocol v1, framing, state model và database schema đã có. Server xử lý `JOIN_EXAM`, `ANSWER_UPDATE` và `SUBMIT`: tạo hoặc lấy lại session đang hoạt động, trả đề MCQ, kiểm tra question/option/version, commit đáp án trước `ANSWER_ACK`, và ghi manual submission trước `SUBMIT_ACK`. Nộp lặp trả lại cùng trạng thái và thời điểm nộp. Server tự nộp session quá hạn bằng lịch hẹn và quét định kỳ, kể cả khi Client offline; Client đang kết nối nhận `AUTO_SUBMIT`. Client Swing hiện có thể tham gia, hiển thị đề, khôi phục đáp án đã lưu và autosave lựa chọn qua `ANSWER_UPDATE/ANSWER_ACK`. `RECONNECT/RESYNC_STATE`, timer authoritative và submit từ giao diện vẫn cần triển khai.

## Công nghệ

- Java 17
- Maven
- Java TCP Socket
- Jackson JSON
- H2 file mode
- SLF4J + Logback
- JUnit 5

## Cấu trúc

```text
config/                  Cấu hình Server, Client, protocol và database
data/                    H2 schema và dữ liệu demo
docs/                    Architecture, protocol và test plan
results/                 Raw/processed experiment outputs
scripts/                 Lệnh chạy trên PowerShell
src/main/java/
  vn/edu/exam/common/    Config và protocol dùng chung
  vn/edu/exam/client/    Client state, connection, local answer state và Swing UI
  vn/edu/exam/server/    Server, handler, session, answer, timer, repository
src/test/java/           Unit test cho framing, protocol, version và schema
```

## Build và test

Yêu cầu JDK 17 trở lên.

```powershell
mvn.cmd test
```

Khởi tạo database:

```powershell
mvn.cmd exec:java "-Dexec.mainClass=vn.edu.exam.server.persistence.DatabaseInitMain"
```

Lệnh khởi tạo tạo ba thí sinh `CAND-001` đến `CAND-003` và kỳ thi `EXAM-001` có ba câu MCQ. Thời gian bắt đầu/kết thúc của dữ liệu demo được tính lúc khởi tạo; hãy chạy lại lệnh này nếu kỳ thi demo đã hết giờ.

Chạy Server:

```powershell
mvn.cmd exec:java "-Dexec.mainClass=vn.edu.exam.server.ServerMain"
```

Chạy Client:

```powershell
mvn.cmd exec:java "-Dexec.mainClass=vn.edu.exam.client.ClientMain"
```

Cửa sổ Swing sẽ mở với host/port lấy từ `config/application.properties`. Nhập `CAND-001`, `EXAM-001`, chọn đáp án và quan sát trạng thái `Đang lưu...`/`Đã lưu`. Vùng thời gian mới là placeholder và nút `Nộp bài` được khóa cho tới các phase tương ứng.

## Thứ tự triển khai P0

1. Freeze protocol và state/version rules.
2. JOIN_EXAM và EXAM_STATE. Đã triển khai ở Server và Client.
3. ANSWER_UPDATE, persistence rồi ANSWER_ACK. Đã triển khai ở Server.
4. Duplicate/stale version handling. Đã triển khai ở Server.
5. SUBMIT, Server deadline và AUTO_SUBMIT. Đã triển khai ở Server.
6. RECONNECT và RESYNC_STATE.
7. Integration tests T01-T12 và concurrent-client runner.
8. Experiment, raw CSV/log, report và clean-machine test.

