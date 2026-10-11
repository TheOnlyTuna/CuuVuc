package vn.cuuvuc.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

/**
 * Migration chạy dưới khóa PostgreSQL trong một transaction. Schema v1 cũ chỉ được
 * nhận diện khi cấu trúc khớp mẫu chuẩn; lịch sử, checksum hoặc schema lệch đều dừng.
 * Không tự sửa schema drift, không tự hạ phiên bản và không xóa dữ liệu để khởi động.
 */
public interface Migrations {
    record Migration(int version, String sql, String checksum, String schemaChecksum) { }

    static List<Migration> load() throws IOException {
        return List.of(loadOne(1, "V001__baseline"), loadOne(2, "V002__hardening"), loadOne(3, "V003__playable_world"), loadOne(4, "V004__building_contracts"), loadOne(5, "V005__material_transfers"), loadOne(6, "V006__sects"), loadOne(7, "V007__sect_treasury"), loadOne(8, "V008__project_materials"), loadOne(9, "V009__contract_recovery"));
    }

    static Migration loadOne(int version, String name) throws IOException {
        String sql = resource("/db/migrations/" + name + ".sql");
        return new Migration(version, sql, hash(sql), resource("/db/migrations/" + name + ".sha256").strip());
    }

    static String resource(String path) throws IOException {
        try (InputStream stream = Migrations.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Thiếu tài nguyên migration: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("JVM thiếu SHA-256.", failure); }
    }

    static void apply(Connection connection, List<Migration> migrations, String fingerprintSql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_xact_lock(5080501)");
            boolean historyExists = relationExists(connection, "migration_history");
            int version = relationExists(connection, "schema_version") ? version(connection) : 0;
            if (!historyExists && version != 0 && version != 1) throw incompatible("Không thể baseline phiên bản " + version);
            if (version < 0 || version > migrations.size()) throw incompatible("Phiên bản DB mới hơn plugin: " + version);
            if (version == 0 && relationExists(connection, "profiles")) throw incompatible("Thiếu schema_version nhưng đã có dữ liệu.");
            if (version > 0) verifySchema(connection, migrations.get(version - 1), fingerprintSql);
            if (!historyExists) {
                statement.execute("CREATE TABLE migration_history(version integer PRIMARY KEY, script_sha256 text NOT NULL, schema_sha256 text NOT NULL, applied_at timestamptz NOT NULL DEFAULT clock_timestamp())");
                if (version == 1) record(connection, migrations.getFirst());
            }
            int recorded = 0;
            try (ResultSet result = statement.executeQuery("SELECT version,script_sha256,schema_sha256 FROM migration_history ORDER BY version")) {
                while (result.next()) {
                    recorded++;
                    if (recorded > version || result.getInt(1) != recorded) throw incompatible("Lịch sử migration không liên tục.");
                    Migration expected = migrations.get(recorded - 1);
                    if (!expected.checksum().equals(result.getString(2)) || !expected.schemaChecksum().equals(result.getString(3)))
                        throw incompatible("Checksum migration không khớp V" + recorded);
                }
            }
            if (recorded != version) throw incompatible("Lịch sử migration thiếu phiên bản.");
            for (Migration migration : migrations.subList(version, migrations.size())) {
                statement.execute(migration.sql());
                verifySchema(connection, migration, fingerprintSql);
                record(connection, migration);
            }
        }
    }

    private static int version(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT version FROM schema_version")) {
            if (!result.next()) throw incompatible("schema_version rỗng.");
            int version = result.getInt(1);
            if (result.next()) throw incompatible("schema_version có nhiều dòng.");
            return version;
        }
    }

    private static boolean relationExists(Connection connection, String relation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(quote_ident(current_schema()) || '.' || quote_ident(?)) IS NOT NULL")) {
            statement.setString(1, relation);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private static void verifySchema(Connection connection, Migration migration, String fingerprintSql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(fingerprintSql)) {
            result.next();
            String actual = result.getString(1);
            if (!migration.schemaChecksum().equals(actual)) throw incompatible("Schema drift V" + migration.version() + ": expected=" + migration.schemaChecksum() + ", actual=" + actual);
        }
    }

    private static void record(Connection connection, Migration migration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO migration_history(version,script_sha256,schema_sha256) VALUES (?,?,?)")) {
            statement.setInt(1, migration.version()); statement.setString(2, migration.checksum()); statement.setString(3, migration.schemaChecksum()); statement.executeUpdate();
        }
    }

    private static SQLException incompatible(String message) { return new SQLException(message + ". Khôi phục backup hoặc chạy migration đã rà soát; không sửa checksum để bỏ qua.", "55000"); }
}
