# B01.1 — Crash tại Chunk/Region I/O

**TECHNICAL PASS trong phạm vi 4 ranh giới terrain region đã kiểm thử; chờ đánh giá B01.1. B01 vẫn OPEN.** Nhánh `v0.8-khai-son-lap-phai`, nền `2cb75ff`; không thay đổi logic production, commit, merge `main`, bật CI hoặc phát triển gameplay V0.9. B01.2/B01.3/B02 chưa triển khai trong lượt này.

## Kết quả trên JAR core cuối

Ngày chạy: 12/10/2026, giờ Asia/Bangkok. Paper 26.3 build 166 (`cc7b98a`), Corretto 25.0.4.10.1, PostgreSQL 18.4; Maven 3.9.11. PostgreSQL thử có `fsync=on`, `synchronous_commit=on`, `full_page_writes=on`; Paper có `sync-chunk-writes=true`. Đây là kill riêng tiến trình Java bằng `Stop-Process -Force`, không phải stop mềm, crash PostgreSQL hoặc mô phỏng mất điện.

Core SHA-256: `C5E32B80AE193CF6069EE72CFD66A6DB46EF80BA09D6457D9479D00C2CDCC02B`.

- **93/93 Maven tests PASS:** 4 pure-data + 89 PostgreSQL integration, 0 skipped. [Log](io-environment/maven-verify.log), [DomainTest](io-environment/DomainTest.xml), [GameDatabaseIT](io-environment/GameDatabaseIT.xml).
- **4/4 hard-kill I/O PASS**, baseline flush rõ ràng: [kết quả](io11/completed.txt), [PID kill](io11/kills.txt), [hash binary](io11/binary-hashes.txt).
- **Đối soát toàn bộ 37 bảng DB PASS ở cả 4 ca:** snapshot trước kill bằng snapshot sau kill; startup chỉ thay đổi fence và revision như dự kiến. [Đối soát](io11/full-db-reconciliation.txt).
- **9/9 hard-kill hồi quy PASS** trên cùng core: [io08](io08/completed.txt), [hash](io08/binary-hashes.txt).
- **7/7 hard-kill journal hồi quy PASS** trên cùng core: [io09](io09/completed.txt), [hash](io09/binary-hashes.txt). `missingproof` là ca tiêm thiếu receipt có chủ ý trên DB thử, không phải bằng chứng crash DB.

Probe I/O mới bổ sung đúng một lệnh `BASELINE_FLUSH`; probe hồi quy dùng binary cũ đã build trước thay đổi đó. Hash riêng của từng probe được lưu, không coi hai binary thử nghiệm là một. Core giống nhau trong các ma trận.

## Ranh giới kill thực tế

Agent độc lập chỉ chạy trong runtime `run-b01-io*`. Agent chèn hook vào `RegionFile.write(ChunkPos, ByteBuffer)` của Paper trong bộ nhớ; không sửa JAR Paper/core trên đĩa. Hook bootstrap dùng riêng để tránh classloader Paper/plugin. Bộ kiểm thử lọc **terrain file `cv_sky/region/r.0.0.mca`, chunk (4,0), block (67,100,5)**, không bắt nhầm POI/entity file hoặc world khác.

Marker có phase, PID, UTC, tên thread, đường dẫn region, tọa độ chunk, số byte trả về của payload write và stack. Marker được ghi riêng bằng `force(true)` rồi giữ worker tối đa 45 giây; watcher kiểm marker/PID/command line/runtime trước kill. `force(true)` của marker **không chứng minh region đã fsync**.

| Ca | Vị trí chốt trong đường ghi thật | World sau restart | Fence |
|---|---|---|---|
| payloadbefore | Ngay trước `FileChannel.write(ByteBuffer,long)` của payload | AIR | MISMATCH, riêng hợp đồng sàn |
| payloadafter | Ngay sau payload write trả về, trước cập nhật offset/header | AIR | MISMATCH, riêng hợp đồng sàn |
| headerbefore | Ngay trước gọi `writeHeader()`, payload write đã trả về | AIR | MISMATCH, riêng hợp đồng sàn |
| headerafter | Ngay sau `writeHeader()` trả về | COBBLESTONE | CONFIRMED, không tạo fence |

Marker: [trước payload](io11/payloadbefore-marker.txt), [sau payload](io11/payloadafter-marker.txt), [trước header](io11/headerbefore-marker.txt), [sau header](io11/headerafter-marker.txt). Stack ghi `Paper I/O Worker`, `RegionFile.write`, `RegionFile$ChunkBuffer.moonrise$write`, `RegionFileStorage.moonrise$finishWrite` và `MoonriseRegionFileIO$ChunkIOTask.runWriteIO`.

Không sử dụng `WorldSaveEvent` làm bằng chứng I/O. Callback đó vẫn được chạy lại như một ca hồi quy riêng trong io09, với phạm vi trước ghi chunk giữ nguyên.

Baseline: restore ACTIVE fixture, CLEAR ô thử, gọi **`world.save(true)` và chờ trả về**, chụp DB/region; sau đó JPLACE chuẩn bị INTENT, đổi block trong world rồi commit APPLIED/audit/receipt. Lượt save bị kill vẫn gọi **`world.save()` bình thường**. Đường binary đã được kiểm tra: `World.save()` mặc định gọi `save(false)`; `save(true)` đi qua flush I/O/region storages. [Đường ghi RegionFile](io-environment/RegionFile.javap.txt), [CraftWorld](io-environment/CraftWorld.javap.txt), [ChunkHolderManager](io-environment/ChunkHolderManager.javap.txt).

JPLACE là fixture tiêm thay đổi và gọi journal thật, **không phải thao tác native Minecraft Client**. APPLIED ghi nhận thay đổi đã quan sát ở thời điểm commit; không được hiểu là world đã lưu bền. Việc payload mới có thể được ghi nhưng restart vẫn đọc AIR trước khi header cập nhật phù hợp với đường ghi được kiểm tra; không mở rộng kết luận sang mọi layout region hoặc mọi crash.

## Đối soát tài sản và bằng chứng

Mỗi ca giữ đúng **1 APPLIED, 1 PLACE audit, 1 block receipt**, liên kết event UUID, actor, contract, loại sự kiện và vị trí khớp nhau. Không sinh BREAK, audit/receipt bổ sung hoặc COMPLETE receipt. Cả 37 bảng đều được so dữ liệu đầy đủ, không chỉ đếm các sự kiện cuối.

Hai hợp đồng giữ ACTIVE; ví người chơi/ngân khố 0 LT, escrow 4 LT, tổng tiền 4 LT, minted 4/burned 0. Không RELEASE/REFUND/CONSUME mới; giữ 29 item token, 4 đơn vị dự trữ hạng mục, kho dự án 0; các TRANSFER tài nguyên cân bằng. [Assertions SQL](io-assertions.sql), kết quả từng ca `io11/*-assertions.txt`.

Ba ca MISMATCH tạo đúng một fence cho hợp đồng `279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3`; hợp đồng đường không liên quan không bị fence. DB từ chối submit và abandon/refund bằng `RECOVERY_REQUIRED`: `io11/*-denied.txt`. Không mở fence, sửa world, tự tạo PLACE/BREAK hoặc thanh toán để hợp thức hóa trạng thái sau crash. Ca headerafter giữ world khớp proof, không tăng revision và không tạo fence.

Bằng chứng từng ca trong `io11/`:

- `*-baseline.jsonl`, `*-prekill.jsonl`, `*-killed.jsonl`, `*-restarted.jsonl`: toàn bộ dữ liệu 37 bảng trong transaction snapshot.
- `*-baseline-world-files.json`, `*-killed-world-files.json`, `*-restarted-world-files.json`: độ dài/SHA-256 các terrain region của cv_sky; snapshot sau kill chụp khi tiến trình đã chết, trước restart.
- `*-world.txt`: block do runtime đọc lại; `*-recovery.tsv`: đánh giá startup, gồm proof journal và world/audit.
- `*-marker.txt`, `kills.txt`, log stdout/stderr trước/sau: vị trí kill và tiến trình thật.
- `*-killed-backup-hashes.txt`: SHA-256 bản region bị kill và dump DB giữ ngoài thư mục validation.

Hash online baseline/restarted dùng file share read/write để đọc file Paper đang mở, không coi đó là cold backup của toàn bộ world. Bản region/dump sau kill mới là ảnh hiện vật trước startup; đối soát block sử dụng runtime sau restart.

## Sao lưu và cách chạy lại

Bản gốc `backups/v08-three-player-active-001` không bị sửa. Đã sao chép thành `backups/b01-io-source-001`; mỗi restore kiểm SHA-256 và danh sách file theo manifest trước khi tạo DB/runtime mới. [Manifest nguồn giữ trong bằng chứng](io-environment/source-manifest.json). Không ghép world/DB đang chạy để tự nhận là backup nhất quán.

PostgreSQL riêng ở `.tools/b01-pgdata`, bind `127.0.0.1:55439`. Bốn runtime cuối `run-b01-io11-{payloadbefore,payloadafter,headerbefore,headerafter}`, bốn DB `cv_b01_io11_*`, bind Minecraft `127.0.0.1:25593`. Runtime I/O đã stop mềm sau đối soát; PostgreSQL thử cũng đã stop sau khi xác nhận không còn client khác: [log](io-environment/postgres-shutdown.txt). Data directory, DB và hiện vật được giữ để điều tra. Bản sau kill ở `backups/b01-io11-<case>-killed/` có `r.0.0.mca` và `database.dump`. Binary cuối giữ ở `backups/b01-io-final-binaries/` với [hash](io-environment/retained-binary-hashes.txt).

Trong PowerShell, dùng JDK25/Maven của dự án, DB thử riêng và RunId mới:

```powershell
& 'C:/Program Files/PostgreSQL/18/bin/pg_ctl.exe' -D (Resolve-Path '.tools/b01-pgdata').Path -l '.tools/b01-postgres.log' -o '-p 55439 -h 127.0.0.1' start
$env:JAVA_HOME = (Resolve-Path '.tools/jdk25/jdk25.0.4_10').Path
$maven = (Resolve-Path '.tools/maven/apache-maven-3.9.11').Path
& "$maven/bin/mvn.cmd" -B -ntp -f validation/b01/io-agent/pom.xml package
& validation/b01/build-probe.ps1 -JavaHome $env:JAVA_HOME -MavenHome $maven
& validation/b01/run-io-matrix.ps1 -JavaHome $env:JAVA_HOME -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -BundleDirectory (Resolve-Path 'backups/b01-io-source-001').Path -ProbeJar (Resolve-Path 'target/CrashProbe.jar').Path -RunId io12
```

`verify-io-evidence.ps1 -EvidenceDirectory validation/b01/io11` chạy lại đối soát từ hiện vật, không cần mở runtime. Maven `verify` cần CUUVUC_TEST_URL/USER/PASSWORD trỏ DB thử riêng; không trỏ về DB vận hành. Tool archive hashes: [công cụ](io-environment/tool-archive-hashes.txt); nguồn tải chính thức [Corretto](https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/downloads-list.html), [Maven](https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/).

## Phạm vi chưa kiểm chứng và điểm dừng

- Kill **bên trong một lời gọi OS write**, partial/torn write, sector lỗi, ổ đầy hoặc crash trong `FileChannel.force/fsync`: chưa kiểm chứng. Hook hiện tại giữ ở ranh giới lời gọi, không ở trong kernel.
- Mất điện, hỏng ổ đĩa, crash PostgreSQL, filesystem/network storage khác: chưa kiểm chứng; các cờ sync không thay thế thử nghiệm.
- Terrain chunk bình thường (4,0), một PLACE: đã thử. BREAK tại chính các marker I/O mới, nhiều chunk đồng thời, external oversized `.mcc`, POI/entity/playerdata và Folia runtime: chưa thử ở ma trận này. BREAK/chuỗi PLACE-BREAK-PLACE trong ma trận cũ là hồi quy, không thay thế các ca I/O đó.
- Minecraft Client trên **core SHA-256 mới ở trên chưa chạy lại**. Bằng chứng client tại checkpoint cũ được giữ nguyên và không tính PASS cho binary mới. Phiên công cụ này không có điều khiển native Minecraft; cần lượt client riêng trước nghiệm thu B01. DB denial từ probe không thay thế kiểm tra GUI/callback client.
- Không chứng nhận bảo toàn mọi inventory/vật phẩm vanilla khi process crash; các tài sản DB/PDC nêu trên được đối soát trong fixture này.
- Plugin interference thuộc B01.2; xử lý thủ công UNRECOVERABLE thuộc B01.3. Chưa triển khai song song.

Các lượt dựng agent lỗi `io01`–`io07` không tính PASS: frame/bytecode, bind port của runtime thử, accessor ChunkPos, classloader hook và encoding câu SQL. Hiện vật/log giữ ở `backups/b01-io-setup-attempts/`, [hash](io-environment/setup-attempt-hashes.json). `io10` là lượt chẩn đoán 4/4 trước khi bổ sung baseline flush; chỉ `io11` dùng làm kết quả I/O chính thức.

Đầu ra này đủ để đánh giá **phạm vi B01.1 đã kiểm thử**, chưa phải quyết định đóng B01. Sau đánh giá B01.1 mới chuyển B01.2, B01.3, nghiệm thu B01, B02 Benchmark và đánh giá Pre-Alpha.
