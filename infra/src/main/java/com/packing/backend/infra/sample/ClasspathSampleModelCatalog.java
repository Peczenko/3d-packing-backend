package com.packing.backend.infra.sample;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.file.SampleModel;
import com.packing.backend.core.file.port.out.SampleModelCatalog;
import com.packing.backend.core.packing.port.out.PackingInputSchema;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ClasspathSampleModelCatalog implements SampleModelCatalog {

    static final String DIRECTORY = "samples";
    static final String MANIFEST  = "catalog.json";

    private static final Pattern ID          = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int     BUFFER_SIZE = 8192;

    private final Map<String, SampleModel> samples;

    @Autowired
    public ClasspathSampleModelCatalog(ObjectMapper objectMapper, PackingInputSchema packingInput) {
        this(objectMapper, packingInput.acceptedFormats(), DIRECTORY);
    }

    ClasspathSampleModelCatalog(ObjectMapper objectMapper, Set<ModelFormat> acceptedFormats, String directory) {
        Map<String, SampleModel> loaded = new LinkedHashMap<>();
        for (Entry entry : readManifest(objectMapper, directory)) {
            SampleModel sample = load(entry, acceptedFormats, directory);
            if (loaded.putIfAbsent(sample.id(), sample) != null) {
                throw invalid(entry.id(), "the id is listed twice");
            }
        }
        this.samples = Collections.unmodifiableMap(loaded);
    }

    @Override
    public List<SampleModel> list() {
        return List.copyOf(samples.values());
    }

    @Override
    public Optional<SampleModel> find(String id) {
        return Optional.ofNullable(samples.get(id));
    }

    private static List<Entry> readManifest(ObjectMapper objectMapper, String directory) {
        String path = directory + "/" + MANIFEST;
        try (InputStream stream = open(path)) {
            return objectMapper.readerForListOf(Entry.class)
                               .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                               .readValue(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the sample model catalogue " + path, e);
        }
    }

    private static SampleModel load(Entry entry, Set<ModelFormat> acceptedFormats, String directory) {
        if (entry.id() == null || !ID.matcher(entry.id())
                                     .matches()) {
            throw invalid(entry.id(), "the id must be lowercase letters, digits and single hyphens");
        }
        if (isBlank(entry.name()) || isBlank(entry.description())) {
            throw invalid(entry.id(), "name and description must not be blank");
        }
        FileName filename = filenameOf(entry, acceptedFormats);

        String path = directory + "/" + filename.value();
        Content content = digest(entry.id(), path);
        if (content.sizeBytes() == 0 || content.sizeBytes() > StoredFile.MAX_SIZE_BYTES) {
            throw invalid(entry.id(),
                          path + " must hold between 1 and " + StoredFile.MAX_SIZE_BYTES + " bytes, got "
                                  + content.sizeBytes());
        }
        return new SampleModel(entry.id(),
                               entry.name(),
                               entry.description(),
                               filename,
                               content.sizeBytes(),
                               content.checksum(),
                               () -> open(path));
    }

    private static FileName filenameOf(Entry entry, Set<ModelFormat> acceptedFormats) {
        FileName filename;
        ModelFormat format;
        try {
            filename = new FileName(entry.file());
            format = filename.format();
        } catch (DomainException e) {
            throw invalid(entry.id(), e.getMessage());
        }
        if (!filename.value()
                     .equals(entry.file())) {
            throw invalid(entry.id(), "file must be a plain file name inside the catalogue directory");
        }
        if (!acceptedFormats.contains(format)) {
            throw invalid(entry.id(), format + " is not a format the packer accepts " + acceptedFormats);
        }
        return filename;
    }

    private static Content digest(String id, String path) {
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream stream = open(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (FileNotFoundException e) {
            throw invalid(id, path + " is not on the classpath");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sample model " + path, e);
        }
        return new Content(size,
                           Checksum.ofHex(HexFormat.of()
                                                   .formatHex(digest.digest())));
    }

    private static InputStream open(String path) throws IOException {
        InputStream stream = ClasspathSampleModelCatalog.class.getClassLoader()
                                                              .getResourceAsStream(path);
        if (stream == null) {
            throw new FileNotFoundException(path + " is not on the classpath");
        }
        return stream;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException invalid(String id, String reason) {
        return new IllegalStateException("Sample model '" + id + "' in the catalogue is invalid: " + reason);
    }

    private record Entry(String id, String file, String name, String description) {
    }

    private record Content(long sizeBytes, Checksum checksum) {
    }
}
