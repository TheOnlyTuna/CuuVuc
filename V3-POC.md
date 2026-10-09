# V3 — kế hoạch thử nghiệm thế giới có chiều cao tùy chỉnh

Trạng thái: **kế hoạch, chưa nhập heightmap, chưa tạo world V3**. Server thử V0.5 chỉ là world vanilla flat. Không có PNG/RAW/TIFF heightmap V3, metadata thang cao độ, mask biome hoặc dự án WorldPainter trong tài liệu đã đọc. Không giả định đã có bản đồ chín vùng hoàn chỉnh.

## Mục tiêu và đầu vào

Chứng minh client Minecraft Java 26.3 và Paper build 166 lưu/tải, chiếu sáng, di chuyển và sinh chunk đúng với chiều cao mở rộng, trước khi chuyển địa hình thật. Đây là thử nghiệm riêng; không thay `dimension_type` hay chiều cao của world V0.5 đã sinh chunk.

Cần nhận file gốc có checksum, kích thước ảnh, độ sâu mẫu 16-bit hoặc float, đơn vị cao độ, min/max nguồn, mặt nước, tỉ lệ block/pixel, chiều Bắc, gốc tọa độ, vùng crop và quyền sử dụng. Cần thống nhất phép biến đổi trước khi nhập: `y = yLow + round((sample-sourceLow)/(sourceHigh-sourceLow)*(yHigh-yLow))`; báo lỗi nếu vượt khoảng đã thỏa thuận, không âm thầm cắt đỉnh hoặc đảo chiều ảnh.

## Cổng kiểm chứng

1. **Khóa phiên bản và schema thực tế.** Giữ JDK/Paper/Minecraft như V0.5 để so sánh. Trích xuất `version.json`, registry/dimension mẫu từ chính server JAR; đối chiếu [release notes 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3). Bản này có thay đổi worldgen, nên không chép nguyên datapack JSON từ 1.21.x. Xác minh pack format, codec `dimension_type`, generator/noise settings và giới hạn chiều cao bằng server chính xác này.
2. **Fixture tổng hợp 512×512.** Trước file V3 thật, tạo một world mới và datapack dimension có mục tiêu ban đầu `min_y=-64`, `height=1024`, miền đặt khối dự kiến `[-64,960)`. Đây là tham số đề xuất cần codec chấp nhận, chưa phải tuyên bố đã chạy. Chiều cao chia hết cho 16; kiểm tra `logical_height` và phạm vi generator tương ứng. Nếu server từ chối, giữ log và sửa nguyên nhân; không tự chuyển về chiều cao vanilla.
3. **Bậc thang và vách kiểm chuẩn.** Đặt dấu ở min, 0, 319, 320, 511, 959; thử đặt ngoài miền và kiểm tra từ chối. Đọc `World#getMinHeight()`/`getMaxHeight()` trong probe; lưu/tắt/bật hai lần rồi xác minh tất cả dấu. Kiểm tra ánh sáng, heightmap nội bộ của chunk, nước, hang, cấu trúc và mob spawning bằng client thật.
4. **Nhập một ô V3.** Sau khi có nguồn, nhập crop 512×512 bằng công cụ hỗ trợ đúng định dạng Anvil/data version/chiều cao đã kiểm chứng. Nếu WorldPainter chưa hỗ trợ bản khóa, dừng bước xuất và xác minh công cụ; không tuyên bố import thành công từ việc chỉ mở ảnh. Xuất world mới, giữ checksum nguồn, cấu hình biến đổi, phiên bản công cụ, log xuất và tọa độ mẫu kiểm tra.
5. **Biên và dữ liệu.** So 20 điểm mẫu với công thức; sai số tối đa 1 block sau làm tròn. Kiểm tra không có đường nối ở biên chunk/region, lớp đáy và mặt nước đúng. Xác định chiến lược chunk ngoài vùng nhập: biên world có chủ đích hoặc generator cùng hệ; không để vanilla tạo vách nối bất ngờ.
6. **Đo hiệu năng.** Cùng seed/crop/view-distance/máy, chạy baseline vanilla và bản 1024. Đo kích thước world, thời gian khởi động, heap, GC, p50/p95/p99 MSPT và tốc độ sinh chunk. Thử 1, 5, 10 người chơi phân tán, bay nhanh theo phương đứng, teleport và reconnect. Mục tiêu POC: 20 TPS, p95 MSPT dưới 50ms ở 10 người; ghi cấu hình máy và thời lượng tối thiểu 15 phút. Không suy rộng kết quả idle sang tải người chơi.
7. **Tích hợp và rollback.** Chỉ khi các cổng trên đạt mới đặt điểm mỏ và test CuuVucCore trong dimension mới. Sau đó mới thử Folia cùng bản tương thích và plugin claims/PvP/Skyblock dự kiến. Snapshot DB + world trước phát hành, rollback bằng cặp snapshot tương ứng; không hạ chiều cao world đã có dữ liệu.

## Bằng chứng phải bàn giao trước quyết định triển khai

Datapack và world fixture thực tế; JSON cấu hình, checksum, log registry; báo cáo min/max runtime; bảng 20 điểm cao độ; ảnh client ở các tầng; log restart; số đo hiệu năng; danh sách plugin tương thích. POC tổng hợp và import file V3 thật là hai kết quả riêng. Chỉ đánh dấu “đã nhập V3” khi world xuất từ đúng file nguồn vượt qua cổng 4–5.
