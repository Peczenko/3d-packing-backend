package com.packing.backend.core.user.port.out;

import com.packing.backend.core.user.UserSearchResult;

import java.util.List;

public interface UserFinder {

    List<UserSearchResult> search(String pattern, int limit);
}
