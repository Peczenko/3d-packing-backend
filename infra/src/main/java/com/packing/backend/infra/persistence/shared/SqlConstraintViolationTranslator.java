package com.packing.backend.infra.persistence.shared;

import com.packing.backend.infra.shared.Causes;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public final class SqlConstraintViolationTranslator {

    /** PostgreSQL SQLSTATE for unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final Map<String, Supplier<? extends RuntimeException>> byConstraintName;

    public SqlConstraintViolationTranslator(
            Map<String, Supplier<? extends RuntimeException>> byConstraintName) {
        this.byConstraintName = Map.copyOf(byConstraintName);
    }

    public <T> T translating(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            RuntimeException translated = translate(e);
            throw translated != null ? translated : e;
        }
    }

    public void translating(Runnable action) {
        translating(() -> {
            action.run();
            return null;
        });
    }

    private RuntimeException translate(DataIntegrityViolationException e) {
        SQLException sqlException = findSqlException(e);
        if (sqlException == null || !UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
            return null;
        }

        String message = String.valueOf(sqlException.getMessage()).toLowerCase(Locale.ROOT);
        return byConstraintName.entrySet().stream()
                .filter(entry -> message.contains(entry.getKey()))
                .findFirst()
                .map(entry -> (RuntimeException) entry.getValue().get())
                .orElse(null);
    }

    private SQLException findSqlException(Throwable throwable) {
        return Causes.firstOfType(throwable, SQLException.class).orElse(null);
    }
}
