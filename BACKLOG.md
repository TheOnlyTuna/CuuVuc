# Backlog Cửu Vực

V0.7 được người dùng chấp nhận ngày 11/10/2026 làm nền tảng phát triển tiếp, không phải chứng nhận production. Các mục đang mở dưới đây không bác bỏ nghiệm thu V0.7. Bằng chứng hiện có nằm trong [đánh giá V0.7](V0.7-EVALUATION.md); phạm vi milestone tiếp theo nằm trong [kế hoạch V0.8](V0.8-PLAN.md).

| ID | Vấn đề đang mở | Điều kiện để đóng |
|---|---|---|
| B01 | Crash consistency: hàng đợi lịch sử PLACE/BREAK bất đồng bộ chỉ có journal khi shutdown mềm; kill đột ngột có thể mất sự kiện. | Thiết kế ranh giới ghi bền vững và đối soát world/DB; thử kill trước/sau ghi journal, lưu world và commit DB. Sau phục hồi, sự kiện được ghi một lần hoặc báo rõ phần không thể khôi phục; không suy diễn lịch sử đã đủ từ trạng thái block. Không tuyên bố chống crash nếu chưa có bằng chứng. |
| B02 | Hiệu năng lưu world: Paper đã cảnh báo `world.save()` thủ công; chưa có số đo khi nhiều hợp đồng đồng thời. | Đo baseline và các mức đồng thời được ghi rõ; lưu TPS, MSPT p50/p95/p99, thời gian lưu và độ trễ nghiệm thu, cấu hình/phạm vi world, số người, thời lượng. Chọn ngân sách hiệu năng trước khi đo; thiết kế giảm/gộp thao tác lưu phải giữ quy tắc nghiệm thu và được kiểm tra restart/restore. |
| B03 | Mục tiêu xây dựng: mẫu sàn 3×3 cố định chưa đủ cho công trình tông môn. | Có template có phiên bản, vùng/tọa độ và tiêu chí rõ cho từng hạng mục; thử công trình đúng, thiếu block, sai vật liệu, vượt vùng và thay đổi sau gửi nghiệm thu. Nghiệm thu đúng hạng mục và trả tiền một lần; không mở mục tiêu tùy ý khi chưa có kiểm tra quyền. |
| B04 | Binary cuối V0.7 chưa được lặp lại toàn bộ phiên hai client; 44 tests và kiểm tra startup/restart/nghiệm thu lịch sử đã đạt trên bản cuối. | Ghi SHA-256 JAR đang chạy và lặp lại hai client: ký quỹ, tranh nhận, quyền vùng, xây, gửi/duyệt, trả tiền, quyền sau hoàn thành, restart và restore. Đối soát ledger/token/lịch sử; dùng chính binary được đưa ra nghiệm thu. |

B01 là điều kiện trước khi tuyên bố phục hồi crash đáng tin cậy; B02 trước khi mở tải nhiều công trình; B03 trước công trình tông môn lớn. B04 được kết hợp với kiểm thử nhiều client trên binary cuối V0.8, nhưng chỉ đóng phần V0.7 khi phiên hai client trên đúng JAR V0.7 đã được chạy. Không đổi tên một phiên thử V0.8 thành bằng chứng V0.7.

Việc đóng mục cần dẫn đến bằng chứng cục bộ có thể kiểm tra trong repository. CI GitHub tiếp tục tắt theo quyết định người dùng; không phải điều kiện đóng backlog. Không có benchmark hoặc crash test mới được thực hiện khi lập tài liệu này.
