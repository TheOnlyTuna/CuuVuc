package vn.cuuvuc.core;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Template có phiên bản bất biến; định mức tu tiên độc lập với block thi công vanilla. */
public interface BuildingTemplates {
    record Cell(int x, int y, int z, String material) { }
    record Template(String id, int offsetX, int centerZ, int depth, String material, Map<String, Long> costs) {
        public Template { costs = Map.copyOf(costs); }
        public List<Cell> cells(int slot) {
            return IntStream.rangeClosed(-1, 1).boxed().flatMap(x -> IntStream.rangeClosed(-(depth / 2), depth / 2)
                    .mapToObj(z -> new Cell(slot * 64 + offsetX + x, 100, centerZ + z, material))).toList();
        }
        public boolean contains(int slot, int x, int y, int z) {
            return x >= slot * 64 + offsetX - 1 && x <= slot * 64 + offsetX + 1
                    && y >= 100 && y <= 103 && z >= centerZ - depth / 2 && z <= centerZ + depth / 2;
        }
    }
    static Template get(String id) {
        return switch (id) {
            case "LEGACY_FLOOR_V1" -> new Template(id, 8, 8, 3, "COBBLESTONE", Map.of());
            case "STONE_PAD_V1" -> new Template(id, 4, 4, 3, "COBBLESTONE", Map.of("linh_thach", 2L, "huyen_thiet", 1L));
            case "STONE_PATH_V1" -> new Template(id, 11, 11, 1, "STONE_BRICKS", Map.of("linh_thach", 1L));
            default -> throw new IllegalArgumentException("Không có template công trình: " + id);
        };
    }
}
