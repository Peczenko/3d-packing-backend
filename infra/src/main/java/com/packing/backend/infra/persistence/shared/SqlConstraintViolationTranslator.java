package com.packing.backend.infra.persistence.shared;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Turns PostgreSQL unique-constraint violations into the domain exception that names the
 * rule that was actually broken.
 *
 * <p>Without this, a race on {@code users.username} surfaces as an opaque
 * {@code DataIntegrityViolationException} and the client gets a 500 for what is really a
 * 409. Every aggregate needs the same translation, so the constraint-name-to-exception
 * mapping is supplied per repository and the plumbing lives here.
 */
public final class SqlConstraintViolationTranslator {

    /** PostgreSQL SQLSTATE for unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final Map<String, Supplier<? extends RuntimeException>> byConstraintName;

    /**
     * @param byConstraintName constraint name (as declared in the migration, lower case)
     *                         to the exception to raise
     */
    public SqlConstraintViolationTranslator(
            Map<String, Supplier<? extends RuntimeException>> byConstraintName) {
        this.byConstraintName = Map.copyOf(byConstraintName);
    }

    /**
     * Runs {@code action}, translating a recognised unique violation into a domain
     * exception. Anything else propagates untouched — swallowing unknown integrity errors
     * would hide real bugs.
     */
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
        // The driver reports the constraint name in the message rather than in a
        // structured field, so match on containment.
        String message = String.valueOf(sqlException.getMessage()).toLowerCase(Locale.ROOT);
        return byConstraintName.entrySet().stream()
                .filter(entry -> message.contains(entry.getKey()))
                .findFirst()
                .map(entry -> (RuntimeException) entry.getValue().get())
                .orElse(null);
    }

    private SQLException findSqlException(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }
}
