# V0.8 Phase 1 — Material Transfer: báo cáo giao nhận và phục hồi

Ngày **11/10/2026**, Asia/Bangkok. Nhánh `v0.8-khai-son-lap-phai`.

**Trạng thái: Phase 1 ACCEPTED — Gameplay Prototype**, theo quyết định người dùng tại `12fdc25`: 53/53 local tests, 20/20 bảng DB khớp. Giới hạn crash, cạnh tranh backend và tải/PDC chưa thử hết vẫn được giữ; không chứng nhận production hoặc toàn bộ V0.8. Phase 2 được triển khai và báo cáo riêng tại [Tông Môn](phase2/RESULTS.md). Không merge `main`, không bật GitHub CI.

## Binary và môi trường

JAR cuối `CuuVucCore-0.8.0-SNAPSHOT.jar`, SHA-256:
`17001c7816e4174ba041ced3a82dab2df57df44322ee8e0c68dbaf280f0d44d9`.

Paper 26.3 build 166 BETA, API `26.3.build.166-beta`, Java 25.0.4.1, PostgreSQL 18.4, Maven Wrapper 3.9.11. Đây là phiên bản baseline, không tuyên bố mới nhất hoặc hỗ trợ Folia. Hai Minecraft Java 26.3 client Prism: CuuVucNPC2 (nguồn), CuuVucNPC1 (đích). Server offline-mode, whitelist, chỉ `127.0.0.1:25580`, theo cho phép của người dùng. DB/world thử riêng, không sửa runtime V0.7. Không cung cấp hoặc nhập V3 heightmap.

[Fixture](client-fixture.sql) cấp nguồn 20 `linh_thach_tho`, không cấp tiền/XP. Inventory đầy được tạo bằng 2.304 stone vanilla; clear/give/kick chỉ chuẩn bị trạng thái thử, không cấp tài sản kinh tế. Mọi chuyển tài nguyên thực hiện qua GUI client.

## Sáu điều kiện giao nhận

| Tình huống | Bằng chứng và kết quả |
|---|---|
| Hai client, GUI, xác nhận liên tục | Trên JAR cuối, chuyển 3: nguồn 12→9, đích 8→11. Tám lần click được thử liên tục; GUI đóng sau xác nhận thành công. Một receipt `0e9af7d3-d92d-4a5c-8ed1-3758bfa914b0`, hai bút toán mới. Không suy ra rằng cả tám click đã thành tám packet GUI. [GUI](final-transfer-gui.png), [đối soát](final-double-click-check.txt). Tám yêu cầu cùng ID đồng thời còn được kiểm tra trực tiếp bằng integration DB. |
| Người nhận offline rồi rejoin | Chuyển 2 khi đích offline: nguồn 8→6, đích 12→14, đích chưa có token. Sau restart/restore và đăng nhập lại trên chính JAR cuối, Inventory có 14 item PDC, DB có 14 token thuộc đích. [Trước đồng bộ](final-committed-before-sync.txt), [Inventory sau restore](final-recipient-restored-fourteen.png), [đối soát sau rejoin](final-after-rejoin.txt). |
| Inventory đầy, token cũ/sao chép | Client đích đầy 36 slot stone; chuyển 1 vẫn giữ kho đích 11→12, thông báo chờ nhận đủ 12. Hai bản sao PDC chứa token thật đã bị thu hồi được đưa vào Inventory nguồn qua console; registry không có token đó, tổng kho vẫn 20, lần đồng bộ loại bản sao. [Inventory đầy](final-recipient-full.png), [thông báo](final-recipient-pending-twelve.png), [đối soát token](final-stale-token-check.txt), [log có NBT fixture](final-client-material-transfers.log). Không có đường nạp kho bằng ItemStack tùy ý. |
| Craft, sell, transfer cạnh tranh | Integration PostgreSQL thật cho ba worker cùng tiêu thụ kho 5; chỉ một thành công, đúng một operation, kho/tiền/quota/ledger tương ứng, không có giao dịch một phần. `craftSellAndTransferRaceConsumesOneStockWithoutPartialTransactions` trong [XML](GameDatabaseIT.xml). Đây là cạnh tranh backend, chưa phải ba GUI được bấm cùng lúc. |
| Restart sau commit trước Inventory sync | Receipt `0442749a-cbb7-4020-8649-5309aad1f6cf` commit khi đích offline; ghi nhận kho 14/token 0 rồi stop mềm. Sau khởi động từ bản phục hồi, rejoin dựng đủ 14 token, không thêm giao nhận. [Log trước stop](final-client-material-transfers.log), [log rejoin](final-restored-startup-rejoin.log). Đây là cửa sổ commit→projection của người nhận offline; chưa thử kill tiến trình giữa hai bước khi người nhận online. |
| Backup/restore DB và world | Cold backup sau drain/world I/O hoàn tất, restore vào DB `cv_v08_restored_final` và runtime mới. Script kiểm checksum đủ 236 file, gồm 104 file world. Snapshot dữ liệu toàn bộ 20 bảng trước/sau restore trùng SHA-256 `89fde4b3cf22f7208f24a4a67ab4e71a7a024f592b87f077a9d571fddb8d3164`; kho/token/receipt/Resource Ledger khớp. Sau rejoin: nguồn 6/token 6, đích 14/token 14; 14 bút toán, tổng delta 0. [Manifest](final-backup-manifest.json), [kiểm tra](final-restore-check.txt), [trước](final-before-restore.jsonl), [sau](final-after-restore.jsonl), [shutdown](final-restored-shutdown.properties). |

JAR cuối thực hiện ba giao nhận GUI mới (3, 1, 2), tức sáu bút toán mới. Bốn giao nhận trước trên binary checkpoint được giữ làm lịch sử: tổng cộng bảy receipt/14 bút toán, tổng tài nguyên 20. Không gọi phiên trước là kiểm thử binary cuối. [Log phiên trước](client-material-transfers.log), [Inventory nhận 8 sau phục hồi đầu](recipient-restored-eight.png) được giữ để kiểm tra nguồn gốc fixture.

## Lỗi phát hiện bằng restore thật và cách sửa

Lần restore đầu khớp dữ liệu và world nhưng JAR checkpoint từ chối khởi động: `pg_dump/pg_restore` làm phẳng nhóm AND trong CHECK `resource_ledger_delta_check`; `pg_get_constraintdef` trả chuỗi khác dù giới hạn vẫn đúng. [Log lỗi](restore-fingerprint-failure.log), [đối soát lần đầu](restore-check.txt), [manifest](backup-manifest.json).

`schema-fingerprint.sql` chuẩn hóa đúng một biểu thức tương đương đã quan sát về dạng chuẩn cũ. Không sửa V001–V005, checksum hoặc fingerprint lưu trong DB; không bỏ kiểm tra schema. Integration round-trip dựng lại constraint từ deparser khởi động được, nhưng đổi upper bound 1000→1001 vẫn bị từ chối SQLSTATE 55000. Hai lượt restore thực và startup trên JAR sửa xác nhận kết quả.

## Kiểm thử và giới hạn

Build cuối `clean verify`: **53/53 đạt**, 4 Domain + 49 PostgreSQL integration, 0 lỗi/skip. [Tóm tắt](test-summary.txt), [Domain XML](DomainTest.xml), [integration XML](GameDatabaseIT.xml). Không thêm unit test mới; bổ sung hai integration cho race và constraint round-trip. Các ca replay, revision, rollback bằng trigger thật, bất biến ledger, quota, migration v4→v5 và hợp đồng cũ tiếp tục đạt.

Server cuối báo `cuuvuc_database_ready`, shutdown exit 0, drain và world I/O hoàn tất. Server/PG thử đã tắt mềm. Log có lỗi OSHI Windows Perflib đã biết; không sửa registry. Snapshot dùng [script chỉ đọc](snapshot-database.ps1), chạy khi writer đã dừng; các bundle đầy đủ nằm ngoài Git, manifest và bằng chứng đối soát được commit.

Phạm vi còn mở: recipient đang bận, mọi biến thể PDC giả/Creative, tải nhiều người, hard-kill online giữa commit và projection, backup nóng. Không tuyên bố transaction nguyên tử xuyên DB/world/Inventory. Ca race kiểm chứng backend, không chứng nhận thao tác craft/sell đồng thời qua client. Giới hạn block audit, world.save và template công trình giữ tại [BACKLOG](../../BACKLOG.md). Các ca này không dùng để tuyên bố toàn bộ V0.8 đã đạt.

Thứ tự phát triển đã được người dùng chấp thuận sau nghiệm thu Phase 1: Phase 2 tông môn; Phase 3 ngân khố SECT/Linh Tệ trên Economy hiện tại; Phase 4 kho dự án và dự trữ hạng mục; Phase 5 nghiệm thu ba người. Báo cáo này giữ bằng chứng Phase 1; trạng thái Phase 2 nằm trong báo cáo riêng.
