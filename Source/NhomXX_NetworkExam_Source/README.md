# Fault-Tolerant Networked Examination System

Khung project cho bài tập lớn Lập trình mạng, Chủ đề 3 - Ứng dụng mạng. Hệ thống hướng tới đồng bộ câu trả lời có ACK/sequence number, phục hồi phiên sau mất kết nối và auto-submit theo thời gian do server quản lý.

## Trạng thái hiện tại

- Maven project Java 17.
- TCP server dùng worker thread pool.
- TCP client kiểm tra kết nối bằng `PING/PONG`.
- Framing 4-byte big-endian length + UTF-8 JSON.
- SQLite schema và dữ liệu mẫu.
- Unit test cho framing và database schema.
- Các handler nghiệp vụ login/join/answer/resume/submit chưa được triển khai.

## Công nghệ

- Java 17
- Maven
- TCP Socket
- Jackson JSON
- JDBC + SQLite
- JUnit 5
- SLF4J + Logback

## Cấu trúc

```text
config/                  Cấu hình server, protocol, heartbeat và database
data/                    Schema, seed data và file SQLite
docs/                    Architecture, protocol và test plan
results/                 Raw/processed CSV cho experiment
scripts/                 Lệnh hỗ trợ chạy server/client
src/main/java/           Mã nguồn
src/test/java/           Test
statics/                 Hình architecture/protocol/results về sau
```

## Database

Database mẫu nằm tại `data/network_exam.db` và được tạo từ:

- `data/schema.sql`
- `data/sample-data.sql`

Các nhóm bảng chính:

- Danh mục: `candidate`, `exam`, `question`, `exam_candidate`.
- Runtime: `exam_session`, `answer_current`, `submission`.
- Reliability: `processed_request`, `answer_event`.
- Audit: `session_event`.

Ba tài khoản demo là `candidate01`, `candidate02`, `candidate03`. Trường password hiện chỉ là placeholder và phải được thay bằng password hash thật khi triển khai login.

## Build và test

Yêu cầu `java -version` phải là Java 17 trở lên. Trên máy hiện tại, Java mặc định đang là Java 8 nhưng có JDK 25 tại `C:\Program Files\Java\jdk-25`. Có thể chọn JDK tương thích cho terminal hiện tại bằng:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

Project vẫn được compile với `--release 17`, do đó bytecode đầu ra tương thích Java 17.

```powershell
mvn clean test
```

## Chạy server

```powershell
mvn compile exec:java '-Dexec.mainClass=vn.edu.exam.server.ServerMain'
```

## Chạy client kiểm tra

Mở terminal khác:

```powershell
mvn compile exec:java '-Dexec.mainClass=vn.edu.exam.client.ClientMain'
```

Client sẽ gửi `PING` và nhận `PONG`. Đây là gate đầu tiên trước khi triển khai LOGIN/JOIN.

## Việc cần làm tiếp theo

1. Điền số nhóm và thông tin thành viên.
2. Thay password placeholder bằng hash demo thật.
3. Implement `LOGIN_REQ/LOGIN_RESP`.
4. Implement `JOIN_REQ/JOIN_RESP`.
5. Implement transaction `ANSWER_UPDATE/ANSWER_ACK`.
6. Thêm request deduplication và sequence validation.
7. Implement reconnect bằng `RESUME_REQ/RESUME_STATE`.
8. Implement deadline scheduler và auto-submit.
9. Hoàn thiện integration test và experiment CSV.

## Đóng gói nộp bài

Thay `NhomXX` bằng số nhóm chính thức. Cấu trúc cuối:

```text
NhomXX_NetworkExam/
├── Source/
│   └── NhomXX_NetworkExam_Source/
└── Report/
    └── NhomXX_NetworkExam_Report.docx
```

