# V0.8 bước 1 — Giao nhận nguyên liệu giữa người chơi

Ngày: **11/10/2026**, Asia/Bangkok. Nhánh: `v0.8-khai-son-lap-phai`, từ V0.7 đã chấp nhận tại `e8d0a1cf1f525c7732f37aab9009f7aaf3e48d11`.

**Trạng thái:** đã triển khai giao nhận kho DB và GUI xác nhận; kiểm thử DB/build/server đạt. Chưa nghiệm thu giao nhận bằng hai Minecraft Client, chưa chốt milestone V0.8 và chưa triển khai tông môn/ngân khố/kho dự án. `main` và CI không thay đổi.

## Artifact và môi trường

JAR `target/CuuVucCore-0.8.0-SNAPSHOT.jar`, SHA-256: `6f5f91087146b4df86beee27c8b86c07fe05051b21fc4b886053016593f2a2bb`.

Paper 26.3 build 166 BETA/API `26.3.build.166-beta`, Java 25.0.4.1, PostgreSQL 18.4, Maven Wrapper 3.9.11. Giữ phiên bản Paper baseline, không tuyên bố là build mới nhất hoặc hỗ trợ Folia. Runtime báo chậm hơn ba build. Test dùng DB mới `cv_v08_tests`; smoke dùng DB mới `cv_v08_smoke`, thư mục `run-v08-smoke`, loopback `127.0.0.1:25580`. Không nâng cấp DB/world V0.7 đã nghiệm thu.

## Kết quả đã chạy

| Kiểm tra | Kết quả |
|---|---|
| Build cuối `clean verify` | **51/51 đạt**, 4 Domain + 47 PostgreSQL integration, không bỏ qua; [tóm tắt](test-summary.txt), [Domain XML](DomainTest.xml), [integration XML](GameDatabaseIT.xml) |
| Chuyển và ledger | Nguồn/đích bảo toàn số lượng; hai bút toán đối ứng, balance_after đúng; không tạo wallet ledger hoặc thay quota; UPDATE/DELETE/TRUNCATE ledger bị từ chối |
| Replay | Tám yêu cầu cùng ID đồng thời chuyển một lần; mở lại connector vẫn trả receipt cũ; khác actor/tham số với cùng ID bị từ chối |
| Cạnh tranh | Hai giao nhận khác ID cùng revision chỉ một thành công; kho không âm, bên thua không có chuyển một phần |
| Validation | Tự chuyển, người nhận chưa có hồ sơ, resource không tồn tại, quantity không hợp lệ, thiếu nguyên liệu và revision cũ sau craft bị từ chối |
| Lỗi giữa transaction | Trigger thật gây lỗi lúc ghi bút toán nhận sau debit/credit; kho, revision, token và receipt cùng rollback |
| Token và số lượng lớn | Thu hồi token resource cũ hai phía; snapshot mới có revision tăng và token mới. Kho nhận 105 đơn vị khi chưa dựng item; snapshot chỉ cấp 36 token, giữ đủ 105 trong DB |
| Migration | Nâng schema v4→v5 giữ kho, ví, token và receipt fixture; initialize lặp đạt. Các ca nâng schema cũ và hợp đồng hiện có tiếp tục đạt |
| Paper startup/restart | Chính JAR trên được load, `cuuvuc_database_ready`; console `/cv` trả `database-ready=true`; [schema v5/history](schema-check.txt) có đủ năm migration |
| Shutdown mềm | Phiên chạy lại nhận `stop`, process exit 0, world I/O hoàn tất; [log](restart-shutdown.log), [marker](shutdown-pending.properties): `drained=true`, `contract-audit-persisted=true` |
| Script chuẩn bị | Parser PowerShell không có lỗi; tên artifact cập nhật theo version snapshot |

Phiên khởi động đầu đạt ready nhưng launcher đóng stdin và console không nhận `stop`; đã dừng cưỡng bức đúng tiến trình server thử trên port 25580, rồi chạy lại với stdin tương tác và tắt mềm. [Log khởi động đầu](initial-startup.log) được giữ lại. Đây không phải bài chứng minh crash consistency: không có người chơi/giao nhận/block audit đang chạy lúc dừng cưỡng bức. Log còn lỗi OSHI đọc Windows Perflib như môi trường V0.7; không sửa registry, plugin vẫn ready.

Các ca kho lớn/token là kiểm thử DB, không phải bằng chứng Inventory Minecraft đầy hoặc PDC giả trên client. Không thêm unit test mới. File bằng chứng được chuẩn hóa LF và khoảng trắng cuối dòng; log gốc còn trong runtime cục bộ bị Git ignore.

## Điều kiện trước khi mở phần tông môn

Chạy **hai Minecraft Client trên chính JAR có hash trên**: xác nhận chuyển qua GUI, double click, người nhận đang bận, offline/rejoin, Inventory đầy, thoát sau commit và kiểm tra đồng bộ khi đăng nhập lại. Chứng minh icon GUI, PDC sao chép/token cũ không tạo quyền sở hữu; thử craft/sell/chuyển cạnh tranh rồi đối soát kho và ledger. GUI chuyển số lượng kho DB, không có đường nạp tài sản từ ItemStack tùy ý.

Chưa chạy backup/restore giao nhận V0.8 hoặc kill giữa giao dịch và resync Inventory; cần bổ sung khi nghiệm thu bước giao nhận. Giới hạn block audit/world-save/template và khoảng trống binary V0.7 tiếp tục theo [backlog](../../BACKLOG.md). Kết quả này không đóng các mục đó.

Chạy lại: theo phần kiểm thử trong [README](../../README.md), dùng PostgreSQL test riêng rồi `clean verify`. Nâng cấp V0.7 cần backup DB/world trước V005; hạ về JAR V0.7 phải restore backup trước migration vào đích mới. Không sửa checksum hoặc dùng DB nghiệm thu làm DB test.
