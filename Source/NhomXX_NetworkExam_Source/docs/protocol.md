# Protocol v1 (frozen)

Tài liệu này là contract duy nhất cho giao tiếp Client/Server của hệ thống thi trắc nghiệm. Protocol v1 chỉ hỗ trợ MCQ một lựa chọn.

## 1. Framing

```text
[4-byte signed big-endian body length][UTF-8 JSON body]
```

- `0 < body length <= 65536` byte.
- TCP là byte stream: bên nhận phải đọc đủ header và body, hỗ trợ partial read và nhiều frame liên tiếp.
- Một frame chỉ chứa đúng một JSON message.

## 2. Envelope chung

Mọi message có đúng bốn field bắt buộc; không có field tùy chọn ở envelope.

| Field | Kiểu | Quy tắc |
|---|---|---|
| `type` | string enum | Một giá trị trong message catalog |
| `protocolVersion` | integer | Bắt buộc bằng `1` |
| `requestId` | UUID string | ID tương quan request/response hoặc ID của server-push |
| `payload` | object | Tuân theo schema của `type`; field lạ bị từ chối |

```json
{
  "type": "ANSWER_UPDATE",
  "protocolVersion": 1,
  "requestId": "4e0cd3ae-b734-4d73-85de-5204b2623414",
  "payload": {}
}
```

Không dùng `sentAt`, `seq` hoặc `lastAckSeq`. Thời gian nghiệp vụ nằm trong payload cụ thể.

### Tương quan request/response

| Client request | Server response thành công | Response lỗi |
|---|---|---|
| `JOIN_EXAM` | `EXAM_STATE` | `ERROR` |
| `ANSWER_UPDATE` | `ANSWER_ACK` | `ERROR` |
| `HEARTBEAT` | `HEARTBEAT_ACK` | `ERROR` |
| `RECONNECT` | `RESYNC_STATE` | `ERROR` |
| `SUBMIT` | `SUBMIT_ACK` | `ERROR` |

Response trực tiếp, kể cả `ERROR`, phải giữ nguyên `requestId` của request. Với request đã decode được nhưng không hợp lệ, Server trả `ERROR` cùng `requestId`. Nếu envelope/frame không thể decode đáng tin cậy, Server có thể đóng kết nối vì không có `requestId` hợp lệ để tương quan.

`AUTO_SUBMIT` là server-push: Server tạo một UUID mới làm `requestId`; Client xử lý idempotent theo `sessionId` và trạng thái, không gửi ACK cho message này. `ERROR` không được dùng làm server-push trong v1.

## 3. Payload contract

Tất cả field dưới đây là bắt buộc, trừ field được ghi rõ là tùy chọn. Timestamp là Unix epoch milliseconds và phải lớn hơn `0`. String ID phải khác rỗng.

### Client -> Server

| Message | Field bắt buộc | Field tùy chọn |
|---|---|---|
| `JOIN_EXAM` | `candidateId: string`, `examId: string` | Không |
| `ANSWER_UPDATE` | `sessionId: string`, `questionId: string`, `version: integer >= 1`, `selectedOptionId: string` | Không |
| `HEARTBEAT` | `sessionId: string`, `clientTime: timestamp` | Không |
| `RECONNECT` | `sessionId: string`, `candidateId: string`, `knownVersions: object<string, integer >= 1>` | Không |
| `SUBMIT` | `sessionId: string` | Không |

`knownVersions` có thể là object rỗng. Mỗi entry biểu diễn version cao nhất Client biết của một câu hỏi.

### Server -> Client

| Message | Field bắt buộc | Field tùy chọn |
|---|---|---|
| `EXAM_STATE` | `sessionId`, `examId`, `title`, `status`, `deadline`, `serverTime`, `questions`, `answers` | Không |
| `ANSWER_ACK` | `sessionId`, `questionId`, `confirmedVersion`, `selectedOptionId`, `serverTime` | Không |
| `HEARTBEAT_ACK` | `serverTime`, `deadline` | Không |
| `RESYNC_STATE` | `sessionId`, `status`, `deadline`, `serverTime`, `answers` | Không |
| `SUBMIT_ACK` | `sessionId`, `status`, `submittedAt` | Không |
| `AUTO_SUBMIT` | `sessionId`, `status`, `submittedAt` | Không |
| `ERROR` | `code`, `message`; thêm `currentAnswer` khi `code=VERSION_CONFLICT` | `currentAnswer` với các code khác phải được bỏ hoặc là `null` |

Các object lồng nhau:

- `QuestionView`: `questionId: string`, `position: integer >= 1`, `content: string`, `options: OptionView[]` (không rỗng).
- `OptionView`: `optionId: string`, `label: string`, `content: string`.
- `AnswerView`: `questionId: string`, `version: integer >= 1`, `selectedOptionId: string`.
- `answers`: object có key là `questionId`, value là `AnswerView`; key phải trùng `AnswerView.questionId`. Object có thể rỗng.
- `EXAM_STATE.status` luôn là `ACTIVE`.
- `RESYNC_STATE.status` là `ACTIVE`, `SUBMITTED` hoặc `AUTO_SUBMITTED`.
- `SUBMIT_ACK.status` luôn là `SUBMITTED`; `AUTO_SUBMIT.status` luôn là `AUTO_SUBMITTED`.

`QuestionView` và `OptionView` tuyệt đối không chứa `correct`, `isCorrect`, `correctAnswer` hoặc `correctOptionId`. Đáp án đúng chỉ tồn tại phía Server.

## 4. JSON examples

### JOIN_EXAM

```json
{"type":"JOIN_EXAM","protocolVersion":1,"requestId":"10000000-0000-0000-0000-000000000001","payload":{"candidateId":"CAND-001","examId":"EXAM-001"}}
```

### EXAM_STATE

```json
{
  "type": "EXAM_STATE",
  "protocolVersion": 1,
  "requestId": "10000000-0000-0000-0000-000000000001",
  "payload": {
    "sessionId": "SESSION-001",
    "examId": "EXAM-001",
    "title": "Network Programming Midterm",
    "status": "ACTIVE",
    "deadline": 1790803600000,
    "serverTime": 1790800000000,
    "questions": [{
      "questionId": "Q1",
      "position": 1,
      "content": "TCP provides which service?",
      "options": [
        {"optionId":"Q1-A","label":"A","content":"Reliable byte stream"},
        {"optionId":"Q1-B","label":"B","content":"Unreliable datagrams"}
      ]
    }],
    "answers": {}
  }
}
```

### ANSWER_UPDATE

```json
{"type":"ANSWER_UPDATE","protocolVersion":1,"requestId":"20000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001","questionId":"Q1","version":1,"selectedOptionId":"Q1-A"}}
```

### ANSWER_ACK

```json
{"type":"ANSWER_ACK","protocolVersion":1,"requestId":"20000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001","questionId":"Q1","confirmedVersion":1,"selectedOptionId":"Q1-A","serverTime":1790800010000}}
```

### HEARTBEAT

```json
{"type":"HEARTBEAT","protocolVersion":1,"requestId":"30000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001","clientTime":1790800020000}}
```

### HEARTBEAT_ACK

```json
{"type":"HEARTBEAT_ACK","protocolVersion":1,"requestId":"30000000-0000-0000-0000-000000000001","payload":{"serverTime":1790800020050,"deadline":1790803600000}}
```

### RECONNECT

```json
{"type":"RECONNECT","protocolVersion":1,"requestId":"40000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001","candidateId":"CAND-001","knownVersions":{"Q1":1,"Q2":3}}}
```

### RESYNC_STATE

```json
{"type":"RESYNC_STATE","protocolVersion":1,"requestId":"40000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001","status":"ACTIVE","deadline":1790803600000,"serverTime":1790800100000,"answers":{"Q1":{"questionId":"Q1","version":1,"selectedOptionId":"Q1-A"}}}}
```

### SUBMIT

```json
{"type":"SUBMIT","protocolVersion":1,"requestId":"50000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001"}}
```

### SUBMIT_ACK

```json
{"type":"SUBMIT_ACK","protocolVersion":1,"requestId":"50000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001","status":"SUBMITTED","submittedAt":1790801000000}}
```

### AUTO_SUBMIT

```json
{"type":"AUTO_SUBMIT","protocolVersion":1,"requestId":"60000000-0000-0000-0000-000000000001","payload":{"sessionId":"SESSION-001","status":"AUTO_SUBMITTED","submittedAt":1790803600000}}
```

### ERROR

```json
{
  "type": "ERROR",
  "protocolVersion": 1,
  "requestId": "20000000-0000-0000-0000-000000000001",
  "payload": {
    "code": "VERSION_CONFLICT",
    "message": "Answer version conflicts with the authoritative state",
    "currentAnswer": {"questionId":"Q1","version":2,"selectedOptionId":"Q1-B"}
  }
}
```

## 5. Answer version và retry invariant

- Version được quản lý riêng theo từng `(sessionId, questionId)`, không có sequence chung cho session.
- Lựa chọn đầu tiên của mỗi câu dùng version `1`; mỗi thay đổi lựa chọn tiếp theo tăng đúng một version ở Client.
- Retry của cùng một thay đổi phải giữ nguyên `requestId`, `version` và `selectedOptionId`.
- `incomingVersion > currentVersion`: Server lưu snapshot rồi trả `ANSWER_ACK`.
- Cùng version và cùng option: duplicate idempotent; Server trả lại `ANSWER_ACK` của state hiện tại.
- Cùng version nhưng khác option, hoặc version thấp hơn state hiện tại: Server không overwrite và trả `ERROR/VERSION_CONFLICT` kèm `currentAnswer`.
- Socket `write()` thành công không đồng nghĩa đáp án đã được lưu. Client chỉ coi an toàn sau `ANSWER_ACK` phù hợp hoặc `RESYNC_STATE` authoritative.

## 6. Reconnect reconciliation

- `RESYNC_STATE` là state authoritative của Server.
- Nếu Server có version bằng hoặc lớn hơn pending local version, Client coi snapshot tương ứng đã an toàn.
- Nếu Server còn version thấp hơn, Client có thể retry pending snapshot mới nhất khi session còn `ACTIVE` và chưa qua deadline; retry giữ nguyên ba giá trị đã nêu ở trên.
- Reconnect không reset deadline.

## 7. Error codes

| Code | Ý nghĩa |
|---|---|
| `INVALID_REQUEST` | Envelope/payload hoặc quan hệ message không hợp lệ |
| `INVALID_SESSION` | Session không tồn tại, không khớp hoặc không còn hợp lệ |
| `CANDIDATE_NOT_ALLOWED` | Candidate không được phép vào kỳ thi/session |
| `EXAM_NOT_STARTED` | Kỳ thi chưa đến thời gian bắt đầu |
| `EXAM_ENDED` | Đã quá deadline/kỳ thi đã kết thúc |
| `VERSION_CONFLICT` | Version/option xung đột state authoritative |
| `ALREADY_SUBMITTED` | Session đã nộp trước đó |
| `SERVER_BUSY` | Server tạm thời không thể nhận thêm công việc |
| `INTERNAL_ERROR` | Lỗi Server không nên lộ chi tiết nội bộ |
| `FRAME_TOO_LARGE` | Frame vượt giới hạn cấu hình |
| `UNSUPPORTED_VERSION` | `protocolVersion` không được hỗ trợ |
| `QUESTION_NOT_FOUND` | Question không thuộc bài thi/session |
| `INVALID_OPTION` | Option không thuộc question |

`currentAnswer` bắt buộc có giá trị với `VERSION_CONFLICT`; với các code khác field này phải được bỏ khỏi JSON hoặc đặt `null`.
