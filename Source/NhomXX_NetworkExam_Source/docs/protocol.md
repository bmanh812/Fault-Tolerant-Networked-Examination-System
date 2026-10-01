# Protocol v1

## Framing

Mỗi message gồm:

```text
[4-byte signed big-endian body length][UTF-8 JSON body]
```

Body không vượt quá 65536 byte. TCP là byte stream nên implementation phải hỗ trợ partial read và nhiều frame trong cùng một lần read.

## Envelope

```json
{
  "type": "ANSWER_UPDATE",
  "version": 1,
  "requestId": "uuid",
  "sessionId": "uuid-or-null",
  "sentAt": 1790800000000,
  "payload": {}
}
```

## Message catalog

- `PING`, `PONG`: kiểm tra kết nối trong giai đoạn dựng skeleton.
- `LOGIN_REQ`, `LOGIN_RESP`
- `JOIN_REQ`, `JOIN_RESP`
- `ANSWER_UPDATE`, `ANSWER_ACK`
- `RESUME_REQ`, `RESUME_STATE`
- `HEARTBEAT`, `HEARTBEAT_ACK`
- `SUBMIT_REQ`, `SUBMIT_ACK`
- `ERROR`

## Sequence và idempotency

- `lastCommittedSeq` bắt đầu từ 0.
- Request mới hợp lệ khi `seq = lastCommittedSeq + 1`.
- Cùng `requestId` và cùng payload trả lại response đã lưu.
- Cùng sequence nhưng payload khác trả `CONFLICT_DUPLICATE`.
- Sequence nhảy cóc trả `OUT_OF_SYNC` và `expectedSeq`.
- Client MVP chỉ có một answer update đang chờ ACK.

## Error codes

- `INVALID_REQUEST`
- `FRAME_TOO_LARGE`
- `UNSUPPORTED_VERSION`
- `AUTH_FAILED`
- `SESSION_NOT_FOUND`
- `OUT_OF_SYNC`
- `CONFLICT_DUPLICATE`
- `EXAM_CLOSED`
- `DB_ERROR`
- `SERVER_BUSY`
- `INTERNAL_ERROR`

