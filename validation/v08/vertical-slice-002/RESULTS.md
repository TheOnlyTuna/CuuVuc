# Vertical Slice 002 — Con Đường Tu Tiên

**IMPLEMENTED / CLIENT VALIDATION PARTIAL — dừng chờ người dùng kiểm tra tiến độ ngày 15/10/2026. Chưa Playable, chưa nghiệm thu gameplay hoàn chỉnh.** HEAD trước triển khai xác minh là `a30f53ecab9936a649bf8051c32c0054b4a28460`, nhánh `v0.8-khai-son-lap-phai`. Commit chứa báo cáo là checkpoint mã/bằng chứng hiện tại; SHA được xác nhận sau push khi bàn giao.

## Mã đã triển khai

- Tu vi tích lũy tách khỏi cảnh giới bền vững. Phàm Nhân → Luyện Khí 1–9 bằng xác nhận chủ động, đủ XP và nguyên liệu; XP không bị trừ hoặc tự nâng cảnh giới.
- `/cv` có Tu Luyện, `/cv tuluyen` mở trực tiếp. GUI đọc tiến trình, kho, điều kiện/thiếu tài nguyên và kết quả. Tu luyện/thử thách yêu cầu quyền chơi, Survival và động phủ READY của chính mình. Một cửa sổ chỉ gửi một ý định tiêu hao; phải bấm Tiếp tục/Làm mới trước ý định mới.
- Trúc Cơ dùng một thử thách ghi nhớ **Trận Tâm Ma**, không mở framework combat/dungeon. Chuỗi ngẫu nhiên 5 phù ấn, xem 15 giây ở hàng trên, trả lời đúng thứ tự trong tổng 180 giây. Phí 2 Linh Thạch + 2 Huyền Thiết, điều kiện Luyện Khí 9/500 XP. Sai/hết giờ/thoát/rời đảo/đổi khỏi Survival/restart kết thúc lượt, không hoàn phí; cooldown 15 giây. Kết quả và tiến trình cùng transaction/receipt, không nhận thưởng lần hai.
- Mỗi tầng đã đạt tăng XP khai thác; Trúc Cơ mở `ket_tinh_truc_co`: 5 thô → 2 Linh Thạch, công thức cũ giữ 5 → 1. Không tạo đường mint/burn Linh Tệ.
- Prison nhận cú chạm cuốc qua BlockDamageEvent thật, dùng chung harvest với BlockBreakEvent, cooldown/transaction/receipt cũ. Không phát sinh PLACE/BREAK/audit để hợp thức hóa việc tu luyện.

Cách thử khi được tiếp tục: khởi động runtime đã sao lưu `run-vs002-client03` với DB `cv_vs002_client03`, kết nối `127.0.0.1:25603`. Người mới dùng `/cv` → Động phủ lấy cuốc → Prison khai thác → Kết tinh → về động phủ và Tu Luyện. Sau mỗi kết quả bấm Tiếp tục/Làm mới. Cân bằng đầy đủ nằm trong `src/main/resources/cultivation.properties`; chính sách có chữ ký riêng, không sửa trực tiếp DB để né kiểm tra.

## DB và dữ liệu cũ

V012 thêm `cultivation_progress`, `cultivation_policy`, `cultivation_trials`, `cultivation_history` (schema 11 → 12, 39 → 43 bảng). Lịch sử bất biến; profile được khóa trước tiến trình/thử thách; tiêu hao tái sử dụng ResourceLedger, revision, thu hồi PDC và receipt hiện có. Thiếu tiến trình của profile cũ phải lỗi, không tự tạo lại hoặc hạ cảnh giới. V001–V011, catalog và chữ ký Economy cũ không sửa.

Migration giữ nguyên XP/kho/tiền. Profile cũ XP ≥500 nhận trạng thái Trúc Cơ kế thừa, XP ≥100 nhận Luyện Khí 1, còn lại Phàm Nhân; danh hiệu cũ vẫn được lưu. Kim Đan/Nguyên Anh hiển thị là danh hiệu kế thừa, chưa mở nội dung mới. Lịch sử `MIGRATED` được phân biệt với thắng thử thách, không tạo SUCCESS giả. Nâng cấp phải cold backup DB/world; rollback cần restore backup trước V012 vào đích riêng.

Integration test nâng V11 và bảo toàn danh hiệu chạy với mã cuối. Kiểm dump thật 37/37 bảng nghiệp vụ cũ và 11/11 checksum/history migration nằm trong `preview60/upgrade-reconciliation.txt`, **thuộc JAR E8ADF…9581C trước chỉnh GUI/thời gian**, không đổi tên thành kiểm dump trên JAR cuối. Paper ở DB nâng cấp riêng đã hết hạn một hợp đồng cũ và hoàn 2 Linh Tệ; kiểm migration-only riêng xác nhận đây không phải thay đổi của migration.

## Binary, kiểm thử và phiên Client

JAR cuối: `D83222CC8DAEB4EF773AAD6988A846A1EAFA20332AD8F38B11DF53A0807455E5` (SHA-256). Target, runtime và bản cold backup khớp. Paper 26.3 build 166, Java 25, PostgreSQL 18.4; chỉ một plugin CuuVucCore trong runtime cuối. Runtime loopback dùng offline-mode cho tài khoản Client thử nghiệm; phiên này không chứng minh xác thực tài khoản production.

Maven compile/package đã tạo binary này. Sau điều chỉnh thời gian, hai integration tests còn chờ cố định 6,1 giây đã được sửa để đọc cấu hình. Lần xác minh cuối dùng `test-compile surefire:test failsafe:integration-test failsafe:verify`: **108/108 PASS, 4 unit + 104 integration PostgreSQL thật, không skipped**. Xem `maven-verify-final.log`. Ca liên quan: đầy đủ 9 tầng/thắng/lợi ích, receipt replay, wrong actor/choice/stale step/cooldown, rollback tài nguyên/token, logout/register/restart/timeout, thiếu state và nâng V11. Đây là bằng chứng kỹ thuật, không thay cho Client E2E.

**Một Minecraft Client thật C (`TyninWorld`), A/B không tham gia.** Client gửi packet khai thác, lệnh và Inventory click thật; không dùng PluginManager event-fixture, probe hoặc agent trong runtime. Điều khiển UI tự động, có hỗ trợ console **chỉ quay camera tại nguyên tọa độ** khi công cụ chuột không xoay được góc nhìn. Không cấp XP, vật phẩm, tiền, OP hay quyền recovery để vượt luồng chuẩn. Attack/Destroy tạm Toggle khi thử, đã trả lại Hold. Vì có hỗ trợ camera, không gọi đây là phiên chơi tay hoàn toàn độc lập.

| Bằng chứng trên đúng JAR D832…55E5 | Kết quả |
|---|---|
| Profile mới, `/cv` → Tu Luyện → Động phủ, lấy cuốc từ rương | Phàm Nhân, 0 XP/tiền/kho; ảnh `01-pham-nhan.png`, `new-player.txt` |
| Prison và craft `ket_tinh 5` bằng Client | 52 thô + 4 Huyền Thiết, 600 XP; vẫn rank 0. Craft tiêu hao 25 thô → 5 Linh Thạch. Inventory đầy vẫn giữ phần chờ trong kho. `mining-complete.jsonl` |
| Double-click đủ điều kiện cho nhiều tầng | Chỉ rank 1/revision 1, một TRAIN, trừ 2 thô. `double-click-funded.txt` |
| Xác nhận riêng tầng 2–6 | Sáu TRAIN tổng cộng, rank/revision 6; 600 XP; 15 thô + 3 Linh Thạch + 4 Huyền Thiết; Linh Tệ 0. `progress-stop.txt/jsonl` |
| Dừng theo yêu cầu kiểm tra tiến độ | Soft stop/drain/Region I/O hoàn tất; PID 22312 đã thoát. 43/43 bảng DB giống snapshot trước stop. `closing-checks.txt`, `paper-final.log`, `after-stop.jsonl` |

Runtime/DB cuối hoàn toàn mới, riêng production/reference. Cold backup `backups/vs002-progress-review` có DB dump, world/playerdata, JAR và config, 232 file với SHA-256; manifest trong `backup-manifest.json`. Bằng chứng trước và sau stop giữ kho/token, receipt, ResourceLedger và tiến trình. Cuối phiên có 63 operations, 22 token, 8 bút toán ResourceLedger tiêu hao cho sáu tầng; Ledger tiền, trial, journal, fence và recovery decision đều trống. **Stop không tương đương với restart đã kiểm.**

`pre-final/` thuộc bản thăm dò trước đó. `preview60/` thuộc JAR E8ADF…9581C: Client tới Luyện Khí 9, đã chọn sai, logout giữa thử thách và nhiều lượt hết giờ; chưa thắng. Tooltip che phù ấn cùng độ trễ điều khiển là lý do chuyển chuỗi lên hàng trên và chọn cấu hình demo 15/180 giây. Không cộng tiến độ các binary thành một hành trình đạt trên JAR cuối.

## Phần còn thiếu trước Playable

Tiếp tục C ở Luyện Khí 6 trên đúng JAR cuối: tầng 7–9 → tham gia và thắng Trận Tâm Ma → dùng công thức Trúc Cơ → logout/login và soft restart → đối soát XP/kho/token/trial/history/perk. Chưa kiểm thử thách/hiển thị preview mới bằng Client trên JAR cuối, chưa đối soát sau restart hoặc cold restore bundle cuối. Không tự mở lại runtime trong khi chờ người dùng kiểm tra tiến độ.

Chưa chạy A/B giao nhận/Tông Môn/hợp đồng trên binary mới, không lặp toàn bộ hồi quy Client V0.8/B01; không suy diễn bộ tests thành bảo đảm mọi tác động inventory/drop, crash, plugin vượt quyền hay Folia. B01 vẫn CLOSED WITH SCOPE LIMIT theo hồ sơ cũ. B02 2/4/8 và tối ưu `world.save()` PAUSED; main/CI giữ nguyên, không nâng Alpha hoặc nghiệm thu production.
