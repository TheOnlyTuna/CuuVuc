# B02 — Baseline hiệu năng world.save()

**IN PROGRESS — đã đo ban đầu idle/một request, chưa hoàn tất baseline.** [Kết quả q01](BASELINE-RESULTS.md) ghi protocol kiểm chuẩn ngắn và các giới hạn; protocol đầy đủ dưới đây cùng tải 2/4/8 chưa chạy. B01 CLOSED WITH SCOPE LIMIT theo quyết định người dùng ngày 14/10/2026 tại `c062adf`; các giới hạn ngoài phạm vi được giữ tại [BACKLOG.md](../../BACKLOG.md). B02 đo baseline trước khi sửa production source. V0.8 giữ mức nghiệm thu hiện tại, không merge main hoặc bật CI.

## Binary và ranh giới đo

Baseline dùng nguyên JAR `A54A30F01407D3D46B64837C35D841C310A62D0B6299E7B53D76E858B23B5991` đã kiểm B01. Restore backup có manifest vào DB/runtime benchmark riêng; giữ nguồn và backup trước/sau mỗi bộ đo. Ghi Paper/JDK/PostgreSQL, JVM flags/heap, CPU/RAM/ổ đĩa, cấu hình autosave, chunk tải/dirty và kích thước region/world. Không so sánh hai phiên khác môi trường rồi quy mọi chênh lệch cho mã.

Đường hiện tại `ContractWorld.checkAndSave` kiểm template ở region scheduler, chuyển sang global scheduler gọi `world.save()`, rồi entity scheduler chuyển công việc sang DB async. Cả submit và complete/approve đều đi qua đường này. Phải đo cả thời gian save và thời gian chờ scheduler/queue/DB để tránh quy độ trễ toàn tuyến cho save.

| Đại lượng | Định nghĩa và dữ liệu bắt buộc |
|---|---|
| Thời gian save | Thời gian monotonic từ vào tới ra đúng lời gọi `world.save()` của core; số lần, p50/p95/p99/max, loại world và trạng thái dirty. Thời gian save callback hoặc WorldSaveEvent không thay thế phép đo này. Return của save không được gọi là flush/fsync hoàn tất nếu không đo ranh giới I/O đó |
| MSPT | Thời gian thực xử lý từng tick, lấy từ API/instrumentation phù hợp với Paper đang chạy; raw sample có timestamp/tick ID. Báo p50/p95/p99/max, cỡ mẫu và số tick vượt ngân sách tick đã ghi từ cấu hình runtime |
| TPS | Số tick hoàn thành trên từng cửa sổ wall-clock 1 giây không chồng lấp; giữ cả cửa sổ không có tick. Báo p50/p95/p99, min và các khoảng sụt TPS. Không biến ba rolling average TPS 1/5/15 phút thành phân phối percentile |
| Submit/approve | Đo riêng từ tiếp nhận request đến thông báo kết quả; tách request→save-start, save-duration, save-end→DB commit và commit→response nếu thu thập được. Ghi request/contract/actor ID, thành công/từ chối/lỗi/timeout/replay. Độ trễ connector fixture phải được ghi riêng với độ trễ Minecraft Client qua mạng |
| Đồng thời | Số hợp đồng đang có request thực sự in-flight, số actor và số chunk; không dùng tổng hàng ACTIVE trong DB thay concurrency. Ghi offered load, throughput thực đạt, queue/rejection và lock contention |

Probe/instrumentation dành riêng validation, không đổi core JAR hoặc hợp đồng giao dịch. Kiểm API/method thực tế từ dependency đang cài trước khi chọn hook; đo một lượt không probe và một lượt có probe để ước lượng tác động thu thập dữ liệu. Nếu chưa tách được một pha thì báo thiếu số đo, không ước lượng thay bằng thời gian event.

## Ma trận baseline

1. Idle không có submit/approve: nền TPS/MSPT với cùng world/chunk và autosave.
2. Một hợp đồng: submit rồi approve, thử riêng world ít thay đổi và world có chunk dirty. Chuẩn bị fixture ngoài khoảng đo, không lấy chi phí mint/cấp tài nguyên hoặc dựng template làm độ trễ nghiệm thu.
3. Các mức **2, 4, 8** hợp đồng đang xử lý; ghi mức thực đạt. Dùng các actor/hợp đồng riêng để cơ chế một request mỗi người không biến workload thành chuỗi tuần tự. Tách hai bố trí: chunk/nguồn cấp độc lập và nhiều hợp đồng cùng nguồn cấp để thấy ảnh hưởng lock contention. Nếu giới hạn hiện tại ngăn mức tải, báo giới hạn và không sửa core để đạt số mong muốn.
4. Lặp lại control một hợp đồng sau các mức tải để phát hiện drift do cache/GC/host. Autosave và trạng thái chunk phải được ghi, không chỉ giữ số lượng contract giống nhau.

Mỗi cấu hình warm-up 3 phút, thu mẫu 10 phút, lặp 3 lần từ fixture tương đương. Warm-up tách khỏi percentile. Lưu từng lượt, không chỉ một số tổng gộp. Báo sample count cho từng đại lượng; p99 submit/approve với ít request phải được đánh dấu chưa ổn định và thu thêm mẫu trước khi quyết định tối ưu. Percentile dùng nearest-rank `ceil(p*N)`, không bù mẫu bị thiếu; ghi khoảng trống và lỗi đo.

Đây là protocol ban đầu, không phải số liệu đã chạy hoặc cam kết tải đạt được. Ghi thay đổi protocol trước phiên đo tương ứng. Baseline nhằm xác định nút thắt; chưa có ngưỡng nghiệm thu latency hoặc lý do để gộp/bỏ save. Ngân sách tối ưu sẽ được chốt từ yêu cầu và kết quả baseline, không điều chỉnh ngưỡng sau đo để biến một kết quả thành PASS.

## Đầu ra và điều kiện chuyển sang tối ưu

Lưu `validation/b02/BASELINE-RESULTS.md`, raw samples, cấu hình/workload, PID/log, hash core/probe, manifest backup và đối soát trước/sau. Báo riêng save, TPS/MSPT và submit/approve p50/p95/p99, max, cỡ mẫu, throughput và mức đồng thời thực đạt; phân biệt Paper fixture với Client thật.

Đối soát tiền, escrow, resource Ledger/token, audit/receipt, journal và fence trước/sau restart. Hợp đồng không đủ proof tiếp tục bị khóa; không bỏ lưu/kiểm world chỉ để giảm độ trễ. Benchmark không thay các kiểm thử correctness còn mở ở B01-R01–R06.

Chỉ đề xuất sửa mã khi dữ liệu cho thấy vấn đề và chỉ ra pha gây chậm. Thay đổi phải có workload so sánh tương đương, cải thiện đo được và hồi quy phạm vi B01 liên quan trên JAR mới, gồm restart/restore. Commit/push từng phần hoàn tất lên nhánh V0.8; chưa phát triển gameplay V0.9.
