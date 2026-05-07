package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class V4__normalize_datetime_columns_to_utc extends BaseJavaMigration {

    private static final ZoneId OPERATOR_ZONE = ZoneId.of("Asia/Seoul");
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        normalizeColumn(connection, "execution_job", "id", "execute_after", OPERATOR_ZONE);
        normalizeColumn(connection, "execution_job", "id", "started_at", OPERATOR_ZONE);
        normalizeColumn(connection, "execution_job", "id", "completed_at", OPERATOR_ZONE);
        normalizeColumn(connection, "execution_order", "id", "requested_market_at", MARKET_ZONE);
    }

    private void normalizeColumn(
            Connection connection,
            String table,
            String idColumn,
            String timeColumn,
            ZoneId sourceZone
    ) throws Exception {
        String selectSql = "SELECT " + idColumn + ", " + timeColumn + " FROM " + table + " WHERE " + timeColumn + " IS NOT NULL";
        String updateSql = "UPDATE " + table + " SET " + timeColumn + " = ? WHERE " + idColumn + " = ?";

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rs = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            while (rs.next()) {
                Object id = rs.getObject(idColumn);
                LocalDateTime storedDateTime = rs.getObject(timeColumn, LocalDateTime.class);
                if (storedDateTime == null) {
                    continue;
                }

                LocalDateTime utcLocalDateTime = storedDateTime
                        .atZone(sourceZone)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();

                update.setObject(1, utcLocalDateTime);
                update.setObject(2, id);
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
