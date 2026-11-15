package com.packing.backend.infra.conversion;

import com.packing.backend.core.file.ModelConversionException;
import com.packing.backend.core.file.port.out.ModelConverter;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.infra.shared.mesh.MalformedObjException;
import com.packing.backend.infra.shared.mesh.ObjReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
@EnableConfigurationProperties(ConversionProperties.class)
@Slf4j
public class AssimpModelConverter implements ModelConverter {

    static final String SERVICE = "model-conversion";

    private static final String SOURCE   = "source.";
    private static final String EXPORTED = "assimp.obj";
    private static final String PACKABLE = "model.obj";

    private final Assimp    assimp;
    private final Semaphore slots;

    @Autowired
    public AssimpModelConverter(ConversionProperties properties) {
        this(new Assimp(properties.assimpPath(), properties.timeout()), properties.maxConcurrent());
    }

    AssimpModelConverter(Assimp assimp, int maxConcurrent) {
        this.assimp = assimp;
        this.slots = new Semaphore(maxConcurrent, true);
    }

    @Override
    public ConvertedModel toObj(ContentSource source, ModelFormat format) {
        if (format == ModelFormat.OBJ || !format.isUploadable()) {
            throw new IllegalArgumentException(format + " is not converted to OBJ");
        }
        acquireSlot();
        Path work = null;
        try {
            work = Files.createTempDirectory("model-conversion-");
            return convert(source, format, work);
        } catch (IOException e) {
            deleteQuietly(work);
            throw new ExternalServiceException(SERVICE, "Could not convert a " + label(format) + " model: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteQuietly(work);
            throw e;
        } finally {
            slots.release();
        }
    }

    private ConvertedModel convert(ContentSource source, ModelFormat format, Path work) throws IOException {
        long started = System.nanoTime();
        String input = SOURCE + extension(format);
        try (InputStream content = source.open()) {
            Files.copy(content, work.resolve(input), StandardCopyOption.REPLACE_EXISTING);
        }

        Assimp.Result result = assimp.export(work, input, EXPORTED);
        if (result.timedOut()) {
            throw new ModelConversionException("The " + label(format) + " model took longer than " + assimp.timeout()
                                                                                                           .toSeconds()
                    + " s to convert to OBJ");
        }
        if (!result.exported() || !Files.isRegularFile(work.resolve(EXPORTED))) {
            String reason = result.reason() == null ? "the converter exited with code " + result.exitCode()
                                                    : result.reason()
                                                            .replace("\"" + input + "\"", "the file");
            throw new ModelConversionException("The " + label(format) + " file could not be read: " + reason);
        }

        double scale = millimetresPerUnit(format, work.resolve(input));
        PackableObjWriter writer;
        try (InputStream exported = Files.newInputStream(work.resolve(EXPORTED));
             Writer out = Files.newBufferedWriter(work.resolve(PACKABLE), StandardCharsets.US_ASCII)) {
            writer = new PackableObjWriter(out, scale);
            ObjReader.read(exported, writer);
            writer.finish();
        } catch (MalformedObjException e) {
            throw new ModelConversionException("The " + label(format) + " model could not be converted to OBJ: " + e.getMessage());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Files.delete(work.resolve(EXPORTED));
        Files.delete(work.resolve(input));
        log.info("Converted a {} model to OBJ: {} triangles, {} degenerate dropped, scale {}, {} ms",
                 label(format),
                 writer.triangles(),
                 writer.dropped(),
                 scale,
                 TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return new TemporaryObj(work, work.resolve(PACKABLE));
    }

    static double millimetresPerUnit(ModelFormat format, Path file) {
        return switch (format) {
            case GLTF, GLB -> 1000.0;
            case THREE_MF -> ThreeMfUnit.millimetresPerUnit(file);
            default -> 1.0;
        };
    }

    private void acquireSlot() {
        try {
            if (!slots.tryAcquire(assimp.timeout()
                                        .toMillis(),
                                  TimeUnit.MILLISECONDS)) {
                throw new ExternalServiceException(SERVICE, "Every model converter stayed busy for " + assimp.timeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new ExternalServiceException(SERVICE, "Interrupted while waiting for a model converter", e);
        }
    }

    private static String extension(ModelFormat format) {
        return format.extensions()
                     .iterator()
                     .next();
    }

    private static String label(ModelFormat format) {
        return extension(format).toUpperCase(Locale.ROOT);
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
            log.warn("Could not delete conversion workspace {}: {}", work, e.getMessage());
        }
    }

    record TemporaryObj(Path work, Path obj) implements ConvertedModel {

        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(obj);
        }

        @Override
        public void close() {
            deleteQuietly(work);
        }
    }
}
