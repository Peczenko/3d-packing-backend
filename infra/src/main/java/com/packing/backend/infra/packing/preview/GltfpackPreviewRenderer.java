package com.packing.backend.infra.packing.preview;

import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingPreviewRenderer;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewModel;
import com.packing.backend.domain.packing.PreviewQuality;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

@Component
@EnableConfigurationProperties(PreviewProperties.class)
@Slf4j
public class GltfpackPreviewRenderer implements PackingPreviewRenderer {

    static final double MIN_REDUCTION = 0.9;

    private static final List<PreviewQuality> FINEST_FIRST = List.of(PreviewQuality.HIGH, PreviewQuality.MEDIUM, PreviewQuality.LOW);

    private final PackingJobArtifactStore artifacts;
    private final Gltfpack                gltfpack;

    @Autowired
    public GltfpackPreviewRenderer(PackingJobArtifactStore artifacts, PreviewProperties properties) {
        this(artifacts, new Gltfpack(properties.gltfpackPath(), properties.gltfpackTimeout()));
    }

    GltfpackPreviewRenderer(PackingJobArtifactStore artifacts, Gltfpack gltfpack) {
        this.artifacts = artifacts;
        this.gltfpack = gltfpack;
    }

    @Override
    public Rendered render(PackingJobId jobId, List<PackingItem> items, String resultChecksum, int binCount) {
        long started = System.nanoTime();
        Path work = null;
        try {
            work = Files.createTempDirectory("packing-preview-" + jobId + "-");
            List<InstanceExtractor.Item> layout = items.stream()
                                                       .map(item -> new InstanceExtractor.Item(item.fileName()
                                                                                                   .value(),
                                                                                               item.copies()))
                                                       .toList();
            BinMembership membership = binCount > 1 ? new BinMembership(layout, binCount) : null;
            List<PreviewModel> models;
            try (InputStream result = artifacts.openResult(jobId)) {
                models = renderOne(result,
                                   layout,
                                   membership == null ? UnaryOperator.identity() : membership::observing,
                                   work.resolve("result"),
                                   (quality, glb) -> artifacts.storePreview(jobId, quality, glb));
            }
            List<List<PreviewModel>> bins = new ArrayList<>();
            for (int number = 1; membership != null && number <= binCount; number++) {
                int bin = number;
                try (InputStream result = artifacts.openResultBin(jobId, resultChecksum, bin)) {
                    bins.add(renderOne(result,
                                       membership.layout(bin),
                                       UnaryOperator.identity(),
                                       work.resolve("bin-" + bin),
                                       (quality, glb) -> artifacts.storeBinPreview(jobId, bin, quality, glb)));
                }
            }
            log.info("Preview built for packing job {} in {} ms: {}, {} containers",
                     jobId,
                     (System.nanoTime() - started) / 1_000_000,
                     models,
                     bins.size());
            return new Rendered(models, bins);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the preview of packing job " + jobId, e);
        } finally {
            deleteQuietly(work);
        }
    }

    private List<PreviewModel> renderOne(InputStream source,
                                         List<InstanceExtractor.Item> layout,
                                         UnaryOperator<PackerObjReader.Sink> observe,
                                         Path work,
                                         BiConsumer<PreviewQuality, Path> store)
                                                                                 throws IOException {
        Files.createDirectory(work);
        try {
            Map<PreviewQuality, List<GlbMerger.Part>> packed = pack(source, layout, observe, work);
            List<PreviewModel> models = new ArrayList<>();
            long finer = Long.MAX_VALUE;
            for (PreviewQuality quality : FINEST_FIRST) {
                Path merged = work.resolve(quality.name() + ".glb");
                GlbMerger.Merged result = GlbMerger.merge(packed.get(quality), merged);
                if (finer != Long.MAX_VALUE && result.triangles() > finer * MIN_REDUCTION) {
                    continue;
                }
                store.accept(quality, merged);
                models.add(new PreviewModel(quality, result.triangles(), result.sizeBytes()));
                finer = result.triangles();
            }
            return models;
        } finally {
            deleteQuietly(work);
        }
    }

    private Map<PreviewQuality, List<GlbMerger.Part>> pack(InputStream source,
                                                           List<InstanceExtractor.Item> layout,
                                                           UnaryOperator<PackerObjReader.Sink> observe,
                                                           Path work)
                                                                      throws IOException {
        Map<PreviewQuality, List<GlbMerger.Part>> packed = new EnumMap<>(PreviewQuality.class);
        for (PreviewQuality quality : PreviewQuality.values()) {
            packed.put(quality, new ArrayList<>());
        }
        InstanceExtractor extractor = new InstanceExtractor(layout, part -> {
            try {
                Path input = work.resolve("part-" + part.index() + ".glb");
                PreviewPartWriter.write(part, input);
                for (PreviewQuality quality : PreviewQuality.values()) {
                    Path output = work.resolve("part-" + part.index() + "-" + quality.name() + ".glb");
                    gltfpack.pack(input, output, quality);
                    packed.get(quality)
                          .add(new GlbMerger.Part(output, part.name()));
                }
                Files.delete(input);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            PackerObjReader.read(source, observe.apply(extractor));
            extractor.finish();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return packed;
    }

    private static void deleteQuietly(Path work) {
        if (work == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(work)) {
            paths.sorted(Comparator.reverseOrder())
                 .forEach(path -> path.toFile()
                                      .delete());
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not delete preview workspace {}: {}", work, e.getMessage());
        }
    }
}
