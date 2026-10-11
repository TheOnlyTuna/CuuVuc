# Pre-Alpha — Builder Survival, quyền vùng và audit

**PASS các ca xây tay được liệt kê — chờ người dùng đánh giá hồ sơ bổ sung.** Phiên riêng ngày 12/10/2026, 01:46–02:01 Asia/Bangkok. V0.8 vẫn ACCEPTED WITH SCOPE LIMIT tại `02adf1e`; không sửa Java, migration hoặc JAR. Đây là phiên mới bổ sung bằng chứng, không đổi phiên dựng console Phase 5 thành xây tay.

Runtime `run-v08-builder-rights`, DB `cv_v08_builder_rights`, restore từ bundle ACTIVE `backups/v08-three-player-active-001`. Checkpoint V0.8 đã hoàn tất giữ riêng, không ghi đè. Paper 26.3 build 166, Corretto 25.0.4.1, PostgreSQL 18.4; localhost 25580, offline-mode được phép từ trước. JAR SHA-256 `79359d168fdd32f5ad7de85dea82969fae03ddd6435c43cf160247cd97505c10`.

C là TyninWorld, UUID `3bb5ddbc-5b75-377f-8647-6f25c51f252a`, client Light Craft 173 mod. Người dùng trực tiếp đặt/phá bằng chuột; agent phụ trách GUI, chuẩn bị và đối soát. Không cấp OP/Creative; console đọc playerGameType=0 (Survival).

Console chuẩn bị riêng: dọn 9 COBBLESTONE và 2 STONE_BRICKS có sẵn từ phiên dựng console; thêm nền Dirt y=99 x=66..70 z=2..6 để đứng/đặt; giữ chunk đảo tải; cấp 16 Cobblestone + 6 Stone Bricks và teleport C tới vị trí ngắm. Cuốc và 8 Dirt đã có từ checkpoint. Không phát hành PDC/Linh Tệ hoặc sửa DB nghiệp vụ. Những thao tác này không tính là tự thu thập vật liệu hoặc PLACE/BREAK của Builder.

[DB nền](baseline-db.txt): hai contract ACTIVE, Builder C, mỗi escrow 2 LT, tất cả ví 0; CREATE=2, ACCEPT=2, chưa PLACE/BREAK, không có island_builders. Sàn `279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3` tại x=67..69,y=100,z=3..5, hạn 02:20:13; đường `7c9c9656-2602-48b4-98de-447f06e34e97` tại x=74..76,y=100,z=11, hạn 02:24:06. Thời hạn thực DB, không gia hạn bằng SQL.

| Ca | Trạng thái |
|---|---|
| Đặt Dirt sai vật liệu ở (68,100,5) và phá nền y=99 | Người dùng báo cả hai bị chặn. Console kiểm mục tiêu vẫn AIR, nền vẫn Dirt, 8 Dirt Inventory giữ nguyên; DB chưa PLACE/BREAK. Không có log sự kiện cancelled riêng; bằng chứng là lời xác nhận thao tác + trạng thái world/DB |
| Đặt/phá Cobblestone trong vùng ACTIVE | Người dùng thao tác thêm ở cả (67,100,5) và (68,100,5), đã xác nhận; audit thực tế 5 PLACE + 3 BREAK, cuối cùng hai ô Cobblestone. [Audit](active-audit.txt) ghi đúng actor C, hợp đồng sàn và tọa độ. Không ép số đếm về chuỗi tối thiểu dự kiến; chưa phải đo mất/trùng sự kiện dưới crash |
| Đặt/phá ngoài vùng khi ACTIVE | Người dùng xác nhận cả hai bị chặn ở (66,100,5)/(66,99,5); console kiểm AIR/Dirt giữ nguyên; audit vẫn 5 PLACE/3 BREAK ([DB](outside-db.txt)) |
| Xây đủ hai template bằng Survival | Sàn đủ 9/9 COBBLESTONE, console chỉ kiểm chín block, không dựng. Audit 12 PLACE/3 BREAK còn đúng 9 block. [Ảnh sàn](survival-pad-built.png), [DB khi gửi](submitted-db.txt). Đường đủ 3/3 STONE_BRICKS; ba PLACE của C đúng x=74,75,76,y=100,z=11. [Ảnh đường](survival-path-built.png) |
| Thu hồi quyền thi công khi SUBMITTED | Người dùng xác nhận phá sàn và đặt thêm đều bị chặn; ô (68,100,5) còn Cobblestone, phía trên AIR; audit giữ 12 PLACE/3 BREAK, SUBMIT=1. Chưa giải ngân tại lúc thử |
| Quyền sau COMPLETED, lịch sử và trả tiền | Người dùng xác nhận phá/đặt thêm trên sàn đều bị chặn; probe chín ô Cobblestone và chín ô AIR phía trên khớp, audit giữ 12 PLACE/3 BREAK. A duyệt sàn và đường qua GUI, mỗi lần thử tám click xác nhận liên tiếp: cuối cùng đúng hai RELEASE, hai COMPLETE và C nhận tổng 4 LT. [Ảnh nghiệm thu sàn](survival-pad-completed.png), [ảnh nghiệm thu đường](survival-path-completed.png) |
| Restart, đối soát world/audit | Shutdown mềm 02:00:56 có database_drain_complete; ready lại 02:01:07, probe 02:01:27 kiểm cả 12 block đúng vật liệu. Hai tệp audit trước/sau restart giống hệt nhau; hai tệp đối soát tài sản cũng giống hệt nhau. Không phải thử kill đột ngột |

## Đối soát và cách kiểm tra

[Audit SQL](audit-reconcile.sql) chạy read-only trên PostgreSQL thật trước và sau restart: 15 PLACE, 3 BREAK, 12 tọa độ riêng; mỗi tọa độ PLACE trừ BREAK bằng 1, actor đúng Builder, tọa độ đúng template, event_id nằm sau ACCEPT và trước SUBMIT. [Trước restart](completed-audit.txt) và [sau restart](restarted-audit.txt) đều có SURVIVAL_AUDIT_ASSERTIONS_PASS và giống hệt nhau. Đây là đối soát trong phiên bình thường, không chứng minh không mất sự kiện khi crash.

[SQL tài sản](assets-reconcile.sql) kiểm ba hồ sơ/thành viên, hai hạng mục và hợp đồng COMPLETED, C có 4 LT, tổng ví 4, ngân khố/escrow 0, minted=4/burned=0; dự trữ/kho dự án 0, tiêu thụ đúng 3 linh_thach + 1 huyen_thiet bằng hai request; TRANSFER cân bằng; 22 linh_thach_tho + 7 huyen_thiet khớp 29 token, quyền chi B vẫn bị thu hồi. [Trước restart](completed-assets.txt) và [sau restart](restarted-assets.txt) có SURVIVAL_ASSETS_ASSERTIONS_PASS, giống hệt nhau. Các DEPOSIT/mint/đóng góp đã có trong snapshot ACTIVE; phiên này chỉ thi công và hoàn tất hai hợp đồng trong DB riêng, không phát sinh thanh toán bổ sung ở DB Phase 5 gốc.

Chạy lại hai script bằng psql -X -v ON_ERROR_STOP=1 -f trên DB phiên `cv_v08_builder_rights` (localhost:55439, user cuuvuc_test). Script chỉ đọc và dừng nếu invariant sai. [Log phiên](session.log) chứa chuẩn bị, probe world và shutdown; [log restart](restart.log) chứa ready và probe SURVIVAL_WORLD_12_OF_12. SHA-256 JAR runtime được kiểm lại và giữ nguyên hash phía trên.

## Giới hạn giữ lại

Chỉ A và C online trong phiên bổ sung; không chạy lại toàn bộ luồng ba người lập/góp/thuê. Các thử phủ định ACTIVE/SUBMITTED/COMPLETED tập trung trên sàn; đường kiểm xây đủ, gửi/duyệt và tồn tại sau restart, chưa lặp mọi ca quyền trên đường. Chưa thử người khác đặt/phá trong vùng hợp đồng. Không có video độc lập hoặc log cancelled riêng: các thao tác bị chặn dựa trên xác nhận của người dùng và kiểm tra world/audit, không đếm số lần click từ DB. Các lần đặt/phá thêm đã được người dùng xác nhận, ghi theo audit thực tế, không ép về chuỗi tối thiểu dự kiến.

Vật liệu vanilla được cấp để thử quyền, chưa chứng minh tự khai thác toàn bộ vật liệu xây dựng. Client C có 173 mod, chưa xác minh tương thích vanilla rộng. Restart là shutdown mềm, không phải backup/restore mới; bằng chứng restore V0.8 vẫn theo Phase 5. Chưa có client reconnect/xem GUI lịch sử sau restart trong phiên bổ sung; lịch sử sau restart được đọc trực tiếp PostgreSQL.

Không đóng B01–B06 hoặc công bố Alpha/production. Hard-kill, commit/resync và đo TPS/MSPT chưa thực hiện. Cảnh báo Paper world.save() vẫn thuộc B02; lỗi OSHI/Perflib đầu startup không ngăn database_ready nhưng chưa được xử lý. 78/78 giữ baseline Phase 4, không phải suite chạy lại trong phiên này. Không thay main, không bật CI. Ưu tiên kỹ thuật tiếp theo là B01, sau đó B02 theo quyết định người dùng.
