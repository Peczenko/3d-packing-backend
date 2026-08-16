package com.packing.backend.domain.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPermissionTest {

    @ParameterizedTest
    @CsvSource({
            "READ,  READ,  true",
            "READ,  WRITE, false",
            "READ,  OWNER, false",
            "WRITE, READ,  true",
            "WRITE, WRITE, true",
            "WRITE, OWNER, false",
            "OWNER, READ,  true",
            "OWNER, WRITE, true",
            "OWNER, OWNER, true" })
    void allowsIsReflexiveAndOrderedWeakestFirst(ProjectPermission held,
                                                 ProjectPermission required,
                                                 boolean expected) {
        assertThat(held.allows(required)).isEqualTo(expected);
    }

    @Test
    void theDeclarationOrderIsTheAuthorityModel() {
        assertThat(ProjectPermission.values())
                                              .containsExactly(ProjectPermission.READ,
                                                               ProjectPermission.WRITE,
                                                               ProjectPermission.OWNER);
    }
}
