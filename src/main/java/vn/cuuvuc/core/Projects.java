package vn.cuuvuc.core;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Kho dự án là tài sản DB; dự trữ và tiêu thụ được ghi cùng receipt hợp đồng. */
public interface Projects {
    record Project(UUID id, UUID sect, UUID owner, int slot, String name, long revision) { }
    record Stage(UUID id, UUID project, int worksite, String template, String status, Optional<UUID> contract,
                 Map<String, Long> costs, Map<String, Long> reserved) {
        public Stage { costs = Map.copyOf(costs); reserved = Map.copyOf(reserved); }
    }
    record View(Project project, Map<String, Long> stock, List<Stage> stages, List<String> history) {
        public View { stock = Map.copyOf(stock); stages = List.copyOf(stages); history = List.copyOf(history); }
    }
    static Project read(Connection connection, UUID project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT project_id,sect_id,owner_id,island_slot,name,revision FROM projects WHERE project_id=?")) {
            statement.setObject(1, project);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Không có dự án: " + project);
                return new Project(result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getObject(3, UUID.class), result.getInt(4), result.getString(5), result.getLong(6));
            }
        }
    }
    static List<Project> list(Connection connection, UUID actor) throws SQLException {
        List<Project> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT p.project_id FROM projects p JOIN sect_members m USING(sect_id) WHERE m.player_id=? ORDER BY p.created_at,p.project_id")) {
            statement.setObject(1, actor);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(read(connection, rows.getObject(1, UUID.class))); }
        }
        return List.copyOf(result);
    }
    static View view(Connection connection, UUID actor, UUID project) throws SQLException {
        Project data = read(connection, project); Sects.role(connection, data.sect(), actor);
        List<Stage> stages = new ArrayList<>(); List<String> history = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT stage_id FROM project_stages WHERE project_id=? ORDER BY worksite")) {
            statement.setObject(1, project);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) stages.add(stage(connection, rows.getObject(1, UUID.class))); }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT h.event_type,p.player_name,h.detail,h.created_at FROM project_history h JOIN profiles p ON p.player_id=h.actor_id WHERE h.project_id=? ORDER BY h.entry_id DESC LIMIT 10")) {
            statement.setObject(1, project);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) history.add(rows.getString(1) + " | " + rows.getString(2) + " | " + rows.getString(3) + " | " + rows.getString(4)); }
        }
        return new View(data, stock(connection, "project_inventory", "project_id", project), stages, history);
    }
    static Stage stage(Connection connection, UUID stage) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT project_id,worksite,template_id,status,contract_id FROM project_stages WHERE stage_id=?")) {
            statement.setObject(1, stage);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Không có hạng mục: " + stage);
                return new Stage(stage, rows.getObject(1, UUID.class), rows.getInt(2), rows.getString(3), rows.getString(4), Optional.ofNullable(rows.getObject(5, UUID.class)),
                        stock(connection, "project_stage_costs", "stage_id", stage), stock(connection, "stage_inventory", "stage_id", stage));
            }
        }
    }
    private static Map<String, Long> stock(Connection connection, String table, String key, UUID id) throws SQLException {
        Map<String, Long> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT resource_id,quantity FROM " + table + " WHERE " + key + "=? ORDER BY resource_id")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) values.put(rows.getString(1), rows.getLong(2)); }
        }
        return Map.copyOf(values);
    }
    static Project lock(Connection connection, UUID project) throws SQLException {
        Project data = read(connection, project); Sects.lockSect(connection, data.sect());
        try (PreparedStatement statement = connection.prepareStatement("SELECT project_id FROM projects WHERE project_id=? FOR UPDATE")) {
            statement.setObject(1, project); statement.executeQuery().close();
        }
        return read(connection, project);
    }
    static Stage lockStage(Connection connection, UUID id) throws SQLException {
        Stage data = stage(connection, id); lock(connection, data.project());
        try (PreparedStatement statement = connection.prepareStatement("SELECT stage_id FROM project_stages WHERE stage_id=? FOR UPDATE")) {
            statement.setObject(1, id); statement.executeQuery().close();
        }
        return stage(connection, id);
    }
    static String create(Connection connection, UUID request, UUID actor, UUID sect, String name) throws SQLException {
        if (!name.matches("[\\p{L}\\p{N} _-]{3,32}")) throw new IllegalArgumentException("Tên dự án cần 3–32 chữ/số/khoảng trắng/_/-.");
        Sects.lockSect(connection, sect); Sects.requireLeader(connection, sect, actor); GameDatabase.lockPlayer(connection, actor);
        int slot;
        try (PreparedStatement statement = connection.prepareStatement("SELECT slot FROM islands WHERE player_id=? AND ready")) {
            statement.setObject(1, actor);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Chủ cần động phủ sẵn sàng trước khi giao đất cho dự án.");
                slot = result.getInt(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO projects(project_id,sect_id,owner_id,island_slot,name) VALUES (?,?,?,?,?) ON CONFLICT(island_slot) DO NOTHING")) {
            statement.setObject(1, request); statement.setObject(2, sect); statement.setObject(3, actor); statement.setInt(4, slot); statement.setString(5, name);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Động phủ đã giao cho một dự án; prototype chưa giải tán dự án.");
        }
        for (int site : List.of(1, 2)) {
            UUID id = UUID.nameUUIDFromBytes((request + ":stage:" + site).getBytes(StandardCharsets.UTF_8));
            BuildingTemplates.Template template = BuildingTemplates.get(site == 1 ? "STONE_PAD_V1" : "STONE_PATH_V1");
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO project_stages(stage_id,project_id,worksite,template_id) VALUES (?,?,?,?)")) {
                statement.setObject(1, id); statement.setObject(2, request); statement.setInt(3, site); statement.setString(4, template.id()); statement.executeUpdate();
            }
            for (Map.Entry<String, Long> cost : template.costs().entrySet()) {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO project_stage_costs VALUES (?,?,?)")) {
                    statement.setObject(1, id); statement.setString(2, cost.getKey()); statement.setLong(3, cost.getValue()); statement.executeUpdate();
                }
            }
        }
        event(connection, request, request, Optional.empty(), actor, "CREATE", name + "; chủ giao hai vùng mẫu trong động phủ");
        Sects.event(connection, request, sect, actor, actor, "PROJECT_CREATE", name + ": " + request);
        return "Đã tạo dự án " + request + ". Hai hạng mục chưa xây; chưa dự trữ hoặc chi tiền.";
    }
    static String contribute(Connection connection, UUID request, UUID actor, UUID project, String resource, long count, long revision) throws SQLException {
        Project data = lock(connection, project); Sects.role(connection, data.sect(), actor); GameDatabase.lockPlayer(connection, actor);
        try (PreparedStatement statement = connection.prepareStatement("SELECT inventory_revision FROM profiles WHERE player_id=?")) {
            statement.setObject(1, actor);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next(); if (rows.getLong(1) != revision) throw new IllegalArgumentException("Kho đã đổi; mở lại xác nhận góp nguyên liệu.");
            }
        }
        ResourceLedger.transfer(connection, request, actor, new ResourceLedger.Account(ResourceLedger.Type.PLAYER, actor),
                new ResourceLedger.Account(ResourceLedger.Type.PROJECT, project), resource, count);
        String detail = "Góp " + count + " " + resource + "; dự án " + project + "; chưa xây block";
        event(connection, request, project, Optional.empty(), actor, "CONTRIBUTE", detail);
        Sects.event(connection, request, data.sect(), actor, actor, "PROJECT_CONTRIBUTE", detail);
        return "Đã góp " + count + " " + resource + ". Không rút lại đóng góp; block vanilla chưa được xây.";
    }
    static void reserve(Connection connection, UUID request, UUID actor, Stage stage, UUID contract) throws SQLException {
        if (!stage.status().equals("READY")) throw new IllegalArgumentException("Hạng mục đã dự trữ hoặc hoàn thành.");
        if (stage.costs().isEmpty()) throw new SQLException("Thiếu định mức hạng mục: " + stage.id(), "55000");
        for (Map.Entry<String, Long> cost : stage.costs().entrySet()) ResourceLedger.transfer(connection, request, actor,
                new ResourceLedger.Account(ResourceLedger.Type.PROJECT, stage.project()), new ResourceLedger.Account(ResourceLedger.Type.STAGE, stage.id()), cost.getKey(), cost.getValue());
        status(connection, stage.id(), "RESERVED", contract);
        event(connection, request, stage.project(), Optional.of(stage.id()), actor, "RESERVE", "Dự trữ " + stage.costs() + "; hợp đồng " + contract);
    }
    static void consume(Connection connection, UUID request, UUID actor, UUID stageId, UUID contract) throws SQLException {
        Stage stage = stage(connection, stageId); requireReservation(stage, contract);
        ResourceLedger.Account owner = new ResourceLedger.Account(ResourceLedger.Type.STAGE, stageId);
        for (Map.Entry<String, Long> cost : stage.costs().entrySet()) {
            long remaining = ResourceLedger.change(connection, owner, cost.getKey(), -cost.getValue());
            ResourceLedger.entry(connection, request, actor, owner, Optional.empty(), cost.getKey(), -cost.getValue(), remaining);
        }
        status(connection, stageId, "COMPLETED", contract);
        event(connection, request, stage.project(), Optional.of(stageId), actor, "CONSUME", "Tiêu thụ định mức " + stage.costs() + "; nghiệm thu " + contract);
    }
    static void refund(Connection connection, UUID request, UUID actor, UUID stageId, UUID contract) throws SQLException {
        Stage stage = stage(connection, stageId); requireReservation(stage, contract);
        for (Map.Entry<String, Long> cost : stage.costs().entrySet()) ResourceLedger.transfer(connection, request, actor,
                new ResourceLedger.Account(ResourceLedger.Type.STAGE, stageId), new ResourceLedger.Account(ResourceLedger.Type.PROJECT, stage.project()), cost.getKey(), cost.getValue());
        status(connection, stageId, "READY", contract);
        event(connection, request, stage.project(), Optional.of(stageId), actor, "REFUND", "Hoàn định mức chưa tiêu thụ; hợp đồng " + contract + "; không tháo block vanilla");
    }
    private static void requireReservation(Stage stage, UUID contract) {
        if (!stage.status().equals("RESERVED") || !stage.contract().equals(Optional.of(contract)) || !stage.reserved().equals(stage.costs()))
            throw new IllegalArgumentException("Dự trữ không khớp hợp đồng/định mức: " + contract + "; hạng mục " + stage.id());
    }
    private static void status(Connection connection, UUID stage, String status, UUID contract) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE project_stages SET status=?,contract_id=? WHERE stage_id=?")) {
            statement.setString(1, status); statement.setObject(2, contract); statement.setObject(3, stage); statement.executeUpdate();
        }
    }
    private static void event(Connection connection, UUID request, UUID project, Optional<UUID> stage, UUID actor, String type, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO project_history(project_id,stage_id,request_id,actor_id,event_type,detail) VALUES (?,?,?,?,?,?)")) {
            statement.setObject(1, project); statement.setObject(2, stage.orElse(null)); statement.setObject(3, request); statement.setObject(4, actor); statement.setString(5, type); statement.setString(6, detail); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE projects SET revision=revision+1 WHERE project_id=?")) { statement.setObject(1, project); statement.executeUpdate(); }
    }
}
