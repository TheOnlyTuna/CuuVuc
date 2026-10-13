# B01.2 — Plugin Interference Validation

**TECHNICAL PASS trong phạm vi 8 ca event-fixture trên Paper; chờ đánh giá B01.2. B01 vẫn OPEN.** Nhánh `v0.8-khai-son-lap-phai`, nền `13aa20d`. B01.3, B02 và gameplay V0.9 chưa triển khai; không merge main hoặc bật CI.

Core JAR cuối SHA-256: `F8F65AD44A8423600D1EF629DC5D68681D729E2D2B773766A3C05BB1126F1FB9`.

Ngày chạy: 13/10/2026, Asia/Bangkok. Paper 26.3 build 166, Corretto 25.0.4.10.1, PostgreSQL 18.4, Maven 3.9.11. PostgreSQL thử có fsync/synchronous_commit/full_page_writes bật. Runtime/DB riêng, bind loopback; dữ liệu nguồn có bản sao và manifest kiểm checksum trước restore.

## Kết quả và lỗi đã được tái hiện

- **8/8 ca B01.2 PASS** trên core cuối: [if03/reconciliation.txt](if03/reconciliation.txt), [hash binary](if03/binary-hashes.txt), [PID](if03/pids.txt), [restart hard-kill](if03/kills.txt).
- **95/95 Maven tests PASS**, 4 pure-data + 91 PostgreSQL integration, không skipped. Hai integration test mới kiểm rollback khi quan sát world lệch/lỗi và commit/replay một proof. [Log](interference-environment/maven-verify.log), [DB tests](interference-environment/GameDatabaseIT.xml), [Domain tests](interference-environment/DomainTest.xml).
- **22/22 hard-kill hồi quy PASS trên cùng core:** [9 ca queue/settlement](if04/completed.txt), [7 ca journal](if05/completed.txt), [4 ca region I/O](io13/completed.txt), [2 ca quyết định phục hồi hiện có](if07/completed.txt). Đây là hồi quy B01, không phải triển khai B01.3.
- **Từ chối thanh toán thực tế qua API của chủ hợp đồng PASS:** [if08/denied.txt](if08/denied.txt), [kết quả](if08/completed.txt). Submit, abandon/refund và complete đều báo RECOVERY_REQUIRED; toàn bộ 37 bảng DB trước/sau bằng nhau.

Lượt đối chứng trên core cũ C5E32B… tại [if02](if02/completed.txt) đã tái hiện hai vấn đề:

1. Plugin đổi COBBLESTONE thành STONE trước HIGHEST của lần BREAK thử lại: callback bị hủy, INTENT hết hạn thành ABORTED, world vẫn STONE nhưng không có fence. [Bằng chứng live](if02/beforebreak-live-summary.json).
2. Plugin đổi world trong lúc UPDATE receipt APPLIED đang chờ gate: core cũ vẫn commit APPLIED/audit/receipt từ quan sát tick trước, không khóa hợp đồng khi server còn chạy. Restart mới phát hiện MISMATCH. [PLACE](if02/preplace-live-summary.json), [BREAK](if02/prebreak-live-summary.json), [marker transaction](if02/preplace-precommit-marker.txt).

Không gọi các APPLIED cũ là bằng chứng world hiện tại đáng tin cậy. Quan sát tick trước là có thật trong fixture; vấn đề là world đổi trước commit nhưng giao thức vẫn hoàn tất và chưa có fence live.

## Phần sửa trong core

`BlockJournalWorld` kiểm tra lại ô có ticket PREPARING/READY sau callback bị từ chối. Kiểm tra phải còn đúng event ID và phase chưa tiêu thụ, tránh coi callback hợp lệ tiếp theo là can thiệp. Nếu world khác vật liệu trước INTENT, giữ ticket RECOVERY_REQUIRED và ghi fence INCOMPLETE. Ticket này không tự hết hạn thành ABORTED; ca beforebreak chờ quá 12 giây từ prepare để kiểm điều đó.

Luồng callback live gọi `applyObservedBlock`: dùng cùng logic receipt/replay và ghi APPLIED/audit hiện có, sau đó quan sát lại world qua Region Scheduler **sau UPDATE receipt, trước connection.commit()**. World lệch hoặc không đọc được làm transaction rollback cả APPLIED, audit và receipt; INTENT bền đã chuẩn bị vẫn còn. Không đọc block Bukkit từ thread SQL. Quan sát có timeout 5 giây; lỗi dẫn tới giữ khóa.

Sau commit còn một kiểm tra Region Scheduler trước khi giải phóng ticket. Nếu lệch, giữ ticket và fence MISMATCH; không xóa audit đã commit để viết lại lịch sử. Nhánh này là bảo vệ bổ sung, **chưa có ca tiêm lỗi riêng đúng giữa lần đọc cuối và commit/check sau commit**, nên không tính một ranh giới PASS mới từ việc có code đó.

Không đổi schema/checksum migration, quy tắc phát hành tiền, cơ chế thanh toán hoặc tạo PLACE/BREAK từ ảnh world. Hàm ghi receipt được tách để tái sử dụng, không đổi SQL/idempotency của các operation khác. Audit sequence PostgreSQL có thể có khoảng trống do nextval không rollback; sequence không được coi là receipt hoặc tài sản.

## Ma trận plugin độc lập

`InterferenceProbe.jar` không import core, không gọi prepare/apply journal hoặc ghi DB. Plugin sử dụng Bukkit setType và listener LOWEST/MONITOR; phần watcher chỉ đọc pg_stat_activity của chính DB thử qua JDBC, sau đó đổi block trên Server thread. CrashProbe chỉ phục vụ flush, dừng runtime và kiểm API bị fence.

Fixture tạo BlockPlaceEvent/BlockBreakEvent, dispatch qua **PluginManager thật**, các handler bảo vệ/authorize/observe thật và Region/Async Scheduler thật, với actor UUID đã có trong DB. Actor là adapter Player tối thiểu, không có login/network/inventory client; API ngoài tập getter/message đã khai báo sẽ ném lỗi. PLACE hủy được phục hồi vật liệu trước; BREAK hợp lệ được fixture áp dụng AIR rồi plugin đổi STONE ở đuôi callback. Đây là kiểm thử connector trên server thật, **không phải callback phát sinh từ Minecraft Client**.

Mỗi ca riêng có một ô (67,100,5) trong cv_sky, hợp đồng sàn `279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3`, một INTENT đã chuẩn bị bằng lần thử đầu bị hủy. “Trước callback” trong ma trận là **trước HIGHEST authorize của lần thử lại có INTENT**, không bao phủ mọi thay đổi trước lần thử đầu.

| Ca | Can thiệp | World live | Journal | Audit/receipt mới | Fence live |
|---|---|---|---|---|---|
| beforeplace | LOWEST đổi block trước authorize PLACE; callback hủy và fixture phục hồi AIR | AIR | ABORTED | 0/0 | 0 |
| beforebreak | LOWEST đổi block trước authorize BREAK; thay đổi trực tiếp còn lại sau callback hủy | STONE | INTENT | 0/0 | 1 INCOMPLETE |
| afterplace | MONITOR của plugin chạy sau MONITOR core, đổi block trước tick quan sát | STONE | INTENT | 0/0 | 1 INCOMPLETE |
| afterbreak | Plugin đổi block sau dispatch BREAK và sau fixture áp dụng AIR | STONE | INTENT | 0/0 | 1 INCOMPLETE |
| preplace | Plugin đổi block khi UPDATE receipt PLACE đang ở PgSleep | STONE | INTENT | 0/0 | 1 INCOMPLETE |
| prebreak | Plugin đổi block khi UPDATE receipt BREAK đang ở PgSleep | STONE | INTENT | 0/0 | 1 INCOMPLETE |
| controlplace | Không can thiệp | COBBLESTONE | APPLIED | 1/1 | 0 |
| controlbreak | Không can thiệp | AIR | APPLIED | 1/1 | 0 |

Gate là trigger chỉ trên DB thử, sleep 2 giây, dưới statement_timeout 5 giây của core. Watcher kết nối trước retry, polling 25 ms, ghi backend PID/query/UTC và marker mutation có PID/runtime/thread/world. Hai ca precommit cuối **không chạm statement timeout**; log ghi lỗi world expected=COBBLESTONE/AIR, actual=STONE. Trigger/function được gỡ trước restart, startup xác nhận schema bình thường.

Marker: `if03/*-plugin-markers.txt`, [preplace](if03/preplace-precommit-marker.txt), [prebreak](if03/prebreak-precommit-marker.txt). Listener trong plugin có thể vi phạm quy ước MONITOR chỉ quan sát; việc đó là can thiệp được tiêm có chủ ý, không phải hướng dẫn viết plugin gameplay.

## Đối soát và bảo toàn bằng chứng

[Verifier](verify-interference-evidence.ps1) kiểm tất cả 37 bảng public. Năm bảng journal/fence/revision/audit/receipt được kiểm theo biến đổi cho phép; 32 bảng còn lại phải bằng baseline, gồm hồ sơ/ví, hợp đồng, ngân khố, escrow/ledger, inventory/token, dự trữ hạng mục, kho dự án và lịch sử quyền/quyết định. Audit/receipt tổng thể chỉ được tăng đúng số proof đối chứng, không cho thêm event/receipt khác.

Hai hợp đồng vẫn ACTIVE. Tổng tiền 4 LT, ví/ngân khố 0, escrow 4; minted 4/burned 0, 29 token và 4 đơn vị dự trữ hạng mục, kho dự án 0; không RELEASE/REFUND/CONSUME mới. Ca APPLIED đối chứng kiểm khớp UUID/actor/contract/event/position/fingerprint và receipt đã hoàn tất.

Năm ca lỗi chỉ khóa hợp đồng sàn; hợp đồng đường không liên quan không có fence. Submit/abandon-refund bị từ chối trên runtime live: `if03/*-denied.txt`. if08 bổ sung complete của chủ hợp đồng trên bản restore lạnh của preplace; không thay đổi 37 bảng hoặc mở khóa. Năm fence B01.2 giữ nguyên, không Force Unlock hoặc tự tạo lịch sử.

Sau quan sát live, world được flush rồi hard-kill đúng PID/runtime thử; restart đọc lại STONE/AIR/COBBLESTONE theo ca. Snapshot live bằng snapshot sau kill và sau restart ở cả 8 ca, bao gồm fence, journal/audit/receipt và mọi bảng tài sản. Báo cáo recovery TSV riêng từng ca nằm ở `if03/*-recovery.tsv`. Hard-kill tại đây kiểm độ bền kết quả can thiệp đã được đối soát, không phải kill trong transaction đang chạy.

`if03/*-baseline.jsonl`, `*-live.jsonl`, `*-killed.jsonl`, `*-restarted.jsonl` là hiện vật DB; `*-live-world.txt`, `*-restart-world.txt` là quan sát world; log stdout/stderr, marker, PID và binary hashes đi kèm. Log console Windows có một số chữ Việt bị thay bằng dấu hỏi; event/contract/position/expected/actual ASCII và dữ liệu DB UTF-8 vẫn đủ đối chiếu. Không sửa log gốc để làm đẹp bằng chứng.

## Sao lưu và chạy lại

Nguồn ACTIVE được sao chép thành `backups/b01-interference-source-001`; [manifest](interference-environment/source-manifest.json) giữ trong hồ sơ. Mỗi ca restore tạo DB/runtime mới từ bundle đã kiểm mọi checksum và danh sách file. DB `cv_b01_if03_*`, runtime `run-b01-if03-*`, Minecraft loopback cổng 25594, PostgreSQL thử `.tools/b01-pgdata` ở 127.0.0.1:55439.

Các runtime đã dừng sau đối soát; PostgreSQL thử cũng đã stop sau khi kiểm không còn client khác: [log](interference-environment/postgres-shutdown.txt). Data directory và DB được giữ. Hash core tại từng runtime đã kiểm trực tiếp trên đĩa: [danh sách](interference-environment/runtime-core-hashes.json).

Đã tạo thêm cold backup nhất quán DB/world của preplace sau stop/drain cho ca thanh toán: `backups/b01-interference-fenced-preplace`, [log backup](interference-environment/fenced-backup.txt). if08 dùng DB/runtime mới từ bản đó. Binary được giữ trong `backups/b01-interference-final-binaries/`; [hash lưu trữ](interference-environment/retained-binary-hashes.txt). Probe settlement if08 có hash riêng; không coi các probe khác build là cùng binary. Core trong if03/if04/if05/io13/if07/if08 cùng SHA-256 nêu trên.

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk25/jdk25.0.4_10').Path
$maven = (Resolve-Path '.tools/maven/apache-maven-3.9.11').Path
& validation/b01/build-probe.ps1 -JavaHome $env:JAVA_HOME -MavenHome $maven
& validation/b01/build-interference.ps1 -JavaHome $env:JAVA_HOME -MavenHome $maven
& validation/b01/run-interference-matrix.ps1 -JavaHome $env:JAVA_HOME -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -BundleDirectory (Resolve-Path 'backups/b01-interference-source-001').Path -CoreJar (Resolve-Path 'target/CuuVucCore-0.8.0-SNAPSHOT.jar').Path -RunId if09
```

Dùng RunId chưa có và PostgreSQL thử đã khởi động. Runner gọi verifier sau khi hoàn tất ma trận; `completed.txt` OBSERVED riêng lẻ chưa đủ tính PASS. Maven verify cần CUUVUC_TEST_URL/USER/PASSWORD chỉ vào DB thử riêng. `verify-interference-evidence.ps1 -EvidenceDirectory validation/b01/if03` có thể chạy lại từ hiện vật khi runtime/DB đang tắt.

if01 là lượt hiệu chuẩn: gate sleep 10 giây chạm statement_timeout; không tính các ca đó là chứng minh precommit. if02 mới tái hiện đúng cửa sổ precommit trên core cũ. if06 chọn nhầm bundle đã có hồ sơ/lệnh thử quản trị, nên dừng, không tính PASS; if07 chạy từ đúng fixture ACTIVE sạch. Các hiện vật lỗi/đối chứng giữ nguyên, không đưa vào số PASS của core cuối.

## Giới hạn để nghiệm thu

World và PostgreSQL vẫn không có transaction nguyên tử. Quan sát trước commit rồi kiểm sau commit không chứng nhận mọi biến đổi nằm giữa hai lần đọc, thay đổi được plugin phục hồi về vật liệu cũ trước khi đọc, hoặc biến đổi xảy ra sau lần kiểm cuối. Chưa kiểm plugin cố ý giả mạo identity/event, hủy/vô hiệu hóa guard, gọi trực tiếp API journal hoặc ghi DB.

Chưa chạy lại Minecraft Client trên core cuối, chưa kiểm inventory/drop vanilla, GUI cũ, Folia runtime, plugin cụ thể ngoài probe, nhiều chunk/người chơi hoặc mọi thứ tự listener. Các ca adapter không thay thế bằng chứng client. Chưa chứng nhận mất điện, crash PostgreSQL hoặc ghi dở bên trong kernel/fsync; phạm vi I/O vẫn theo B01.1, với lượt hồi quy io13 trên core mới.

B01.2 có kết quả kỹ thuật để người dùng đánh giá theo SHA commit và hiện vật cục bộ. B01 chưa đóng; B01.3 xử lý UNRECOVERABLE vẫn là bước kế tiếp được xem xét sau đánh giá, rồi mới nghiệm thu B01/B02/Pre-Alpha.
