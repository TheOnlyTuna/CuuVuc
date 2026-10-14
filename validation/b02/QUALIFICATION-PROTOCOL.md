# B02.1 — Kiểm chuẩn và đo ban đầu

Protocol này ghi trước khi lấy mẫu q01. Đây là bước đầu xác minh công cụ và workload idle/một request thực sự in-flight; không thay benchmark 3 phút warm-up/10 phút, 3 lượt trong BASELINE-PLAN. Chưa mở tải 2/4/8 hoặc đặt ngân sách nghiệm thu hiệu năng core.

Mỗi chế độ chạy 3 lượt idle, clean, dirty theo cùng thứ tự, warm-up 30 giây và lấy mẫu 60 giây; cycle submit→approve mục tiêu 500 ms, tuần tự chờ phản hồi thật. Mỗi cycle tạo contract mới và tái cấp vốn qua API pay/escrow thật; không reset trạng thái hay receipt của contract đã hoàn thành. Dựng template console ngoài thời gian request; dirty thay một block ngoài template trước từng request. Thời gian chuẩn bị giữa cycle vẫn ảnh hưởng tick/throughput và được giữ trong cửa sổ, không tính vào latency endpoint.

Control có collector tick API và hook tối thiểu nhận request/phản hồi. Full thêm tick method độc lập, ranh giới lời gọi World.save của core, delegate CraftWorld.save(false) và phương thức DB trả về sau commit. Đây là chi phí gia tăng của probe đầy đủ so với collector tối thiểu; không coi control là runtime hoàn toàn không bị quan sát. Hai chế độ restore cùng backup vào DB/runtime riêng và chạy tuần tự, có hai Client online thật cho entity scheduler. Workload do plugin gọi connector server, không phải click GUI hay đo round-trip mạng Client.

Kiểm chuẩn trước so sánh: không lỗi transform/recorder hoặc thiếu cặp request/save; không có request qua ranh giới cửa sổ; API MSPT phát hiện delay tick biết trước 2100 ms; TPS giữ cửa sổ không tick; delegate save nằm trong thời gian save gọi bên ngoài/callsite; p99 chi phí hook được ghi phải ≤0.25 ms. Ngưỡng này là ngân sách độ nhiễu của công cụ cho bước kiểm chuẩn, không phải ngân sách save/submit/approve hoặc kết luận hiệu năng core đạt. Chi phí writer và phần observer không được đo trực tiếp phải ghi là giới hạn; đối chứng idle chỉ cung cấp bằng chứng quan sát về tác động tổng thể trên host này.

Tick API và tick method có ranh giới khác nhau: đối chiếu mẫu cùng tick ID và báo độ lệch, không ép hai phân phối giống nhau. Không gọi sự chênh lệch có thể do host/GC là tối ưu hoặc suy giảm core. p99 ở phiên ngắn phải ghi cỡ mẫu và không dùng làm cam kết tải.

Mức đồng thời lấy từ hook ngay sau khi contract fence nhận request, kết thúc tại phản hồi trên entity scheduler; save có gauge riêng. Tổng ACTIVE chỉ được ghi làm metadata. Data/raw log, PID, cấu hình, source/core/probe hash, đối soát DB/world và restart đi kèm báo cáo. Nếu kiểm chuẩn không đạt, sửa probe và chạy lại trước khi so sánh hoặc tăng tải.
