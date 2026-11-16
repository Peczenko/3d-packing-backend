package com.packing.backend.infra.packing.preview;

import com.packing.backend.infra.shared.mesh.MalformedObjException;
import com.packing.backend.infra.shared.mesh.ObjReader;
import com.packing.backend.core.packing.port.out.PackingArtifact;
import com.packing.backend.core.packing.port.out.PackingArtifact.PreviewArtifact;
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
import java.util.Optional;
import java.util.function.Function;
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
            Optional<PlacementLayout> placements = placements(jobId, items, resultChecksum, binCount);
            Rendered rendered = placements.isPresent()
                                                       ? renderPlaced(jobId, items, placements.get(), binCount, work)
                                                       : renderResult(jobId, items, resultChecksum, binCount, work);
            log.info("Preview built for packing job {} from {} in {} ms: {}, {} containers",
                     jobId,
                     placements.isPresent() ? "placements" : "the result",
                     (System.nanoTime() - started) / 1_000_000,
                     rendered.models(),
                     rendered.bins()
                             .size());
            return rendered;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the preview of packing job " + jobId, e);
        } finally {
            deleteQuietly(work);
        }
    }

    private Optional<PlacementLayout> placements(PackingJobId jobId, List<PackingItem> items, String resultChecksum, int binCount) {
        Optional<byte[]> stored = artifacts.findPlacements(jobId, resultChecksum);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PlacementLayout.parse(stored.get(), items, binCount));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring the placements of packing job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    private Rendered renderPlaced(PackingJobId jobId, List<PackingItem> items, PlacementLayout placements, int binCount, Path work)
                                                                                                                                    throws IOException {
        List<Map<PreviewQuality, Path>> packed = new ArrayList<>();
        List<double[]> centroids = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            PackingItem item = items.get(index);
            InputMesh mesh = new InputMesh(item.fileName()
                                               .value());
            try (InputStream input = artifacts.openInput(item)) {
                ObjReader.read(input, mesh);
            } catch (MalformedObjException e) {
                throw new UnrecognizedPackerOutputException("'" + item.fileName()
                                                                      .value()
                        + "' is malformed at " + e.getMessage());
            }
            centroids.add(mesh.centroid());
            packed.add(pack(mesh.centered(index), work));
        }
        List<PreviewModel> models = store(placed(items, placements, 0, packed, centroids),
                                          work.resolve("result"),
                                          quality -> new PackingArtifact.Preview(jobId, quality));
        List<List<PreviewModel>> bins = new ArrayList<>();
        for (int number = 1; binCount > 1 && number <= binCount; number++) {
            int bin = number;
            bins.add(store(placed(items, placements, bin, packed, centroids),
                           work.resolve("bin-" + bin),
                           quality -> new PackingArtifact.BinPreview(jobId, bin, binCount, quality)));
        }
        return new Rendered(models, bins);
    }

    private static Map<PreviewQuality, List<GlbMerger.Part>> placed(List<PackingItem> items,
                                                                    PlacementLayout placements,
                                                                    int bin,
                                                                    List<Map<PreviewQuality, Path>> packed,
                                                                    List<double[]> centroids) {
        Map<PreviewQuality, List<GlbMerger.Part>> parts = new EnumMap<>(PreviewQuality.class);
        for (PreviewQuality quality : PreviewQuality.values()) {
            parts.put(quality, new ArrayList<>());
        }
        for (int index = 0; index < items.size(); index++) {
            List<PreviewPart.Instance> instances = placements.instances(index, bin, centroids.get(index));
            if (instances.isEmpty()) {
                continue;
            }
            for (PreviewQuality quality : PreviewQuality.values()) {
                parts.get(quality)
                     .add(new GlbMerger.Part(packed.get(index)
                                                   .get(quality),
                                             items.get(index)
                                                  .fileName()
                                                  .value(),
                                             instances));
            }
        }
        return parts;
    }

    private Rendered renderResult(PackingJobId jobId, List<PackingItem> items, String resultChecksum, int binCount, Path work)
                                                                                                                               throws IOException {
        List<InstanceExtractor.Item> layout = items.stream()
                                                   .map(item -> new InstanceExtractor.Item(item.fileName()
                                                                                               .value(),
                                                                                           item.copies()))
                                                   .toList();
        BinMembership membership = binCount > 1 ? new BinMembership(layout, binCount) : null;
        List<PreviewModel> models;
        try (InputStream result = artifacts.open(new PackingArtifact.Result(jobId))) {
            models = renderOne(result,
                               layout,
                               membership == null ? UnaryOperator.identity() : membership::observing,
                               work.resolve("result"),
                               quality -> new PackingArtifact.Preview(jobId, quality));
        }
        List<List<PreviewModel>> bins = new ArrayList<>();
        for (int number = 1; membership != null && number <= binCount; number++) {
            int bin = number;
            try (InputStream result = artifacts.open(new PackingArtifact.BinResult(jobId, resultChecksum, bin, binCount))) {
                bins.add(renderOne(result,
                                   membership.layout(bin),
                                   UnaryOperator.identity(),
                                   work.resolve("bin-" + bin),
                                   quality -> new PackingArtifact.BinPreview(jobId, bin, binCount, quality)));
            }
        }
        return new Rendered(models, bins);
    }

    private List<PreviewModel> renderOne(InputStream source,
                                         List<InstanceExtractor.Item> layout,
                                         UnaryOperator<ObjReader.Sink> observe,
                                         Path work,
                                         Function<PreviewQuality, PreviewArtifact> target)
                                                                                           throws IOException {
        Files.createDirectory(work);
        try {
            return store(pack(source, layout, observe, work), work, target);
        } finally {
            deleteQuietly(work);
        }
    }

    private List<PreviewModel> store(Map<PreviewQuality, List<GlbMerger.Part>> packed,
                                     Path work,
                                     Function<PreviewQuality, PreviewArtifact> target)
                                                                                       throws IOException {
        Files.createDirectories(work);
        List<PreviewModel> models = new ArrayList<>();
        long finer = Long.MAX_VALUE;
        for (PreviewQuality quality : FINEST_FIRST) {
            Path merged = work.resolve(quality.name() + ".glb");
            GlbMerger.Merged result = GlbMerger.merge(packed.get(quality), merged);
            if (finer != Long.MAX_VALUE && result.triangles() > finer * MIN_REDUCTION) {
                continue;
            }
            artifacts.storePreview(target.apply(quality), merged);
            models.add(new PreviewModel(quality, result.triangles(), result.sizeBytes()));
            finer = result.triangles();
        }
        return models;
    }

    private Map<PreviewQuality, Path> pack(PreviewPart part, Path work) throws IOException {
        Map<PreviewQuality, Path> packed = new EnumMap<>(PreviewQuality.class);
        Path input = work.resolve("part-" + part.index() + ".glb");
        PreviewPartWriter.write(part, input);
        for (PreviewQuality quality : PreviewQuality.values()) {
            Path output = work.resolve("part-" + part.index() + "-" + quality.name() + ".glb");
            gltfpack.pack(input, output, quality);
            packed.put(quality, output);
        }
        Files.delete(input);
        return packed;
    }

    private Map<PreviewQuality, List<GlbMerger.Part>> pack(InputStream source,
                                                           List<InstanceExtractor.Item> layout,
                                                           UnaryOperator<ObjReader.Sink> observe,
                                                           Path work)
                                                                      throws IOException {
        Map<PreviewQuality, List<GlbMerger.Part>> packed = new EnumMap<>(PreviewQuality.class);
        for (PreviewQuality quality : PreviewQuality.values()) {
            packed.put(quality, new ArrayList<>());
        }
        InstanceExtractor extractor = new InstanceExtractor(layout, part -> {
            try {
                pack(part, work).forEach((quality, output) -> packed.get(quality)
                                                                    .add(new GlbMerger.Part(output, part.name())));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            ObjReader.read(source, observe.apply(extractor));
            extractor.finish();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (MalformedObjException e) {
            throw new UnrecognizedPackerOutputException("The packing result is malformed at " + e.getMessage());
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
