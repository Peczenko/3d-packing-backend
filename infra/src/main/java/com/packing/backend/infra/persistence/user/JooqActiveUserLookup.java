package com.packing.backend.infra.persistence.user;

import com.packing.backend.core.shared.port.out.ActiveUserLookup;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;

@Repository
@RequiredArgsConstructor
public class JooqActiveUserLookup implements ActiveUserLookup {

    private final DSLContext dsl;


    @Override
    public Optional<UserId> findActiveUser(FirebaseUid firebaseUid) {
        return dsl.select(USERS.ID)
                .from(USERS)
                .where(USERS.FIREBASE_UID.eq(firebaseUid.value())
                        .and(USERS.STATUS.eq(UserStatus.ACTIVE.name())))
                .fetchOptional(USERS.ID)
                .map(UserId::new);
    }
}
