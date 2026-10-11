# V0.8 Phase 5 — Nghiệm thu ba người

**PENDING — mới hoàn tất phần chuẩn bị, tổ chức và quyền.** Phase 4 đã được người dùng chấp nhận tại `631f5a3`; toàn bộ V0.8 chưa được nghiệm thu. Không merge `main`, không bật GitHub CI, không chứng nhận production-ready.

## Môi trường và binary

Ngày 12/10/2026, Asia/Bangkok, phiên 00:44–00:59. Paper 26.3 build 166, Java Corretto 25.0.4.1, PostgreSQL 18.4 tại `127.0.0.1:55439`. Hai client Prism Minecraft 26.3 và một client Modrinth Light Craft Minecraft 26.3 (173 mods); client C có mod nên đây chưa phải ca ba client vanilla.

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
| Ba client kết nối đồng thời | PASS trước và sau lần điều chỉnh thiết lập ban đầu; log có ba danh tính riêng. Chưa kiểm tra cả ba đăng nhập lại sau cold restore cuối |
| A lập tông môn, mời B/C | PASS, bấm xác nhận 8 lần cho mỗi thao tác; một CREATE, hai INVITE |
| B/C nhận lời mời qua GUI | PASS, 8 click mỗi lần; hai ACCEPT, ba membership, một LEADER. [Client C](three-members-client-C.png), [lịch sử C](sect-history-client-C.png) |
| A tạo động phủ và dự án | PASS qua `/cv home` và GUI tạo dự án; 8 click chỉ tạo một project, hai stage READY, một lịch sử CREATE. [Xác nhận đã dùng](project-created-repeat-confirm.png) |
| A thuê sàn với ngân khố trống | PASS cho trường hợp thiếu tiền: GUI báo tài khoản SECT không đủ Linh Tệ. 0 contract, 0 escrow, 0 Resource Ledger, hai stage vẫn READY. Không gọi đây là ca thiếu nguyên liệu vì tiền bị kiểm tra trước. [Client](hire-rejected-no-money.png) |
| GUI B mở trước khi thu hồi quyền chi | PASS ở ca ngân khố trống: A đổi B thành ELDER, cấp quyền chi; B mở xác nhận rút 2; A thu hồi bằng GUI khác; B bấm GUI cũ 8 lần. Lỗi rõ "Quyền chi ngân khố đã bị thu hồi hoặc chưa được cấp trong DB", không tạo WITHDRAW/ledger. Cần lặp lại với ngân khố có tiền khi hoàn thành luồng. [GUI cũ](withdraw-open-before-revoke.png), [lỗi quyền](stale-withdraw-rejected.png) |
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

## Phần còn thiếu để hoàn tất Phase 5

Người dùng đã đồng ý thao tác đào trên C, rồi yêu cầu cấp cuốc và **dựng sẵn cả hai công trình, không tính ca xây bằng tay**. API điều khiển Windows hiện có click, nhấn phím và drag, không có giữ phím/giữ chuột với thời lượng để khai thác liên tục. Theo yêu cầu, console đã cấp đúng một IRON_PICKAXE cho TyninWorld lúc 01:06:34; [log chuẩn bị](asset-preparation.log). Cuốc vanilla này không đi qua Resource Ledger, không phát hành PDC hoặc Linh Tệ. C đã đăng nhập lại sau restore; chưa chứng minh cả ba kiểm tra tài sản sau restore.

Hai template chưa được dựng: `ContractWorld.createChecked` yêu cầu ô trống khi thuê, nên phải cấp nguyên liệu/tiền bằng gameplay, mở hợp đồng và cho C nhận trước. Việc dựng bằng console sau đó chỉ hỗ trợ kiểm tra gửi/duyệt template; **không đóng điều kiện Builder đặt block bằng tay** của kế hoạch gốc. Chưa thực hiện khai thác PDC, góp tài sản, thuê C, dựng/gửi/duyệt hoặc đối soát sau phục hồi có tài sản. Không dùng fixture hoặc console tạo tài sản PDC, tiền, hợp đồng, membership hay quyền để thay các luồng GUI.

Khi tiếp tục, dùng cùng JAR và checkpoint đã restore, cả ba kết nối lại `127.0.0.1:25580`. Một định lượng nhỏ để kiểm chứng là:

1. C khai thác 17 `linh_thach_tho` và 1 `huyen_thiet` thật tại Prison. Qua GUI giao nhận, C chuyển 7 raw cho A, 10 raw và 1 `huyen_thiet` cho B. A craft 5 raw thành 1 `linh_thach`, bán 2 raw tại NPC lấy 4 Linh Tệ; B craft 10 raw thành 2 `linh_thach`. Việc bán là mint hợp lệ có ledger/hạn mức, tách rõ khỏi các chuyển tiền hợp đồng.
2. A góp 4 Linh Tệ vào ngân khố, góp 1 `linh_thach` vào project; B góp 2 `linh_thach` và 1 `huyen_thiet`. Mỗi xác nhận thử click lặp và đối soát token/Inventory/ledger, không chỉ icon GUI.
3. Theo yêu cầu mới, chuẩn bị hai công trình bằng console sau khi hợp đồng ACTIVE, không chuẩn bị vật phẩm vanilla để C tự xây. Đây là thay đổi phạm vi bằng chứng, không phải đã PASS ca thu thập block, quyền đặt sai vật liệu/ngoài vùng hoặc xây tay. Nếu muốn chạy lại các ca đó thì dùng một phiên riêng và ghi lại thao tác client.
4. A thuê từng stage với 2 Linh Tệ qua GUI; C nhận cả hai. Thử gửi lúc thiếu block trước khi console đặt 9 COBBLESTONE tại x=67..69,y=100,z=3..5 và 3 STONE_BRICKS tại x=74..76,y=100,z=11. Không gán các block console cho audit PLACE của C.
5. C gửi, A duyệt cả hai; xác nhận lặp, thử GUI sau thu hồi quyền với tiền/nguyên liệu dương. Cuối cùng tiêu thụ đúng 3 `linh_thach` + 1 `huyen_thiet`, C nhận tổng 4 Linh Tệ, escrow/project/stage dự trữ về 0; mint/burn không đổi trong các bước góp/thuê/duyệt.
6. Backup lúc đang thi công có dự trữ/escrow, restore vào đích mới, tiếp tục hoàn thành; restart sau commit trước resync; cả ba đăng nhập và kiểm tra Inventory PDC, token, ledger, quyền, project, lịch sử và block thực tế. Không dùng kết quả phục hồi checkpoint trống ở trên thay cho ca này.

B01–B06, hard-kill giữa commit/resync khi online, tải đông người và tính nguyên tử xuyên DB/world vẫn mở. Paper tiếp tục cảnh báo world.save() thủ công; phiên này không có phép đo TPS hoặc thời gian lưu đủ để đóng vấn đề hiệu năng. V3 heightmap chưa được cung cấp hoặc nhập.
