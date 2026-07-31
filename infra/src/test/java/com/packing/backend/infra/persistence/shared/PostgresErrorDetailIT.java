package com.packing.backend.infra.persistence.shared;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresErrorDetailIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private Connection connect(String extraParams) throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + extraParams,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void provoke(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists leak_probe ("
                    + "id int primary key, secret text, constraint uq_leak_probe_secret unique (secret))");
            statement.execute("delete from leak_probe");
            statement.execute("insert into leak_probe values (1, 'secret-design.stl')");
            statement.execute("insert into leak_probe values (2, 'secret-design.stl')");
        }
    }

    @Test
    void detailLeaksTheSubmittedValueByDefault() throws Exception {
        try (Connection connection = connect("")) {
            assertThatThrownBy(() -> provoke(connection))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageContaining("secret-design.stl");
        }
    }

    @Test
    void detailIsSuppressedButTheConstraintNameSurvives() throws Exception {
        try (Connection connection = connect("&logServerErrorDetail=false")) {
            assertThatThrownBy(() -> provoke(connection))
                    .isInstanceOf(PSQLException.class)
                    .hasMessageNotContaining("secret-design.stl")
                    .satisfies(e -> assertThat(
                            ((PSQLException) e).getServerErrorMessage().getConstraint())
                            .isEqualTo("uq_leak_probe_secret"));
        }
    }
}
