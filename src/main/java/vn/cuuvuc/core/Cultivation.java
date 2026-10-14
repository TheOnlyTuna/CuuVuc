package vn.cuuvuc.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.IntStream;

/** Luật tu luyện bất biến; cảnh giới đã đạt tách khỏi XP đủ điều kiện. */
public interface Cultivation {
    record Gate(int rank, long xp, Map<String, Long> cost) {
        public Gate { cost = Map.copyOf(cost); }
    }
    record Rules(List<Gate> gates, long bonus, long trialXp, Map<String, Long> trialCost,
                 int previewSeconds, int durationSeconds, int cooldownSeconds, Domain.Recipe advanced, String signature) {
        public Rules { gates = List.copyOf(gates); trialCost = Map.copyOf(trialCost); }
    }
    record State(int rank, long revision, Optional<String> legacyTitle) { }
    record Trial(UUID id, UUID player, List<Integer> sequence, int step, String status,
                 Instant previewUntil, Instant deadline, Optional<Instant> retryAt, Optional<String> reason) {
        public Trial { sequence = List.copyOf(sequence); }
    }
    record Snapshot(Domain.PlayerView view, Optional<Trial> trial, Instant now) { }

    static String title(State state) {
        if (state.legacyTitle().filter(name -> name.equals("Kim Đan") || name.equals("Nguyên Anh")).isPresent())
            return state.legacyTitle().orElseThrow() + " (kế thừa, chưa mở nội dung mới)";
        return state.rank() == 0 ? "Phàm Nhân" : state.rank() == 10 ? "Trúc Cơ" : "Luyện Khí tầng " + state.rank();
    }

    static Gate next(Rules rules, State state) {
        if (state.rank() >= 9) throw new IllegalArgumentException("Tầng 9 cần vượt Trận Tâm Ma; Trúc Cơ chưa mở cảnh giới tiếp theo.");
        return rules.gates().get(state.rank());
    }

    static List<String> missing(Map<String, Long> stock, Map<String, Long> cost) {
        return cost.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> stock.getOrDefault(entry.getKey(), 0L) < entry.getValue())
                .map(entry -> entry.getKey() + ": thiếu " + (entry.getValue() - stock.getOrDefault(entry.getKey(), 0L))).toList();
    }

    static Rules load() {
        try (InputStream input = Cultivation.class.getResourceAsStream("/cultivation.properties")) {
            if (input == null) throw new IOException("Thiếu cultivation.properties");
            String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Properties properties = new Properties();
            try (InputStreamReader reader = new InputStreamReader(new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            List<Gate> gates = IntStream.rangeClosed(1, 9).mapToObj(rank -> {
                String[] fields = required(properties, "training." + rank).split(",", -1);
                if (fields.length != 3) throw new IllegalArgumentException("training cần XP,thô,kết tinh: " + rank);
                long xp = Long.parseLong(fields[0]); long rough = Domain.quantity(fields[1]); long crystals = Long.parseLong(fields[2]);
                if (xp < 1 || crystals < 0 || crystals > 1000) throw new IllegalArgumentException("Định mức tầng sai: " + rank);
                return new Gate(rank, xp, crystals == 0 ? Map.of("linh_thach_tho", rough)
                        : Map.of("linh_thach_tho", rough, "linh_thach", crystals));
            }).toList();
            long trialXp = number(properties, "trial.xp");
            int preview = Math.toIntExact(number(properties, "trial.preview-seconds"));
            int duration = Math.toIntExact(number(properties, "trial.duration-seconds"));
            int cooldown = Math.toIntExact(number(properties, "trial.cooldown-seconds"));
            long bonus = number(properties, "mining.bonus-per-rank");
            for (int index = 1; index < gates.size(); index++)
                if (gates.get(index).xp() <= gates.get(index - 1).xp()) throw new IllegalArgumentException("Ngưỡng XP phải tăng theo tầng");
            if (trialXp <= gates.getLast().xp() || preview < 1 || duration <= preview || duration > 300 || cooldown < 1 || cooldown > 3600
                    || bonus < 0 || bonus > 10 || number(properties, "trial.steps") != 5)
                throw new IllegalArgumentException("Cấu hình thử thách cần 5 phù ấn; thời gian hoặc bonus sai");
            String[] recipe = required(properties, "recipe.advanced").split(",", -1);
            if (recipe.length != 5 || !recipe[0].equals("ket_tinh_truc_co") || !recipe[1].equals("linh_thach_tho") || !recipe[3].equals("linh_thach"))
                throw new IllegalArgumentException("Công thức Trúc Cơ phải dùng nguyên liệu hiện có");
            Domain.Recipe advanced = new Domain.Recipe(recipe[0], recipe[1], Domain.quantity(recipe[2]), recipe[3], Domain.quantity(recipe[4]));
            return new Rules(gates, bonus, trialXp, Map.of("linh_thach", Domain.quantity(required(properties, "trial.crystals")),
                    "huyen_thiet", Domain.quantity(required(properties, "trial.iron"))), preview, duration, cooldown, advanced, Migrations.hash(raw));
        } catch (IOException failure) { throw new UncheckedIOException("Không đọc được cân bằng tu luyện", failure); }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Thiếu cấu hình tu luyện: " + key);
        return value;
    }
    private static long number(Properties properties, String key) { return Long.parseLong(required(properties, key)); }
}
