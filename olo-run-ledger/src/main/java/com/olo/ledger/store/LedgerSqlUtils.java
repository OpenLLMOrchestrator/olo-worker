package com.olo.ledger.store;

import org.postgresql.util.PGobject;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Single responsibility: SQL value conversion for ledger columns (UUID, name truncation, JSONB).
 */
public final class LedgerSqlUtils {

    public static final int NAME_MAX_LEN = 255;

    private LedgerSqlUtils() {}

    /**
     * Converts a string to a UUID for id columns. When the value is not a valid UUID (e.g. "default", "root"),
     * returns a deterministic UUID. The original string is stored in the corresponding name column.
     */
    public static UUID toUuid(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(t.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Truncate to max length for name columns; null/blank returns null. */
    public static String toName(String s, int maxLen) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }

    /** Wrap string as PG jsonb for a single ? placeholder. */
    public static PGobject toJsonbPgObject(String json) throws SQLException {
        PGobject o = new PGobject();
        o.setType("jsonb");
        o.setValue(json != null ? json : "{}");
        return o;
    }
}
