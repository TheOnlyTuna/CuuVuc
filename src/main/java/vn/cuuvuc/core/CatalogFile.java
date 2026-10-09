package vn.cuuvuc.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface CatalogFile {
    static String signature(Domain.Catalog catalog) {
        return Migrations.hash(catalog.resources().values().stream().sorted(Comparator.comparing(Domain.Resource::id))
                .map(Domain.Resource::toString).collect(Collectors.joining("\n")) + "\n"
                + catalog.recipes().values().stream().sorted(Comparator.comparing(Domain.Recipe::id))
                .map(Domain.Recipe::toString).collect(Collectors.joining("\n")) + "\n" + catalog.stages());
    }

    static Domain.Catalog read(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Thiếu catalog.properties trong JAR.");
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { properties.load(reader); }
        Map<String, Domain.Resource> resources = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("resource.")).map(key -> parseResource(key.substring(9), properties.getProperty(key)))
                .collect(Collectors.toUnmodifiableMap(Domain.Resource::id, Function.identity()));
        Map<String, Domain.Recipe> recipes = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("recipe.")).map(key -> parseRecipe(key.substring(7), properties.getProperty(key)))
                .collect(Collectors.toUnmodifiableMap(Domain.Recipe::id, Function.identity()));
        List<Domain.Stage> stages = properties.stringPropertyNames().stream().filter(key -> key.startsWith("stage."))
                .map(key -> parseStage(properties.getProperty(key))).sorted(Comparator.comparingLong(Domain.Stage::xp)).toList();
        if (resources.isEmpty() || stages.isEmpty() || stages.getFirst().xp() != 0
                || stages.stream().map(Domain.Stage::xp).distinct().count() != stages.size()) {
            throw new IllegalArgumentException("Catalog phải có nguyên liệu và các ngưỡng cảnh giới riêng biệt bắt đầu từ 0.");
        }
        for (Domain.Recipe recipe : recipes.values()) {
            if (!resources.containsKey(recipe.input()) || !resources.containsKey(recipe.output()) || recipe.input().equals(recipe.output()))
                throw new IllegalArgumentException("Nguyên liệu công thức không hợp lệ: " + recipe.id());
        }
        return new Domain.Catalog(resources, recipes, stages);
    }

    static Domain.Resource parseResource(String id, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 5) throw new IllegalArgumentException("Cấu hình nguyên liệu sai: " + id);
        long xp = Long.parseLong(fields[2]);
        long buy = Long.parseLong(fields[3]);
        long sell = Long.parseLong(fields[4]);
        if (xp < 0 || sell <= 0 || buy < sell) throw new IllegalArgumentException("XP hoặc giá sai: " + id);
        return new Domain.Resource(id, fields[0], fields[1], xp, buy, sell);
    }

    static Domain.Recipe parseRecipe(String id, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 4) throw new IllegalArgumentException("Cấu hình công thức sai: " + id);
        return new Domain.Recipe(id, fields[0], Domain.quantity(fields[1]), fields[2], Domain.quantity(fields[3]));
    }

    static Domain.Stage parseStage(String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 2 || Long.parseLong(fields[1]) < 0) throw new IllegalArgumentException("Cấu hình cảnh giới sai: " + raw);
        return new Domain.Stage(fields[0], Long.parseLong(fields[1]));
    }
}
