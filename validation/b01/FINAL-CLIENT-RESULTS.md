# B01 — Final Minecraft Client Validation

**ACCEPTED — B01 CLOSED WITH SCOPE LIMIT**, theo quyết định người dùng ngày 14/10/2026 tại `c062adf`. **B02 NEXT**, baseline chưa đo; V0.8 giữ ACCEPTED WITH SCOPE LIMIT. B01.1/B01.2/B01.3 và Final Minecraft Client được chấp nhận trong phạm vi có bằng chứng; không mở rộng các kết quả dưới đây. Rủi ro ngoài phạm vi tiếp tục OPEN tại [BACKLOG.md](../../BACKLOG.md); bước tiếp theo là [baseline B02](../b02/BASELINE-PLAN.md).

Phiên ngày **14/10/2026, 18:53–19:21 Asia/Bangkok**. Không sửa Java, migration, cấu hình build hoặc rebuild JAR. SHA-256 core giữ đúng **`A54A30F01407D3D46B64837C35D841C310A62D0B6299E7B53D76E858B23B5991`** ở target và runtime trước/sau phiên. Giữ nhánh `v0.8-khai-son-lap-phai`, không merge `main`, không bật CI hoặc bắt đầu B02.

## Môi trường và nguồn fixture

- Runtime riêng `run-b01-client-final01`, DB riêng `cv_b01_client_final01`, server loopback **25596**, PostgreSQL riêng loopback **55439**. PostgreSQL 18.4 với fsync/full_page_writes/synchronous_commit on. Paper **26.3 build 166**, Corretto **25.0.4.1**. Không thay đổi Windows hoặc nâng Paper theo thông báo runtime.
- Restore từ backup ACTIVE `backups/b01-unrecoverable-source-001`, [manifest nguồn](client-final01/source-manifest.json). Không sửa DB/runtime nguồn. Gia hạn hai hợp đồng thêm 7 ngày trong DB fixture trước startup, ghi tại `expiry-fixture.txt`.
- Agent điều khiển UI của ba Minecraft Client thật đang mở: **C/TyninWorld** (`3bb5ddbc-5b75-377f-8647-6f25c51f252a`, Light Craft), **A/CuuVucNPC2** (`f3d83b55-4452-39bc-85f4-29f8cebdcc4f`, Minecraft 26.3), **B/CuuVucNPC1** (`1194cfbc-1195-338c-9eb1-0c49dd118ae3`, Minecraft 26.3). Login/UUID và lệnh Client được ghi trong server logs. Runtime offline-mode, không chứng minh danh tính qua Mojang hoặc hai con người độc lập.
- Console chỉ chuẩn bị vị trí/vật liệu/quyền: forceload 4 chunk; nền Dirt; dọn 9 ô sàn; teleport C; cấp 16 Cobblestone. C **Survival (playerGameType=0), không OP/Creative**. Để thử BREAK bằng click thật qua UI, cấp netherite pickaxe Efficiency V và Haste IV 600 giây; đây là dụng cụ thử nghiệm, không chứng minh tự thu thập vật liệu hay thời gian đào thông thường. Haste đã hết hạn khi cleanup.
- Console tạo Cobblestone ngoài vùng ở `(66,100,5)` để thử quyền BREAK. Sau khi có fence, console tạo Cobblestone tại `(67,100,5)` để có mục tiêu BREAK bị khóa. Các thay đổi này được ghi rõ trong log, **không tính là PLACE của Builder**, không thêm audit hoặc journal.
- CrashProbe JAR cũ có tải nhưng **không nhận lệnh nào** (`probe-command-files.json=[]`). Không gọi JPLACE/JBREAK, PluginManager event-fixture hoặc reflection RecoveryCommands trong phiên này. PLACE/BREAK tính kết quả và lệnh review/case/approve đều đến từ thao tác UI trên Client.

## Kết quả từ Client

| Ca | Bằng chứng và kết quả |
|---|---|
| Builder Survival PLACE/BREAK | Click chuột tại `(67,100,5)` tạo **1 PLACE APPLIED + 1 BREAK APPLIED**, actor C, audit tương ứng và receipt có đúng fingerprint, không pending. Snapshot [native-intact](client-final01/native-intact.jsonl), [proof](client-final01/native-intact-proof.txt), [ảnh BREAK](client-final01/builder-break.png). Vật liệu cuối phiên native là AIR |
| Quy trình hai nhịp | Callback đầu chuẩn bị durable INTENT và yêu cầu thử lại; retry phù hợp cho phép thao tác. **3 PLACE intent khác hết hạn thành ABORTED**, không audit/receipt. Giữ nguyên các hàng này, không ép số lượng thành hai sự kiện. Ảnh client có thể hiển thị block dự đoán tạm thời; DB proof sau callback mới được dùng để đếm APPLIED |
| Ngoài vùng ACTIVE | C thử đặt/phá ngoài vùng. Không thêm audit/receipt hoặc đổi tài sản; Cobblestone ngoài vùng vẫn tồn tại sau BREAK. [Ảnh PLACE](client-final01/outside-place-denied.png), [ảnh BREAK](client-final01/outside-break-denied.png). Snapshot ngoài vùng khác snapshot PLACE/BREAK trước đó chỉ ở một intent ABORTED do lần thử trong vùng trước đó hết hạn |
| UNRECOVERABLE có nguồn gốc | Shutdown/backup native trước khi tiêm lỗi. Xóa **duy nhất** receipt PLACE APPLIED `2338c597-186b-49cc-968b-b40dcf612719`, không sửa journal/audit. [Lệnh/receipt bị xóa](client-final01/missing-receipt-fixture.txt). Startup tạo fence UNRECOVERABLE đúng contract `279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3`; hợp đồng đường khác không bị khóa |
| Client thi công khi fence | C reconnect, thử BREAK block console đã chuẩn bị và PLACE ô trống trong sàn; world giữ nguyên, không thêm journal/audit/receipt. [BREAK](client-final01/fenced-break-denied.png), [PLACE](client-final01/fenced-place-denied.png). Cả **39 bảng trước/sau thử** giống hệt |
| Gửi nghiệm thu/owner | C mở GUI hợp đồng và click vị trí gửi nghiệm thu: nhận “Hợp đồng đang chờ phục hồi; chưa thể nghiệm thu hoặc trả công.” [Ảnh](client-final01/client-submit-denied.png). A mở GUI cùng hợp đồng: RECOVERY_REQUIRED, không có nút trả công/nghiệm thu; [ảnh owner](client-final01/owner-fenced-no-payment-button.png). Không có callback complete được chấp nhận hoặc audit/receipt COMPLETE; không coi việc không có nút là đã gửi một request COMPLETE vào DB |
| Review/hold từ A | A nhập `/cv recovery review ...`, rồi `incident-hold ...` từ chat Client. Review `458809ab-90cc-42c7-b583-f6579a30d231`, case **`ca2b5b59-957d-4b20-bbf3-d379d15e4e70`**. Hồ sơ có thiếu proof gốc, world/tài sản và lý do. [Review](client-final01/client-review-hold.png), [đề xuất](client-final01/client-hold-proposed.png) |
| Tự phê duyệt | A nhập approve-case cho case mình lập: bị từ chối, không ghi quyết định hoặc chuyển tài sản. [Ảnh](client-final01/client-self-approval-denied.png) |
| Quyền DB bị thu hồi | Console thu hồi quyền B trong khi B vẫn OP/cuuvuc.recovery. B nhập approve-case từ Client: bị từ chối bởi quyền PostgreSQL. [Ảnh](client-final01/client-revoked-approval-denied.png); **39 bảng trước/sau từ chối giống hệt** |
| Duyệt hold và replay | Cấp lại quyền B; B yêu cầu `case`, đọc hồ sơ xuất rồi approve-case với lý do. Quyết định APPROVED có actor B, epoch quyền 3, receipt **`694c9c27-ee0e-34c7-a40a-c437f722b00e`**. [Duyệt](client-final01/client-hold-approved.png), [replay](client-final01/client-hold-replayed.png). Hold chỉ thêm decision/receipt; replay không đổi 39 bảng |
| Review/settle mới | A tạo review **`efccc939-70ea-4703-bc94-a6e2b24b2e1d`** và case **`adb176eb-40cd-4399-ad06-68fc64030236`**, bồi thường 1 LT trong escrow 2 LT, lý do rõ ràng. Snapshot chứa case/quyết định hold trước. B xuất case rồi approve từ Client. [Đề xuất](client-final01/client-settle-proposed.png), [duyệt](client-final01/client-settle-approved.png) |
| Settlement/replay | Receipt **`32ff9235-6a0c-3972-bc4e-a79e61f690e3`**, một RECOVERY_SETTLEMENT −2 LT; hoàn sect 1 LT, bồi thường C 1 LT; trả dự trữ bằng TRANSFER cân bằng. Replay từ B trả kết quả cũ, không thêm tài sản/decision/receipt. [Ảnh replay](client-final01/client-settle-replayed.png) |
| Thu hồi quyền thử nghiệm | Console revoke A và B, deop cả hai. A revision 2 và B revision 4 đều disabled, `ops.json=[]`. B thử đọc case từ Client: nhận thiếu quyền Bukkit/PostgreSQL. [Ảnh](client-final01/client-all-rights-revoked.png), [ops](client-final01/ops-revoked.json). Không còn quyền thử nghiệm sau restart |
| Restart/reconnect | Shutdown mềm, startup cùng JAR, **39 bảng giống hệt trước/sau**; world tại hai ô kiểm tra giữ nguyên. C reconnect và vẫn không PLACE được trong site CANCELLED có fence. [Ảnh](client-final01/post-restart-client-place-denied.png). Snapshot sau thao tác này cũng giống hệt sau startup |

## Đối soát và lưu bằng chứng

[verify-final-client-evidence.ps1](verify-final-client-evidence.ps1) đọc snapshot độc lập, kiểm proof APPLIED trước fault, audit/receipt sau fault, actor/epoch quyết định, bất biến journal/case, tài sản và phạm vi fence. [Kết quả đối soát](client-final01/reconciliation.txt) **PASS**.

Tổng tiền **4 LT**: cuối cùng ví C **1**, sect **1**, escrow target **0**, escrow hợp đồng đường **2**. Hai DEPOSIT giữ nguyên, chỉ một RECOVERY_SETTLEMENT; không RELEASE/REFUND thường hoặc COMPLETE. Tổng tài nguyên từng loại giữ **22 linh_thach_tho, 3 linh_thach, 8 huyen_thiet**, inventory/PDC và **29 token** không đổi. Reserve target 2 linh thạch + 1 huyền thiết về project bằng 4 hàng TRANSFER cân bằng, không CONSUME; reserve hợp đồng khác vẫn 1 linh thạch.

Journal cuối **5 hàng: 2 APPLIED, 3 ABORTED**, giống nguyên bản trước fault. Audit thi công chỉ **1 PLACE + 1 BREAK**; thêm duy nhất CANCEL hành chính của settlement. Receipt BREAK và các receipt không liên quan giữ nguyên; receipt PLACE gốc vẫn thiếu. Chỉ thêm **2 receipt hành chính** cho hai quyết định thực tế. Hợp đồng target CANCELLED nhưng fence UNRECOVERABLE còn nguyên; hợp đồng đường ACTIVE, tài sản/metadata không đổi.

Logs: [native](client-final01/native-session.log), [quản trị Client](client-final01/recovery-client-session.log), [restart](client-final01/final-restart-session.log). PID Java lần restart cuối **43552**, [bản ghi process](client-final01/restart-process.json). Không có hard-kill trong phiên Client này.

Backup sau native: `backups/b01-client-final-native-001`; backup sau settlement/restart/stop: `backups/b01-client-final-settled-001`. Manifest và shutdown/drain được lưu trong thư mục bằng chứng. Server và PostgreSQL riêng đã dừng, dữ liệu giữ lại để điều tra. Hash từng file bằng chứng được lưu tại `client-final01/evidence-hashes.json`; Git giữ nguyên byte gốc của thư mục bằng chứng.

Các kết quả **101/101 Maven tests, 22/22 hard-kill hồi quy, 4/4 hard-kill B01.3 và 8/8 interference** đã chạy trên chính hash này tại `e175c43`; phiên Client không chạy lại build/test đó và không thay binary. Không đổi fixture thành kết quả Client E2E: bằng chứng Client bổ sung ở đây được tách riêng khỏi các ma trận trước.

## Giới hạn giữ lại

Đạt kiểm thử Client đối với callback PLACE/BREAK một Builder tại một ô, quyền vùng được thử, GUI fence và quy trình hai tài khoản quản trị trên binary cuối. Không phải kiểm tra toàn bộ thi công template, mọi vật liệu/vị trí, thao tác đào thông thường, toàn bộ inventory/drop vanilla hoặc người dùng đồng thời/chunk đồng thời. Mặc dù ba Client kết nối trong phiên, không suy diễn thành kiểm thử tải/concurrency hoặc Folia. Phiên dùng Client có sẵn (C có mod), offline-mode và dụng cụ/vật liệu chuẩn bị.

Owner không có nút nghiệm thu trong trạng thái fence; phiên không phát sinh một request COMPLETE mạng thành công để thử precommit. Replay được kiểm trước restart; sau restart quyền quản trị đã bị thu hồi, không cấp lại chỉ để thử replay nữa. Không thử thay đổi ngay sau lần đọc cuối/thay đổi rồi khôi phục trước quan sát, plugin cố ý vượt DB/journal, mất điện, hỏng đĩa hoặc crash PostgreSQL. Không sửa world để khôi phục proof, không Force Unlock, không bồi thường vượt escrow.

Kết quả này hoàn tất vòng Final Minecraft Client trong phạm vi đã ghi và đã được người dùng chấp nhận để **đóng B01 với scope limit tại `c062adf`**. B02 là công việc tiếp theo: đo baseline trước khi sửa mã; phiên này không có số đo benchmark B02.
