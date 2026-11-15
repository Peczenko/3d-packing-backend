package com.packing.backend.domain.packing;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record PackingPreview(PreviewStatus status, List<PreviewModel> models, List<List<PreviewModel>> bins) {

    public static final PackingPreview NONE = new PackingPreview(PreviewStatus.NONE, List.of(), List.of());
    public static final PackingPreview PENDING = new PackingPreview(PreviewStatus.PENDING, List.of(), List.of());
    public static final PackingPreview FAILED = new PackingPreview(PreviewStatus.FAILED, List.of(), List.of());

    public PackingPreview {
        Objects.requireNonNull(status, "status");
        models = ladder(models, "A preview");
        bins = Objects.requireNonNull(bins, "bins")
                      .stream()
                      .map(bin -> ladder(bin, "A container preview"))
                      .toList();
        if ((status == PreviewStatus.READY) == models.isEmpty()) {
            throw new DomainRuleViolationException("A preview has models exactly when it is ready");
        }
        if (status != PreviewStatus.READY && !bins.isEmpty()) {
            throw new DomainRuleViolationException("Only a ready preview has container previews");
        }
        if (bins.stream()
                .anyMatch(List::isEmpty)) {
            throw new DomainRuleViolationException("A container preview must have at least one model");
        }
    }

    public static PackingPreview ready(List<PreviewModel> models) {
        return ready(models, List.of());
    }

    public static PackingPreview ready(List<PreviewModel> models, List<List<PreviewModel>> bins) {
        return new PackingPreview(PreviewStatus.READY, models, bins);
    }

    public Optional<PreviewModel> model(PreviewQuality quality) {
        return find(models, quality);
    }

    // A lone container is the whole result, so its preview is the whole preview, as its OBJ is the whole OBJ.
    public List<PreviewModel> binModels(int number, int binCount) {
        if (status != PreviewStatus.READY) {
            return List.of();
        }
        if (binCount == 1) {
            return models;
        }
        return bins.isEmpty() ? List.of() : bins.get(number - 1);
    }

    public Optional<PreviewModel> binModel(int number, int binCount, PreviewQuality quality) {
        return find(binModels(number, binCount), quality);
    }

    private static Optional<PreviewModel> find(List<PreviewModel> models, PreviewQuality quality) {
        return models.stream()
                     .filter(model -> model.quality() == quality)
                     .findFirst();
    }

    private static List<PreviewModel> ladder(List<PreviewModel> models, String owner) {
        List<PreviewModel> sorted = Objects.requireNonNull(models, "models")
                                           .stream()
                                           .sorted(Comparator.comparing(PreviewModel::quality))
                                           .toList();
        Set<PreviewQuality> seen = EnumSet.noneOf(PreviewQuality.class);
        for (PreviewModel model : sorted) {
            if (!seen.add(model.quality())) {
                throw new DomainRuleViolationException(owner + " lists " + model.quality() + " more than once");
            }
        }
        return sorted;
    }
}
