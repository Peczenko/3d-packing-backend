package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.port.out.FileOwnerLookup;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserStatus;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;

/**
 * The status filter is the port's contract, not an optimisation: a disabled or deleted
 * account must not reach its files.
 */
@Repository
public class JooqFileOwnerLookup implements FileOwnerLookup {

    private final DSLContext dsl;

    public JooqFileOwnerLookup(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<UserId> findActiveOwner(FirebaseUid firebaseUid) {
        return dsl.select(USERS.ID)
                .from(USERS)
                .where(USERS.FIREBASE_UID.eq(firebaseUid.value())
                        .and(USERS.STATUS.eq(UserStatus.ACTIVE.name())))
                .fetchOptional(USERS.ID)
                .map(UserId::new);
    }
}
