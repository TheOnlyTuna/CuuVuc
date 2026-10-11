import vn.cuuvuc.core.CatalogFile;
import vn.cuuvuc.core.Domain;
import vn.cuuvuc.core.GameDatabase;
import vn.cuuvuc.core.Projects;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Fixture tích hợp thật trên bản phục hồi Phase 3 riêng; chỉ chạy khi Paper đã dừng. */
public final class Phase4Scenario {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Cần JDBC URL của DB thử riêng.");
        String password = System.getenv("CUUVUC_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("Thiếu CUUVUC_DB_PASSWORD.");
        Domain.Catalog catalog = CatalogFile.read(Phase4Scenario.class.getResourceAsStream("/catalog.properties"));
        UUID leader = UUID.fromString("f3d83b55-4452-39bc-85f4-29f8cebdcc4f");
        UUID builder = UUID.fromString("1194cfbc-1195-338c-9eb1-0c49dd118ae3");
        UUID project = UUID.fromString("48000000-0000-0000-0000-000000000001");
        try (GameDatabase database = new GameDatabase(arguments[0], "cuuvuc_test", password, "public", catalog, new Domain.EconomyPolicy(10000, 500))) {
            database.initialize();
            for (int index = 0; index < 5; index++) database.mine(request("mine:" + index), builder, "CuuVucNPC1", "phase4/refined/" + index, "linh_thach_tho", 3);
            database.craft(request("craft"), builder, "CuuVucNPC1", "ket_tinh", 3);
            database.mine(request("iron"), builder, "CuuVucNPC1", "phase4/iron", "huyen_thiet", 3);
            UUID sect = database.treasuryView(leader).sect();
            database.createProject(project, leader, sect, "Khai Son Phase4");
            database.contributeProject(request("crystal"), builder, project, "linh_thach", 3, database.view(builder, "PDC").revision());
            database.contributeProject(request("metal"), builder, project, "huyen_thiet", 1, database.view(builder, "PDC").revision());
            Projects.View view = database.projectView(leader, project);
            UUID cancelled = UUID.fromString("48000000-0000-0000-0000-000000000002");
            database.createStageContract(cancelled, leader, view.stages().getFirst().id(), 2, Duration.ofHours(1));
            database.cancelContract(cancelled, leader);
            UUID active = UUID.fromString("48000000-0000-0000-0000-000000000003");
            database.createStageContract(active, leader, view.stages().getFirst().id(), 2, Duration.ofHours(1));
            database.acceptContract(active, builder);
            UUID open = UUID.fromString("48000000-0000-0000-0000-000000000004");
            database.createStageContract(open, leader, view.stages().getLast().id(), 2, Duration.ofHours(1));
            System.out.println(database.projectView(builder, project));
            System.out.println(database.treasuryView(leader));
            System.out.println(database.contractBoard());
        }
    }
    private static UUID request(String name) {
        return UUID.nameUUIDFromBytes(("cuuvuc:phase4:fixture:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
