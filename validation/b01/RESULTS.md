# B01 — Hard-kill và đối soát khởi động

Báo cáo này giữ phạm vi chẩn đoán đã được chấp nhận tại `c5687a4`. Bản triển khai journal/fence và kết quả mới được tách tại [JOURNAL-RESULTS.md](JOURNAL-RESULTS.md); không đổi các ca fixture cũ thành bằng chứng callback client.

**DIAGNOSTIC VALIDATION — B01 CHƯA ĐÓNG.** V0.8 giữ ACCEPTED WITH SCOPE LIMIT; phiên Builder Survival `f9ef1d8` đã được người dùng ACCEPTED. Đây là bước củng cố tiếp theo, chưa chuyển B02 hoặc nâng Alpha. Không merge main, không bật CI.

## Phạm vi và môi trường

Ngày 12/10/2026 Asia/Bangkok, Paper 26.3 build 166, Corretto 25.0.4.1, PostgreSQL 18.4 ở localhost:55439. Server thử nghiệm bind 127.0.0.1:25590, whitelist/offline-mode giữ theo cấu hình đã được phép. Mỗi ca dùng DB `cv_b01_a02_<case>` và runtime `run-b01-a02-<case>` mới, restore từ backup ACTIVE có manifest/checksum `backups/v08-three-player-active-001`; checkpoint và server đã nghiệm thu không bị kill hoặc ghi đè. Fixture chỉ kéo dài expiry một ngày trên DB thử để tránh hết giờ giữa ma trận, không sửa reward/reservation/ledger. [Manifest nguồn](a02/source-backup-manifest.json).

JAR mới có SHA-256 `dcb83fc93f077568729408eda22e8523adc9f37796b620e7c612d839da22cc6d`, khác JAR Builder Survival `79359d…`. JAR mới chỉ được kiểm bằng suite và harness Paper dưới đây; không tuyên bố đã chạy lại gameplay client trên binary mới. [Hash binary](a02/binary-hashes.txt).

CrashProbe là plugin **chỉ dành kiểm thử**, mã trong [probe](probe/CrashProbe.java), không đóng vào JAR CuuVucCore. Harness sửa đúng một ô `(67,100,5)` qua Paper rồi gọi điểm nhận sự kiện của queue thật bằng reflection; đây không phải BlockPlaceEvent/BlockBreakEvent phát sinh từ client. Các block khác có sẵn từ backup dựng console. HOLD giữ worker audit để tạo cửa sổ chưa commit; RESUME thả worker và chờ SQL xác nhận audit commit. CLEAR/SAVE chuẩn bị baseline của ca PLACE. Chưa kiểm toàn bộ callback native của Paper, disconnect hoặc inventory drop khi crash.

Hard-kill dùng Stop-Process -Force, kiểm PID và command line phải chứa đường dẫn paper.jar của runtime `run-b01-*` trước khi kill; không dùng stop hoặc shutdown hook. [Kill log](a02/kills.txt) phân biệt 9 lần fault và 9 lần cleanup sau quan sát. PostgreSQL vẫn chạy; không phải kill DB server hoặc mô phỏng mất điện. Backup nguồn được kiểm trước từng restore; đây không phải backup đang chạy hoặc backup sau crash.

## Kết quả ma trận

Các ca kỳ vọng mất dữ liệu hoặc lệch world/audit **không phải PASS độ bền**. Chúng kiểm chứng lỗi và khả năng báo đúng trạng thái. [Tổng hợp thực chạy](a02/completed.txt); từng ca có `*-before.json`, `*-after.json`, `*-world.txt`, `*-recovery.tsv` và log trước/sau restart.

| Ca | Điểm kill | World phục hồi | Audit tại ô | Báo cáo khởi động |
|---|---|---|---|---|
| pq0 | PLACE trong queue, trước save | AIR | Không có | UNKNOWN_HISTORY |
| pq1 | PLACE trong queue, sau save | COBBLESTONE | Không có | UNKNOWN_HISTORY |
| pc0 | PLACE đã commit, trước save | AIR | PLACE | WORLD_AUDIT_MISMATCH |
| pc1 | PLACE đã commit, sau save | COBBLESTONE | PLACE | MATCH_OBSERVED_STATE |
| bq0 | BREAK trong queue, trước save | COBBLESTONE | Không có | UNKNOWN_HISTORY |
| bc0 | BREAK đã commit, trước save | COBBLESTONE | BREAK | WORLD_AUDIT_MISMATCH |
| bc1 | BREAK đã commit, sau save | AIR | BREAK | MATCH_OBSERVED_STATE |
| txbefore | Đã chạy thay đổi COMPLETE trong transaction, chặn trước commit | Sàn giữ nguyên | Không tự thêm block event | SUBMITTED, ví C=0, escrow=4, RELEASE/CONSUME/receipt COMPLETE=0 |
| txafter | COMPLETE đã trả kết quả sau commit | Sàn giữ nguyên | Không tự thêm block event | COMPLETED, ví C=2, escrow=2, RELEASE=1, CONSUME=2 dòng, receipt COMPLETE=1 |

Đối với txbefore, trigger thử nghiệm trên UPDATE operations dừng bằng pg_sleep(4) sau các thay đổi nghiệp vụ, trước connection.commit(). Runner chỉ kill khi pg_stat_activity xác nhận đúng UPDATE đang PgSleep; nếu không xác định điểm đó thì dừng, không tính đạt. Trigger/function được gỡ sau kill và trước restart; không sửa migration/fingerprint hoặc giữ hook lỗi trong bản phát hành.

Sau mỗi restart, snapshot nghiệp vụ khớp trước restart. Với hai ca transaction, gọi lại COMPLETE hai lần cùng contract/request qua API GameDatabase thật: cuối cùng chỉ một RELEASE -2, một receipt COMPLETE, C=2 LT, escrow còn 2 LT cho hợp đồng đường ACTIVE. [SQL đối soát thanh toán](settlement-assertions.sql) kiểm cả tổng Ledger và receipt đã hoàn tất, đạt B01_SETTLEMENT_ASSERTIONS_PASS ở [txbefore](a02/txbefore-settlement.txt) và [txafter](a02/txafter-settlement.txt). Hai dòng CONSUME là 2 linh_thach + 1 huyen_thiet theo STONE_PAD_V1; dự trữ đường còn 1 linh_thach. Tổng tiền 4, minted=4/burned=0, hai DEPOSIT, không REFUND, TRANSFER tài nguyên cân bằng và 29 token giữ nguyên. [Trước commit](a02/txbefore-before.json), [replay sau rollback](a02/txbefore-replayed.json), [sau commit](a02/txafter-before.json), [replay sau commit](a02/txafter-replayed.json).

## Thay đổi mã nguồn

`GameDatabase.contractBlockAudit()` đọc sự kiện cuối theo từng contract/tọa độ, không thêm receipt hoặc lịch sử. `ContractRecovery` lấy snapshot trước READY, đọc block trên region scheduler, ghi báo cáo TSV riêng mỗi lần khởi động trong `plugins/CuuVucCore/recovery/`, force file ra filesystem và ghi cảnh báo có đường dẫn báo cáo. DB/filesystem chạy trên worker I/O. Không đọc block từ worker, không tự set block hoặc ghi PLACE/BREAK để lấp khoảng trống. Lỗi đọc/ghi hoặc timeout 30 giây làm startup thất bại trước READY.

UNKNOWN_HISTORY nghĩa là không có sự kiện block đã commit cho ô, **không có nghĩa chưa từng có thao tác**. MATCH_OBSERVED_STATE chỉ khớp trạng thái cuối, không chứng minh các sự kiện trung gian đầy đủ. WORLD_AUDIT_MISMATCH không tự xác định bên nào đúng. Báo cáo chạy cả khi restart mềm; không phải detector chứng minh lần chạy trước đã crash. Những ô dựng console trong backup cũng có UNKNOWN_HISTORY, đúng với nguồn bằng chứng.

## Kiểm thử và tái chạy

`mvn -B verify`: **79/79** gồm 4 test hiện có và 75 PostgreSQL integration tests, 0 failure/error/skip. Chỉ thêm một integration test cho truy vấn audit cuối, replay và tính chỉ đọc; không thêm mock/unit test. [Log](verify.log), [JUnit PostgreSQL](GameDatabaseIT.xml).

Thiết lập JAVA_HOME/Path và CUUVUC_TEST_URL/USER/PASSWORD cho DB test mới trước khi chạy Maven. Sau build, chạy `build-probe.ps1` với JavaHome/MavenHome; chạy `run-matrix.ps1` với JavaHome, PostgresBin, BundleDirectory và RunId mới. Runner cố định các UUID của backup ACTIVE được nêu ở trên; không dùng cho bundle tùy ý. Đích DB/runtime/evidence đã tồn tại sẽ bị từ chối, không tự xóa dữ liệu. Báo cáo/trigger/kill không phục vụ server production.

Lần a01 là lần phát triển harness chưa hoàn tất: assertion dự kiến một dòng CONSUME trong khi template hiện hữu có hai loại nguyên liệu. Đối chiếu BuildingTemplates và ledger xác nhận lỗi kỳ vọng của harness, không sửa dữ liệu để khớp test; lần a02 chạy lại ma trận với kỳ vọng đúng và kiểm tra lượng tiêu thụ từng resource. Suite lần đầu cũng lỗi cấu hình vì DB `cuuvuc_test` không tồn tại; đã tạo DB riêng `cv_b01_suite`, chạy lại verify thành công. Không tính các lần lỗi thành PASS.

## Phần chưa đạt

Queue audit vẫn có thể mất sự kiện khi kill; journal hiện hữu vẫn chỉ lưu khi shutdown mềm. Đã tái hiện lệch world/audit, **chưa có cơ chế commit nguyên tử chung world/PostgreSQL hoặc journal đủ để phục hồi mọi thao tác**. Báo cáo khởi động là chẩn đoán; chưa quarantine hợp đồng hoặc ngăn nghiệm thu chỉ vì báo cáo có UNKNOWN/MISMATCH. Người vận hành phải xem báo cáo trước khi tiếp tục các hợp đồng nghi vấn; không dùng cảnh báo làm chứng nhận phục hồi tự động.

Chưa thử kill trong chính world.save() đang chạy, trong fsync, mất điện/ổ đĩa, crash PostgreSQL, client online tại callback native, queue đầy, nhiều region/Folia, tải đông người hoặc benchmark B02. Chưa chứng minh các chuỗi PLACE/BREAK trung gian có trạng thái cuối giống nhau không bị mất. Chưa kiểm lặp mọi vùng/template hoặc nâng tương thích client. B01–B06 vẫn mở; chưa chuyển B02.

Bước kỹ thuật tiếp theo của B01 là thiết kế ghi bền intent/audit và hàng rào phục hồi cho hợp đồng nghi vấn, giữ request idempotent và không tự suy diễn lịch sử từ world. Chỉ nâng mức bảo đảm sau khi chạy lại các ca tương ứng trên binary triển khai.
