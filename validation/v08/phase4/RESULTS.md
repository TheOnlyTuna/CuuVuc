# V0.8 Phase 4 — Kho dự án và hạng mục

Ngày 12/10/2026, Asia/Bangkok; nhánh `v0.8-khai-son-lap-phai`. Nền `acd2e48`: Phase 1, 2 và 3 đã được người dùng chấp nhận; Phase 3 là Controlled Gameplay Prototype. **Người dùng đã chấp nhận Phase 4 ở mức Controlled Gameplay Prototype tại `631f5a3`.** Các giới hạn fixture/console, binary và phạm vi thử bên dưới vẫn giữ nguyên. [Phase 5](../phase5/RESULTS.md) đang thực hiện, chưa hoàn tất luồng ba người từ đầu đến cuối. Không merge main, không bật CI và không chứng nhận production-ready.

## Hành vi và ranh giới tài sản

V008 thêm `projects`, `project_stages`, `project_inventory`, `project_stage_costs`, `stage_inventory`, `project_history`; mở rộng `resource_ledger` hiện tại với chủ PROJECT/STAGE, đối tác có kiểu, actor và loại CONSUME. Kho PLAYER vẫn ở `inventory`, không sao chép để cùng ghi. Mỗi góp kho PLAYER→PROJECT và mỗi dự trữ/hoàn PROJECT↔STAGE có hai bút toán đối ứng; tiêu thụ chỉ có debit STAGE loại CONSUME. Tổng tài nguyên còn lại cộng phần đã tiêu thụ được đối soát, không gọi tiêu thụ là chuyển hoặc mint/burn tiền.

Góp qua GUI theo resource/số lượng kho DB, như Phase 1; không cộng tài sản từ ItemStack/PDC tùy ý. DB đọc revision nguồn, khóa hồ sơ, trừ kho, thu hồi token resource đã góp, tăng revision và ghi ledger/receipt/lịch sử cùng transaction. Sau commit, entity scheduler đồng bộ Inventory. Snapshot cũ hoặc token cũ không tạo thêm số lượng. Kho dự án không cấp token Inventory; chưa cho rút lại đóng góp về người chơi. Không có đường giao nhận qua rương/drop. Không có thao tác mở từ item cầm tay nên không suy ra count từ PDC.

LEADER xác nhận giao hai vùng trong động phủ của mình để tạo dự án; một dự án mỗi động phủ, chưa giải tán/chuyển đất. Thành viên hiện tại được góp/xem. LEADER hoặc ELDER có quyền chi ngân khố hiện tại được mở hợp đồng/duyệt/hủy hợp lệ. Quyền dùng chung khóa hàng tông môn với cấp/thu hồi/role/kick/leave. Member đang là Builder ACTIVE/SUBMITTED của hạng mục cùng tông môn phải hoàn thành hoặc bỏ việc trước khi rời/bị loại; không tự hủy ký quỹ hoặc tài nguyên.

Mở hợp đồng hạng mục chạy qua kiểm tra ô trống trong Paper. Transaction DB khóa receipt → sect → project → stage → contract/escrow → hồ sơ cần thiết; trừ Linh Tệ từ SECT vào escrow hiện tại và chuyển đủ định mức PROJECT→STAGE. Thiếu tiền, nguyên liệu, role hoặc stage không READY: rollback toàn bộ. `stage.contract_id` và template/worksite có FK; mỗi worksite chỉ một hợp đồng chưa kết thúc. Các đường settle cũng khóa project/stage trước contract. [Khóa PostgreSQL](https://www.postgresql.org/docs/18/explicit-locking.html) và [scheduler Paper](https://docs.papermc.io/paper/dev/folia-support/) là tài liệu đối chiếu; chưa bật Folia support.

Builder nhận/gửi không tiêu thụ hoặc nhận tiền. Gửi và duyệt đều kiểm đủ block theo template ở region scheduler, giữ fence hợp đồng, lưu world theo kiến trúc hiện tại. Sau kiểm tra/lưu, DB duyệt đọc lại role, trạng thái và dự trữ đúng contract/định mức, tiêu thụ một lần, trả Builder qua Economy hiện tại, ghi cả lịch sử công trình/dự án/tông môn và receipt trong một commit. Hủy OPEN, Builder bỏ việc ACTIVE/SUBMITTED hoặc hết hạn: hoàn toàn bộ dự trữ chưa tiêu thụ về PROJECT và tiền về SECT gốc. Stage trở lại READY và có thể thuê lại; block vanilla không bị tháo/hoàn. Phí 0%, không đổi mint/burn khi xử lý dự án.

## Template phiên bản và cách dùng

| Hạng mục | Block cần Builder đặt | Định mức tu tiên tiêu thụ khi duyệt | Vùng trong động phủ slot S |
|---|---|---|---|
| STONE_PAD_V1 | 9 COBBLESTONE, sàn 3×3 | 2 `linh_thach` + 1 `huyen_thiet` | x=S×64+3..5, y=100, z=3..5 |
| STONE_PATH_V1 | 3 STONE_BRICKS, đường 3×1 | 1 `linh_thach` | x=S×64+10..12, y=100, z=11 |

Template/định mức được chốt khi tạo; `project_stage_costs` chặn UPDATE/DELETE/TRUNCATE. Hai vùng không chồng nhau, nằm trong một chunk và không chồng mẫu legacy giữa đảo. Builder chỉ được sửa đúng vật liệu trong vùng của hợp đồng ACTIVE đang nhận; không thêm quyền xây toàn đảo. Có thể mở đồng thời hai hạng mục và thuê hai Builder. Template legacy V0.7/Phase 3 vẫn dùng sàn 3×3 cũ, không có dự trữ PDC.

Đây là bộ hai template mẫu có phiên bản, chưa phải editor/config nhập template tùy ý, chưa công trình lớn nhiều chunk. Nguyên liệu tu tiên là chi phí theo định mức; Builder vẫn tự chuẩn bị block vanilla. **Chuyển PDC vào PROJECT hoặc STAGE không chứng minh đã xây.** Linh Tệ là tiền; Linh Thạch là tài nguyên, không tự quy đổi.

`/cv projects` mở danh sách; `create <tên>` xác nhận giao đất; `view <project>` xem kho, hai hạng mục và mười sự kiện gần nhất. `contribute <project> <resource> <1..1000>` xác nhận đóng góp không rút lại. `hire <project> <stage> <1..1000>` xác nhận dự trữ và ký quỹ; GUI có nút góp 1 nguyên liệu và thuê hạng mục READY với 2 Linh Tệ. Dùng `/cv contracts` nhận/gửi/duyệt/hủy. Mỗi GUI xác nhận giữ một request, tiêu thụ một lần, không tự chuyển sang cửa sổ khác dưới click còn lại. [README](../../../README.md) giữ hướng dẫn chạy/nâng cấp.

## Kiểm thử thực chạy

**78/78 đạt**, 0 fail/error/skip: 4 Domain hiện có + 74 integration PostgreSQL 18.4 thật. Tám integration mới, không thêm unit hoặc mock. `clean verify` cuối hoàn tất 00:09:25 ngày 12/10/2026. [Maven](maven-verify.log), [DB XML](GameDatabaseIT.xml), [Domain XML](DomainTest.xml).

- Góp/replay/revision cũ/token thu hồi, typed Resource Ledger, quyền người ngoài, dự án trùng đất, reopen và lịch sử/định mức bất biến.
- Hai hạng mục mở đồng thời, hai Builder, vùng và tiêu chí riêng; tám complete đồng thời cùng intent chỉ tiêu thụ/trả một lần; chặn kick/leave khi đang thi công.
- Hủy lặp, thuê lại, Builder bỏ việc và expiry lặp: hoàn đúng PROJECT và SECT, không tiêu thụ thêm.
- Thiếu nguyên liệu/tiền và trigger lỗi khi reserve/consume: rollback contract, escrow, stock, ledger, history và receipt; bỏ lỗi rồi settle được.
- Captured hire chờ khóa thật trong PostgreSQL; thu hồi quyền commit trước khiến request bị từ chối, không giữ dự trữ/escrow. Người bị kick không được góp mới.
- Craft/sell/transfer/góp dự án tranh năm nguyên liệu nguồn: chỉ một thành công, không giao dịch một phần hoặc kho âm.
- Tám góp cùng intent chỉ một lần; hai intent thuê cùng stage chỉ một thắng.
- V007→V008 giữ ngân khố, ví, Resource Ledger, token và escrow SECT đang OPEN; template/worksite legacy được backfill đúng, initialize lặp đạt, hủy vẫn hoàn SECT cũ.

Lần kiểm thử đầu của fixture migration chèn trực tiếp identity `islands.slot` và bị PostgreSQL từ chối; đã sửa fixture bằng `OVERRIDING SYSTEM VALUE`. Không sửa quy tắc phát slot production. V001–V007, catalog và thuật toán fingerprint không thay đổi. V008 fingerprint `3ec58bcc0536a6951aa73cb838ee3aac863f1a5660fff3a057fdb53be136bc78`; B05 vẫn mở.

## Binary, fixture và client mục tiêu

Paper/API khóa **Minecraft 26.3 build 166 BETA**, Java 25.0.4.1, Maven Wrapper 3.9.11. Không tuyên bố mới nhất. Hai client Java 26.3 Prism, `CuuVucNPC2` (A/LEADER) và `CuuVucNPC1` (B/Builder), loopback 25580, offline-mode/whitelist đã được cho phép. JAR cuối dùng xuyên suốt tests, source runtime và runtime phục hồi:

```text
79359d168fdd32f5ad7de85dea82969fae03ddd6435c43cf160247cd97505c10
```

Cold backup Phase 3 được restore vào DB/runtime mới `cv_v08_projects` / `run-v08-projects`, giữ nguyên bản đã nghiệm thu. [Phase4Scenario.java](Phase4Scenario.java) gọi API thật khi Paper dừng: B có 10 linh thạch thô từ baseline, mine thêm 5, craft 3 Linh Thạch, mine 1 Huyền Thiết; A tạo dự án, B góp 3+1 qua API, không seed số lượng bằng SQL. Mở/hủy sàn một lần để kiểm hoàn, mở lại sàn rồi B nhận, đồng thời mở đường. [Log fixture](scenario.log). Lập dự án/góp/mở/nhận trong fixture là gọi DB API, **chưa phải GUI E2E**.

Client B xem GUI sàn RESERVED với định mức/dự trữ đúng. B gửi nghiệm thu khi chưa có block: Paper từ chối, vẫn ACTIVE, dự trữ 4 đơn vị toàn dự án, escrow 4, CONSUME 0. [GUI dự trữ](reserved-stage.png), [từ chối thiếu block](missing-template-rejected.png), [DB](missing-template-db.txt), [log source](source-session.log).

Sau đó operator console chuẩn bị chín cobblestone tại `(67..69,100,3..5)`, forceload tạm chunk `[4,0]` rồi gỡ. **Builder chưa tự đặt chín block bằng tay trong phiên này.** Source dừng mềm khi sàn ACTIVE và đường OPEN; cold backup/restore như phần dưới.

Trên đúng JAR cuối ở runtime mới, B đăng nhập lại và gửi nghiệm thu: SUBMITTED, chưa tiêu thụ/trả. A đăng nhập cùng B, mở GUI hợp đồng, duyệt với tám click thử: COMPLETED; chỉ hai bút toán CONSUME (`linh_thach=-2`, `huyen_thiet=-1`), một project history CONSUME và một escrow RELEASE. B tăng ví 10→12. Đường vẫn OPEN, RESERVED 1 Linh Thạch và escrow 2. [Gửi sau restore](restored-submit.png), [DB chưa trả](restored-submit-db.txt), [duyệt](restored-complete.png), [đối soát cuối](final-reconcile.txt), [log phục hồi](restored-session.log). Click thử không chứng minh số packet đã đến server.

Tiền cuối: ví 12 + SECT 6 + escrow 2 = **20**, minted 20/burned 0 kế thừa Phase 3. Tài nguyên góp 4 = dự trữ còn 1 + đã tiêu thụ 3; các bút toán TRANSFER có tổng delta 0 theo từng resource. Không dùng block vanilla làm nguồn phát hành tài nguyên hoặc chứng từ thay cho ledger.

## Phục hồi khi còn dự trữ

Bundle `backups/v08-projects-active-001` gồm 243 file. Trước startup đích mới, **31/31 bảng** trùng snapshot/hash và **105/105 file world** được kiểm trực tiếp ở destination; JAR SHA đúng bản cuối. Bao gồm token/kho cũ, tài khoản/project/stage, định mức, quyền, cả hai ledgers, receipts và lịch sử. [Manifest](backup-manifest.json), [snapshot SQL](snapshot.sql), [trước](before-restore.jsonl), [sau](after-restore.jsonl), [đối soát](restore-check.txt). Snapshot SHA-256 `44dd6b94e31b98e048e1a386fb3368fb2d987036239a98bbca5cdbb04c4afb28`.

Restore vào `cv_v08_projects_restored` / `run-v08-projects-restored`, startup đạt `cuuvuc_database_ready`, không drift. Client tiếp tục nghiệm thu sau restore như trên. Source và đích đã stop mềm, world I/O hoàn tất, `drained=true`, `contract-audit-persisted=true`; PostgreSQL thử đã dừng. [Marker nguồn](source-shutdown.properties), [marker đích](restored-shutdown.properties).

Đây là cold restore hợp đồng đang ACTIVE/dự trữ có block fixture đã lưu; không phải hard-kill giữa commit và world save. Không nhập V3/heightmap.

## Giới hạn và chuyển giao Phase 5

Chưa chạy ba người A/B/C lập tổ chức/góp tiền/góp PDC qua GUI/thi công bằng tay/duyệt/phục hồi từ đầu đến cuối. Client mục tiêu chỉ đọc dự án, từ chối thiếu template, gửi và duyệt sau restore. GUI create/contribute/hire, path STONE_BRICKS qua client, PDC sao chép/hard-kill lúc góp và tải nhiều dự án chưa kiểm chứng E2E; cạnh tranh/role/rollback và hai stage hoàn thành được kiểm ở PostgreSQL. Không gọi fixture console là Builder đã xây.

Giữ [B01–B06](../../../BACKLOG.md). Paper vẫn cảnh báo `world.save()` thủ công; chưa đo TPS/latency. DB và world không atomic xuyên hệ thống; audit block còn async. Template phiên bản bước đầu giảm phụ thuộc sàn legacy nhưng chưa mô hình công trình lớn/đa chunk. Quyền DB chặn settle sau thu hồi, còn chi phí kiểm/lưu world trước từ chối quyền vẫn là B02. Không production-ready.

Triển khai V008 cần dừng mọi writer, backup DB/world/JAR/config trước migration và restore thử. Không chạy JAR Phase 3 trên schema V008, không hot reload hoặc hai runtime chung DB. Rollback về Phase 3 cần backup trước V008 vào đích mới; không sửa checksum hoặc bỏ fingerprint. Nghiệm thu Phase 4 do người dùng quyết định; Phase 5 tiếp tục theo [V0.8-PLAN](../../../V0.8-PLAN.md).
