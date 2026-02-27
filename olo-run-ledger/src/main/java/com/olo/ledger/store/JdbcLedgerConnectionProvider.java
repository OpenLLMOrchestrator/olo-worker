package com.olo.ledger.store;

import com.olo.config.OloConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.TimeZone;

/**
 * Single responsibility: provide JDBC connections to the ledger database (PostgreSQL, UTC).
 */
public final class JdbcLedgerConnectionProvider {

    private final OloConfig config;

    public JdbcLedgerConnectionProvider(OloConfig config) {
        this.config = config != null ? config : throwNPE();
    }

    private static OloConfig throwNPE() {
        throw new NullPointerException("OloConfig");
    }

    public Connection getConnection() throws SQLException {
        String url = "jdbc:postgresql://" + config.getDbHost() + ":" + config.getDbPort() + "/" + config.getDbName();
        TimeZone prev = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            return DriverManager.getConnection(url, config.getDbUser(), config.getDbPassword() != null ? config.getDbPassword() : "");
        } finally {
            TimeZone.setDefault(prev);
        }
    }
}
