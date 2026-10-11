# V0.8 Phase 2 — Tông Môn: triển khai và kiểm chứng

Ngày 11/10/2026, Asia/Bangkok; nhánh `v0.8-khai-son-lap-phai`.

**Phase 1 ACCEPTED** tại `12fdc25`: Gameplay Prototype, 53/53 tests, 20/20 bảng DB khớp. **Phase 2 ACCEPTED — Controlled Gameplay Prototype** tại `18d13ee` theo quyết định người dùng; chưa nghiệm thu toàn bộ V0.8 hoặc production. Giới hạn gameplay binary cuối vẫn giữ bên dưới. Không merge main, không bật CI. Ngân khố được kiểm chứng riêng trong [báo cáo Phase 3](../phase3/RESULTS.md).

## Phạm vi

V006 thêm `sects`, `sect_members`, `sect_invitations`, `sect_history`. Giữ nguyên V001–V005 và Economy/ContractEscrow. Tông môn có tên Unicode chuẩn hóa không trùng hoa/thường; người lập là LEADER. ELDER được mời/hủy lời mời, LEADER thêm quyền đổi MEMBER/ELDER và loại thành viên. Thành viên được xem lịch sử/rời; prototype không chuyển chức, giải tán hoặc cho chưởng môn rời. Chưa cấp quyền đất hoặc quyền chi tiền cho vai trò tông môn.

Một thành viên được bảo vệ bởi PK `sect_members.player_id`; FK deferred bảo đảm chưởng môn thuộc chính tông môn với role LEADER, partial unique index chỉ cho một LEADER. Thành lập khóa hồ sơ; nhận lời mời khóa tông môn rồi hồ sơ. Mọi quản trị/thu hồi quyền khóa cùng bản ghi tông môn và đọc role mới trong transaction READ COMMITTED. Quyền GUI không quyết định quyền DB; quyền đổi ở tổ chức khác không làm ý định cũ hợp lệ. Đây là thứ tự khóa dựa trên [PostgreSQL 18](https://www.postgresql.org/docs/18/explicit-locking.html), kết hợp [ràng buộc DB](https://www.postgresql.org/docs/18/ddl-constraints.html).

Lời mời tồn tại một giờ theo `clock_timestamp()`, đúng người nhận, chỉ nhận khi còn PENDING/chưa hết hạn/chưa có tổ chức. Lời mời đã cấp hợp lệ không tự hủy khi người mời bị hạ quyền; quản trị còn quyền có thể hủy. Retry cùng request trả receipt cũ; tham số/actor khác bị từ chối. Receipt, thay đổi tổ chức, revision và lịch sử commit/rollback cùng nhau; lịch sử chặn UPDATE/DELETE/TRUNCATE. Không có giao dịch tiền/tài nguyên trong Phase 2.

GUI `/cv sect` có thành viên phân trang, lời mời, lịch sử và xác nhận ý định. Mỗi cửa sổ xác nhận giữ một request ID. Snapshot có kiểu, bất biến được chuyển từ worker DB về entity scheduler qua cổng `PlayerTasks` dùng chung với gameplay cũ; không dùng global cache role làm quyền. Lịch sử hiển thị tên hiện tại từ profiles; actor/target UUID và thời điểm gốc được giữ bất biến tại DB. Xem [README](../../../README.md) để chạy và dùng các lệnh.

## Tests thực chạy

**61/61 đạt** trên source cuối: 4 Domain + 57 integration PostgreSQL 18.4, 0 fail/error/skip. `clean verify` cuối hoàn tất 02:07:21. [Tóm tắt](test-summary.txt), [Domain XML](DomainTest.xml), [DB XML](GameDatabaseIT.xml).

Tám integration mới xác minh:

- Luồng lập/mời/nhận/cấp-hạ quyền/rời/tái gia nhập/kick, reopen connector, lịch sử bất biến, không thay Economy/Resource Ledger.
- Hai lời mời từ hai tông môn được nhận đồng thời: đúng một membership/receipt, một bên thua không có thay đổi một phần; PK còn từ chối insert trùng trực tiếp.
- Thành lập và nhận lời mời cạnh tranh: chỉ một thắng, không tạo tổ chức/membership/receipt của bên thua.
- Ý định từ snapshot ELDER chờ khóa thật: fixture hạ role dưới cùng khóa, quan sát backend chờ trong pg_stat_activity, rồi commit; thao tác đọc quyền mới và bị từ chối. Sau kick cũng không quản trị được. Thu hồi quyền qua API và GUI được xác minh riêng trong lifecycle/client.
- Sai người nhận, hết hạn giờ DB, hủy lời mời, lời mời trùng còn hạn và replay/fingerprint.
- Trigger thật gây lỗi INSERT lịch sử: lời mời, revision và receipt rollback cùng nhau.
- Migration v5→v6 giữ wallet/material/token/receipt/Resource Ledger, initialize lặp đạt.
- Tên chuẩn hóa trùng, tên sai và sửa role chưởng môn trực tiếp bị từ chối bởi FK.

## Client và binary

Paper **26.3 build 166 BETA**, API `26.3.build.166-beta`, Java 25.0.4.1, Maven Wrapper 3.9.11. Hai Minecraft Java 26.3 client Prism trên loopback `127.0.0.1:25580`, offline-mode và whitelist theo cho phép đã có. Không tuyên bố build mới nhất hoặc Folia support; không sửa runtime Phase 1 đã nghiệm thu hoặc world V3.

Binary đầu: `d898cba726433bc149c6c8f99b4374cadf7bd32f2b5d4a773de175332b0afed3`.
JAR cuối `CuuVucCore-0.8.0-SNAPSHOT.jar`: **`68154ef0de7a4ce293f4abb210b06319c7637bd78b61b27dfbfb2e5398bd5587`**.

| Phiên | Thực hiện và đối soát |
|---|---|
| Binary đầu, hai client | CuuVucNPC2 lập Thanh Van qua GUI, thử tám click liên tục; đúng một tổ chức/sự kiện. Mời CuuVucNPC1, client đích mở GUI nhận và thử xác nhận liên tục; đúng một lần nhận. Cấp ELDER; đích mở xác nhận mời người thứ ba, chưởng môn hạ xuống MEMBER; GUI cũ bấm lặp bị từ chối. [Log](client-session.log), [thành lập](created.png), [gia nhập](member-joined.png), [thông báo](revoked-role-message.png), [DB](client-db-check.txt). |
| Restore đầu | Cold backup/restore vào đích mới, 24 bảng trùng; client NPC1 rejoin, giữ MEMBER/revision 5, đọc năm sự kiện. [Log](restored-session-before-display-fix.log), [quyền](restored-member-role.png), [snapshot trước](before-restore.jsonl), [sau](after-restore.jsonl). Phát hiện lore lịch sử quá dài vì UUID. |
| JAR cuối | Sửa riêng snapshot tên người và cách hiển thị lịch sử; bộ 61 tests chạy lại. Client đọc được lịch sử sau restart trên DB đã phục hồi. Hai client trên JAR cuối lặp cấp ELDER, giữ GUI mời mở, hạ MEMBER và xác nhận lặp: bị từ chối, không tạo lời mời thứ ba. [Lịch sử rõ tên](final-readable-history.png), [GUI cũ](final-stale-confirmation.png), [thông báo cuối](final-revoked-role-message.png), [log cuối](final-client-session.log), [DB cuối](final-client-db-check.txt). |

Cuối phiên có hai thành viên, bảy sự kiện/receipt tông môn (năm trước + hai lần đổi quyền trên JAR cuối), không có lời mời cho UUID fixture thứ ba. Tổng tài nguyên giữ 20; 14 bút toán tài nguyên có tổng delta 0. [Fixture](client-fixture.sql) chỉ đăng ký hồ sơ offline `SectNPC3`, không thêm tiền/tài nguyên. Đây là hai client thật và một hồ sơ offline, không gọi là nghiệm thu ba người Phase 5.

Bấm liên tục là các click thử trong GUI; không suy ra số packet đã đến server. Lập/mời/nhận ban đầu chạy trên binary đầu; sau sửa hiển thị đã lặp đúng phần ảnh hưởng snapshot/lịch sử và ca quyền GUI với hai client trên JAR cuối, **chưa lặp toàn bộ lập/mời/nhận trên binary cuối**. Luồng đó được kiểm tra lại bằng integration cuối. Rời/kick/hết hạn/đua vào hai tổ chức là integration, chưa phải E2E client.

## Phục hồi cuối

Backup khi server đã stop mềm, `drained=true`, world I/O hoàn tất. Bundle cuối chứa 239 file đã kiểm SHA-256 bởi script restore, gồm 104 file world. Restore vào `cv_v08_sects_final_restored` và runtime mới. Snapshot **24/24 bảng** khớp SHA-256 `7fc2577b73e367789c3d8c8b0e94402b73642916212141c9b384297fe4097746` trước khi startup, bao gồm memberships, role, invitations, history, receipt, wallet, kho, token và ledgers. [Manifest cuối](final-backup-manifest.json), [trước](final-before-restore.jsonl), [sau](final-after-restore.jsonl), [kiểm tra](final-restore-check.txt).

JAR cuối từ bundle khởi động lại đạt `cuuvuc_database_ready`, rồi shutdown mềm exit 0. [Log](final-restored-startup.log), [marker](final-restored-shutdown.properties). Không có schema drift sau pg_restore V006. Log vẫn có lỗi OSHI Perflib môi trường Windows đã biết; không sửa registry. Server thử và PostgreSQL đã tắt mềm.

## Giới hạn giữ lại

Không hard-kill, không tải đông người, chưa thử mọi đường PDC, không tuyên bố chống crash tuyệt đối hoặc atomic xuyên DB/world. Giới hạn Phase 1 và cải thiện fingerprint được ghi [BACKLOG B05/B06](../../../BACKLOG.md). Không thay thuật toán fingerprint/checksum cũ trong Phase 2. GUI hiển thị mười sự kiện và chín lời mời gần nhất mỗi nhóm, chưa có tìm kiếm toàn lịch sử; DB giữ lịch sử đầy đủ. Trong phạm vi Phase 2 chưa có ngân khố SECT, dự án hoặc hợp đồng tông môn; trạng thái các bước tiếp theo xem [kế hoạch](../../../V0.8-PLAN.md).
