package com.iptv.saas.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMaintenance implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMaintenance.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public SchemaMaintenance(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        widenColumnIfNeeded("user_sessions", "item_id", 8192);
        widenColumnIfNeeded("user_sessions", "stream_url", 8192);
    }

    private void widenColumnIfNeeded(String tableName, String columnName, int targetLength) {
        Integer currentLength = currentVarcharLength(tableName, columnName);
        if (currentLength == null || currentLength >= targetLength) {
            return;
        }
        String database = databaseProductName();
        try {
            if (database.contains("h2")) {
                jdbc.execute("alter table " + tableName + " alter column " + columnName + " varchar(" + targetLength + ")");
            } else if (database.contains("postgres")) {
                jdbc.execute("alter table " + tableName + " alter column " + columnName + " type varchar(" + targetLength + ")");
            } else {
                LOGGER.info(
                        "Migration ignoree pour {}.{}: base {} non geree automatiquement",
                        tableName,
                        columnName,
                        database
                );
                return;
            }
            LOGGER.info("Colonne {}.{} elargie de {} a {}", tableName, columnName, currentLength, targetLength);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Impossible d'elargir {}.{} de {} a {}: {}",
                    tableName,
                    columnName,
                    currentLength,
                    targetLength,
                    exception.getMessage()
            );
        }
    }

    private Integer currentVarcharLength(String tableName, String columnName) {
        return jdbc.query("""
                select character_maximum_length
                from information_schema.columns
                where lower(table_name) = ? and lower(column_name) = ?
                """,
                ps -> {
                    ps.setString(1, tableName.toLowerCase(Locale.ROOT));
                    ps.setString(2, columnName.toLowerCase(Locale.ROOT));
                },
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Number length = (Number) rs.getObject(1);
                    return length == null ? null : length.intValue();
                }
        );
    }

    private String databaseProductName() {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        } catch (SQLException exception) {
            return "";
        }
    }
}
