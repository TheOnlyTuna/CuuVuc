import vn.cuuvuc.core.CatalogFile;
import vn.cuuvuc.core.Domain;
import vn.cuuvuc.core.GameDatabase;

/** Connector kiểm migration trên dump thật, không chạy vòng hết hạn hợp đồng của Paper. */
public final class InitializeUpgrade {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) throw new IllegalArgumentException("Cần JDBC URL, user và schema thử nghiệm");
        Domain.Catalog catalog = CatalogFile.read(InitializeUpgrade.class.getResourceAsStream("/catalog.properties"));
        String password = System.getenv("CUUVUC_DB_PASSWORD");
        if (password == null) throw new IllegalArgumentException("Thiếu CUUVUC_DB_PASSWORD");
        try (GameDatabase database = new GameDatabase(arguments[0], arguments[1], password, arguments[2], catalog,
                new Domain.EconomyPolicy(10000, 500))) {
            database.initialize();
            System.out.println("V012_INITIALIZED_FROM_FINAL_JAR");
        }
    }
}
