# B01.3 — UNRECOVERABLE Workflow

Trạng thái: **ACCEPTED WITH SCOPE LIMIT — Technical + Controlled Paper Fixture Validation**, theo quyết định người dùng ngày 14/10/2026 tại `e175c43`. B01 tổng thể đã **CLOSED WITH SCOPE LIMIT tại `c062adf`** sau [Final Minecraft Client](FINAL-CLIENT-RESULTS.md); **B02 NEXT**. B01.2 ACCEPTED có giới hạn tại `fd64170` được giữ nguyên. Không nâng nghiệm thu V0.8.

## Binary và môi trường

- Core JAR SHA-256: `A54A30F01407D3D46B64837C35D841C310A62D0B6299E7B53D76E858B23B5991`.
- JDK 25.0.4, Paper runtime của bundle B01.1/B01.2; PostgreSQL 18 riêng tại loopback:55439, `fsync/full_page_writes/synchronous_commit=on`.
- Backup đầu vào: `backups/b01-unrecoverable-source-001`, bản sao có manifest của backup ACTIVE đã dùng B01.2. Restore tạo từng DB `cv_b01_<run>_<case>` và runtime `run-b01-<run>-<case>`; không sửa runtime/DB nguồn.
- Binary được giữ tại `backups/b01-unrecoverable-final-binaries`; hash, manifest nguồn, Maven và XML kiểm thử tại [unrecoverable-environment](unrecoverable-environment/).
- Backup sau settlement và stop/drain: `backups/b01-unrecoverable-settled-001`; manifest lưu cùng bằng chứng môi trường. DB/runtime kiểm thử được giữ để điều tra lại.
- Migration V011 bổ sung hồ sơ/quyết định bất biến và loại escrow `RECOVERY_SETTLEMENT`; các migration V001–V010 không sửa. Fingerprint schema V011: `3fc8d4dcec35933b2afc11ba9bf79ae45a3da6d38a39045445b7ad93dae6b2df`.
- Nhánh triển khai `v0.8-khai-son-lap-phai`; không merge `main`, không bật CI.

## Quy trình quản trị

1. Sao lưu và giữ Recovery Fence. Dùng `/cv recovery review <contract>` để chụp journal, audit, receipt, fence, world và quyền người lập. Chỉ tạo hồ sơ nếu proof của terminal APPLIED/REVIEWED_UNCONFIRMED thực sự không thể xác minh. Không suy diễn từ vật liệu hiện tại rằng người chơi đã PLACE/BREAK.
2. Người có cả permission `cuuvuc.recovery` và quyền PostgreSQL tạo một trong hai đề xuất, với lý do 8–500 ký tự:
   - `/cv recovery incident-hold <review> <lý do>`: giữ cách ly, không chuyển tài sản.
   - `/cv recovery incident-settle <review> <bồi thường LT> <lý do>`: phân bổ escrow đã chứng minh còn nguyên. Bồi thường 0..1000 LT và không vượt escrow; phần còn lại trả đúng nguồn cấp player/sect. Chỉ hoàn dự trữ tài nguyên ảo có đối ứng Ledger vào kho dự án.
3. `/cv recovery case <case>` xuất hồ sơ để người duyệt đọc dữ liệu không xác minh được, UUID sự kiện/vị trí/audit liên quan, snapshot tài sản và lý do. Người duyệt phải có quyền phục hồi hiện tại, khác người lập; khi chuyển tiền còn phải khác owner và Builder. Phần mềm kiểm hai danh tính, không xác nhận danh tính con người bên ngoài server.
4. `/cv recovery approve-case <case> <lý do>` kiểm lại bằng chứng, world, tài sản, quyền/epoch người lập và người duyệt. Case và review phải còn hạn 10 phút. Quyết định, receipt hành chính và toàn bộ bút toán cùng một transaction. Replay cùng người/lý do trả kết quả đã commit, không chuyển tài sản lại.
5. Nếu dữ liệu/quyền/world đã đổi, dùng `/cv recovery reject-case <case> <lý do>`, sau đó tạo review/hồ sơ mới. Từ chối ghi quyết định bất biến nhưng không chuyển tài sản. Mỗi hợp đồng chỉ có một case chưa quyết định. Không sửa case cũ hoặc dùng lại review đã lập case.
6. Case đã APPROVED không được sửa quyết định: fence vẫn giữ, `resolve` thông thường không thể mở khóa. Nếu cần đề xuất phân bổ tài sản sau KEEP_QUARANTINED, phải lập review/case mới với snapshot chứa quyết định trước và một người độc lập duyệt lại; không có thanh toán tự động. SETTLE_HELD_ESCROW chuyển metadata hợp đồng thành CANCELLED nhưng site vẫn bị cách ly và không được tái sử dụng.

Nếu không chứng minh được escrow, ví/ngân khố, receipt nguồn cấp hoặc dự trữ/resource Ledger, phương án settle bị từ chối; chỉ có thể giữ cách ly và chuyển sang điều tra thủ công. Không mint tiền để bù số liệu không rõ, không hoàn vật phẩm vanilla hoặc suy đoán drop. Bồi thường vượt escrow và xử lý site sau cách ly chưa nằm trong chức năng này.

## Bằng chứng bất biến và đối soát

`unrecoverable_cases` lưu người lập, review, phương án, mức bồi thường, lý do, evidence, world, findings và assets trước. `unrecoverable_decisions` lưu người duyệt, epoch quyền, lý do, outcome, world đã kiểm và assets sau. Trigger chặn UPDATE/DELETE/TRUNCATE; các hồ sơ này và Ledger được lưu trong DB. File xuất được ghi flush xuống đĩa, có thể xuất lại từ DB nếu xuất file lỗi; file không phải nguồn quyết định duy nhất và không tự chống sửa bởi OS administrator.

Journal, audit PLACE/BREAK và receipt block gốc không được sửa/tạo lại. Receipt mới có fingerprint `recovery:unrecoverable:approve:<case>` và request UUID xác định theo case: đây là receipt quyết định hành chính thực tế. Ledger tiền phân biệt `RECOVERY_RETURN` và `RECOVERY_COMPENSATION_NOT_WORK_PAYMENT`; không có RELEASE hoặc COMPLETE cho công việc không chứng minh được. Audit CANCEL hành chính ghi rõ lý do; không chứng nhận thi công.

Đối soát giữ nguyên tổng tiền qua player + sect + escrow, tổng tài nguyên từng loại qua player/project/stage, inventory/token của người chơi và hợp đồng khác. Reserve hoàn bằng cặp TRANSFER cân bằng, không CONSUME. Khóa quyền quản trị trước sect/project/stage/contract rồi player theo UUID, theo thứ tự khóa hiện có.

## Kết quả B01.3

Maven **101/101**: 4 Domain + 97 PostgreSQL integration, không fail/error/skip. Sáu ca integration mới kiểm:

- hoàn/bồi thường đúng một lần; giữ thiếu receipt gốc; từ chối tự duyệt và replay khác người/lý do/outcome;
- hồ sơ/quyết định không sửa/xóa/truncate được; cố tiêm proof giả cũng không mở `resolve` qua case cách ly;
- world thay đổi, quyền người duyệt bị thu hồi, epoch người lập thay đổi và từ chối case cũ;
- vượt escrow, thiếu proof tài chính và lỗi ghi quyết định rollback toàn bộ Ledger/receipt;
- funding player/sect, hoàn reserve có Ledger, bảo toàn tài sản và cấm tái sử dụng site;
- bốn phê duyệt đồng thời chỉ ghi một quyết định và một settlement.

Runtime riêng **4/4 hard-kill mới**, [uc01](uc01/), đối soát đầy đủ 39 bảng bằng [verify-unrecoverable-evidence.ps1](verify-unrecoverable-evidence.ps1):

| Ca | Vị trí kill | Kết quả sau restart/retry |
|---|---|---|
| holdbefore | DB backend PgSleep trong INSERT quyết định, chưa commit | Toàn bộ snapshot rollback; retry giữ cách ly; replay không đổi |
| holdafter | Callback quản trị đã trả kết quả commit | Quyết định tồn tại; retry/replay không thêm receipt |
| settlebefore | PgSleep trong INSERT quyết định, sau bút toán nhưng trước commit | Bút toán/receipt/quyết định rollback đồng thời; retry phân bổ đúng một lần |
| settleafter | Callback quản trị đã trả kết quả commit | Settlement/quyết định tồn tại; retry/replay không chuyển lại |

Marker backend PID/query: `*-precommit-marker.json`; PID Java và runtime thực sự bị kill: `kills.txt`. Không tính SQL timeout là precommit kill. Mỗi ca intentionally xóa receipt APPLIED trong DB fixture sau khi server dừng để tạo lỗi UNRECOVERABLE; đây là fault injection, không phải thao tác phục hồi.

Đối soát: tiền **4 LT trước/sau**. Hold giữ toàn bộ tài sản/ledger. Settle chuyển escrow target **2→0**, hoàn sect **1 LT**, bồi thường Builder **1 LT**, escrow hợp đồng khác vẫn **2 LT**; 2 linh thạch + 1 huyền thiết từ stage về project bằng TRANSFER. World quan sát sau restart vẫn COBBLESTONE. Journal APPLIED/audit PLACE giữ nguyên, receipt block gốc vẫn thiếu, không BREAK/COMPLETE giả. Một fence UNRECOVERABLE đúng hợp đồng, submit/refund/ownercomplete vẫn bị từ chối. Snapshot killed==restarted và committed==replayed trên cả 39 bảng.

## Hồi quy và giới hạn nghiệm thu

Hồi quy trên cùng JAR cuối: **22/22 hard-kill cũ + 4/4 B01.3 mới + 8/8 interference**. Hash core khớp trên toàn bộ **34 runtime** (file `unrecoverable-environment/runtime-core-hashes.json`).

| Bộ | Kết quả | Bằng chứng |
|---|---|---|
| Audit/queue/transaction legacy | 9/9 | [uc02/completed.txt](uc02/completed.txt) |
| Durable journal/recovery fence | 7/7 | [uc03/completed.txt](uc03/completed.txt) |
| RegionFile payload/header I/O | 4/4 | [io14/completed.txt](io14/completed.txt), [đối soát 39 bảng](io14/reconciliation.txt) |
| Recovery resolution trước/sau commit | 2/2 | [uc04/completed.txt](uc04/completed.txt) |
| Plugin interference | 8/8 | [if11/reconciliation.txt](if11/reconciliation.txt) |
| UNRECOVERABLE trước/sau commit | 4/4 | [uc01/completed.txt](uc01/completed.txt), [đối soát 39 bảng](uc01/verify.log) |

Region I/O dùng marker thực tế `PAYLOAD_BEFORE_WRITE/PAYLOAD_AFTER_WRITE/HEADER_BEFORE_WRITE/HEADER_AFTER_WRITE` của agent B01.1. Ba ca đầu quan sát AIR và MISMATCH, ca sau header quan sát COBBLESTONE và CONFIRMED; hợp đồng khác không bị fence. Không thay thế bằng kill trong WorldSaveEvent trước ghi chunk. Các giới hạn crash và Client của báo cáo B01.1/B01.2 vẫn áp dụng.

Các kết quả fixture trong báo cáo này không phải Minecraft Client E2E. Fixture gọi connector/RecoveryCommands với danh tính thử nghiệm và đọc world thật trong Paper; thao tác phê duyệt tự động không tương đương người quản trị nhập lệnh từ Client. PLACE/BREAK event-fixture qua PluginManager trong hồi quy B01.2 không tương đương thao tác mạng người chơi. Vòng Client thật bổ sung trên cùng JAR được ghi riêng tại [FINAL-CLIENT-RESULTS.md](FINAL-CLIENT-RESULTS.md).

Không mở rộng bảo đảm cho thay đổi ngay sau kiểm tra cuối, thay đổi rồi khôi phục trước quan sát, plugin cố ý vượt quyền/ghi trực tiếp DB/journal, DB superuser/OS administrator phá bằng chứng, Folia, nhiều người/chunk đồng thời, mọi inventory/drop vanilla, mất điện/hỏng ổ đĩa/crash PostgreSQL. Fence được giữ qua quyết định; không có Force Unlock hoặc thao tác sửa world để tạo proof.

B01.3 đã được chấp nhận trong phạm vi bằng chứng này. B01 Full Crash Recovery đã CLOSED WITH SCOPE LIMIT sau Final Minecraft Client tại `c062adf`; các rủi ro ngoài phạm vi giữ OPEN trong [BACKLOG.md](../../BACKLOG.md). Tiếp theo là baseline B02 trước khi tối ưu và đánh giá Pre-Alpha.
