# Protocol v1

## Framing

```text
[4-byte signed big-endian body length][UTF-8 JSON body]
```

Quy tắc:

- `0 < body length <= 65536`.
- Phải đọc đủ header và body vì TCP là byte stream.
- Hỗ trợ partial read và nhiều frame liên tiếp.

## Envelope

```json
{
  "type": "ANSWER_UPDATE",
  "protocolVersion": 1,
  "requestId": "uuid",
  "sentAt": 1790800000000,
  "payload": {}
}
```

Response sử dụng lại `requestId` của request khi có quan hệ request-response.

## Message catalog

| Message | Direction | Payload chính |
|---|---|---|
| JOIN_EXAM | C to S | candidateId, examId |
| EXAM_STATE | S to C | sessionId, questions, answers, deadline, serverTime, status |
| ANSWER_UPDATE | C to S | sessionId, questionId, version, selectedOptionId |
| ANSWER_ACK | S to C | sessionId, questionId, confirmedVersion, selectedOptionId, serverTime |
| RECONNECT | C to S | sessionId, candidateId, knownVersions |
| RESYNC_STATE | S to C | sessionId, answers, deadline, status, serverTime |
| SUBMIT | C to S | sessionId |
| SUBMIT_ACK | S to C | sessionId, status, submittedAt |
| AUTO_SUBMIT | S to C | sessionId, status, submittedAt |
| HEARTBEAT | C to S | sessionId, clientTime; P1 |
| HEARTBEAT_ACK | S to C | serverTime, deadline; P1 |
| ERROR | S to C | code, message, optional currentAnswer |

## Answer version invariant

- Version được quản lý theo từng `(sessionId, questionId)`.
- Lựa chọn đầu tiên dùng version `1`.
- `incomingVersion > currentVersion`: persist snapshot rồi ACK.
- Cùng version và cùng option: duplicate an toàn, trả lại ACK/current state.
- Cùng version nhưng khác option hoặc version thấp hơn: không overwrite, trả `VERSION_STALE` cùng state hiện tại.
- Socket `write()` thành công không đồng nghĩa answer đã được lưu.

## Example ANSWER_UPDATE

```json
{
  "type": "ANSWER_UPDATE",
  "protocolVersion": 1,
  "requestId": "4e0cd3ae-b734-4d73-85de-5204b2623414",
  "sentAt": 1790800000000,
  "payload": {
    "sessionId": "session-001",
    "questionId": "Q12",
    "version": 5,
    "selectedOptionId": "Q12-C"
  }
}
```

## Reconnect reconciliation

- RESYNC_STATE là authoritative state.
- Nếu Server có version bằng hoặc lớn hơn pending local version, Client coi snapshot đó đã an toàn.
- Nếu Server còn version cũ, Client có thể gửi lại pending snapshot mới nhất khi session còn ACTIVE và chưa qua deadline.
- Reconnect không reset deadline.

