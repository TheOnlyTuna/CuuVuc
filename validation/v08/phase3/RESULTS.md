# V0.8 Phase 3 — Ngân Khố Linh Tệ

Ngày 11/10/2026, Asia/Bangkok; nhánh `v0.8-khai-son-lap-phai`, nền Phase 2 `18d13ee` đã được người dùng chấp nhận Controlled Gameplay Prototype. Phase 1 đã được chấp nhận tại `12fdc25`. **Phase 3 đã triển khai và kiểm chứng, chờ người dùng đánh giá.** Không tự nghiệm thu toàn bộ V0.8, không merge `main`, không bật GitHub CI hoặc chứng nhận production-ready.

## Phạm vi và quyền

V007 bổ sung tài khoản SECT dùng `linh_te`, quyền `treasury_spend` và nguồn `funding_sect_id` cho hợp đồng. Ví PLAYER vẫn có số dư chuẩn tại `profiles`; SECT có một số dư tại `sect_accounts`. Cả hai đi qua `WalletLedger` và bảng `ledger` hiện tại; escrow tiếp tục dùng tài khoản/ledger hợp đồng đã có. Không giả UUID người chơi làm ngân khố, không tạo tiền tệ thứ hai hoặc quy đổi `linh_thach`.

Mỗi tông môn cũ được một tài khoản số dư 0; ELDER cũ chưa có quyền chi. LEADER luôn được chi; ELDER cần cấp quyền riêng bởi LEADER. Hạ/thay đổi role, rời và kick xóa quyền chi; thăng lại không khôi phục. MEMBER được góp và xem lịch sử. Rút chỉ về ví của chính người chi. Mỗi lệnh tiền nhận 1–1000 Linh Tệ; phí 0%. Không ký quỹ PDC hoặc thêm kho dự án, template linh hoạt, hợp đồng nhiều hạng mục hay đất tông môn.

Góp, rút, cấp/thu hồi quyền và các thao tác hợp đồng nguồn SECT cùng khóa hàng tông môn. Sau khóa, DB đọc quyền hiện tại; snapshot GUI không cấp quyền. Thứ tự cho hợp đồng là receipt → tông môn → hợp đồng/escrow → hồ sơ cần thiết → trạng thái quyền hợp đồng. Debit/credit, ledger, receipt và lịch sử tổ chức nằm cùng transaction. Lỗi rollback toàn bộ; số dư không âm. Replay cùng ý định trả receipt cũ, không tái chi hoặc khôi phục quyền; request khác tham số bị từ chối.

Hợp đồng cá nhân cũ giữ nguồn PLAYER. Hợp đồng SECT dùng nguồn ghi lúc tạo: nghiệm thu trả Builder, hủy/bỏ việc/hết hạn hoàn đúng SECT, kể cả người tạo đã mất quyền/rời tổ chức. Quản trị hiện tại có thể tiếp tục xử lý; thu hồi quyền không tự hủy khoản ký quỹ đã commit. Tạo/hủy/duyệt của người hết quyền bị từ chối. Chuyển ví/ngân khố/escrow không đổi mint/burn.

GUI `/cv treasury` hiển thị tiền, quyền, thành viên phân trang và mười bút toán mới nhất. Lệnh góp/rút/cấp/thu hồi mở xác nhận; thuê Builder dùng cùng kiểm tra world của `ContractWorld`. Xác nhận giữ một request, được tiêu thụ một lần trước khi gửi worker; sau góp/rút giữ cửa sổ đã khóa, dùng nút Làm mới để mở ý định mới. I/O DB chạy worker giới hạn; GUI/Inventory dùng entity scheduler, block dùng region scheduler theo kiến trúc hiện có. [README](../../../README.md) ghi lệnh và cách nâng cấp.

## Kiểm thử PostgreSQL thật

**70/70 đạt, 0 fail/error/skip** trên source/JAR cuối: 4 Domain có sẵn và 66 PostgreSQL integration. Không thêm unit test hoặc mock. Java 25.0.4.1, Maven Wrapper 3.9.11, PostgreSQL 18.4 trên loopback 55439; DB test riêng `cv_v08_tests`, schema riêng mỗi ca. `clean verify` hoàn tất 02:40:48. [Log Maven](maven-verify.log), [DB XML](GameDatabaseIT.xml), [Domain XML](DomainTest.xml).

Chín integration mới kiểm tra hành vi thực:

- Góp/rút, ledger chung, reopen, receipt lặp, lịch sử bất biến, người ngoài và bảo toàn quota.
- Cấp/thu hồi, hạ rồi thăng role, rời/kick; replay cấp quyền cũ không cấp lại.
- Request rút chờ khóa thật trong PostgreSQL; thu hồi dưới cùng khóa rồi commit khiến request bị từ chối, không ghi operation mới.
- 12 request trùng chạy đồng thời chỉ chi một lần; sáu khoản chi khác nhau cạnh tranh số dư chỉ ba thành công.
- Trigger lỗi tại ledger hoặc lịch sử rollback cả hai tài khoản và receipt.
- Tạo ký quỹ và rút cạnh tranh: đúng một thắng, không có hợp đồng/lịch sử giao dịch một phần.
- Trả công, hủy, Builder bỏ việc và hết hạn: đúng nguồn SECT, trả/hoàn một lần.
- Người tạo bị thu hồi/kick không được tạo/hủy/duyệt mới; LEADER hiện tại vẫn xử lý được.
- Nâng V006→V007 giữ wallet/ledger/membership và escrow cá nhân đang mở; hoàn hợp đồng cá nhân về ví cũ, tài khoản SECT backfill 0.

V001–V006 và thuật toán fingerprint giữ nguyên. Fingerprint V007 được tạo từ SQL nguyên byte; lần thử ban đầu chuyển xuống dòng trong thân hàm PostgreSQL đã tạo hash khác và bị startup/test từ chối. Đã sửa hash của migration V007 chưa phát hành, không bỏ kiểm tra hoặc sửa checksum cũ. Cải thiện fingerprint cấu trúc dài hạn vẫn là B05.

## Hai Minecraft Client và binary cuối

Paper **26.3 build 166 BETA**, API `26.3.build.166-beta`, Java 25.0.4.1. Đây là phiên bản khóa của prototype, không tuyên bố mới nhất hoặc hỗ trợ Folia. Hai client Minecraft Java 26.3 Prism là `CuuVucNPC2` (A, chưởng môn) và `CuuVucNPC1` (B, Builder/ELDER), loopback `127.0.0.1:25580`, offline-mode/whitelist theo cho phép đã có. `SectNPC3` chỉ là hồ sơ offline kế thừa Phase 2, không phải client thứ ba.

JAR cuối `CuuVucCore-0.8.0-SNAPSHOT.jar`, SHA-256:

```text
01410d740768cbe03fadc04a131e9669ceae7700db0c3f8b1cf30671f9dd813d
```

Candidate trước sửa GUI có hash `d87923ace92958b3a2f92353c739e0dfdf8ccef839559e1c0713d6f4072646c0`: A bán sáu nguyên liệu được 12 Linh Tệ, góp 10. Bấm lặp không góp hai lần, nhưng chuyển màn hình tự động khiến click còn lại chạm một nút khác. [Ảnh lỗi](candidate-repeat-navigation.png), [DB candidate](candidate-repeat-db.txt), [log](candidate-client-session.log). Đã thay bằng xác nhận tiêu thụ một lần và giữ cửa sổ khóa; sau đó chạy lại 70 tests và những luồng dưới đây trên chính JAR cuối.

| Ca trên JAR cuối | Bằng chứng và kết quả |
|---|---|
| A góp 2, thử tám click liên tục | Chỉ một góp 2; SECT 12, ví A 0, chưa tạo hợp đồng. [GUI](final-repeat-confirm.png), [DB](final-repeat-confirm.txt). |
| B bán bốn nguyên liệu được 8, góp 2 và thử bấm lặp | SECT 14, ví B 6; hai người đã góp. Tổng phát hành 20, tiêu hủy 0. [GUI](member-contribution.png), [DB](two-contributors.txt). |
| A cấp ELDER và quyền chi; B rút 2, thử tám click | Chỉ một khoản rút; SECT 12, ví B 8. [GUI rút](authorized-withdraw-repeat.png). |
| B giữ xác nhận rút tiếp; A thu hồi quyền rồi B bấm GUI cũ | Bị từ chối theo quyền DB. SECT 12, B 8, vẫn một WITHDRAW và một receipt rút. [GUI cũ sau thu hồi, trước bấm](captured-withdraw-before-revoke.png), [quyền DB](revoked-before-old-gui.txt), [thông báo](old-gui-withdraw-rejected.png), [đối soát](old-gui-withdraw-rejected.txt). Tên ảnh không biểu thị thời điểm thu hồi. |
| A thuê 2 rồi hủy khi chưa có Builder | SECT 10/escrow 2 khi tạo, hoàn đúng SECT 12; ví A vẫn 0. [Nạp](sect-deposit.txt), [hủy](sect-contract-cancelled.png), [hoàn](sect-refund.txt). |
| A thuê lần hai; B nhận và gửi nghiệm thu | ACTIVE rồi SUBMITTED, escrow 2, SECT 10, B 8; chưa trả công lúc nhận/gửi. [Nhận](builder-accept-no-payment.txt), [gửi](builder-submit.txt). |
| B thử duyệt khi đã mất quyền chi | Thông báo từ chối; vẫn SUBMITTED, RELEASE 0, không receipt complete. [Client](revoked-approval.png), [DB](revoked-approval.txt). |
| A duyệt và thử bấm lặp | COMPLETED, trả đúng 2 một lần: B 10, SECT 10, escrow 0. [Client](sect-builder-payment.png), [đối soát cuối](final-money-reconcile.txt). |

[Log toàn phiên JAR cuối](final-client-session.log) ghi command, startup, drain và world I/O. Thử bấm liên tục là thao tác GUI, không suy ra số packet. Hai hợp đồng cuối là `b2c6b5a8-8abc-45f1-9764-193991d69db8` (CANCELLED) và `0b41db49-4426-4567-8c54-506ce5d701bc` (COMPLETED). Tổng ví 10 + SECT 10 + escrow 0 = **20**, bằng minted 20 − burned 0 từ bán nguyên liệu; mọi giao dịch ngân khố giữ tổng này.

**Sàn chín block cobblestone được chuẩn bị bằng console**, tại `(71..73,100,7..9)` sau Builder nhận. B thực hiện nhận/gửi, A thực hiện duyệt qua client; chưa xác minh B đặt từng block bằng Inventory trong Phase 3. Đây là E2E ngân khố và hợp đồng nguồn SECT, không phải nghiệm thu thi công ba người Phase 5. Cạnh tranh chi tiền/hợp đồng, abandon/expire và migration legacy được xác minh ở PostgreSQL, chưa gọi là client E2E.

## Migration, restart và phục hồi

Không dùng runtime đã nghiệm thu làm writer thử. Cold bundle Phase 2 được phục hồi vào DB mới `cv_v08_treasury` và runtime `run-v08-treasury`, sau đó nâng V007 bằng JAR cuối. [Trạng thái trước migration](pre-migration.txt). Restart bằng JAR cuối giữ tổ chức, quyền, kho và số dư; startup đạt `cuuvuc_database_ready`.

Sau luồng tài chính, dừng server mềm và tạo `backups/v08-treasury-final-001`; phục hồi vào DB/runtime mới `cv_v08_treasury_restored` / `run-v08-treasury-restored`. Script kiểm 242 file bundle; kiểm trực tiếp **105/105 file world ở đích** trước startup. Snapshot dữ liệu **25/25 bảng** trùng, SHA-256 `2aaceb79be4bb79f4226e1e85b2e1281b226fed17a80ef64034110858e856efc`. Bao gồm ví, SECT, escrow/ledgers, kho/token/resource ledger, membership/quyền, receipts/lịch sử. [Manifest](backup-manifest.json), [trước](before-restore.jsonl), [sau](after-restore.jsonl), [đối soát](restore-check.txt).

Restore chạy đúng JAR cuối, không lỗi schema drift. Sau tải chunk, điều kiện kiểm đủ chín block cobblestone trả game time `1096568` ticks lúc 17:26:31; forceload thử được gỡ ngay. B đăng nhập lại và mở ngân khố: **SECT 10, ví 10, quyền chi false**, không có nút rút/thuê. [Ảnh sau restore](restored-treasury.png), [DB sau rejoin](restored-state.txt), [log phục hồi](restored-session.log).

Cả runtime nguồn và phục hồi đã dừng mềm, world I/O hoàn tất; marker `drained=true`, `contract-audit-persisted=true`. [Marker nguồn](final-shutdown.properties), [marker phục hồi](restored-shutdown.properties). PostgreSQL thử đã dừng mềm. Đây là cold backup sau giao dịch kết thúc, chưa phải phục hồi hợp đồng đang thi công hoặc hard-kill giữa DB/world. Không có file V3/heightmap được nhập.

## Giới hạn và triển khai

Giữ [B01–B06](../../../BACKLOG.md): audit block async chưa bền vững trước kill, `world.save()` thủ công chưa đo tải/TPS, mẫu sàn cố định, giới hạn kiểm thử client/PDC và fingerprint. Không hard-kill, không tải đông người, không bảo đảm nguyên tử xuyên DB/world. Nút duyệt hợp đồng SECT được hiển thị theo membership; DB vẫn quyết định quyền chi. Một thử duyệt không có quyền đã đi qua kiểm tra/lưu world trước khi bị DB từ chối: không chuyển tiền nhưng vẫn có chi phí world I/O, cần cải thiện cùng B02. Lỗi OSHI Perflib của Windows vẫn xuất hiện; không sửa registry.

Không dùng kết quả này để lấp giới hạn toàn bộ lập/mời/nhận Phase 2 trên binary cuối hoặc toàn bộ nghiệm thu V0.8. Phase 4 kho dự án/PDC và Phase 5 ba người chưa triển khai. GUI ngân khố chỉ hiển thị mười bút toán mới nhất; lịch sử đầy đủ nằm trong PostgreSQL.

Trước nâng cấp thực tế: dừng mọi writer, cold backup DB/world/JAR/config bằng script hiện có, restore thử vào đích mới và đối soát, rồi chạy JAR V007. Không sửa migration cũ, bỏ fingerprint hoặc chạy hai runtime trên cùng DB. Rollback cần backup trước V007 vào đích mới và JAR Phase 2; hạ JAR trực tiếp trên schema mới bị từ chối. Lệnh build/test và backup/restore được giữ tại [README](../../../README.md), không yêu cầu GitHub CI có phí.
