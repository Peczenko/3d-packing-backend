package com.packing.backend.infra.shared;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class Causes {

    private Causes() {
    }

    public static List<Throwable> chainOf(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = throwable; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    public static <T extends Throwable> Optional<T> firstOfType(Throwable throwable, Class<T> type) {
        return chainOf(throwable).stream().filter(type::isInstance).map(type::cast).findFirst();
    }
}
