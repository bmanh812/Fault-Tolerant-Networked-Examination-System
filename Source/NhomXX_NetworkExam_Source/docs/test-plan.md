# Test plan tối thiểu

1. Frame bị chia thành nhiều lần đọc.
2. Nhiều frame nằm liền nhau trên TCP stream.
3. Frame âm, rỗng hoặc vượt giới hạn.
4. Answer được commit trước khi ACK.
5. Retry cùng request không tạo commit trùng.
6. Cùng sequence nhưng payload khác bị từ chối.
7. Sequence nhảy cóc trả `OUT_OF_SYNC`.
8. Mất socket sau commit nhưng trước ACK rồi resume.
9. Hai connection cùng resume một session.
10. Race giữa answer update và auto-submit.
11. Database error không sinh ACK giả.
12. Tối thiểu 10 client không trộn dữ liệu.

