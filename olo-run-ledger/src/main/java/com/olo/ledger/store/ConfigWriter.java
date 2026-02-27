package com.olo.ledger.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Single responsibility: write config snapshot records to olo_config.
 */
public final class ConfigWriter {

    private static final String TABLE_CONFIG = "olo_config";
    private static final Logger log = LoggerFactory.getLogger(ConfigWriter.class);

    public void configRecorded(Connection c, String runId, String tenantId, String pipeline,
                               String configVersion, String snapshotVersionId, String pluginVersionsJson,
                               String configTreeJson, String tenantConfigJson) throws SQLException {
        String sql = "INSERT INTO " + TABLE_CONFIG + " (run_id, tenant_id, tenant_name, pipeline, config_version, snapshot_version_id, plugin_versions, config_tree_json, tenant_config_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, LedgerSqlUtils.toUuid(runId));
            ps.setObject(2, LedgerSqlUtils.toUuid(tenantId));
            ps.setString(3, LedgerSqlUtils.toName(tenantId, LedgerSqlUtils.NAME_MAX_LEN));
            ps.setString(4, pipeline);
            ps.setString(5, configVersion);
            ps.setString(6, snapshotVersionId);
            ps.setString(7, pluginVersionsJson);
            ps.setObject(8, configTreeJson != null && !configTreeJson.isBlank() ? LedgerSqlUtils.toJsonbPgObject(configTreeJson) : null);
            ps.setObject(9, tenantConfigJson != null && !tenantConfigJson.isBlank() ? LedgerSqlUtils.toJsonbPgObject(tenantConfigJson) : null);
            ps.setTimestamp(10, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
            log.info("Ledger entry created | olo_config | runId={} tenantId={} pipeline={}", runId, tenantId, pipeline);
        }
    }
}
