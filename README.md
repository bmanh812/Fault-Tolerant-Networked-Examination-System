# NhomXX Networked MCQ Examination System

Project môn Lập trình mạng: hệ thống thi trắc nghiệm Client-Server có autosave và phục hồi phiên sau khi mất kết nối.

- `Source/NhomXX_NetworkExam_Source`: bộ khung Java 17/Maven, TCP protocol, H2 database, tài liệu kỹ thuật và test.
- `Report`: nơi đặt báo cáo cuối cùng theo quy định học phần.

Phạm vi P0 được khóa ở MCQ single-choice, versioned `ANSWER_UPDATE/ACK`, reconnect/resync, Server-side deadline và manual/auto-submit.


