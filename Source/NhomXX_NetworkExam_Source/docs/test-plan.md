# Test Plan

| ID | Scenario | Expected result |
|---|---|---|
| T01 | Join exam hợp lệ | Nhận đúng MCQ, answer state và deadline |
| T02 | Đổi đáp án cùng câu nhiều lần | Server giữ version mới nhất và ACK đúng state |
| T03 | Duplicate ANSWER_UPDATE | Idempotent, không tạo state sai |
| T04 | Stale version đến sau version mới | Không overwrite answer mới hơn |
| T05 | Server lưu answer nhưng ACK bị mất | RESYNC xác nhận answer đã tồn tại |
| T06 | Connection đứt trước khi persist | RESYNC trả version cũ, Client resend pending snapshot |
| T07 | Disconnect 15-30 giây | Timer vẫn chạy, deadline không reset |
| T08 | Hết giờ khi Client offline | Server auto-submit, reconnect nhận final state |
| T09 | SUBMIT lặp do timeout | Chỉ có một submission |
| T10 | 10 Client đồng thời | Không trộn session/answer, Server không crash |
| T11 | Malformed/invalid request | Chỉ request/connection liên quan lỗi |
| T12 | Clean-machine run | Build, run và test không cần IDE |

## Experiment outputs

- Raw CSV/log đặt tại `results/raw`.
- Kết quả xử lý đặt tại `results/processed`.
- Không ghi số liệu giả vào repository.
- Metric chính: latency median/p95, throughput, error rate, reconnect time, resync time và `lost_confirmed_answers`.

