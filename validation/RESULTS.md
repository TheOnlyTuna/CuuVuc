# Kết quả xác minh CuuVucCore V0.5.1

Ngày 10/10/2026, Asia/Bangkok. Windows 11, Corretto JDK 25.0.4.1+10, Maven 3.9.11, PostgreSQL 18.4 thật trên loopback 55439. Paper 26.3 build 166 BETA/API 26.3.build.166-beta được khóa theo phiên kiểm tra 09/10; không tuyên bố build này là mới nhất. Minecraft Client Java 26.3 chạy qua Modrinth/Fabric, tài khoản TyninWorld được server online-mode xác thực. Server thử chỉ nghe 127.0.0.1:25579 và bật whitelist.

| Kiểm tra | Kết quả |
|---|---|
| clean verify với mã nguồn/JAR cuối | BUILD SUCCESS; 15,378 giây |
| DomainTest | 4 đạt, 0 lỗi, 0 bỏ qua |
| GameDatabaseIT | 26 đạt, 0 lỗi, 0 bỏ qua; PostgreSQL thật |
| Paper tải JAR cuối | CuuVucCore 0.5.1 enabled; database-ready=true |
| Client Survival | Đào 5 lần → 5 nguyên liệu và 50 XP; craft → 1 linh thạch; sell → 18 tiền; buy → còn 8 tiền và 1 huyền thiết |
| Kho Minecraft | Console đọc inventory: chỉ có cuốc; nguyên liệu ở kho DB, không xuất vật phẩm vanilla |
| Client sau restart JAR cuối | Đăng nhập lại được; receipt trả kết quả bán +18 đã commit |
| Stop khi giao dịch đang chạy | Trì hoãn UPDATE balance 3 giây bằng trigger thử; stop nhận cùng giây với lệnh sell; drain hoàn tất sau 3 giây |
| Đối soát sau stop | Ví 13 = tổng ledger 13; XP 50; kho nguyên liệu 0; phát hành 23, tiêu hủy 10 |
| Báo cáo shutdown | drained=true; giữ request chưa trả lời cho người chơi |
| Restart sau dọn fixture | Ready; schema v2; request bán trong lúc stop vẫn tồn tại |
| Backup/restore | pg_dump custom thành công; phục hồi bằng pg_restore --exit-on-error vào database thử riêng cv_restore_v051 |

## Kiểm thử giao dịch và migration

26 integration tests gồm vòng mine/craft/sell/buy/pay, rollback khi thiếu tiền/nguyên liệu và overflow, chống request trùng, tranh chấp ví/mỏ, cooldown qua kết nối lại, chuyển tiền ngược chiều, ledger bất biến và tham số sai. Các ca hardening kiểm tra đọc không tạo operations, receipt giới hạn đúng chủ sở hữu, hạn mức toàn DB khi 8 người cạnh tranh, hạn mức mỗi người, mua/chuyển không hoàn quota, request lỗi hoặc replay không tính quota thêm và ngày UTC trước không tính vào ngày mới.

Migration kiểm tra v1 → v2 giữ dữ liệu, backfill phát hành cũ kể cả đã vượt cap, checksum/cấu trúc/schema tương lai/catalog không khớp phải lỗi, rollback DDL và migration history khi nâng cấp thất bại. SQLSTATE 40001 được tạo bằng trigger PostgreSQL thật để xác minh retry một lần chỉ commit một giao dịch và lỗi kéo dài hết 3 lần không thay đổi kho/quota. Row lock thật kiểm tra drain chờ I/O; timeout giữ context pending và pool đóng sau I/O cuối. Các fault SQL trong log là fixture có assert, không phải lỗi bị bỏ qua.

## Bằng chứng client và shutdown

Vòng đào/craft/sell/buy chạy trên V0.5.1 trước khi thêm file báo cáo shutdown bền. Sau bổ sung này đã chạy lại toàn bộ 30 tests, cài JAR cuối, reconnect bằng client, tra receipt và chạy giao dịch sell trong lúc stop. Không chỉnh trực tiếp ví, XP hoặc kho để tạo kết quả vòng chơi; console chỉ chuẩn bị sàn, vị trí, cuốc, haste và OP trên world thử.

Receipt bán linh thạch: `3148c1e3-01aa-4dd5-97c3-af7d304ae320`. Lần tra đầu nhập nhầm UUID được từ chối, lần tra đúng trả kết quả đã commit. Request sell trong lúc shutdown: `3c2bdfb3-60b3-4f4a-a0bc-8ee138df9eb3`. File `v051-shutdown-pending.properties` lưu request này; DB và ledger xác nhận +5 tiền đúng một lần. Trigger thử được xóa trước restart; startup kiểm tra fingerprint và vào ready thành công.

Các file `v051-client-before-final.log`, `v051-client-final-server.log`, `v051-final-restart.log`, `v051-client-final-db.txt`, `v051-after-restart-db.txt`, `v051-shutdown-fixture.sql` và báo cáo pending ghi lại ca thử. JUnit XML/TXT cuối có tiền tố `v051-`; `v051-verify.log` là log build cuối. Các file không có tiền tố này thuộc kiểm thử V0.5 trước đó.

Backup thực tế: `backups/cuuvuc-20261010023226847.dump`, SHA-256 `dea1be67a736c0c116faedaab920b3e1f37f2a33a9141ae803104a895afdb358`. Backup chụp trước lần đào thứ 5; restore có XP 40, ví 0, schema 2 đúng thời điểm chụp. Dump chứa dữ liệu thử cục bộ, không đóng gói trong release. Đây là xác minh công cụ restore; migration từ V0.5 được kiểm tra riêng bằng integration test.

JAR cuối SHA-256: `efb4a350081d24a5b338b08be3123628074a528193c0595f7e3c531cd89709f1`.

## Giới hạn còn lại

Bản hardening đã qua các ca trên, chưa chứng nhận production/alpha mở đông người. Chưa thử hai client thật, claims/WorldGuard, Creative/Fortune/Silk Touch, piston/TNT trên client, mất mạng đúng lúc commit, kill process giữa transaction, DB outage qua client hoặc Folia runtime. Không suy diễn unit/integration tests thành các ca client chưa thực hiện. Hạn mức và drain timeout được kiểm tra tích hợp; chưa cố ý vượt cap bằng client thật. Docker daemon không chạy; dùng PostgreSQL native riêng, không tuyên bố compose đã chạy container.

Log Paper có lỗi bộ đếm hiệu năng Windows OSHI/Perflib không chặn startup hoặc giao dịch; không sửa registry. Server và cụm PostgreSQL riêng được dừng sau kiểm thử. Dữ liệu thử nằm trong run-v051/.tools, không nằm trong release; mật khẩu runtime không ghi vào mã nguồn. V3 vẫn chỉ là kế hoạch POC; không có heightmap được cung cấp hoặc nhập.
