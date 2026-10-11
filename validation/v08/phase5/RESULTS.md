# V0.8 Phase 5 — Nghiệm thu ba người

**VALIDATED WITH SCOPE LIMIT — luồng ba client đã chạy qua góp tài sản, thuê, gửi/duyệt, giải ngân và phục hồi; công trình dựng bằng console theo yêu cầu. Chờ người dùng đánh giá Phase 5.** Phase 4 đã được người dùng chấp nhận tại `631f5a3`; toàn bộ V0.8 chưa được nghiệm thu. Không merge `main`, không bật GitHub CI, không chứng nhận production-ready.

## Môi trường và binary

Ngày 12/10/2026, Asia/Bangkok, phiên chuẩn bị 00:44–00:59, tiếp tục gameplay 01:06–01:38. Paper 26.3 build 166, Java Corretto 25.0.4.1, PostgreSQL 18.4 tại `127.0.0.1:55439`. Hai client Prism Minecraft 26.3 và một client Modrinth Light Craft Minecraft 26.3 (173 mods); client C có mod nên đây chưa phải ca ba client vanilla.

Plugin giữ nguyên binary cuối Phase 4, SHA-256 `79359d168fdd32f5ad7de85dea82969fae03ddd6435c43cf160247cd97505c10`. Không sửa/build lại plugin trong phiên này. **78/78 là kết quả suite tại checkpoint Phase 4, không phải suite vừa chạy lại ở Phase 5.** Không thêm unit test hoặc fixture nghiệp vụ.

Runtime mới `run-v08-three-player`, DB mới `cv_v08_three_player`, world sinh mới; chỉ copy Paper, plugin, cấu hình và whitelist. Không copy world hoặc dữ liệu gameplay Phase 4. Server offline-mode được người dùng cho phép, chỉ bind `127.0.0.1:25580`, whitelist ba tài khoản; người chơi không được cấp OP/Creative.

| Vai trò nghiệm thu | Client | UUID |
|---|---|---|
| A — Chưởng môn | CuuVucNPC2, Prism | `f3d83b55-4452-39bc-85f4-29f8cebdcc4f` |
| B — Người góp | CuuVucNPC1, Prism | `1194cfbc-1195-338c-9eb1-0c49dd118ae3` |
| C — Builder | TyninWorld, Modrinth | `3bb5ddbc-5b75-377f-8647-6f25c51f252a` |

Các thao tác nghiệp vụ trong bảng ca đã chạy thực hiện bằng lệnh `/cv` của người chơi và nút xác nhận GUI trên client thật. SQL chỉ đọc để đối soát; không INSERT/UPDATE fixture, không console tạo tiền hoặc hợp đồng. Phiên preflight chỉ dùng console điều chỉnh Peaceful và dừng server. Chuẩn bị công cụ/block của phiên tiếp tục được người dùng cho phép riêng bên dưới; không gộp vào bằng chứng tự thu thập hoặc xây tay.

## Các ca đã chạy

| Ca | Kết quả và giới hạn |
|---|---|
| Ba client kết nối đồng thời | PASS trước và sau lần điều chỉnh thiết lập ban đầu; log có ba danh tính riêng. Cả ba đã đăng nhập lại sau cold restore có tài sản và restart sau hoàn tất; xem log phiên tiếp tục bên dưới |
| A lập tông môn, mời B/C | PASS, bấm xác nhận 8 lần cho mỗi thao tác; một CREATE, hai INVITE |
| B/C nhận lời mời qua GUI | PASS, 8 click mỗi lần; hai ACCEPT, ba membership, một LEADER. [Client C](three-members-client-C.png), [lịch sử C](sect-history-client-C.png) |
| A tạo động phủ và dự án | PASS qua `/cv home` và GUI tạo dự án; 8 click chỉ tạo một project, hai stage READY, một lịch sử CREATE. [Xác nhận đã dùng](project-created-repeat-confirm.png) |
| A thuê sàn với ngân khố trống | PASS cho trường hợp thiếu tiền: GUI báo tài khoản SECT không đủ Linh Tệ. 0 contract, 0 escrow, 0 Resource Ledger, hai stage vẫn READY. Không gọi đây là ca thiếu nguyên liệu vì tiền bị kiểm tra trước. [Client](hire-rejected-no-money.png) |
| GUI B mở trước khi thu hồi quyền chi | PASS ở ca ngân khố trống: A đổi B thành ELDER, cấp quyền chi; B mở xác nhận rút 2; A thu hồi bằng GUI khác; B bấm GUI cũ 8 lần. Lỗi rõ "Quyền chi ngân khố đã bị thu hồi hoặc chưa được cấp trong DB", không tạo WITHDRAW/ledger. Đã bổ sung ca GUI thuê cũ với ngân khố/nguyên liệu dương bên dưới; không coi đó là lặp ca rút tiền dương. [GUI cũ](withdraw-open-before-revoke.png), [lỗi quyền](stale-withdraw-rejected.png) |
| Stop/drain và cold restore checkpoint chuẩn bị | PASS 31/31 bảng, 108/108 file world, cùng JAR; plugin khởi động lại và DB ready. Chưa có PDC, tiền, escrow hay block thi công để chứng minh phục hồi tài sản đầy đủ |

[Đối soát đọc DB](preflight-reconcile.txt): A LEADER, B ELDER không có quyền chi, C MEMBER. Cả ba ví 0, ngân khố 0, revision Inventory 0. Money Ledger và Resource Ledger đều 0. Hai stage READY, contract null; không có hợp đồng/escrow. Sect history: CREATE 1, INVITE 2, ACCEPT 2, PROJECT_CREATE 1, ROLE 1, SPEND_GRANT 1, SPEND_REVOKE 1. Các kết quả này chỉ chứng minh phần tổ chức/quyền, không chứng minh bảo toàn một lượng tài sản dương.

## Checkpoint và phục hồi

- Tông môn: `5e305313-c14d-473f-8101-e76fb79db1ee`, **Khai Son Phase5**.
- Dự án: `2c3b158d-d5cd-47de-8228-179c315a0c45`, **Cong Trinh Phase5**, đảo slot 1 của A.
- Sàn: `c94506f3-7b05-3a74-8905-dc2f1bf0ba5a`, STONE_PAD_V1, READY.
- Đường: `ccd5125b-fa79-37a5-9f4a-00b99ce180a4`, STONE_PATH_V1, READY.
- Cold bundle cục bộ: `backups/v08-three-player-preflight-001`, 345 file theo [manifest](backup-manifest.json); có cả thư mục cách ly artifact thiết lập, không coi chúng là file world.
- Đích restore riêng: `run-v08-three-player-restored`, DB `cv_v08_three_player_restored`; không ghi đè nguồn.

[Trước](db-before.jsonl) và [sau](db-after.jsonl) restore có cùng 31 dòng hash nội dung bảng, SHA-256 file UTF-8/LF `6a9f643ec6170a94f91c1851b9ec142121e3d772a3388b948704e42e0d77177a`. Snapshot dùng [SQL chung đã có ở Phase 4](../phase4/snapshot.sql). 108 file dưới `server/world/` bao gồm các dimension Paper có checksum khớp trước lần khởi động đích; [kết quả](restore-check.txt). [Shutdown](shutdown-pending.properties): drained=true, contract-audit-persisted=true. [Log nguồn](source-session.log) và [log khởi động đích](restored-startup.log).

Sự cố thiết lập phải giữ minh bạch: phiên đầu không giữ được stdin nên không gửi được `stop`; tiến trình bị kết thúc sau CREATE tông môn, trước lời mời/dự án/tài sản. CREATE vẫn tồn tại khi chạy lại, nhưng **không tính đây là bài hard-kill có tài sản hoặc audit block**. Một lần gọi Paper sai thư mục làm sinh cache/config ở gốc repo; tiến trình dừng do eula=false, không mở world/server, artifact đã cách ly trong runtime bị Git ignore. Phiên chính sau đó chạy đúng thư mục với terminal giữ stdin và dừng sạch. Slime của world mặc định gây chết người chơi trong giai đoạn đầu; console chuyển Peaceful, không cấp bù tài sản.

## Gameplay ba người và phục hồi có tài sản

Người dùng đào trên C rồi xác nhận đã đào; DB và log ghi nhận 47 lần khai thác thành công: 39 `linh_thach_tho` và 8 `huyen_thiet`. Console chỉ cấp một IRON_PICKAXE theo yêu cầu ([log](asset-preparation.log)) và đặt block vanilla sau khi hợp đồng ACTIVE. Không dùng SQL ghi hoặc console tạo PDC, Linh Tệ, membership, hợp đồng hay quyền.

| Ca thực chạy trên JAR cuối Phase 4 | Kết quả |
|---|---|
| C chuyển khi A/B offline | C chuyển 7 raw cho A, 10 raw + 1 Huyền Thiết cho B. Mỗi xác nhận 8 click; người nhận đăng nhập nhận đủ token. [C chuyển A](transfer-C-to-A-repeat.png), [A nhận](A-offline-receive-login.png), [B nhận](B-offline-receive-login.png) |
| Craft và kiếm tiền thật qua GUI | A craft 5 raw thành 1 Linh Thạch, bán 2 raw bằng hai lần click riêng, nhận 4 Linh Tệ. B craft hai lần, tiêu 10 raw thành 2 Linh Thạch. Không dùng click lặp để giả định các giao dịch craft/sell độc lập là cùng request |
| Góp tiền và tài nguyên | A góp 4 Linh Tệ + 1 Linh Thạch; B góp 2 Linh Thạch + 1 Huyền Thiết. Xác nhận 8 click chỉ commit một lần mỗi ý định. [Tiền A](A-treasury-contribute-repeat.png), [nguyên liệu A](A-project-contribute-repeat.png), [nguyên liệu B](B-crystal-contribute-repeat.png) |
| Thu hồi quyền với tài sản dương | A cấp B quyền chi; B mở GUI thuê sàn khi SECT có 4 Linh Tệ và project có 3 Linh Thạch + 1 Huyền Thiết. A thu hồi; B xác nhận GUI cũ 8 click bị lỗi quyền DB. Không có contract, escrow hay reservation; tài sản giữ nguyên. [GUI trước](funded-hire-open-before-revoke.png), [từ chối](funded-stale-hire-rejected.png), [DB](funded-revoke-db.txt) |
| A thuê hai hạng mục, C nhận | Mỗi hạng mục giữ 2 Linh Tệ; tổng dự trữ 3 Linh Thạch + 1 Huyền Thiết. Hire/accept thử 8 click. Hai contract ACTIVE, Builder C; chưa trả công hoặc tiêu thụ. [Thuê đường](A-path-hire-repeat.png), [C nhận](C-accept-first-repeat.png) |
| Gửi khi chưa có block | C gửi sàn còn trống bị từ chối rõ: kho PDC không thay thế thi công. Hợp đồng giữ ACTIVE. Ca này chỉ chạy cho sàn. [Ảnh](C-submit-missing-block-rejected.png) |
| Cold restore khi đang thi công | Dựng 9 COBBLESTONE và 2/3 STONE_BRICKS bằng console; stop/drain, backup DB/world; restore đích mới. 31/31 bảng và 109/109 file world khớp, cùng JAR. Cả ba reconnect; token, quyền, escrow và dự trữ giữ đúng. Kiểm block xác nhận sàn có, đường còn thiếu ô x=76. [Kho C](C-inventory-after-active-restore.png), [B đọc lịch sử](B-project-history-after-restore.png) |
| Gửi và duyệt sau restore | Console đặt STONE_BRICKS cuối. C gửi hai hạng mục, 8 click mỗi lần; cả hai SUBMITTED, escrow vẫn 4, không CONSUME, ví C vẫn 0 ([DB](submitted-reconcile.txt)). A duyệt mỗi hạng mục 8 click: đúng hai lần thanh toán 2 Linh Tệ. [C gửi sàn](C-submit-pad-repeat.png), [C gửi đường](C-submit-path-repeat.png), [A duyệt sàn](A-complete-pad-repeat.png), [A duyệt đường](A-complete-path-repeat.png) |
| Restart sau hoàn tất | Stop sạch rồi chạy lại cùng DB/world/JAR; cả ba reconnect. A/B/C xem lịch sử CONSUME, CONTRIBUTE, RESERVE, CREATE. C thấy 4 Linh Tệ trên GUI. Đây là restart thông thường sau Inventory đã đồng bộ, **không phải** restart được chặn chính xác giữa DB commit và Inventory resync |

Sàn contract `279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3`, đường contract `7c9c9656-2602-48b4-98de-447f06e34e97`; hai stage ở mục checkpoint đều đã COMPLETED trong đích cuối. Tiền công có receipt `602e0c14-bef9-3e76-9592-71183c3461e4` và `0a46f7ba-c876-39a9-8813-0ea9228ed6b8`; escrow có đúng hai DEPOSIT, hai RELEASE, không REFUND.

[Đối soát sau hoàn tất](completed-reconcile.txt) và [sau restart](restarted-reconcile.txt) chạy [SQL chỉ đọc có assertion](reconcile.sql) trên PostgreSQL thật, đều `PHASE5_DB_ASSERTIONS_PASS`:

- A/B ví 0, C ví 4; SECT 0, escrow 0. Tổng tiền 4 Linh Tệ; mint 4 từ hai NPC sale, burn 0. Góp/thuê/duyệt không thay đổi mint/burn.
- Project/stage dự trữ 0; đúng ba dòng CONSUME thuộc hai request, tổng -3 Linh Thạch và -1 Huyền Thiết. Mỗi cặp Resource TRANSFER có hai dòng tổng delta 0.
- C còn 22 raw + 7 Huyền Thiết và đúng 29 token; A/B không còn kho/token sau đóng góp. Không phát hành token mới thành tài sản thêm khi reconnect.
- Project history CREATE 1, CONTRIBUTE 3, RESERVE 2, CONSUME 2. Contract history CREATE/ACCEPT/SUBMIT/COMPLETE mỗi loại 2, **PLACE/BREAK 0** vì block được đặt bởi console. Sect history có hai CONTRACT_PAYMENT và ba PROJECT_CONTRIBUTE.
- B vẫn ELDER, treasury_spend=false. A LEADER có quyền theo role; cờ treasury_spend=false của A không có nghĩa mất quyền Chưởng môn.

Ảnh lịch sử sau restart: [A](A-completed-history-after-restart.png), [B](B-completed-history-after-restart.png), [C](C-completed-history-after-restart.png); [ví C](C-wallet-after-completed-restart.png). Tooltip dài bị giới hạn chiều rộng client; dùng DB receipt/history làm bằng chứng đầy đủ, không suy số lượng từ icon.

## Bundle phục hồi có tài sản

Nguồn `run-v08-three-player-restored`, DB `cv_v08_three_player_restored`; cold bundle `backups/v08-three-player-active-001`. Đích mới `run-v08-three-player-final-restored`, DB `cv_v08_three_player_final_restored`. Không ghi đè nguồn. [Manifest](active-backup-manifest.json) có 347 file, gồm 109 file world; archive DB/world thực tế giữ cục bộ, không upload runtime hoặc database dump lên GitHub.

[Snapshot trước](active-db-before.jsonl) và [sau](active-db-after.jsonl) khớp 31/31 bảng, SHA-256 UTF-8/LF `229a514cc19073d9ae1ee0297ee80b2b24aeeae442b62d8fecbf80786869a218`. [Kiểm checksum](active-restore-check.txt) thực hiện trước khi khởi động đích; client reconnect tạo hoạt động mới nên không so hash toàn DB sau gameplay với snapshot bất động.

[Log góp/thuê/dựng và shutdown nguồn](active-source-session.log), [shutdown nguồn](active-shutdown.properties), [log restore rồi hoàn thành](active-restored-session.log), [shutdown sau hoàn thành](completed-shutdown.properties), [log restart cuối](completed-restart-session.log). Hai shutdown đều drained=true, contract-audit-persisted=true. Runtime cuối được giữ chạy cục bộ để người dùng xem lại, không mở public.

## Phạm vi còn mở

Đây là bằng chứng gameplay có kiểm soát trên đúng JAR cuối Phase 4; không sửa plugin hoặc migration. **78/78 vẫn là baseline suite Phase 4**; phiên này bổ sung GUI E2E, cold restore có tài sản và assertion PostgreSQL, không tuyên bố vừa chạy lại 78 test.

Người dùng cho phép dựng sẵn cả hai công trình và không tính ca xây tay. Vì vậy chưa PASS thu thập block vanilla, Builder đặt/phá bằng tay, đặt sai vật liệu/ngoài vùng hoặc audit PLACE/BREAK. Những ca liên quan quyền block từ milestone trước không được tự coi đã lặp trong phiên này. Chưa thử hard-kill hoặc restart đúng cửa sổ commit/resync, tải đông người, mọi cách sao chép PDC hay craft/sell/transfer đồng thời bằng ba client. B01–B06 và tính nguyên tử xuyên DB/world giữ trong backlog.

Paper vẫn cảnh báo world.save() thủ công; không có phép đo TPS/độ trễ đủ để đóng B02. Log khởi động có lỗi OSHI Perflib counters trên Windows, server vẫn DB ready; không chỉnh registry hoặc tuyên bố hết lỗi môi trường. V3 heightmap chưa được cung cấp hoặc nhập. Phase 5 chờ người dùng đánh giá trong phạm vi đã đổi; toàn bộ V0.8 chưa ACCEPTED, chưa production-ready, `main` giữ nguyên, GitHub CI không thuộc điều kiện nghiệm thu.
