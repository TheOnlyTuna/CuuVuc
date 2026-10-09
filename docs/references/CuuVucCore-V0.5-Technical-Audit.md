# Cửu Vực Tu Tiên — Technical Audit / V0.5.0

**Ngày rà soát:** 2026-10-09  
**Đầu vào:** `CuuVucCore-0.5.0-source.zip`  
**Phạm vi:** Rà soát mã nguồn, build artifacts *được báo cáo*, test reports và scripts. Không thay đổi source ZIP.

## 1. Quyết định

**CONDITIONAL PASS cho Technology Prototype, NOT READY cho Closed Alpha.**

Các báo cáo kèm source xác nhận `BUILD SUCCESS`, 4 unit tests và 13 integration tests PASS, Paper 26.3 build 166 nạp plugin thành công và cơ sở dữ liệu báo `cuuvuc_database_ready`. Đây là kết quả từ logs/tests đính kèm, **không phải lần chạy lại độc lập**. Không có client E2E, Folia runtime, terrain V3 hoặc thử nghiệm tải nhiều người. Máy rà soát hiện có Java 21; ứng dụng yêu cầu Java 25 nên không thể tái build full trong phiên rà soát này.

Đã biên dịch bằng Java 21 chỉ hai lớp `Domain.java`, `CatalogFile.java` và chạy một harness đọc catalog thành công: 3 resource, 1 recipe, 5 stages. Đây là kiểm tra cục bộ một phần, không thay thế Maven Verify.

## 2. Phạm vi chức năng thực sự

- **Có:** Hồ sơ UUID; XP & stage theo ngưỡng; kho nguyên liệu ảo PostgreSQL; hai mạch quặng tọa độ cố định; craft 1 recipe; NPC buy/sell; chuyển tiền; ledger; request-id idempotency; các integration test cạnh tranh/rollback.
- **Chưa có:** Skyblock và sản xuất; Building/claims/tông môn; PvP; PDC item thật; giao dịch item giữa người chơi/marketplace; GUI; thử thách đột phá; world V3.
- **Không có file build JAR** trong bản source ZIP; có mã nguồn và `validation` báo cáo SHA-256 của JAR cũ. Người triển khai cần build JAR từ source và lưu checksum mới.

## 3. Phát hiện, ưu tiên và thay đổi đề xuất

| Mức | Phát hiện có căn cứ | Source | Thay đổi cần làm |
|---|---|---|---|
| Gate P0 | Không có E2E trên client, chưa kiểm tra permission/protection plugin trong game. | `validation/RESULTS.md`, `README.vi.md` | Chạy test survival 2 người, mine/build/sell/craft/pay, restart DB & server, block cancel, mất kết nối DB. |
| P1 | NPC sell không giới hạn, mỗi lượt mine có thể chuyển thành tiền được phát hành; lệch kiến trúc Economy V0.4. | `GameDatabase.sell`, `CatalogFile`, `catalog.properties` | Thêm mint cap/hợp đồng NPC số lượng hữu hạn, ghi metrics mint/burn, không mở kinh tế này cho public dài hạn. |
| P1 | Shutdown gọi `cancelTasks()` + `database.close()` ngay, không có hàng rào chờ I/O đang chạy. | `CuuVucPlugin.onDisable`, `PlayerTasks` | Chặn giao dịch mới, đợi số in-flight hoàn thành đến timeout, ghi trạng thái pending và đóng pool sau; test shutdown/crash. |
| P1 | Khởi tạo DB v1 dùng `CREATE TABLE IF NOT EXISTS`; chỉ so `max(version)=1`; chưa có migration tăng cấp. | `db/schema.sql`, `GameDatabase.initialize` | Versioned migration, rollback/backup plan, kiểm tra schema drift và compatibility catalog. |
| P2 | `profile` và `history` đều đi qua `operation()` và ghi bản ghi `operations`; tăng kích thước DB vì đọc dữ liệu. | `GameDatabase.profile/history/operation` | Tách query chỉ đọc khỏi operation log; chỉ ghi request-id cho mutation. |
| P2 | Gameplay hiện tự tăng cảnh giới 5 mốc, chưa có Luyện Khí 1–9 hoặc đột phá Trúc Cơ. | `catalog.properties`, `Domain.stage` | Đưa stage vào domain riêng, thêm thử thách đột phá và cây XP theo thiết kế. |
| P2 | Hệ thống kho và craft là số dư ảo; chưa có ItemStack / PDC và chống duplication khi đi qua inventory. | `GameDatabase`, `CatalogFile` | Tích hợp `ItemDefinition`, `ItemInstance`, PDC; quy trình giao nhận an toàn trước khi mở market. |
| P2 | Chỉ có 2 node cố định và cooldown chung mỗi node, chưa có vùng mỏ/mine regeneration thật. | `config.yml`, `MineNodes.java` | Tạo mine regions, quota/cooldown theo loại và chính sách tái tạo an toàn. |
| P2 | Tên tiền đang dùng `linh tệ`, trong GDD gọi `Linh Thạch`; item `linh_thach` lại là nguyên liệu. | `README.vi.md`, `GameDatabase`, `catalog.properties` | Chốt từ vựng: ví currency ID ổn định, vật phẩm/đơn vị kinh tế không trùng nghĩa. |
| P2 | Chưa hỗ trợ runtime Folia; đã dùng scheduler theo ngữ cảnh nhưng không có runtime test. | `README.vi.md`, `PlayerTasks`, `MineNodes` | Giữ `folia-supported` chưa bật; thử plugin dưới Folia với worlds/claims/teleport. |

## 4. Kết quả tệp test đi kèm

- `validation/TEST-vn.cuuvuc.core.DomainTest.xml`: 4 tests; failures=0, errors=0, skipped=0.
- `validation/TEST-vn.cuuvuc.core.GameDatabaseIT.xml`: 13 tests; failures=0, errors=0, skipped=0.
- `validation/server-smoke.log`: Paper 26.3 load/enabled, `cuuvuc_database_ready`, `database-ready=true`, shutdown.
- `validation/verify.log`: BUILD SUCCESS (kèm warning SQLSTATE 22003 trong negative test cố ý gây overflow và warning Maven Shade trùng Manifest).

## 5. Cổng nghiệm thu trước khi phát triển V0.6

1. **Runtime E2E:** client Survival thực tế thử mine/craft/buy/sell/pay/ledger. Chặn Creative, không rơi item vanilla, không trả thưởng ngoài điểm mỏ.
2. **Concurrency E2E:** 2 người đào cùng mạch, nhiều lệnh đồng thời, logout/teleport ngay sau giao dịch, trạng thái giữ nguyên sau restart.
3. **Fault injection:** tắt DB giữa giao dịch; dừng/kết thúc Paper trong khi DB query đang chạy; tránh trả lời thành công giả hoặc mất nguyên liệu/tiền.
4. **Permissions:** người không phải OP không tạo mỏ; thử piston, TNT, WorldGuard/claim và event cancellation.
5. **Production readiness:** nâng cấp DB bằng migration có backup; metrics phát hành/tiêu hao; hạn mức NPC; release pipeline kèm hash của JAR.

## 6. Phạm vi Sprint V0.6 đề xuất

- **V0.5.1 Hardening:** Sửa shutdown, tách read queries, migration setup, kiểm soát NPC issuance; thêm E2E checklist/diagnostics.
- **V0.6 Playability:** Vật phẩm thật bằng PDC, kho và crafting UI đơn giản, một mỏ nhỏ có thể tái tạo, một động phủ cá nhân thử nghiệm có vùng bảo vệ và 1 cây dược liệu. Sau đó bổ sung hợp đồng Builder và Luận Kiếm.
- **V3 World:** Là POC độc lập, không ép chung với plugin runtime đầu tiên; cần heightmap thật và thử dimension height theo `V3-POC.md`.

**Quy tắc đóng gói:** ZIP nguồn nên đi cùng artifact JAR có checksum, báo cáo verify, và hướng dẫn test bằng client; không đánh dấu PASS cho chức năng chưa test trên Minecraft.
