package vn.cuuvuc.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface Domain {
    record EconomyPolicy(long serverDailyMint, long playerDailyMint) {
        public EconomyPolicy {
            if (serverDailyMint < 1 || serverDailyMint > 1_000_000_000_000L || playerDailyMint < 1 || playerDailyMint > serverDailyMint)
                throw new IllegalArgumentException("Hạn mức mint phải dương; hạn mức người chơi không vượt toàn server, tối đa 10^12.");
        }
    }
    record Resource(String id, String name, String block, long xp, long buy, long sell) { }
    record Recipe(String id, String input, long inputCount, String output, long outputCount) { }
    record Stage(String name, long xp) { }
    record Catalog(Map<String, Resource> resources, Map<String, Recipe> recipes, List<Stage> stages) {
        public Catalog {
            resources = Map.copyOf(resources);
            recipes = Map.copyOf(recipes);
            stages = List.copyOf(stages);
        }
    }
    record Profile(UUID id, String name, long xp, long balance, Map<String, Long> inventory) {
        public Profile { inventory = Map.copyOf(inventory); }
    }

    static long quantity(String raw) {
        long value;
        try { value = Long.parseLong(raw); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Số lượng phải là số nguyên từ 1 đến 1000.", failure); }
        if (value < 1 || value > 1000) throw new IllegalArgumentException("Số lượng phải từ 1 đến 1000.");
        return value;
    }

    static Stage stage(long xp, List<Stage> stages) {
        if (xp < 0 || stages.isEmpty()) throw new IllegalArgumentException("XP hoặc danh sách cảnh giới không hợp lệ.");
        return stages.stream().filter(stage -> stage.xp() <= xp).reduce((first, second) -> second).orElseThrow();
    }

    static Resource resource(Catalog catalog, String id) {
        Resource resource = catalog.resources().get(id);
        if (resource == null) throw new IllegalArgumentException("Không có nguyên liệu: " + id);
        return resource;
    }

    static Recipe recipe(Catalog catalog, String id) {
        Recipe recipe = catalog.recipes().get(id);
        if (recipe == null) throw new IllegalArgumentException("Không có công thức: " + id);
        return recipe;
    }
}
