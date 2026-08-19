package com.packing.backend.infra.persistence.user;

import com.packing.backend.core.user.UserSearchResult;
import com.packing.backend.core.user.port.out.UserFinder;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.packing.backend.domain.user.UserStatus.DELETED;
import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;

@Repository
@RequiredArgsConstructor
public class JooqUserFinder implements UserFinder {

    private final DSLContext dsl;

    @Override
    public List<UserSearchResult> search(String pattern, int limit) {
        Condition usernameMatches = USERS.USERNAME.containsIgnoreCase(pattern);
        Condition displayNameMatches = USERS.DISPLAY_NAME.containsIgnoreCase(pattern);
        Condition emailMatches = USERS.EMAIL.containsIgnoreCase(pattern);
        Field<Integer> rank = DSL.when(usernameMatches, 0)
                                 .when(displayNameMatches, 1)
                                 .otherwise(2);

        return dsl.select(USERS.ID, USERS.USERNAME, USERS.DISPLAY_NAME, USERS.STATUS)
                  .from(USERS)
                  .where(USERS.STATUS.ne(DELETED)
                                     .and(usernameMatches.or(displayNameMatches)
                                                         .or(emailMatches)))
                  .orderBy(rank.asc(), USERS.USERNAME.asc(), USERS.ID.asc())
                  .limit(limit)
                  .fetch(record -> new UserSearchResult(
                                                        record.value1(),
                                                        record.value2(),
                                                        record.value3(),
                                                        record.value4()));
    }
}
