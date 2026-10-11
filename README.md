# Cửu Vực Tu Tiên — V0.8 Khai Sơn Lập Phái (đang phát triển)

> Người chơi đến vì tu tiên, nhưng ở lại vì thế giới mà họ cùng nhau xây dựng.

Phiên bổ sung trước Alpha đã kiểm chứng Builder Survival xây hai template, quyền vùng/vật liệu và thu hồi quyền trên sàn, audit 15 PLACE/3 BREAK và restart mềm trên cùng JAR. [Bằng chứng và giới hạn](validation/v08/prealpha-builder/RESULTS.md). Hồ sơ mới chờ người dùng đánh giá; B01–B06 vẫn mở, chưa nâng mức nghiệm thu toàn bộ V0.8.

V0.5.1 là baseline tại [tag v0.5.1](https://github.com/TheOnlyTuna/CuuVuc/tree/v0.5.1), commit `4097ac93729670a0f81d24e3869155725d08dff6`. Người dùng đã chấp nhận V0.6 là Playable Prototype và V0.7 là nền tảng phát triển tiếp ngày 11/10/2026. V0.7 giữ trên `v0.7-living-world`; V0.8 phát triển ở `v0.8-khai-son-lap-phai`. Không tự động merge `main` và chưa chứng nhận production. [Backlog](BACKLOG.md) giữ các giới hạn đã chấp nhận; [kế hoạch V0.8](V0.8-PLAN.md) mô tả milestone đầy đủ. Phase 1 đã nghiệm thu Gameplay Prototype tại `12fdc25`; Phase 2 đã nghiệm thu Controlled Gameplay Prototype tại `18d13ee`. Phase 3 đã nghiệm thu Controlled Gameplay Prototype tại `acd2e48` (70/70, 25/25 DB, 105/105 world). Phase 4 đã được chấp nhận tại `631f5a3`, có 78/78 tests và phục hồi khi còn dự trữ. [Phase 5](validation/v08/phase5/RESULTS.md) đã chạy ba client góp/thuê/nghiệm thu/trả công, cold restore có tài sản 31/31 DB và 109/109 world, restart và đối soát; công trình dựng console theo yêu cầu, chưa thay ca xây tay hoặc commit/resync. **V0.8 ACCEPTED WITH SCOPE LIMIT — Controlled Gameplay Prototype tại checkpoint `189bb27`, 5/5 phases đã được đánh giá.** Trước Alpha ưu tiên client xây/phá, quyền vùng và audit, sau đó B01/B02. Các giới hạn trong báo cáo phát hành vẫn giữ nguyên; không merge main hoặc chứng nhận production-ready.

## Giao nhận nguyên liệu — bước đầu V0.8

Dùng `/cv transfer <UUID-người-nhận> <resource> <1..1000>` để mở GUI xác nhận. `/cv catalog` cho biết resource và UUID của mỗi người. Kiểm tra UUID, số lượng rồi bấm **Xác nhận chuyển**; đóng cửa sổ trước khi xác nhận không chuyển tài sản. Người nhận cần hồ sơ, có thể offline. `/cv materials` xem mười giao nhận gần nhất; `/cv receipt <request>` đối soát kết quả khi chưa rõ commit. Không mở giao dịch mới thay thế một giao dịch chưa rõ kết quả; bấm lại cùng cửa sổ dùng cùng request ID.

DB khóa hai hồ sơ theo thứ tự, kiểm tra revision nguồn và số lượng, trừ/cộng kho, thu hồi token resource của cả hai bên, tăng revision và ghi hai bút toán `resource_ledger` cùng receipt trong một transaction. Bút toán bất biến; replay không chuyển lần hai. Giao nhận không thay đổi tiền, XP hoặc quota. Nếu kho nguồn đổi sau khi mở cửa sổ, mở lại để xác nhận mới. GUI chuyển theo số lượng kho DB, không nhận ItemStack hay PDC tùy ý làm tài sản.

Sau commit, Inventory nguồn được đồng bộ; người nhận online được yêu cầu đồng bộ trên entity scheduler. Nếu người nhận đang có thao tác khác hoặc máy chủ đang dừng, số lượng vẫn nằm trong DB: mở `/cv kho` hoặc đăng nhập lại để nhận bản thể hiện. Inventory đầy không hoàn trả hay làm mất nguyên liệu. PDC cũ không cấp thêm giá trị. Chưa mở thả item hoặc chuyển PDC qua rương; Phase 4 nhận đóng góp vào kho dự án bằng transaction DB. Ngân khố chỉ nhận Linh Tệ. [Báo cáo Phase 1](validation/v08/RESULTS.md) ghi 53/53 test, hai client trên JAR cuối và phục hồi DB/world; các giới hạn được phân biệt rõ. Người dùng đã nghiệm thu Phase 1 Gameplay Prototype tại `12fdc25`; chưa chốt toàn bộ V0.8.

## Tông Môn — V0.8 Phase 2

`/cv sect` mở danh sách thành viên, lời mời và mười sự kiện gần nhất. Thành viên phân trang; lời mời hiển thị tối đa chín gần nhất mỗi nhóm. `/cv sect create <tên>` mở xác nhận thành lập (3–32 chữ/số/khoảng trắng/_/-). `/cv sect invite <UUID>` gửi lời mời có hiệu lực một giờ theo giờ DB; người nhận dùng GUI hoặc `/cv sect accept <UUID-lời-mời>`. Tên tông môn được chuẩn hóa Unicode và không trùng khi khác hoa/thường.

Chưởng môn (LEADER) được mời/hủy lời mời, đổi MEMBER/ELDER và loại thành viên. Quản sự (ELDER) chỉ mời/hủy lời mời; thành viên (MEMBER) xem lịch sử và rời tông môn. Chưởng môn chưa thể rời, chuyển chức hoặc giải tán. Dùng `/cv sect role <UUID> <ELDER/MEMBER>`, `/cv sect kick <UUID>`, `/cv sect cancel <UUID-lời-mời>`, `/cv sect leave`; mọi thay đổi đều mở GUI xác nhận. Chưởng môn có thể bấm trái vào thành viên để đổi quyền, bấm phải để loại.

Một người chỉ thuộc một tông môn: khóa hồ sơ và PK `sect_members.player_id` bảo vệ cả tranh nhận lời mời từ hai tổ chức lẫn thành lập/nhận lời mời đồng thời. Mọi quản trị khóa bản ghi tông môn rồi đọc lại quyền tại DB, cùng khóa với thu hồi quyền; GUI cũ không cấp quyền. Receipt và lịch sử bất biến được commit cùng thay đổi. Replay cùng request chỉ trả kết quả đã commit, không ghi thêm sự kiện. Mời trước khi thu hồi quyền vẫn là lời mời đã cấp hợp lệ cho đến khi hết hạn/hủy; thu hồi quyền chặn thao tác mới, không tự hủy lịch sử đã commit.

Phase 2 không cấp quyền xây dựng hoặc kho dự án; ngân khố được bổ sung riêng trong Phase 3 bên dưới. [Bằng chứng Phase 2](validation/v08/phase2/RESULTS.md) giữ rõ giới hạn chưa lặp toàn bộ gameplay trên binary cuối.

## Ngân Khố Linh Tệ — V0.8 Phase 3

`/cv treasury` xem số dư, ví cá nhân, quyền chi, thành viên và mười bút toán gần nhất. Mọi thành viên được góp tiền bằng GUI hoặc `/cv treasury contribute <1..1000>`. Chưởng môn luôn được chi; ELDER cần quyền riêng do chưởng môn cấp qua `/cv treasury grant <UUID>` và thu hồi qua `/cv treasury revoke <UUID>`. Trong GUI, chưởng môn bấm trái thành viên để cấp, phải để thu hồi. Đổi role, rời hoặc bị loại xóa quyền chi; thăng lại ELDER không tự khôi phục quyền.

`/cv treasury withdraw <1..1000>` chỉ rút về ví của chính người thao tác. `/cv treasury hire <1..1000>` ký quỹ từ ngân khố để thuê Builder, dùng động phủ sẵn sàng của người tạo và mẫu sàn 3×3 hiện tại. Không cấp quyền xây toàn đảo; Phase 4 cho chủ xác nhận giao hai vùng mẫu của động phủ vào dự án. Dùng `/cv contracts` nhận, gửi, duyệt hoặc hủy hợp đồng; người có quyền chi hiện tại của đúng tông môn được duyệt/hủy hợp lệ, kể cả khi người tạo đã mất quyền. Hủy/bỏ việc/hết hạn hoàn tiền về ngân khố nguồn, không về ví người tạo.

Mỗi xác nhận giữ một request và chỉ được tiêu thụ một lần; sau góp/rút, bấm **Làm mới** để mở ý định mới. GUI cũ không cấp quyền: DB khóa tông môn và đọc lại role/quyền chi trong transaction cùng ledger, receipt và lịch sử. Ví PLAYER giữ ở `profiles`; SECT dùng `sect_accounts` và chung `WalletLedger`/`ledger`. Escrow hiện tại giữ nguyên. Phí 0%, không mint/burn khi góp/rút/ký quỹ/hoàn/trả công. Ngân khố không nhận Linh Thạch hoặc token PDC; tài nguyên dùng kho dự án Phase 4 riêng bên dưới.

## Kho dự án và hạng mục — V0.8 Phase 4

`/cv projects` xem dự án tông môn; `/cv projects create <tên>` mở xác nhận giao hai vùng mẫu trong động phủ của chưởng môn. Mỗi động phủ có một dự án, chưa giải tán/chuyển đất. `/cv projects view <project>` xem kho DB, định mức/dự trữ từng hạng mục và lịch sử đóng góp. `/cv projects contribute <project> <resource> <1..1000>` xác nhận góp nguyên liệu; đóng góp đã commit chưa được rút lại. PDC là bản thể hiện kho cá nhân: góp trừ kho DB, thu hồi token và đồng bộ Inventory, không nhận item giả làm tài sản.

`/cv projects hire <project> <stage> <1..1000>` thuê Builder từ SECT và dự trữ đủ nguyên liệu trong cùng transaction. GUI có nút góp 1 và thuê 2 Linh Tệ. `STONE_PAD_V1` cần 2 Linh Thạch + 1 Huyền Thiết; Builder đặt 9 cobblestone tại x=slot×64+3..5, y=100, z=3..5. `STONE_PATH_V1` cần 1 Linh Thạch; Builder đặt 3 stone bricks tại x=slot×64+10..12, y=100, z=11. Hai vùng không chồng nhau; có thể thuê hai Builder đồng thời. Chủ cần dọn trống các ô trước khi thuê.

Dùng `/cv contracts` nhận, gửi và duyệt. Gửi kiểm template nhưng chưa tiêu thụ/trả tiền; duyệt kiểm world rồi tiêu thụ định mức một lần và giải ngân qua Economy hiện tại. Hủy OPEN, bỏ việc hoặc hết hạn hoàn dự trữ về PROJECT và tiền về SECT nguồn; không tháo block vanilla. Người đang thi công hạng mục ACTIVE/SUBMITTED cùng tông môn phải giải quyết trước khi rời/bị loại. **Nguyên liệu tu tiên tiêu thụ theo định mức và block vanilla Builder đặt là hai phần riêng; góp PDC không đồng nghĩa đã xây.** [Báo cáo Phase 4](validation/v08/phase4/RESULTS.md) ghi rõ fixture/API và client mục tiêu; nghiệm thu ba người từ đầu đến cuối thuộc Phase 5.

## Hợp đồng kiến thiết — Vertical Slice 001

Mở `/cv`, bấm **Hợp đồng kiến thiết**, hoặc dùng `/cv contracts`. Chủ động phủ dọn trống sàn tại tâm đảo: `x=[slot*64+7,slot*64+9]`, `y=100`, `z=[7,9]`. GUI cho thuê xây sàn 3×3 cobblestone với tiền công 2/10/100 **Linh Tệ**, thời hạn một giờ. Mỗi động phủ có tối đa một hợp đồng legacy chưa kết thúc; hai hạng mục Phase 4 dùng hai worksite riêng không chồng vùng.

Một người khác nhận việc, đến công trình và đặt chín block bằng Minecraft Inventory. Builder chỉ được đặt/phá cobblestone tại sàn hợp đồng; không được sửa nền, mở rương hay xây ngoài vùng nhờ quyền này. Builder gửi nghiệm thu, chủ duyệt: server kiểm tra sàn, lưu world rồi chuyển tiền ký quỹ sang ví Builder. Gửi nghiệm thu kết thúc quyền thi công; hoàn thành, hủy hoặc hết hạn không thêm Builder vào danh sách mời của đảo. Quyền mời riêng của V0.6 vẫn là cơ chế độc lập.

**Linh Tệ (`linh_te`) là tiền tệ; Linh Thạch (`linh_thach`) là tài nguyên tu tiên.** Ký quỹ có tài khoản PostgreSQL và ledger riêng trong cùng transaction/receipt với ledger ví hiện tại. Nạp, giải ngân, hoàn tiền không mint/burn; phí MVP 0%. Vật liệu vanilla do hai bên thỏa thuận, chưa ký quỹ tài nguyên PDC và không tự quy đổi Linh Thạch thành Linh Tệ.

Chủ chỉ hủy trước khi có Builder nhận. Builder được bỏ việc khi đang thi công hoặc chờ nghiệm thu. Hết hạn cũng hoàn ký quỹ cho bên thuê; công trình và vật liệu không tự tháo dỡ/hoàn trả. Cả hai phải nghiệm thu trước thời hạn; chưa có gia hạn, tranh chấp hoặc bồi thường vật liệu. Xem [đánh giá V0.7](V0.7-EVALUATION.md) để biết bằng chứng và giới hạn.

## Vòng chơi đầu tiên

1. Minecraft Java 26.3 kết nối server. Gõ `/cv` để mở GUI tu vi, kho, chế tạo và thương nhân.
2. Bấm **Động phủ**: nhận đảo đầu tiên, cây non và rương gồm 16 gỗ, 32 đất, nước, lava và cuốc gỗ. Rương chỉ được dựng khi cấp đảo lần đầu; quay lại không cấp lại đồ.
3. Mang cuốc, bấm **Prison**: khai thác 32 mạch quặng trên sàn `(0..15,99,0..15)` trong world `cv_prison`. Nửa mỏ thưởng linh thạch thô, nửa thưởng huyền thiết. Quặng biến mất sau commit và tái tạo sau cooldown; DB giữ cooldown qua restart.
4. Nhấp phải bàn chế tạo `(2,100,3)` hoặc quầy barrel `(13,100,3)` để mở GUI. Có 5 linh thạch thô, bấm **Kết tinh** → 1 linh thạch vật lý. Nhấp trái nguyên liệu trong GUI để bán 1; nhấp phải để mua 1.
5. Về động phủ, dùng nguyên liệu vanilla trong rương để xây dựng. `/cv invite <UUID>` cấp quyền xây; `/cv visit <UUID-chủ>` đến thăm; `/cv revoke <UUID>` thu hồi quyền. UUID hiển thị trong `/cv catalog`. Người nhận cần có hồ sơ.

Nguyên liệu kinh tế là vật phẩm thật có PDC `cuuvuccore:token`, `owner`, `resource`, mỗi vật phẩm một đơn vị, không xếp chồng. Chúng gắn với chủ và dùng trong hệ thống chế tạo/giao dịch Cửu Vực, không dùng để đặt khối, craft vanilla, thả hoặc chuyển vào rương. Khối xây dựng vanilla vẫn di chuyển và sử dụng bình thường. GUI chỉ hiển thị thông tin; không thể kéo icon ra làm vật phẩm.

Inventory đầy thì nguyên liệu vẫn thuộc người chơi trong DB. Bấm **Kho & nhận nguyên liệu** khi có chỗ. Nguyên liệu chỉ giao nhận bằng luồng xác nhận V0.8 ở trên; chưa có chợ, kho rương PDC, GUI mời bạn hoặc custom model/resource pack. Vật phẩm kinh tế không được kéo đổi vị trí trong inventory ở bản này; mục đích là giữ đường giao nhận được kiểm soát.

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

JAR: `target/CuuVucCore-0.8.0-SNAPSHOT.jar`. Linux/macOS dùng `JAVA_HOME=/path/to/jdk25 sh ./mvnw -B -ntp clean package`, cùng config và JAR Paper rồi `java -Xms512M -Xmx1536M -jar paper.jar --nogui`. Script Windows chuyển JAR CuuVucCore cũ sang `run/plugin-backups`, giữ config/EULA; không dùng `/reload`.

## Nâng cấp từ baseline

Dừng mọi server dùng chung DB. Tạo backup đồng bộ theo mục bên dưới trước migration, giữ cả JAR/config và world baseline. Backup chỉ PostgreSQL không chứa công trình Minecraft.

Giữ database URL/user/schema, hạn mức và catalog baseline. Cập nhật phần `mine` theo `src/main/resources/config.yml`: **world=cv_prison**, 32 node trong chunk `(0,0)`, `y=100`. Config cũ có `world: world` sẽ bị từ chối, không tự đổi world. Không xóa hoặc đổi các resource ID/cảnh giới/giá để né kiểm tra policy.

Migration V003 thêm revision, token và động phủ; không thay catalog, ví, kho hay lịch sử V0.5.1. Migration cũ giữ nguyên; checksum/cấu trúc DB sai hoặc DB mới hơn plugin đều chặn ready. Quay lại V0.5.1 phải phục hồi **backup trước V003** vào DB trống và dùng JAR/config baseline, không hạ JAR trực tiếp lên schema v3. Phục hồi bằng `pg_restore --exit-on-error`; đối soát dữ liệu trước khi mở lại. Rollback bằng backup mất các giao dịch sau thời điểm chụp.

V0.7 thêm migration V004: trạng thái hợp đồng, tài khoản/ledger ký quỹ, lịch sử công trình và revision quyền hợp đồng. Giữ nguyên V001–V003 và dữ liệu V0.6. Quay lại V0.6 cần restore backup trước V004 vào đích mới; JAR V0.6 không được dùng trên schema v4. Khi khởi động, worker hoàn tiền hợp đồng hết hạn trước ready và dựng lại quyền từ DB. Quyền thi công có lease 5 giây, kiểm tra thời hạn theo đồng hồ DB; lỗi đồng bộ kéo dài sẽ từ chối quyền hợp đồng. Shutdown mềm lưu hàng đợi audit chưa commit và replay bằng request ID; chưa phải journal chống mất điện/crash.

## Kiểm thử và CI

V0.8 có V005 cho ledger tài nguyên, V006 cho tông môn, V007 cho ngân khố và V008 cho kho dự án/hạng mục; giữ nguyên V001–V007 đã phát hành. V008 giữ số dư/token/ledger cũ, gắn hợp đồng cũ với template legacy và không tự tạo dự án. JAR Phase 3 không chạy trên schema V008: rollback cần backup trước V008 vào đích mới và JAR Phase 3. V007 tạo tài khoản SECT số dư 0 cho tông môn cũ, không cấp quyền chi ELDER, không đổi số dư ví hoặc nguồn ký quỹ hợp đồng cá nhân. Trước nâng cấp, dừng mọi writer và backup DB/world/JAR/config như bên dưới; chỉ nâng một runtime, không hot reload. JAR Phase 2 không chạy trên schema v7: rollback cần restore backup trước V007 vào DB/runtime mới, không hạ JAR trực tiếp trên DB đã nâng cấp. JAR V0.7 không chạy trên schema v5/v6/v7; quay lại V0.7 cần backup trước V005. Runtime/DB đã nghiệm thu không được dùng làm DB test.

```powershell
docker compose --profile test up -d --wait db-test
$env:CUUVUC_TEST_URL = 'jdbc:postgresql://127.0.0.1:55433/cuuvuc_test'
$env:CUUVUC_TEST_USER = 'cuuvuc_test'
$env:CUUVUC_TEST_PASSWORD = 'replace-with-your-test-password'
./mvnw.cmd -B -ntp clean verify
```

DB test phải riêng; mỗi ca tạo/xóa duy nhất schema `cv_test_<uuid>`. Thiếu DB test là lỗi, không bỏ qua integration tests. Phase 4 đạt 78/78 (4 Domain hiện có + 74 PostgreSQL thật); [bằng chứng hiện tại](validation/v08/phase4/RESULTS.md) phân biệt integration, client và phần chưa thử. [Kết quả V0.6](validation/V0.6-RESULTS.md) và [RESULTS V0.5.1](validation/RESULTS.md) giữ lịch sử baseline.

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
