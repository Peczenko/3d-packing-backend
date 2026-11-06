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
    public List<PreviewModel> render(PackingJobId jobId, List<PackingItem> items) {
        long started = System.nanoTime();
        Path work = null;
        try {
            work = Files.createTempDirectory("packing-preview-" + jobId + "-");
            Map<PreviewQuality, List<GlbMerger.Part>> packed = pack(jobId, items, work);
            List<PreviewModel> models = new ArrayList<>();
            long finer = Long.MAX_VALUE;
            for (PreviewQuality quality : FINEST_FIRST) {
                Path merged = work.resolve(quality.name() + ".glb");
                GlbMerger.Merged result = GlbMerger.merge(packed.get(quality), merged);
                if (finer != Long.MAX_VALUE && result.triangles() > finer * MIN_REDUCTION) {
                    continue;
                }
                artifacts.storePreview(jobId, quality, merged);
                models.add(new PreviewModel(quality, result.triangles(), result.sizeBytes()));
                finer = result.triangles();
            }
            log.info("Preview built for packing job {} in {} ms: {}", jobId, (System.nanoTime() - started) / 1_000_000, models);
            return models;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the preview of packing job " + jobId, e);
        } finally {
            deleteQuietly(work);
        }
    }

    private Map<PreviewQuality, List<GlbMerger.Part>> pack(PackingJobId jobId, List<PackingItem> items, Path work) throws IOException {
        Map<PreviewQuality, List<GlbMerger.Part>> packed = new EnumMap<>(PreviewQuality.class);
        for (PreviewQuality quality : PreviewQuality.values()) {
            packed.put(quality, new ArrayList<>());
        }
        List<InstanceExtractor.Item> layout = items.stream()
                                                   .map(item -> new InstanceExtractor.Item(item.fileName()
                                                                                               .value(),
                                                                                           item.copies()))
                                                   .toList();
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
        try (InputStream result = artifacts.openResult(jobId)) {
            PackerObjReader.read(result, extractor);
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
