package com.packing.backend.core.file;

import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileFinder;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.file.port.out.ModelConverter;
import com.packing.backend.core.file.port.out.ModelConverter.ConvertedModel;
import com.packing.backend.core.file.port.out.SampleModelCatalog;
import com.packing.backend.core.project.ProjectAccessGuard;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.TemporaryUrl;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.ConvertedObj;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.file.StoredFileNotFoundException;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileApplicationService {

    private final FileRepository       files;
    private final FileFinder           fileFinder;
    private final BinaryStorage        storage;
    private final ModelConverter       converter;
    private final SampleModelCatalog   samples;
    private final ProjectAccessGuard   projectAccess;
    private final DomainEventPublisher eventPublisher;
    private final Clock                clock;

    public FileView upload(UploadFileCommand command) {
        ProjectAccess access = projectAccess.requireWritable(command.firebaseUid(), command.projectId());
        return store(access, new FileName(command.originalFilename()), command.content());
    }

    public FileView addSample(AddSampleCommand command) {
        ProjectAccess access = projectAccess.requireWritable(command.firebaseUid(), command.projectId());
        SampleModel sample = sample(command.sampleId());
        return store(access, sample.filename(), sample.content());
    }

    public List<SampleModel> listSamples() {
        return samples.list();
    }

    public SampleModel sample(String sampleId) {
        return samples.find(sampleId)
                      .orElseThrow(() -> SampleModelNotFoundException.byId(sampleId));
    }

    @Transactional(readOnly = true)
    public TemporaryUrl prepareDownload(PrepareDownloadCommand command) {
        ProjectAccess access = projectAccess.require(command.firebaseUid(), command.projectId(), ProjectPermission.READ);
        StoredFile file = requireAvailable(command.fileId(), access.projectId());

        return storage.readUrl(file.storageKey(),
                               file.name()
                                   .value(),
                               file.contentType());
    }

    @Transactional(readOnly = true)
    public Page<FileView> listFiles(ListFilesCommand command) {
        ProjectAccess access = projectAccess.require(command.firebaseUid(), command.projectId(), ProjectPermission.READ);
        return fileFinder.listAvailableInProject(access.projectId(), command.criteria());
    }

    @Transactional
    public FileView renameFile(RenameFileCommand command) {
        ProjectAccess access = projectAccess.requireWritable(command.firebaseUid(), command.projectId());
        StoredFile file = requireAvailable(command.fileId(), access.projectId());

        file.rename(new FileName(command.name()), clock.instant());
        return FileView.from(files.save(file));
    }

    @Transactional
    public void deleteFile(DeleteFileCommand command) {
        ProjectAccess access = projectAccess.requireWritable(command.firebaseUid(), command.projectId());
        StoredFile file = requireAvailable(command.fileId(), access.projectId());

        file.delete(clock.instant());
        StoredFile saved = files.save(file);
        eventPublisher.publishAll(saved.pullDomainEvents());
    }

    private FileView store(ProjectAccess access, FileName name, ContentSource source) {
        ModelFormat format = name.format();
        format.requireUploadable();
        ContentDigest digest = ContentDigest.of(source);
        FileId id = FileId.generate();
        if (format == ModelFormat.OBJ) {
            StoredFile file = StoredFile.upload(id, access.userId(), access.projectId(), name, digest.sizeBytes(), digest.checksum(), clock.instant());
            return persist(file, source, null);
        }
        try (ConvertedModel converted = converter.toObj(source, format)) {
            ContentDigest obj = ContentDigest.of(converted, ConvertedObj.MAX_SIZE_BYTES, "The converted OBJ");
            StoredFile file = StoredFile.upload(id,
                                                access.userId(),
                                                access.projectId(),
                                                name,
                                                digest.sizeBytes(),
                                                digest.checksum(),
                                                ConvertedObj.of(id, obj.sizeBytes(), obj.checksum()),
                                                clock.instant());
            return persist(file, source, converted);
        }
    }

    private FileView persist(StoredFile file, ContentSource original, ContentSource converted) {
        write(file.storageKey(), original, file.contentType(), file);
        if (converted != null) {
            write(file.convertedObj()
                      .storageKey(),
                  converted,
                  ModelFormat.OBJ.contentType(),
                  file);
        }
        files.save(file);
        return FileView.from(file);
    }

    private void write(StorageKey key, ContentSource source, String contentType, StoredFile file) {
        try (InputStream stream = source.open()) {
            storage.write(key, stream, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload for file " + file.id(), e);
        }
    }

    private StoredFile requireAvailable(UUID fileId, ProjectId projectId) {
        FileId id = new FileId(fileId);
        return files.findById(id)
                    .filter(file -> file.isAvailableIn(projectId))
                    .orElseThrow(() -> StoredFileNotFoundException.byId(id));
    }

    public record UploadFileCommand(String firebaseUid,
            UUID projectId,
            String originalFilename,
            ContentSource content) {
    }

    public record AddSampleCommand(String firebaseUid, UUID projectId, String sampleId) {
    }

    public record PrepareDownloadCommand(String firebaseUid, UUID projectId, UUID fileId) {
    }

    public record RenameFileCommand(String firebaseUid, UUID projectId, UUID fileId, String name) {
    }

    public record ListFilesCommand(String firebaseUid, UUID projectId, FileListCriteria criteria) {
    }

    public record DeleteFileCommand(String firebaseUid, UUID projectId, UUID fileId) {
    }
}
