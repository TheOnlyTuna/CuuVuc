# Backlog Cửu Vực

V0.7 được người dùng chấp nhận ngày 11/10/2026 làm nền tảng phát triển tiếp, không phải chứng nhận production. Các mục đang mở dưới đây không bác bỏ nghiệm thu V0.7. Bằng chứng hiện có nằm trong [đánh giá V0.7](V0.7-EVALUATION.md); phạm vi milestone tiếp theo nằm trong [kế hoạch V0.8](V0.8-PLAN.md).

| ID | Trạng thái và phạm vi | Điều kiện/bước tiếp theo |
|---|---|---|
| B01 | **CLOSED WITH SCOPE LIMIT**, theo quyết định người dùng ngày 14/10/2026 tại `c062adf`. B01.1 I/O, B01.2 interference, B01.3 UNRECOVERABLE và Final Minecraft Client được chấp nhận trong phạm vi kiểm thử. | [Bằng chứng và giới hạn cuối](validation/b01/FINAL-CLIENT-RESULTS.md). Các rủi ro ngoài phạm vi tiếp tục mở tại B01-R01–R06 bên dưới; đóng B01 không phải bảo đảm phục hồi mọi sự cố. |
| B02 | **PAUSED theo yêu cầu Vertical Slice 002 — q01 idle/một request đã đo; tải 2/4/8 chưa chạy, chưa tối ưu**. | Giữ [kết quả ban đầu](validation/b02/BASELINE-RESULTS.md) và [protocol](validation/b02/BASELINE-PLAN.md). Khi được tiếp tục mới hoàn tất baseline và ngân sách mục tiêu trước đánh giá hiệu năng; `world.save()` chưa thay đổi. |
| VS002 | **IMPLEMENTED / CLIENT VALIDATION PARTIAL — chờ người dùng kiểm tra tiến độ, chưa Playable**. XP độc lập cảnh giới, Luyện Khí 1–9, Trận Tâm Ma và lợi ích Trúc Cơ đã có mã/V012; 108/108 kiểm thử kỹ thuật. | [Báo cáo hiện tại](validation/v08/vertical-slice-002/RESULTS.md): một Client thật trên JAR cuối mới tới tầng 6. Cần tiếp tục tầng 7–9 → thắng Trúc Cơ → dùng lợi ích → reconnect/restart và đối soát; không dùng phiên binary trước thay cho ca cuối. |
| B03 | Mục tiêu xây dựng: Phase 4 đã thêm hai template phiên bản, định mức và vùng riêng; vẫn là công trình mẫu trong một chunk, chưa kiểm toàn bộ ca world/template lớn. | Có template có phiên bản, vùng/tọa độ và tiêu chí rõ cho từng hạng mục; thử công trình đúng, thiếu block, sai vật liệu, vượt vùng và thay đổi sau gửi nghiệm thu. Nghiệm thu đúng hạng mục và trả tiền một lần; không mở mục tiêu tùy ý khi chưa có kiểm tra quyền. |
| B04 | Binary cuối V0.7 chưa được lặp lại toàn bộ phiên hai client; 44 tests và kiểm tra startup/restart/nghiệm thu lịch sử đã đạt trên bản cuối. | Ghi SHA-256 JAR đang chạy và lặp lại hai client: ký quỹ, tranh nhận, quyền vùng, xây, gửi/duyệt, trả tiền, quyền sau hoàn thành, restart và restore. Đối soát ledger/token/lịch sử; dùng chính binary được đưa ra nghiệm thu. |
| B05 | Fingerprint schema còn phụ thuộc chuỗi SQL PostgreSQL deparse; sửa đúng biểu thức CHECK V005 đã được chấp nhận cho prototype. | Thiết kế fingerprint có phiên bản, dùng thuộc tính catalog có cấu trúc cho cột/FK/unique/index và kiểm chứng ngữ nghĩa CHECK/default/expression. Giữ lịch sử/checksum V001–V008; thử dump/restore và nâng PostgreSQL với dữ liệu mẫu, chấp nhận biểu thức tương đương nhưng từ chối thay đổi ràng buộc thật. Không whitelist hash hoặc bỏ kiểm tra drift. |
| B06 | Phase 1 chưa thử hard-kill lúc người nhận online; craft/sell/transfer cạnh tranh mới kiểm chứng ở DB, chưa thử mọi đường sao chép PDC hoặc tải đông người. | Có bằng chứng client/cơ chế ghi bền tương ứng cho từng ca; bảo toàn kho/ledger/token hoặc báo rõ trạng thái cần đối soát. Các giới hạn không ngăn Phase 2, nhưng không dùng để tuyên bố chống crash tuyệt đối. |

B01 đã đóng trong phạm vi có bằng chứng; Vertical Slice 002 được ưu tiên trong V0.8 và đang dừng chờ kiểm tra tiến độ. B02 và công trình lớn B03 được hoãn theo brief. B04 chỉ đóng phần V0.7 khi phiên hai client trên đúng JAR V0.7 đã chạy; không đổi tên một phiên thử V0.8 thành bằng chứng V0.7.

Việc đóng mục cần dẫn đến bằng chứng cục bộ có thể kiểm tra trong repository. CI GitHub tiếp tục tắt theo quyết định người dùng; không phải điều kiện đóng backlog. Các ma trận B01 và Final Client giữ nguyên nguồn gốc/phạm vi trong báo cáo; B02 có số đo ban đầu q01, chưa có kết luận nghiệm thu hiệu năng.

## Rủi ro ngoài phạm vi B01 — OPEN

Các mục này không ngăn quyết định CLOSED WITH SCOPE LIMIT tại `c062adf`, nhưng không được dùng làm bảo đảm đã kiểm chứng.

| ID | Phần chưa được bảo đảm | Bằng chứng cần có để đánh giá |
|---|---|---|
| B01-R01 | Mất điện, hỏng ổ đĩa và crash PostgreSQL | Kế hoạch fault injection từng loại trên bản sao riêng, backup và đối soát DB/world/Ledger; không suy diễn từ hard-kill Java hoặc restart mềm. |
| B01-R02 | Plugin cố ý vượt quyền, ghi trực tiếp DB/journal; DB superuser/OS administrator sửa bằng chứng | Xác định mô hình quyền và tác nhân được bảo vệ, cơ chế kiểm chứng phù hợp, rồi kiểm từng đường can thiệp thực tế. Trigger bất biến hiện tại không bảo vệ trước mọi quyền quản trị bên ngoài. |
| B01-R03 | Thay đổi ngay sau lần kiểm tra cuối hoặc thay đổi rồi khôi phục trước quan sát | Tái hiện các cửa sổ cụ thể và đối soát world/proof/settlement; không mở rộng kết quả interference hiện tại thành bảo đảm mọi lịch xen kẽ. |
| B01-R04 | Folia, nhiều người/chunk đồng thời và template lớn/mọi vật liệu/vị trí | Kiểm trên runtime và workload tương ứng, có proof quyền vùng, journal, audit, receipt và restart. Benchmark B02 tự nó không đóng mục correctness này. |
| B01-R05 | Toàn bộ inventory/drop vanilla và cửa sổ Client chưa kiểm | Kiểm packet/callback và đối soát inventory/drop thực tế, gồm request COMPLETE qua mạng trong các cửa sổ liên quan và replay quản trị sau restart. Giữ phân biệt GUI không có nút với request DB đã được gửi. |
| B01-R06 | Sửa chữa world, tái sử dụng site cách ly và bồi thường vượt escrow | Thiết kế/duyệt quy trình riêng với hồ sơ bất biến và nguồn tài sản được chứng minh; không Force Unlock, không tạo PLACE/BREAK/receipt giả hoặc mint để bù dữ liệu không rõ. |

## Điều kiện trước Alpha sau nghiệm thu V0.8

V0.8 được người dùng chấp nhận **ACCEPTED WITH SCOPE LIMIT — Controlled Gameplay Prototype**, checkpoint `189bb27`, 5/5 phases reviewed. [Hồ sơ phát hành và giới hạn](validation/v08/phase5/RESULTS.md) giữ bằng chứng ba client, tài sản và restore; quyết định không đóng B01–B06.

B01 đã được người dùng CLOSED WITH SCOPE LIMIT ngày 14/10/2026 tại `c062adf` sau B01.1, B01.2, B01.3 và Final Minecraft Client. [Báo cáo cuối](validation/b01/FINAL-CLIENT-RESULTS.md) giữ nguồn gốc và binary. Builder Survival tại `f9ef1d8` và mức nghiệm thu V0.8 giữ nguyên. B02 PAUSED; B03–B06 và B01-R01–R06 còn mở. Vertical Slice 002 thuộc V0.8, chưa nghiệm thu Playable. Không merge main, không bật CI hoặc tuyên bố production-ready.
