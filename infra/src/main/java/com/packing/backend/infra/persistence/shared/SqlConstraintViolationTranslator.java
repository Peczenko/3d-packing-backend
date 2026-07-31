package com.packing.backend.infra.persistence.shared;

import com.packing.backend.infra.shared.Causes;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

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
        return Causes.firstOfType(e, PSQLException.class)
                .filter(sql -> UNIQUE_VIOLATION.equals(sql.getSQLState()))
                .map(PSQLException::getServerErrorMessage)
                .map(ServerErrorMessage::getConstraint)
                .map(byConstraintName::get)
                .map(Supplier::get)
                .map(RuntimeException.class::cast)
                .orElse(null);
    }
}
