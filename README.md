# Cửu Vực Tu Tiên — V0.6 Playable World

> Người chơi đến vì tu tiên, nhưng ở lại vì thế giới mà họ cùng nhau xây dựng.

V0.5.1 là baseline tại [tag v0.5.1](https://github.com/TheOnlyTuna/CuuVuc/tree/v0.5.1), commit `4097ac93729670a0f81d24e3869155725d08dff6`. V0.6 được phát triển riêng trên nhánh `v0.6-playable-world`; chưa thay thế baseline hoặc chứng nhận production.

## Vòng chơi đầu tiên

1. Minecraft Java 26.3 kết nối server. Gõ `/cv` để mở GUI tu vi, kho, chế tạo và thương nhân.
2. Bấm **Động phủ**: nhận đảo đầu tiên, cây non và rương gồm 16 gỗ, 32 đất, nước, lava và cuốc gỗ. Rương chỉ được dựng khi cấp đảo lần đầu; quay lại không cấp lại đồ.
3. Mang cuốc, bấm **Prison**: khai thác 32 mạch quặng trên sàn `(0..15,99,0..15)` trong world `cv_prison`. Nửa mỏ thưởng linh thạch thô, nửa thưởng huyền thiết. Quặng biến mất sau commit và tái tạo sau cooldown; DB giữ cooldown qua restart.
4. Nhấp phải bàn chế tạo `(2,100,3)` hoặc quầy barrel `(13,100,3)` để mở GUI. Có 5 linh thạch thô, bấm **Kết tinh** → 1 linh thạch vật lý. Nhấp trái nguyên liệu trong GUI để bán 1; nhấp phải để mua 1.
5. Về động phủ, dùng nguyên liệu vanilla trong rương để xây dựng. `/cv invite <UUID>` cấp quyền xây; `/cv visit <UUID-chủ>` đến thăm; `/cv revoke <UUID>` thu hồi quyền. UUID hiển thị trong `/cv catalog`. Người nhận cần có hồ sơ.

Nguyên liệu kinh tế là vật phẩm thật có PDC `cuuvuccore:token`, `owner`, `resource`, mỗi vật phẩm một đơn vị, không xếp chồng. Chúng gắn với chủ và dùng trong hệ thống chế tạo/giao dịch Cửu Vực, không dùng để đặt khối, craft vanilla, thả hoặc chuyển vào rương. Khối xây dựng vanilla vẫn di chuyển và sử dụng bình thường. GUI chỉ hiển thị thông tin; không thể kéo icon ra làm vật phẩm.

Inventory đầy thì nguyên liệu vẫn thuộc người chơi trong DB. Bấm **Kho & nhận nguyên liệu** khi có chỗ. Đây là bản đầu: nguyên liệu PDC chưa trao trực tiếp giữa người chơi, chưa có chợ, kho rương PDC, GUI mời bạn hoặc custom model/resource pack. Vật phẩm kinh tế không được kéo đổi vị trí trong inventory ở bản này; mục đích là giữ đường giao nhận được kiểm soát.

## An toàn dữ liệu

Ví, XP, số lượng nguyên liệu, ledger, request chống xử lý trùng và quota vẫn dùng transaction PostgreSQL như baseline. PDC không tự cấp giá trị kinh tế: DB xác định quyền sở hữu; một bản sao item không làm tăng số dư. Inventory là bản thể hiện của token DB. Sau chế tạo/bán, token của nguyên liệu tiêu thụ bị thu hồi trong cùng transaction. Đồng bộ loại bản sao và dựng lại vật phẩm hợp lệ; revision chặn snapshot cũ. Tối đa 36 token mỗi loại được cấp cho một người; phần vượt chỗ hiển thị nằm trong kho chờ.

Lệnh cũ `/cv craft`, `buy`, `sell`, `pay`, `profile`, `ledger`, `receipt`, `economy` vẫn dùng backend đã hardening. Tiền nguyên; mặc định toàn DB phát hành 10.000/ngày UTC, mỗi người 500. Mua/chuyển không hoàn hạn mức. Không tự phát lại request chưa rõ kết quả. Shutdown ngừng nhận I/O, chờ drain có giới hạn và lưu `plugins/CuuVucCore/shutdown-pending.properties` để đối soát.

Mỗi động phủ có slot riêng, phạm vi một chunk: `x=[slot*64,slot*64+15]`, `z=[0,15]`, toàn chiều cao. Mặc định chỉ chủ và người được mời được xây, mở rương hoặc tương tác. Quyền DB có revision; cache không nhận snapshot cũ. Nền đảo được dựng theo region scheduler, Paper lưu world trước khi DB chuyển ready. Đảo chưa ready không cho chơi; đảo đã ready không được dựng lại. Prison không cho sửa sàn/quầy hoặc đặt quặng để kiếm XP. Các world này chặn piston, nổ, lửa, portal và thay đổi khối từ entity; nước/cây không được vượt biên claim. PvP bị chặn trong hai world demo.

`cv_prison` và `cv_sky` là tên dành riêng cho plugin. World tồn tại mà thiếu dấu PDC quản lý sẽ khiến startup lỗi, không tự ghi đè. Chưa bật `folia-supported`: tạo/lưu world được xác minh trên Paper, không phải Folia. Không dùng BukkitScheduler/BukkitRunnable; I/O DB chạy async, inventory/GUI trên entity scheduler, khối trên region scheduler.

## Build và chạy

Khóa môi trường để so sánh baseline: Paper 26.3 build 166 BETA, API `26.3.build.166-beta`, Java 25, PostgreSQL 18.4, Maven Wrapper 3.9.11. Build này được kiểm tra ngày 09/10/2026, không tuyên bố là build Paper mới nhất.

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-25'
./mvnw.cmd -B -ntp clean package
$env:CUUVUC_DB_PASSWORD = 'replace-with-your-local-password'
$env:CUUVUC_TEST_PASSWORD = 'replace-with-your-test-password'
docker compose up -d --wait db
./scripts/prepare-server.ps1
```

Đọc [Minecraft EULA](https://aka.ms/MinecraftEULA), đặt `run/eula.txt` thành `eula=true` khi đồng ý. Kiểm tra config rồi chạy `./scripts/start-server.ps1 -JavaHome $env:JAVA_HOME`. Chờ `cuuvuc_database_ready`, kết nối **127.0.0.1:25579**. Server mặc định online-mode, chỉ nghe loopback. PostgreSQL native cũng dùng được: tạo DB/user/schema riêng và đổi URL/user/schema trong config. Không đặt DB vào schema chứa dữ liệu khác.

JAR: `target/CuuVucCore-0.6.0.jar`. Linux/macOS dùng `JAVA_HOME=/path/to/jdk25 sh ./mvnw -B -ntp clean package`, cùng config và JAR Paper rồi `java -Xms512M -Xmx1536M -jar paper.jar --nogui`. Script Windows chuyển JAR CuuVucCore cũ sang `run/plugin-backups`, giữ config/EULA; không dùng `/reload`.

## Nâng cấp từ baseline

Dừng mọi server dùng chung DB. Tạo backup đồng bộ theo mục bên dưới trước migration, giữ cả JAR/config và world baseline. Backup chỉ PostgreSQL không chứa công trình Minecraft.

Giữ database URL/user/schema, hạn mức và catalog baseline. Cập nhật phần `mine` theo `src/main/resources/config.yml`: **world=cv_prison**, 32 node trong chunk `(0,0)`, `y=100`. Config cũ có `world: world` sẽ bị từ chối, không tự đổi world. Không xóa hoặc đổi các resource ID/cảnh giới/giá để né kiểm tra policy.

Migration V003 thêm revision, token và động phủ; không thay catalog, ví, kho hay lịch sử V0.5.1. Migration cũ giữ nguyên; checksum/cấu trúc DB sai hoặc DB mới hơn plugin đều chặn ready. Quay lại V0.5.1 phải phục hồi **backup trước V003** vào DB trống và dùng JAR/config baseline, không hạ JAR trực tiếp lên schema v3. Phục hồi bằng `pg_restore --exit-on-error`; đối soát dữ liệu trước khi mở lại. Rollback bằng backup mất các giao dịch sau thời điểm chụp.

## Kiểm thử và CI

```powershell
docker compose --profile test up -d --wait db-test
$env:CUUVUC_TEST_URL = 'jdbc:postgresql://127.0.0.1:55433/cuuvuc_test'
$env:CUUVUC_TEST_USER = 'cuuvuc_test'
$env:CUUVUC_TEST_PASSWORD = 'replace-with-your-test-password'
./mvnw.cmd -B -ntp clean verify
```

DB test phải riêng; mỗi ca tạo/xóa duy nhất schema `cv_test_<uuid>`. Thiếu DB test là lỗi, không bỏ qua integration tests. [Kết quả V0.6](validation/V0.6-RESULTS.md) ghi tests, client và phần chưa xác minh; [RESULTS V0.5.1](validation/RESULTS.md) là lịch sử baseline.

Theo quyết định của người dùng, CI GitHub đã tắt và không phải điều kiện chốt V0.6. Kiểm chứng bằng `clean verify` cục bộ với PostgreSQL thật, hai Minecraft Client và backup/restore đồng bộ. Không cần thanh toán GitHub. Workflow được giữ để tham khảo, chỉ có trigger thủ công và đang disabled trên GitHub; không tự chạy khi push/PR. [Run cũ](https://github.com/TheOnlyTuna/CuuVuc/actions/runs/37992513554) bị chặn do billing, không có kết quả test CI.

## Backup đồng bộ DB và world

Lên lịch maintenance, tắt auto-restart và mọi writer DB khác, gõ `stop` ở console Paper, chờ process thoát và world I/O hoàn tất. Chỉ `save-all` rồi dump DB khi người chơi tiếp tục giao dịch không tạo snapshot đồng bộ. `backup-database.ps1` chỉ lưu DB; dùng hai script dưới đây cho cả server/world và DB. Bundle chứa cấu hình và dữ liệu riêng, cần giữ ngoài Git và trong nơi lưu trữ có quyền truy cập phù hợp.

```powershell
./scripts/backup-server.ps1 -ServerDirectory ./run -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -HostName 127.0.0.1 -Port 5432 -DatabaseName cuuvuc -UserName cuuvuc -OutputDirectory ./backups/paired-001
./scripts/restore-server.ps1 -BundleDirectory ./backups/paired-001 -ServerDirectory ./backups/restored-server-001 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -HostName 127.0.0.1 -Port 5432 -DatabaseName cuuvuc_restored_001 -UserName cuuvuc
```

Thay tham số bằng cấu hình thực tế; cung cấp mật khẩu qua PGPASSWORD/pgpass. Output backup, đích server và DB restore phải mới; restore kiểm tra toàn bộ checksum trước khi tạo DB và cập nhật URL DB ở config bản sao. Không tự khởi động, xóa DB hoặc ghi đè server gốc. Giữ bản gốc dừng khi chạy bản restore trên cùng port; sau khi bật server, kiểm tra ready, ví/ledger, kho/token, claims, công trình và đăng nhập lại. Nếu lỗi, giữ nguyên output để điều tra rồi chọn tên đích mới sau khi xử lý nguyên nhân.

[Kiểm thử hai client và backup/restore](validation/V0.6-MULTICLIENT-RESTORE.md) ghi phạm vi đã đạt và bằng chứng. Offline mode chỉ bật trong bản sao thử loopback theo yêu cầu người dùng; UUID offline khác online, không tự chuyển hồ sơ. Chưa thử backup nóng hoặc crash recovery.

Tham chiếu: [Paper PDC](https://docs.papermc.io/paper/dev/pdc/), [custom InventoryHolder](https://docs.papermc.io/paper/dev/custom-inventory-holder/), [teleportAsync](https://docs.papermc.io/paper/dev/entity-teleport/), [hướng dẫn scheduler được cung cấp](docs/references/Folia-scheduler-guide.md), [audit baseline](docs/references/CuuVucCore-V0.5-Technical-Audit.md). [V3-POC.md](V3-POC.md) là kế hoạch riêng; **không có heightmap V3 được cung cấp hoặc nhập**.
