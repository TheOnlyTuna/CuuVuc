# B02.1 — Baseline ban đầu và kiểm chuẩn công cụ (q01)

**INITIAL MEASUREMENT COMPLETE — chờ đánh giá; B02 còn mở.** Khởi đầu từ checkpoint tài liệu `25467c5`. B01 CLOSED WITH SCOPE LIMIT, V0.8 giữ mức nghiệm thu hiện tại; main và CI không đổi. Không sửa production source hoặc tối ưu `world.save()`.

## Phạm vi và protocol

Ngày 14–15/10/2026 (UTC+07), chạy tuần tự hai runtime/DB riêng: `run-b02-q01-control`/`cv_b02_q01_control` và `run-b02-q01-full`/`cv_b02_q01_full`, localhost:25597, PostgreSQL riêng localhost:55439. Restore cùng `b01-unrecoverable-source-001`; chỉ kéo hạn hợp đồng nguồn thêm 7 ngày trong DB thử. Backup nguồn và backup lạnh sau đo có manifest, hash và bản dump; không dùng dữ liệu production để tạo workload. [Protocol q01](QUALIFICATION-PROTOCOL.md) được ghi trước phiên: idle → clean → dirty, 3 lượt/chế độ, mỗi lượt warm-up 30 giây + đo 60 giây. Warm-up/calibration không nằm trong percentile dưới đây.

Đây là bước kiểm chuẩn ngắn theo chỉ thị bắt đầu idle/một request. **Chưa chạy protocol đầy đủ 3 phút warm-up/10 phút, chưa chạy tải 2/4/8**, chưa đặt ngân sách nghiệm thu hiệu năng. Điều kiện của công cụ đạt không đồng nghĩa hiệu năng core đạt.

Workload đóng vòng, cycle submit→approve mục tiêu 500 ms, chờ phản hồi và kiểm trạng thái DB trước thao tác tiếp. Hai Client thật online để entity scheduler hoạt động; request được plugin độc lập gọi qua connector core, không phải thao tác GUI hoặc đo mạng Client. Template cá nhân LEGACY_FLOOR_V1 gồm 9 block dựng console; sạch nghĩa không chủ động đổi block giữa request, vẫn có autosave/metadata world. Dirty chủ động đổi một block ngoài template `(64,95,0)` trước mỗi request. Số chunk dirty thực tế và hàng đợi I/O chưa được thu thập, không gọi dirty=1 là số đo thực tế của toàn world.

Chuẩn bị hợp đồng nguồn STONE_PAD qua DB API submit/complete một lần ngoài khoảng đo; việc này có audit/receipt thật nhưng không đi qua connector save. Contract mới tái cấp vốn qua pay/create/accept thật sau mỗi cycle, không reset receipt/ledger. Chi phí chuẩn bị giữa cycle ảnh hưởng tick/throughput, không nằm trong latency endpoint. Hợp đồng STONE_PATH nguồn không được xử lý; world nguồn của nó có hai STONE_BRICKS và một AIR, giữ nguyên. Fixture không chứng minh correctness Client/B01 hoặc template lớn.

## Binary, môi trường và ranh giới

Core SHA-256 **`A54A30F01407D3D46B64837C35D841C310A62D0B6299E7B53D76E858B23B5991`** ở target và cả hai runtime, trước/sau đo. JAR trên đĩa giữ nguyên; Java agent bổ sung quan sát vào bytecode trong bộ nhớ. Probe/agent/hook cùng binary giữa control và full, chỉ đổi `-Db02.mode`. [Hash binary](qualification-environment/binary-hashes.txt), [manifest nguồn](qualification-environment/source-manifest.json), [cấu hình và PID](q01/), [nguồn công cụ](agent/), [probe](probe/BaselineProbe.java).

Paper 26.3-166 `cc7b98a`, Minecraft 26.3; Corretto 25.0.4.1+10; PostgreSQL 18. JVM `-Xms256M -Xmx1024M`, view/simulation distance 4, `sync-chunk-writes=true`, autosave mặc định giữ nguyên. Forceload hai chunk `(4,0)`, `(4,1)`; không có chuỗi số chunk tải thực tế theo cửa sổ. Host Windows 11, Core Ultra 9 285K (24 core/thread), RAM khoảng 127.3 GiB, ổ D trên Samsung 990 PRO 1 TB. Các Client và ứng dụng desktop cùng host; không cô lập CPU/GC/nhiệt độ/cache nền. Bản snapshot kích thước world đang chạy chỉ thu ở control; manifest lạnh chứa file/hash cho cả hai mode; [kích thước world lạnh](q01/cold-world-size.json) được ghi riêng.

Save: monotonic ngay tại lời gọi `World.save()` của `ContractWorld.lambda$checkAndSave$1`; stopwatch độc lập trong `CraftWorld.save(false)` kiểm bounds. Return **không chứng minh flush/fsync hoặc mọi ghi region hoàn tất**. DB marker là method trả về sau transaction commit, không phải timestamp nội bộ chính xác của PostgreSQL commit. Latency từ sau fence nhận request đến ngay trước gửi phản hồi trên entity scheduler; không gồm dispatch trước fence, truyền packet hoặc Client nhận thông báo.

MSPT: `ServerTickEndEvent.getTickDuration()` được kiểm từ dependency cài đặt; đối chiếu `MinecraftServer.tickServer` độc lập cùng tick ID. TPS đếm tick hoàn thành trong bucket 1 giây không chồng lấp, giữ 0; bỏ phần bucket cuối chưa đủ 1 giây, không dùng rolling TPS. Bucket biên có thể 19/21 quanh 20 và không bị clamp. Percentile nearest-rank `ceil(p*N)` trên mẫu thật, không lấy percentile của percentile.

## Kiểm chuẩn phép đo và chi phí probe

Control là collector tối thiểu (tick API, fence, phản hồi), **không phải server hoàn toàn không probe**. Full thêm save/delegate/DB/tick-method. Mỗi mode có 9 cửa sổ đo, 2.160 request kể cả warm-up; full có 2.160 save được ghép đúng request/delegate/DB/response. Trong cửa sổ đo: 1.440 request/mode; mỗi clean/dirty có 720 save, 360 submit và 360 approve gộp 3 lượt. Không thiếu cặp, không request vượt cửa sổ; tick ID liên tiếp trong từng cửa sổ.

Delay chuẩn 2.100 ms xuất hiện trong MSPT: control 2.101,9412 ms, full 2.101,8888 ms; TPS calibration giữ hai bucket 0 ở mỗi mode. Full kiểm 5 save calibration có delegate nằm trong stopwatch ngoài: p50 ngoài 0,8451 ms/delegate 0,8251 ms; lần lạnh đầu 15,1604/14,0232 ms. Calibration bị loại khỏi kết quả tải.

Chi phí hook được ghi p99: control **0,0785 ms**, full **0,0724 ms**, dưới ngân sách kiểm chuẩn công cụ 0,25 ms đã ghi trước đo. Max tương ứng **0,4734 và 3,2399 ms**, nên không tuyên bố mọi hook đều dưới ngân sách. Chi phí queue writer, toàn bộ instrumentation, hook tick-method/delegate và tác động allocation/GC không được đo trực tiếp đầy đủ. So sánh idle dưới đây chỉ là quan sát trên host này: drift giữa lượt lớn hơn hoặc tương đương một số chênh lệch mode; không suy luận tất cả chênh lệch do probe. Delta tick-method trừ API: p50 −0,0007/p95 0,0066/p99 0,0244 ms, min −1,6262/max 0,2447 ms; ranh giới khác nhau được giữ nguyên.

Startup có lỗi OSHI đọc Windows performance counters, cảnh báo offline mode/manual-save/autosave và cảnh báo recovery observation nguồn. Không dùng OSHI counters để tạo các chỉ số chính. Không có lỗi B02 recorder/transform, thiếu mẫu hoặc workload failure. stdout chứa phiên đầy đủ; `latest.log` có thể đã rotate, không dùng riêng latest.log thay toàn bộ log.

## Kết quả định lượng

Các bộ ba dưới đây là **p50/p95/p99**, đơn vị ms trừ TPS. Dấu — là không đo/không có mẫu, không phải 0. [Summary từng lượt, count/min/max/pha](q01/full-summary.json), [control](q01/control-summary.json); phân phối gộp nằm ở khóa `pooled`, được tái tính trực tiếp bằng analyzer.

| Mode/workload (gộp 3 lượt) | MSPT | TPS | Save | Submit | Approve |
|---|---:|---:|---:|---:|---:|
| control/idle | 0.471/0.606/0.684 | 20.000/20.000/20.000 | — | — | — |
| control/clean | 0.556/1.202/1.460 | 20.000/20.000/20.000 | — | 150.052/151.102/151.681 | 150.041/151.160/151.647 |
| control/dirty | 0.556/1.263/1.399 | 20.000/20.000/20.000 | — | 150.028/151.050/151.579 | 150.020/151.223/151.747 |
| full/idle | 0.424/0.655/0.737 | 20.000/20.000/20.000 | — | — | — |
| full/clean | 0.524/1.184/1.488 | 20.000/21.000/21.000 | 0.392/0.595/0.641 | 150.046/151.068/151.389 | 150.045/151.087/151.525 |
| full/dirty | 0.530/1.241/1.421 | 20.000/20.000/20.000 | 0.433/0.642/0.726 | 149.999/151.072/151.396 | 149.991/151.101/151.376 |

Gộp mỗi workload khoảng 3.600 tick và 180 bucket TPS; mỗi endpoint có 360 mẫu (120/lượt), save full có 720 (240/lượt). Endpoint p99 phiên ngắn chưa ổn định để cam kết tải. Không có tick đo vượt 50 ms trong cả hai mode; 50 ms là chu kỳ tick 20 Hz, không phải ngân sách nghiệm thu đã chốt.

| Lượt full | MSPT | Save | Submit | Approve |
|---|---:|---:|---:|---:|
| idle-r1-measure | 0.528/0.704/0.805 | — | — | — |
| clean-r1-measure | 0.598/1.401/1.652 | 0.440/0.620/0.660 | 149.885/151.013/151.440 | 149.989/151.442/151.551 |
| dirty-r1-measure | 0.558/1.302/1.458 | 0.542/0.664/0.726 | 149.999/151.074/151.373 | 150.006/150.982/151.049 |
| idle-r2-measure | 0.407/0.551/0.599 | — | — | — |
| clean-r2-measure | 0.509/1.141/1.300 | 0.334/0.452/0.544 | 150.213/151.053/151.190 | 150.105/151.055/151.129 |
| dirty-r2-measure | 0.541/1.226/1.357 | 0.422/0.628/0.720 | 149.997/151.063/151.251 | 149.773/151.141/151.417 |
| idle-r3-measure | 0.362/0.521/0.585 | — | — | — |
| clean-r3-measure | 0.468/1.094/1.239 | 0.324/0.428/0.526 | 150.037/151.092/151.332 | 150.044/151.082/151.494 |
| dirty-r3-measure | 0.487/1.112/1.284 | 0.382/0.542/0.594 | 150.010/151.020/151.396 | 150.262/151.132/151.376 |

## Đồng thời và phân rã độ trễ

**Peak request in-flight thực đạt: idle 0, clean/dirty 1; peak save 1.** Gauge bắt đầu sau fence nhận request, kết thúc tại entity reply. DB có 2 hàng ACTIVE ngay cả khi idle 0; số này chỉ metadata, không phải đồng thời. Tải đo khoảng 3,99 request/s (khoảng 2 cycle/s); request chiếm khoảng 60% wall-clock, phần còn lại chờ chu kỳ/fixture. Workload tuần tự không tạo bằng chứng về độ chồng lấp save hoặc khả năng xử lý 2/4/8. Không có probe queue/rejection/lock-contention mẫu độc lập; driver chỉ kiểm mọi request thành công, không thu tải bị từ chối.

Full clean lượt 1: request→save p50 100,0075 ms; save p50 0,4402 ms; save-end→DB-return p50 2,6755 ms; DB-return→response p50 46,6412 ms; submit/approve p50 khoảng 150 ms. Đây là bằng chứng gợi ý thời gian chờ scheduler chiếm phần lớn latency của fixture này, **chưa là lý do tối ưu/gộp/bỏ save**. Các median pha không bắt buộc cộng đúng median tổng. Chưa đo DB queue/lock/network RTT riêng hoặc điều tra nguyên nhân từng outlier.

## Đối soát và restart

Mỗi mode tạo thêm 1.081 contract, có 1.081 contract hoàn tất gồm hợp đồng nguồn chuẩn bị và cycle. Snapshot cuối có 1.083 contract: 1.081 COMPLETED, 2 ACTIVE (PATH nguồn + contract cá nhân chuẩn bị tiếp), phản ánh tải và lịch sử fixture, không phải 1.083 request đồng thời. Mỗi hợp đồng hoàn tất có đúng một submit/complete audit và receipt, escrow RELEASE −2 đối ứng payment +2; số operation tăng đúng 5 × số contract mới. Không mint/burn ngoài fixture, tổng tiền **4 LT**, token **29**, inventory/token/authority/case/journal/fence không đổi. Chỉ chuẩn bị nguồn tiêu thụ đúng một lần 2 linh_thach + 1 huyen_thiet qua stage ledger; không tạo PLACE/BREAK audit giả.

[Control reconciliation](q01/control-reconciliation.txt) và [full reconciliation](q01/full-reconciliation.txt) đạt. Hai runtime đã soft-stop, backup lạnh, startup lại trên cùng binary/probe không chạy thêm workload, snapshot 39 bảng rồi soft-stop. **39/39 bảng giống byte trước/sau restart** ở mỗi mode. Đọc NBT từ region lạnh kiểm **22/22 tọa độ**: PAD 9 COBBLESTONE, LEGACY 9 COBBLESTONE, PATH nguồn 2 STONE_BRICKS + 1 AIR, marker dirty DIRT. Dữ liệu block kiểm giống trước/sau restart; đây là kiểm tọa độ fixture, không quét mọi world hoặc giả định region file nguyên byte khi world tiếp tục tick. [Bằng chứng restart](q01/restart-reconciliation.txt), [reader](read-world.py); [world nguồn](q01/source-world.json) được đọc đối chứng từ backup gốc; thay đổi chủ động chỉ gồm 9 tọa độ LEGACY (có một lá cây nguồn bị thay) và marker dirty. [Hash backup nguồn/cả hai backup cuối](q01/backup-verification.txt) được kiểm lại, đều khớp manifest. Không cấp quyền OP/quản trị recovery cho Client thử; quyền tài khoản được giữ nguyên theo snapshot.

PID chính control 32964/full 48424, PID restart control 47080/full 27716. Maven `test` bổ sung chạy **4/4 unit tests** theo lifecycle mặc định, không chạy lại 101 test integration/B01 tại checkpoint cũ. Core hash không đổi sau test. Các process benchmark đã dừng; dữ liệu/binary/backup giữ riêng để kiểm lại. Không dùng hard-kill hoặc sửa journal trong B02.1 này.

## Tái kiểm tra và phần còn thiếu

`build-probe.ps1` chỉ build connector validation; dùng core classes/classpath hiện có từ checkpoint. Restore source qua công cụ backup/restore repository, áp dụng cấu hình lưu trong q01 và cùng JVM flags ở process log; chạy PREPARE, CALIBRATE, RUN bằng command file sau khi hai Client online. Raw bị truncate khi restart recorder, nên raw phiên đo đã được sao chép sau shutdown và trước restart. Bản raw chuẩn là `q01/*-raw.tsv`, không phải raw runtime hiện tại.

Chạy `analyze-baseline.py --raw validation/b02/q01/full-raw.tsv --mode full --output <đích>` (đổi control tương ứng); `reconcile-benchmark.py --before <source.jsonl> --after <after-run.jsonl>`; `read-world.py --runtime <runtime/backup-server đã dừng> --output <đích>`. JSON gộp lấy mẫu raw theo phase đo, không gộp percentile. [Hash bằng chứng](q01/evidence-hashes.json) dùng byte gốc, không gồm chính manifest hash.

Bước tiếp: sau đánh giá q01, đo cửa sổ đầy đủ và mở workload 2/4/8 bằng actor/contract riêng, ghi gauge request/save và tải thực đạt. Bổ sung chuỗi loaded/dirty chunks, ảnh hưởng DB/history lớn, rejection/lock-contention nếu so sánh tải. Chốt ngân sách mục tiêu trước đánh giá đạt/không đạt hoặc tối ưu. Rủi ro B01-R01–R06 giữ ở backlog; baseline không nâng Alpha hay mở rộng bảo đảm crash/plugin/Folia.
