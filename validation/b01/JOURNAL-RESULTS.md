# B01 — Durable Audit Journal và Recovery Fence

**TECHNICAL + CONTROLLED CLIENT PASS · B01 OPEN.** V0.8 giữ Controlled Gameplay Prototype / ACCEPTED WITH SCOPE LIMIT. Không nâng Alpha, merge `main`, chuyển B02 hoặc bật GitHub CI.

## Ranh giới bảo đảm

Native PLACE/BREAK dùng hai nhịp. Callback đầu bị hủy để worker commit `INTENT` vào PostgreSQL. Người chơi thử lại đúng ô, vật liệu và thao tác trong 10 giây; quyền hiện tại và lease vẫn phải hợp lệ. Chỉ một ticket đang xử lý trên mỗi hợp đồng. Callback được phép phải không bị hủy; region scheduler đọc block ở tick kế. Sau đó `APPLIED`, `building_history` và receipt `operations` commit trong cùng transaction, cùng event UUID. Receipt đã commit được trả lại khi replay, không tạo audit hay thanh toán mới. Ticket không được dùng thì chuyển `ABORTED`, không sinh PLACE/BREAK.

`INTENT` là ý định, **không phải sự kiện đã xảy ra**. `APPLIED` chứng minh callback và block đã được quan sát tại thời điểm xác nhận, **không chứng minh world đã lưu bền**. Không có transaction nguyên tử giữa world và PostgreSQL. PostgreSQL thử nghiệm có `fsync=on`, `synchronous_commit=on`; mất điện, hỏng ổ đĩa hoặc crash PostgreSQL chưa được kiểm thử.

Migration V009 bổ sung `contract_block_journal`, liên kết audit ID duy nhất và `contract_recovery_fences`; giữ checksum V001–V008. Startup đối soát trước READY, ghi báo cáo TSV có `force(true)` và lưu fence bền vững. Lỗi I/O/timeout vẫn làm startup thất bại.

| Phân loại journal | Bằng chứng và xử lý |
|---|---|
| CONFIRMED | APPLIED có receipt và audit khớp; sự kiện APPLIED cuối ở ô phải khớp world. ABORTED xác nhận ý định đã bị hủy, không xác nhận có thay đổi block. |
| INCOMPLETE | INTENT chưa phân giải hoặc pending queue cũ; khóa hợp đồng, không tự replay thành lịch sử. |
| MISMATCH | Có audit/APPLIED nhưng trạng thái world cuối khác; khóa hợp đồng. |
| UNRECOVERABLE | APPLIED thiếu receipt/audit tương ứng; giữ bằng chứng và khóa hợp đồng. |

`UNKNOWN_HISTORY` đơn thuần, gồm công trình console cũ, không tự tạo fence. Site được hợp đồng mới sử dụng lại có nhãn `SUPERSEDED_SITE`, không so trạng thái world mới với audit của công trình cũ. Các sự kiện trung gian vẫn được kiểm tra receipt riêng, không coi trạng thái cuối MATCH là lịch sử đầy đủ.

Fence giữ nguyên trạng thái nghiệp vụ ACTIVE/SUBMITTED/COMPLETED, hiển thị `RECOVERY_REQUIRED` riêng. DB đọc fence sau khi lấy khóa hợp đồng; chặn nhận việc, gửi/duyệt, bỏ việc, hoàn tiền và mở hợp đồng mới trên site bị khóa. Hợp đồng hết hạn có fence không tự hoàn tiền. INTENT chưa phân giải cũng chặn settlement. GUI cũ không vượt qua kiểm tra DB. Receipt của giao dịch đã commit có thể được đọc lại, không phát sinh chi mới. Vùng fenced được bảo vệ kể cả hợp đồng đã COMPLETED.

Không tự xóa fence, sửa world hoặc tạo PLACE/BREAK từ trạng thái block. Chưa có quy trình quản trị để quyết định và ghi nhận việc mở khóa sau điều tra; phải giữ nguyên bằng chứng và đối soát thủ công trước khi thiết kế bước đó. Không xem khóa an toàn là phục hồi hoàn toàn lịch sử.

## Kiểm thử đã chạy

Paper 26.3 build 166, Java Corretto 25.0.4.1, PostgreSQL 18.4. Core JAR SHA-256:

`0521b2ff25b6428eda5ef8a0eb2bf552d1a747ac673d3484897a08efddc09768`

- **85/85 local tests:** 4 pure-data + 81 PostgreSQL integration, không skipped. [Log](local-verify.log), [DB test summary](local-db-tests.txt). Có ca intent chặn settlement, abort không tạo audit, replay một lần, ba sự kiện trung gian, rollback APPLIED giữ INTENT và fence commit khi payment đang chờ khóa.
- **9/9 hard-kill hồi quy:** [j01/completed.txt](j01/completed.txt). Giữ các ranh giới queue cũ, world save và commit tài chính. Fixture gọi queue nội bộ, không phải callback native hoặc thao tác client.
- **7/7 hard-kill journal:** [j02/completed.txt](j02/completed.txt). Mỗi ca DB/runtime mới từ backup ACTIVE có manifest. Tiêm journal/world bằng probe, không gọi đó là gameplay client.

| Ca mới | Kết quả |
|---|---|
| intent | 1 INTENT, 0 audit; INCOMPLETE; submit/refund bị chặn |
| applied0 | 1 APPLIED/1 audit, world mất block; MISMATCH |
| applied1 | 1 APPLIED/1 audit và world đã lưu; CONFIRMED |
| sequence0 | PLACE → BREAK → PLACE giữ cả 3 journal/audit; world mất block; MISMATCH |
| sequence1 | Giữ 3 journal/audit và world cuối; CONFIRMED |
| savecallback | Kill trong callback `WorldSaveEvent` thật của `world.save()`; MISMATCH |
| missingproof | Cố ý xóa receipt trên DB thử sau kill; UNRECOVERABLE dù world có block |

Ca `savecallback` có chốt chờ trong plugin kiểm thử ở callback thật, **trước lưu chunk**, không phải bằng chứng kill giữa ghi region file hoặc fsync. [Marker](j02/native-save-boundary.txt). `missingproof` là tiêm lỗi có chủ ý, không khẳng định PostgreSQL tự mất receipt sau transaction.

Cả 7 ca giữ nguyên trạng thái nghiệp vụ trước/sau startup: escrow 4 LT, Builder 0 LT, tổng tiền 4 LT, không RELEASE/CONSUME ngoài ý muốn; không khóa hợp đồng đường console không liên quan. Hai ca tài chính hồi quy giữ đúng một RELEASE, một COMPLETE receipt, tiêu thụ 2 Linh Thạch + 1 Huyền Thiết, Builder nhận 2 LT khi retry hai lần; tổng tiền 4, minted=4/burned=0 và 29 token. Bằng chứng JSON, journal, fence và TSV nằm trong `j01/`, `j02/`.

## Client và phần chưa hoàn tất

Runtime riêng `run-b01-client-j01`, DB `cv_b01_client_j01`, bind `127.0.0.1:25591`; core JAR cùng hash với các ca trên. C (`TyninWorld`) đăng nhập bằng client Modrinth đã có, không phải vanilla-only. Chuẩn bị nền đứng, teleport và cấp vanilla block/cuốc là fixture, không tính là xây tay. Có một lần kill phục vụ sửa nền đứng/probe trước khi có journal; ghi trong `setup.txt`, không tính là ca nghiệm thu.

**Hai ranh giới hard-kill native đã kiểm chứng:**

1. Sau các callback đã commit: 4 BREAK và 4 PLACE của C ở (68,100,4), (68,100,5), (69,100,4), (69,100,5); một ý định BREAK khác hết hạn thành ABORTED. Không ép số đếm về kịch bản tối thiểu. Hard-kill khi C online rồi startup lại giữ đủ 8 journal APPLIED, 8 receipt và 8 audit, kể cả các BREAK trung gian mà world cuối lại là Cobblestone. Báo cáo xác nhận riêng từng proof, không chỉ MATCH trạng thái cuối. [DB sau kill](client-j01/killed-db.txt), [DB sau phục hồi](client-j01/recovered-db.txt), [TSV](client-j01/native-recovery.tsv).
2. Trước commit callback PLACE thật tại (67,100,5): click đầu chuẩn bị INTENT và bị hủy; lần thử lại cho đặt block. Region callback quan sát block rồi worker chạy APPLIED transaction. Trigger thử riêng làm `UPDATE operations` chờ 10 giây; watcher quan sát `PgSleep` trước khi kill đúng PID/runtime. Sau kill gỡ trigger thử trước startup để giữ kiểm tra schema drift. DB giữ INTENT, không commit audit/receipt của lần đặt này. [Marker transaction](client-j01/native-precommit-marker.json), [DB sau kill](client-j01/native-precommit-killed.txt), [danh sách kill](client-j01/kills.txt).

Sau startup: đúng một fence INCOMPLETE cho sàn, hợp đồng đường vẫn không fenced; không tạo audit giả từ world. Journal có **8 APPLIED / 2 ABORTED / 1 INTENT**; audit và receipt đều đúng 8. Tổng tiền 4 LT: ví người chơi 0, ngân khố 0, escrow 4; minted=4/burned=0. Hai hợp đồng ACTIVE, không RELEASE/REFUND/CONSUME mới, còn 29 token, dự trữ hạng mục 4 đơn vị, kho dự án 0; Resource Ledger TRANSFER cân bằng. [Đối soát có assertions](client-j01/fenced-reconcile.txt), [SQL chạy lại](client-reconcile.sql).

**Fence trên client:** sau reconnect, C thử đặt Cobblestone vào ô (67,100,5), vẫn AIR và journal/audit không tăng. GUI có Barrier ở vị trí gửi nghiệm thu; bấm vào đóng cửa sổ và báo “Hợp đồng đang chờ phục hồi; chưa thể nghiệm thu hoặc trả công.” [Ảnh GUI](client-j01/gui-fence-denied.jpg), [world](client-j01/fenced-world.txt), [đối soát sau đặt bị chặn](client-j01/after-denied-placement.txt), [sau GUI bị chặn](client-j01/after-denied-gui.txt). DB cũng từ chối submit và abandon/refund với RECOVERY_REQUIRED: [kết quả](client-j01/native-fence-db.txt). Chưa thử client giữ GUI cũ xuyên qua một thay đổi fence khi server vẫn chạy; ca DB payment chờ khóa kiểm chứng kiểm tra lại fence dưới khóa, không thay thế ca GUI này.

Runtime giữ fence cho việc xem xét, không tự mở khóa hay phát lương. Chưa ghi PASS cho BREAK bằng giữ chuột sau fence, ca phủ định mọi template/người chơi khác, kill giữa ghi chunk/region file, fsync, crash DB hoặc mất điện. Vanilla block/Inventory không có transaction chung với world; kết quả bảo toàn PDC/DB không chứng nhận không mất hoặc nhân bản mọi vật phẩm vanilla khi crash.

B01 tiếp tục OPEN: cần quy trình xử lý/mở fence có kiểm soát và các ca client còn thiếu nêu trên, thêm ranh giới kill trong chunk I/O/fsync và kiểm tra tác động plugin khác/đầy hàng đợi/tải đông người. Chưa chứng nhận Folia runtime hay chống crash tuyệt đối. B02 chưa bắt đầu.

## Chạy lại

Trong repo, đặt JAVA_HOME và CUUVUC_TEST_URL/USER/PASSWORD trỏ DB thử riêng rồi chạy `mvn -B -ntp verify`. Dùng `validation/b01/build-probe.ps1 -JavaHome <jdk25> -MavenHome <maven>`; sau đó chạy `run-matrix.ps1` và `run-journal-matrix.ps1` với `-JavaHome <jdk25> -PostgresBin <pg-bin> -BundleDirectory <backup-active> -RunId <định-danh-mới>`. Các script kiểm checksum backup, tạo DB/runtime mới và kiểm PID/path trước khi kill. Không chạy probe trên server nghiệm thu hoặc production.
