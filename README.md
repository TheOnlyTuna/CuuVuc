# Cửu Vực Tu Tiên — CuuVucCore V0.5.1

Người chơi đến vì tu tiên, nhưng ở lại vì thế giới mà họ cùng nhau xây dựng.

Nguyên mẫu Java Paper: khai thác mạch quặng → nguyên liệu trong kho → kết tinh → bán lấy linh tệ → mua nguyên liệu hoặc chuyển tiền cho người chơi khác. XP khai thác nâng cảnh giới tự động. Hồ sơ, kho, tiền và thời gian hồi mỏ tồn tại qua restart.

## Phiên bản và phạm vi

- Kiểm tra ngày 09/10/2026: Minecraft Java **26.3**, Paper **26.3 build 166, BETA**, API **26.3.build.166-beta**, Java **25**, PostgreSQL **18.4**. Maven Wrapper **3.9.11**. Build beta này dành cho nguyên mẫu, chưa chứng nhận vận hành production.
- Nguồn: [Paper builds](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds), [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/), [Minecraft 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3). Metadata và checksum của build đã lưu tại `validation/paper-build.json`.
- Thiết kế cũ truy xuất được ý tưởng kết hợp Economy, Building, Prison, PvP, Skyblock và kim chỉ nam trên. Các câu trả lời thiết kế chi tiết trả về mã tham chiếu không có nội dung. Tên nguyên liệu, ngưỡng XP, giá và công thức trong bản này là **giả định V0.5 có thể chỉnh**, không phải thông số khôi phục từ bản thiết kế cũ.
- Đã làm: hồ sơ theo UUID, 5 cảnh giới, kho nguyên liệu ảo, tiền nguyên, ledger, mỏ cố định, chế tạo bằng lệnh, cửa hàng NPC bằng lệnh, chuyển tiền trực tiếp.
- Chưa làm: chợ lệnh bán giữa người chơi, vật phẩm vật lý/PDC, GUI, tông môn, claim đất, đảo Skyblock, PvP liên vùng, nhiệm vụ, toàn bộ 9 vùng, nhập terrain V3. Building vẫn là xây dựng vanilla; bản này chưa kết nối kinh tế với công trình tập thể.

## Build

Cài JDK 25 và đặt `JAVA_HOME` tới thư mục JDK. Không cần cài Maven toàn máy. Trên PowerShell:

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-25'
./mvnw.cmd -B -ntp clean package
```

Trên Linux/macOS: `JAVA_HOME=/path/to/jdk25 sh ./mvnw -B -ntp clean package`.

Kết quả: `target/CuuVucCore-0.5.1.jar`, chứa JDBC và HikariCP; Paper API không đóng gói vào JAR. `package` chạy unit tests; `verify` còn bắt buộc integration tests và **báo lỗi nếu thiếu DB test**, không tự bỏ qua.

## Chạy PostgreSQL và Paper trên Windows

1. Đặt mật khẩu riêng cho DB chạy và DB test. Mật khẩu không lưu trong config hay source:

   ```powershell
   $env:CUUVUC_DB_PASSWORD = 'replace-with-your-local-password'
   $env:CUUVUC_TEST_PASSWORD = 'replace-with-your-test-password'
   docker compose up -d --wait db
   ./scripts/prepare-server.ps1
   ```

2. Đọc [Minecraft EULA](https://aka.ms/MinecraftEULA), chỉ đặt `eula=true` trong `run/eula.txt` khi đồng ý. Script không tự chấp thuận. Phiên chạy thử trong tác vụ này đã được chủ máy chấp thuận rõ ràng.
3. Kiểm tra `run/plugins/CuuVucCore/config.yml`: URL mặc định `jdbc:postgresql://127.0.0.1:55432/cuuvuc`, user `cuuvuc`, schema `public`. Plugin yêu cầu schema có sẵn và tự tạo các bảng khi khởi động. Không trỏ vào database chứa dữ liệu không liên quan.
4. Chạy `./scripts/start-server.ps1 -JavaHome $env:JAVA_HOME`. Chờ log `cuuvuc_database_ready`. Gõ `cv` ở console để thấy `database-ready=true`.
5. Dùng Minecraft Java 26.3, kết nối **127.0.0.1:25579**. Server mặc định xác thực tài khoản chính thức. Gõ `op <tên>` ở console để chuẩn bị điểm mỏ. Khi xong, gõ `stop` để dừng an toàn.

Nếu dùng PostgreSQL cài sẵn, tạo database/user riêng rồi chỉnh URL/user trong config. Không cần Docker. Schema test phải thuộc database thử nghiệm; test tự tạo và xóa duy nhất schema `cv_test_<uuid>` của lần chạy đó. Không sửa quyền của DB hiện có.

Để chạy trên Linux, dùng cùng JAR Paper đã xác minh checksum và plugin; chép config, tạo `eula.txt`/`server.properties`, xuất biến môi trường mật khẩu, rồi chạy `java -Xms512M -Xmx1536M -jar paper.jar --nogui` trong thư mục server.

## Thử vòng chơi trong 5 phút

Trên world thử nghiệm trống, OP tạo sàn đứng bằng `/fill 6 99 6 12 99 10 stone`, rồi `/cv minebuild`. Lệnh này đặt hai điểm mỏ tại `(8,100,8)` và `(10,100,8)` nếu vị trí đang trống hoặc đúng khối mong đợi; nó từ chối ghi đè khối khác. `/tp @s 8 100 6`, `/give @s iron_pickaxe`, `/gamemode survival`.

- `/cv profile` hoặc `/cv kho`: hồ sơ, cảnh giới, XP, linh tệ, nguyên liệu.
- Đập STONE tại `(8,100,8)` bằng cuốc: +1 `linh_thach_tho`, +10 XP. Khối giữ nguyên; mỗi điểm hồi sau 3 giây theo đồng hồ DB. Không rơi vật phẩm vanilla, không áp dụng Fortune/Silk Touch, cuốc không hao độ bền trong V0.5.
- Đập IRON_ORE tại `(10,100,8)`: +1 `huyen_thiet`, +20 XP.
- Có 5 linh thạch thô: `/cv craft ket_tinh 1` → 1 linh thạch kết tinh.
- `/cv sell linh_thach 1` → +18 linh tệ; `/cv buy huyen_thiet 1` → -10 linh tệ và +1 huyền thiết.
- `/cv catalog` hiển thị tài nguyên, giá, công thức và UUID của bạn. Người nhận cần đã vào server hoặc dùng `/cv start`; `/cv pay <UUID-người-nhận> 5` chuyển 5 linh tệ.
- `/cv ledger` xem 10 giao dịch tiền gần nhất. Thoát/vào lại để kiểm tra dữ liệu giữ nguyên.
- `/cv receipt <request UUID>` tra kết quả đã commit của chính mình. `/cv economy` dành cho OP, xem phát hành, tiêu hủy và hạn mức ngày UTC.

Số lượng mỗi lệnh từ 1 đến 1000. Nguyên liệu là số dư trong PostgreSQL, **không xuất ra inventory Minecraft**. Chỉ các tọa độ cấu hình mới thưởng XP/nguyên liệu; quặng người chơi đặt ở nơi khác không tạo linh tệ. Trong demo chỉ dùng các khối mỏ bất biến STONE/IRON_ORE.

## Kiểm thử tự động

```powershell
$env:CUUVUC_DB_PASSWORD = 'replace-with-your-local-password'
$env:CUUVUC_TEST_PASSWORD = 'replace-with-your-test-password'
docker compose --profile test up -d --wait db-test
$env:CUUVUC_TEST_URL = 'jdbc:postgresql://127.0.0.1:55433/cuuvuc_test'
$env:CUUVUC_TEST_USER = 'cuuvuc_test'
./mvnw.cmd -B -ntp clean verify
```

Báo cáo JUnit: `target/surefire-reports` và `target/failsafe-reports`. Bằng chứng phiên triển khai ở `validation/`; đọc `validation/RESULTS.md` để phân biệt phần đã chạy và phần chưa kiểm thử bằng client.

## Quy tắc dữ liệu và scheduler

Các quy tắc chi tiết nằm trong Javadoc cạnh triển khai. `catalog.properties` là nguồn duy nhất cho định nghĩa kinh tế. Thay catalog/config cần rebuild/restart tương ứng; không dùng `/reload`. Giữ nguyên resource ID khi đã có dữ liệu. Cảnh giới tính từ XP và catalog, không lưu một bản stage dễ sai lệch.

Ledger ghi thay đổi tiền trong cùng transaction với ví/kho; NPC_SELL tạo tiền và NPC_BUY tiêu tiền có chủ đích. Chuyển tiền ghi hai dòng cộng/trừ cân bằng. Tiền bắt đầu từ 0; không có lệnh cấp tiền cho người chơi. Không dùng số thực. Mặc định mỗi database phát hành tối đa 10.000 linh tệ/ngày UTC, mỗi người 500; mua hoặc chuyển tiền không hoàn lại hạn mức phát hành. Ví, kho, ledger, operation và bộ đếm hạn mức cùng commit hoặc rollback. Đọc profile/ledger/receipt không tạo operation mới.

Kiến trúc theo tài liệu Folia scheduler đã tải lên và [hướng dẫn Paper](https://docs.papermc.io/paper/dev/folia-support/). V0.5 nhắm **Paper**; chưa bật `folia-supported` vì chưa thử trên runtime Folia. Không có BukkitScheduler/BukkitRunnable. Kiểm thử nhiều vùng, đăng xuất, teleport, shutdown và tương tác plugin bảo vệ vùng là điều kiện trước khi công bố hỗ trợ Folia.

Giới hạn vận hành: ledger chống sửa/xóa thông thường, không chống DBA vô hiệu hóa trigger. Operation IDs được lưu bền và chưa có cơ chế lưu trữ lịch sử dài hạn; chưa xóa chúng để tránh làm mất chống xử lý trùng. Crash sau commit nhưng trước thông báo có thể khiến người chơi chưa thấy kết quả; tra receipt/profile/ledger trước khi nhập lại lệnh mới. Retry nội bộ tối đa 3 lần với cùng request ID cho lỗi kết nối, serialization/deadlock và DB tạm dừng; lỗi timeout câu SQL không tự retry. Một lệnh người chơi gõ lại tạo request mới, không phải replay của request cũ.

## Nâng cấp và vận hành V0.5.1

Dừng toàn bộ server dùng chung DB trước khi nâng cấp. Backup DB và giữ JAR/config/catalog cũ. Trên Windows, dùng `PGPASSWORD` hoặc pgpass, rồi:

```powershell
./scripts/backup-database.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -HostName '127.0.0.1' -Port 55432 -DatabaseName 'cuuvuc' -UserName 'cuuvuc'
./mvnw.cmd -B -ntp clean package
./scripts/prepare-server.ps1
```

Script chuẩn bị chuyển các JAR CuuVucCore cũ ra `run/plugin-backups`, giữ config/EULA đang có. Với config V0.5, bổ sung `economy.server-daily-mint: 10000`, `economy.player-daily-mint: 500` và `shutdown.drain-timeout-ms: 15000` theo cấu trúc YAML trong `src/main/resources/config.yml`. Thiếu hoặc sai cấu hình làm startup thất bại; không tự chọn hạn mức khác.

Migration nằm trong `src/main/resources/db/migrations`, chạy tuần tự trong một transaction dưới advisory lock. Baseline V0.5 chỉ được nhận khi cấu trúc đúng fingerprint v1; migration v2 giữ ví/kho/ledger và tính lại lượng đã phát hành theo ngày UTC. Nếu ngày cũ đã vượt hạn mức mới, số tiền vẫn giữ nguyên nhưng không được phát hành thêm trong ngày đó. Checksum migration, cấu trúc DB, phiên bản tương lai hoặc catalog/hạn mức không khớp đều chặn trạng thái ready. Không sửa file migration đã chạy hay sửa history để vượt kiểm tra.

Catalog và policy được khóa theo database. Muốn đổi giá/công thức/cảnh giới/hạn mức cần một migration dữ liệu được rà soát riêng, đồng bộ mọi server; sửa config đơn thuần sẽ bị từ chối. Không hạ JAR V0.5 lên DB đã migration. Muốn quay lại, dừng server, phục hồi backup **trước nâng cấp** vào database trống bằng `pg_restore --exit-on-error`, trỏ config về DB phục hồi và dùng JAR/config cũ. Cần kiểm tra restore trước khi dùng; mất các giao dịch phát sinh sau thời điểm backup là hệ quả của rollback bằng backup.

Khi `stop`, plugin ngừng nhận yêu cầu, hủy tác vụ chưa chạy và chờ I/O đang chạy tối đa thời gian cấu hình (1–30 giây). `plugins/CuuVucCore/shutdown-pending.properties` lưu request chưa trả lời cùng trạng thái drain; giữ file này trước lần stop tiếp theo và đối chiếu bằng receipt sau restart. `drained=true` chỉ nói I/O đã kết thúc, không khẳng định mọi request thành công. Hết hạn chờ, pool đóng sau khi I/O cuối kết thúc; nếu process bị kill, transaction chưa commit có thể rollback. Không tự phát lại request chưa rõ kết quả. Báo cáo shutdown không thay thế ledger/receipt.

Kế hoạch chiều cao thế giới và nhập địa hình nằm riêng trong `V3-POC.md`. **Không có file heightmap V3 được cung cấp hoặc nhập trong bản này.**

## Tài liệu tham chiếu và bản tải xuống

- [Audit V0.5](docs/references/CuuVucCore-V0.5-Technical-Audit.md) là đánh giá của bản trước hardening; kết quả hiện tại ở [validation/RESULTS.md](validation/RESULTS.md).
- [Hướng dẫn Folia scheduler được cung cấp](docs/references/Folia-scheduler-guide.md) là tài liệu tham chiếu. Plugin vẫn chỉ được xác minh trên Paper.
- [Release V0.5.1](https://github.com/TheOnlyTuna/CuuVuc/releases/tag/v0.5.1) chứa JAR và ZIP mã nguồn kèm bằng chứng kiểm thử, checksum SHA-256.

Repository không chứa world thử, dump database, mật khẩu, JDK hoặc PostgreSQL cài trên máy. Đường dẫn máy cá nhân trong bản sao log/báo cáo đã được thay bằng LOCAL_USER; các số liệu kiểm thử được giữ nguyên. Không có heightmap V3 để nhập hoặc phát hành.
