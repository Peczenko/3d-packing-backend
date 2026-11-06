package com.packing.backend.domain.packing;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record PackingPreview(PreviewStatus status, List<PreviewModel> models) {

    public static final PackingPreview NONE = new PackingPreview(PreviewStatus.NONE, List.of());
    public static final PackingPreview PENDING = new PackingPreview(PreviewStatus.PENDING, List.of());
    public static final PackingPreview FAILED = new PackingPreview(PreviewStatus.FAILED, List.of());

    public PackingPreview {
        Objects.requireNonNull(status, "status");
        models = Objects.requireNonNull(models, "models")
                        .stream()
                        .sorted(Comparator.comparing(PreviewModel::quality))
                        .toList();
        if ((status == PreviewStatus.READY) == models.isEmpty()) {
            throw new DomainRuleViolationException("A preview has models exactly when it is ready");
        }
        Set<PreviewQuality> seen = EnumSet.noneOf(PreviewQuality.class);
        for (PreviewModel model : models) {
            if (!seen.add(model.quality())) {
                throw new DomainRuleViolationException("A preview lists " + model.quality() + " more than once");
            }
        }
    }

    public static PackingPreview ready(List<PreviewModel> models) {
        return new PackingPreview(PreviewStatus.READY, models);
    }

    public Optional<PreviewModel> model(PreviewQuality quality) {
        return models.stream()
                     .filter(model -> model.quality() == quality)
                     .findFirst();
    }
}
