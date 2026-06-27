package com.dawn.ai.rag;

import java.util.regex.Pattern;

public final class VectorStoreTableName {

    private static final Pattern VALID_SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private VectorStoreTableName() {
    }

    public static String requireValid(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("spring.ai.vectorstore.pgvector.table-name must not be blank");
        }

        String trimmed = tableName.trim();
        if (!VALID_SQL_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "spring.ai.vectorstore.pgvector.table-name must be a simple SQL identifier: " + tableName);
        }
        return trimmed;
    }
}
