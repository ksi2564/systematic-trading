package db.migration;

import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class V4__normalize_datetime_columns_to_utcTest {

    @Test
    void 기존_datetime_값을_컬럼_의미에_맞춰_utc로_보정한다() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:v4-migration;MODE=MySQL", "sa", "")) {
            createTables(connection);
            insertSampleRows(connection);

            new V4__normalize_datetime_columns_to_utc().migrate(context(connection));

            assertThat(readDateTime(connection, "execution_job", "execute_after"))
                    .isEqualTo(LocalDateTime.of(2026, 5, 7, 3, 0));
            assertThat(readDateTime(connection, "execution_order", "requested_market_at"))
                    .isEqualTo(LocalDateTime.of(2026, 5, 7, 13, 45));
            assertThat(readDateTime(connection, "operation_mode_audit", "created_at"))
                    .isEqualTo(LocalDateTime.of(2026, 5, 7, 3, 0));
            assertThat(readDateTime(connection, "trading_control", "updated_at"))
                    .isEqualTo(LocalDateTime.of(2026, 5, 7, 3, 0));
        }
    }

    private void createTables(Connection connection) throws Exception {
        connection.createStatement().execute("""
                CREATE TABLE execution_job (
                    id BIGINT PRIMARY KEY,
                    execute_after TIMESTAMP,
                    started_at TIMESTAMP,
                    completed_at TIMESTAMP
                )
                """);
        connection.createStatement().execute("""
                CREATE TABLE execution_order (
                    id BIGINT PRIMARY KEY,
                    requested_market_at TIMESTAMP
                )
                """);
        connection.createStatement().execute("""
                CREATE TABLE operation_mode_audit (
                    id BIGINT PRIMARY KEY,
                    approved_at TIMESTAMP,
                    created_at TIMESTAMP
                )
                """);
        connection.createStatement().execute("""
                CREATE TABLE parameter_change_event (
                    id BIGINT PRIMARY KEY,
                    created_at TIMESTAMP
                )
                """);
        connection.createStatement().execute("""
                CREATE TABLE parameter_registry_record (
                    registry_key VARCHAR(64) PRIMARY KEY,
                    last_changed_at TIMESTAMP
                )
                """);
        connection.createStatement().execute("""
                CREATE TABLE trading_control (
                    control_key VARCHAR(64) PRIMARY KEY,
                    updated_at TIMESTAMP
                )
                """);
    }

    private void insertSampleRows(Connection connection) throws Exception {
        connection.createStatement().execute("""
                INSERT INTO execution_job (id, execute_after, started_at, completed_at)
                VALUES (1, '2026-05-07 12:00:00', '2026-05-07 12:00:00', '2026-05-07 12:00:00')
                """);
        connection.createStatement().execute("""
                INSERT INTO execution_order (id, requested_market_at)
                VALUES (1, '2026-05-07 09:45:00')
                """);
        connection.createStatement().execute("""
                INSERT INTO operation_mode_audit (id, approved_at, created_at)
                VALUES (1, '2026-05-07 12:00:00', '2026-05-07 12:00:00')
                """);
        connection.createStatement().execute("""
                INSERT INTO parameter_change_event (id, created_at)
                VALUES (1, '2026-05-07 12:00:00')
                """);
        connection.createStatement().execute("""
                INSERT INTO parameter_registry_record (registry_key, last_changed_at)
                VALUES ('MAX_SLIPPAGE_PCT', '2026-05-07 12:00:00')
                """);
        connection.createStatement().execute("""
                INSERT INTO trading_control (control_key, updated_at)
                VALUES ('OPERATING_MODE', '2026-05-07 12:00:00')
                """);
    }

    private LocalDateTime readDateTime(Connection connection, String table, String column) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT " + column + " FROM " + table)) {
            rs.next();
            return rs.getObject(column, LocalDateTime.class);
        }
    }

    private Context context(Connection connection) {
        return new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return connection;
            }
        };
    }
}
