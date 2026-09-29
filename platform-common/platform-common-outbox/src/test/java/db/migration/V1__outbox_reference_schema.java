package db.migration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Applies the module's own reference schema, so the tests run against exactly what services are told to copy. */
public class V1__outbox_reference_schema extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        String sql;
        try (InputStream in =
                getClass().getClassLoader().getResourceAsStream("META-INF/pallet/outbox/reference-schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String schema = context.getConfiguration().getDefaultSchema();
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("SET LOCAL search_path TO " + schema);
            statement.execute(sql);
        }
    }
}
