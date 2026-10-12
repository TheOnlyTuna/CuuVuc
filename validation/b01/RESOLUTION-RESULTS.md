# B01 — Recovery Resolution Workflow

**TECHNICAL + CONTROLLED CLIENT VALIDATION · B01 OPEN.** V0.8 giữ ACCEPTED WITH SCOPE LIMIT. Journal/fence tại `13d3c35` đã được người dùng ACCEPTED; báo cáo này ghi kiểm thử mới trên binary workflow, chưa phải quyết định nghiệm thu của người dùng.

## Quyết định và bằng chứng

V010 bổ sung quyền phục hồi riêng trong PostgreSQL, lịch sử cấp/thu hồi quyền, review bất biến và quyết định bất biến. Trigger chặn UPDATE/DELETE/TRUNCATE các bảng lịch sử bằng cơ chế ledger hiện tại. Đây là bảo vệ đường ứng dụng và SQL thông thường, không chống quản trị viên sở hữu DB cố ý vô hiệu hóa trigger. Checksum V001–V009 giữ nguyên; fingerprint V010 là `2dadaf8d9a80df38545b5c79c3afc56b55effe5ef62c1775f7430411d0c4220d`.

Review lưu hợp đồng, escrow, fence, toàn bộ journal/receipt/audit, escrow ledger, dự trữ hạng mục, quyết định trước đó và phiên quyền quản trị. World được đọc qua Region Scheduler; worker ghi review và xuất hồ sơ với `FileChannel.force(true)`. Nếu xuất file thất bại, review vẫn trong DB; `show` xuất lại. Review không mở khóa hoặc chuyển tài sản.

`KEEP_LOCKED` ghi lý do và giữ nguyên fence/INTENT. World trong quyết định này là quan sát của review, không phải một lần quan sát mới. Mỗi review có tối đa một quyết định, không được sửa từ HOLD sang RESOLVE; muốn xem xét tiếp phải tạo review mới.

`RESOLVE_INTENTS_AND_REOPEN` đọc world lại rồi kiểm tra quyền DB, khóa tông môn/hạng mục/hợp đồng, kiểm tra lại thời hạn 10 phút và toàn bộ evidence. Đổi quyền, kể cả thu hồi rồi cấp lại, đổi fence/DB hoặc có quyết định khác đều làm review cũ lỗi thời. World phải giống review và khớp audit cuối; mọi APPLIED phải có proof. INTENT chỉ được kết thúc nếu ô còn vật liệu trước ý định. Thiếu receipt, world lệch, audit/queue cũ chưa liên kết journal hoặc không có bằng chứng thì giữ khóa, không có Force Unlock.

Kết thúc INTENT bằng **REVIEWED_UNCONFIRMED**, liên kết tới quyết định bất biến; không dùng APPLIED hoặc ABORTED. Trạng thái này ghi nhận người quản trị chấp nhận giải quyết phần chưa xác minh, không khẳng định callback chưa từng xảy ra. Không tạo PLACE/BREAK, receipt block, tiền công hoặc tiêu thụ tài nguyên. Ghi quyết định, chuyển trạng thái journal, xóa fence và tăng revision commit cùng transaction. Startup kiểm tra liên kết quyết định và ghi `MANUAL_DECISION_NOT_EVENT_PROOF`; không coi quyết định quản trị là proof của sự kiện.

Quyền DB và quyết định cùng advisory lock `5080510`, sau đó dùng thứ tự khóa hợp đồng hiện tại. Yêu cầu đang đợi khóa phải thấy quyền đã thu hồi. Replay cùng review/loại/lý do trả quyết định đã commit; đổi nội dung bị từ chối. Cấp quyền qua console vẫn lưu lịch sử. Permission Bukkit riêng là lớp kiểm tra khi nhận lệnh; **thu hồi vận hành phải dùng console revoke quyền DB**, không chỉ thay permission Bukkit.

Connector giữ vùng bị khóa trong lúc kiểm tra; yêu cầu journal/queue ngừng xử lý trước review/resolve. Ticket lỗi còn giữ trong RAM có thể cần restart để startup đối soát trước khi review. Sau commit, publish board mới rồi mới bỏ local hold. Lỗi DB hoặc lỗi refresh giữ nguyên trạng thái an toàn; có thể cần restart/đối soát tiếp nếu phản hồi không chắc chắn. Hợp đồng đã hết hạn khi mở fence có thể được tác vụ expiry xử lý theo quy tắc hiện có; quyết định phục hồi tự nó không giải ngân hoặc hoàn tiền.

## Quản trị thử nghiệm

Chỉ dùng console tin cậy và UUID hồ sơ đã đăng ký:

```text
cv recovery grant <UUID-quản-trị> <lý do 8..500 ký tự>
cv recovery revoke <UUID-quản-trị> <lý do 8..500 ký tự>
```

Người quản trị cần `cuuvuc.recovery` và quyền DB đang bật. Trong client:

```text
/cv recovery review <contract UUID>
/cv recovery show <review UUID>
/cv recovery hold <review UUID> <lý do 8..500 ký tự>
/cv recovery resolve <review UUID> <lý do 8..500 ký tự>
```

Đọc hồ sơ ở `plugins/CuuVucCore/recovery/reviews/` trước khi quyết định. Lý do không được có ký tự điều khiển. Những ô không có audit/journal vẫn xuất hiện trong quan sát; UNKNOWN_HISTORY không tự biến thành lịch sử đã xác minh. Không dùng SQL sửa proof hoặc world để vượt kiểm tra. Trường hợp không thể xác minh tiếp tục giữ fence để điều tra, không có cơ chế tự tạo sự kiện hoặc tự bồi thường.

## Kiểm thử trên binary đề xuất

Paper 26.3 build 166, Java Corretto 25.0.4.1, PostgreSQL 18.4; giữ phiên bản Paper đã kiểm chứng. Core JAR SHA-256:

`19a087c485c8e7f5d1063c0e1ac9d2ec6a436e9cf00bf1c1d355c12b2ffbe0c3`

- **93/93 tests**, không skipped: 4 pure-data và 89 PostgreSQL integration. Tám ca mới kiểm nâng V009 có INTENT/fence/escrow, resolve/replay, bất biến/HOLD, world/DB/TTL lỗi thời, quyền thu hồi/cấp lại và khác người review, thiếu receipt, rollback/restart, quyền bị thu hồi khi request đang chờ advisory lock. [Log](resolution/verify.log), [DB summary](resolution/db-tests.txt), [pure-data summary](resolution/domain-tests.txt).
- Hồi quy queue/tài chính: [j03/completed.txt](j03/completed.txt). Hồi quy journal: [j04/completed.txt](j04/completed.txt). Các ca này là fixture/probe, không phải gameplay client.
- Quyết định trước/sau commit: [r02/completed.txt](r02/completed.txt), [hash](r02/binary-hashes.txt). Restore nguồn có manifest vào DB/runtime riêng, bind loopback `25593`; không thay runtime/checkpoint client cũ.

Ca trước commit dùng trigger thử làm DELETE fence chờ 10 giây. Watcher thấy `PgSleep` rồi kill đúng Java PID/path; gỡ trigger trước restart để kiểm schema drift bình thường. Sau restart phải còn 1 INTENT, 1 fence, 0 quyết định; retry tạo đúng một quyết định. Ca sau commit kill sau phản hồi connector; restart phải giữ 1 REVIEWED_UNCONFIRMED, 1 quyết định, 0 fence; replay không nhân đôi quyết định. Cả hai giữ escrow 4 LT, tổng tiền 4 LT, minted=4/burned=0, 29 token và dự trữ 4 đơn vị, không RELEASE/CONSUME/PLACE/BREAK mới. Console revoke và regrant đều được chạy qua executor thật; connector từ chối cả quyền bị thu hồi và review thuộc phiên quyền cũ.

Probe gọi connector quản trị bằng reflection và đọc world qua scheduler thật. Các ma trận là **Paper smoke/fixture**, được phân biệt với phiên client dưới đây. Không dùng hai ranh giới native ở báo cáo `13d3c35` để ghi PASS cho binary mới. Nguồn backup ACTIVE giữ nguyên; các lần STOP chuẩn bị/cleanup được phân biệt với fault kill thật.

## Minecraft Client trên chính JAR mới

Runtime riêng `run-b01-client-r02`, DB `cv_b01_client_r02`, loopback `25594`, restore từ bản sao thử nghiệm đã dừng, [manifest nguồn](client-r02/source-manifest.json). Runtime/checkpoint client cũ không bị thay đổi. C là TyninWorld, client Modrinth có mod; không tuyên bố vanilla hoặc tương thích mọi mod. [Hash core triển khai](client-r02/binary-hashes.txt) khớp hash phía trên.

C có kết nối ngắn vào runtime journal cũ trước khi chuyển đúng endpoint; log `client-j01/fenced.stdout.log` được nối thêm dòng đăng nhập/rời server. Không sửa JAR/DB/runtime cũ và không tính kết nối đó vào kết quả workflow mới.

Fence ban đầu được tạo từ **INTENT fixture SQL** sau restore, không gọi đó là crash client mới. Ô `(67,100,5)` là AIR; các block template khác có sẵn từ fixture. Probe chỉ chuẩn bị nền Dirt/teleport, không dựng block hợp đồng trong ca đặt/phá. Cấp OP và quyền DB cho C tạm thời để thử quản trị; sau đó thu hồi cả hai trước khi thử Survival.

| Ca thực hiện từ client | Bằng chứng |
|---|---|
| Chưa có permission Bukkit; có OP nhưng chưa có quyền DB | [Bukkit từ chối](client-r02/bukkit-denied.jpg), [DB từ chối](client-r02/database-denied.jpg) |
| Review rồi revoke; resolve bị từ chối, fence còn nguyên | [Ảnh](client-r02/revoked-resolve-denied.jpg), [DB](client-r02/revoked-still-fenced.txt) |
| Regrant không làm review thuộc phiên quyền cũ hợp lệ | [Ảnh](client-r02/stale-epoch-denied.jpg) |
| Review mới và HOLD giữ INTENT/fence | [Ảnh](client-r02/hold-committed.jpg), [DB](client-r02/hold-still-fenced.txt) |
| Review mới, đọc hồ sơ rồi resolve có lý do | [Review](client-r02/final-review.txt), [Ảnh](client-r02/resolve-committed.jpg), [DB](client-r02/resolved-db.txt) |
| Gửi lại cùng quyết định không tạo thêm quyết định | [Ảnh](client-r02/replay-returned.jpg), [DB](client-r02/replay-once.txt) |
| `show` xuất review và quyết định đã lưu | [Hồ sơ](client-r02/shown-decision.txt) |
| Sau thu hồi OP/quyền DB, C Survival tự đặt 1 và phá 1 Cobblestone tại ô đã mở | [Đối soát](client-r02/completed-reconcile.txt), [World](client-r02/completed-world.txt) |

Quyết định resolve của C là `e7f1330b-4366-49db-bab2-0caa015dcf40`; lý do ghi rõ chấp nhận phần chưa xác minh, không tạo lịch sử. Hai INTENT fixture là REVIEWED_UNCONFIRMED, không có audit_event_id. Hai callback Survival mới là APPLIED với audit PLACE/BREAK và receipt khớp event ID/actor/hợp đồng/tọa độ. Người dùng xác nhận thực hiện đặt/phá; DB ghi chính xác một lần mỗi loại. World cuối AIR, không còn fence.

Ba quyết định tổng cộng gồm một resolve fixture trước phiên, một HOLD và một resolve của C. Escrow giữ 4 LT; ví/ngân khố 0; minted=4/burned=0; hai hợp đồng ACTIVE, không RELEASE/REFUND/CONSUME. Trong phiên, C đào hợp lệ thêm một Linh Thạch Thô ở Prison trước khi tạm dừng: kho cá nhân từ 22 lên 23 thô, Huyền Thiết vẫn 7, token từ 29 lên 30. [Baseline sau đào](client-r02/after-legitimate-mining.txt) được dùng để đối soát; không quy tăng tài nguyên này cho resolve. [Log phiên](client-r02/client-session.log) ghi các lệnh quản trị và thu hồi quyền.

Sau shutdown mềm có drain DB và lưu world, restart cùng JAR trả READY. [Đối soát trước](client-r02/completed-reconcile.txt) và [sau](client-r02/restarted-reconcile.txt) giống hệt, cùng `CLIENT_RESOLUTION_ASSERTIONS_PASS`; [SQL kiểm tra](client-r02/reconcile.sql). [World sau restart](client-r02/restarted-world.txt) vẫn AIR. [Startup recovery](client-r02/restarted-recovery.tsv) phân biệt hai quyết định quản trị `MANUAL_DECISION_NOT_EVENT_PROOF` với hai callback APPLIED `CONFIRMED`; không tạo fence mới. Không tính restart mềm này là hard-kill; chưa thử thao tác client thêm sau restart.

Lần launch restart đầu thiếu biến môi trường `CUUVUC_DB_PASSWORD`: plugin từ chối enable, không READY. Tiến trình riêng đó được dừng cưỡng bức sau kiểm PID/path rồi chạy lại với biến môi trường đúng; không tính là ca fault injection đạt. [Log restart thành công](client-r02/restart.stdout.log) và [stderr của launch lỗi](client-r02/restart-missing-env.stderr.log) được giữ; stdout lần lỗi đã bị launch lại ghi đè. Không có thay đổi mã để che lỗi cấu hình.

## Phần còn mở

B01 chưa đóng. Client đã thử permission, revoke/regrant, HOLD, resolve/replay và Survival sau mở fence; các ca thiếu receipt/world lệch/TTL mới ở PostgreSQL integration, chưa lặp toàn bộ qua client. Chưa kill giữa ghi chunk/region file hoặc fsync trên JAR này. Ca WorldSaveEvent hồi quy chỉ kill trong callback trước lưu chunk, không thay thế I/O thực tế. Chưa thử plugin khác thay block trong khoảng giữa quan sát region và DB commit; hai hệ thống không có transaction nguyên tử. World sau mở khóa có thể bị tác nhân bên ngoài thay đổi, không được suy diễn kết quả quan sát thành bảo đảm lưu bền. Chưa có công cụ sửa world/khôi phục receipt/bồi thường cho trường hợp UNRECOVERABLE, thử crash PostgreSQL/mất điện, tải đông người hoặc Folia runtime. Probe OBSERVE tắt autosave của cv_sky; restart mềm dưới đây có lưu world khi shutdown, không đại diện cho cấu hình tải thực tế hay hard-kill trong I/O.

Chạy lại suite bằng cấu hình DB thử hiện có. Build probe bằng `build-probe.ps1`, rồi chạy `run-resolution-matrix.ps1` với `-JavaHome`, `-PostgresBin`, `-BundleDirectory` và `-RunId` mới. Không chạy probe hoặc kill trên server nghiệm thu/production. B02 chưa bắt đầu; main giữ nguyên và GitHub CI tiếp tục tắt.
