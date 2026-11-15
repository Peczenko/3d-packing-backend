package com.packing.backend.core.file;

import com.packing.backend.core.file.FileApplicationService.AddSampleCommand;
import com.packing.backend.core.file.FileApplicationService.DeleteFileCommand;
import com.packing.backend.core.file.FileApplicationService.ListFilesCommand;
import com.packing.backend.core.file.FileApplicationService.PrepareDownloadCommand;
import com.packing.backend.core.file.FileApplicationService.RenameFileCommand;
import com.packing.backend.core.file.FileApplicationService.UploadFileCommand;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileFinder;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.file.port.out.ModelConverter;
import com.packing.backend.core.file.port.out.SampleModelCatalog;
import com.packing.backend.core.project.ProjectAccessGuard;
import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.core.shared.TemporaryUrl;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.ConvertedObj;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.file.StoredFileNotFoundException;
import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileApplicationServiceTest {

    private static final Instant   NOW     = Instant.parse("2026-07-19T10:15:30Z");
    private static final String    UID     = "firebase-uid-1";
    private static final UserId    CALLER  = UserId.generate();
    private static final ProjectId PROJECT = ProjectId.generate();
    private static final byte[]    BYTES   = "solid cube".getBytes(StandardCharsets.UTF_8);
    // SHA-256 of "solid cube"
    private static final String BYTES_SHA256 = "d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971";
    private static final byte[] OBJ_BYTES    = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".getBytes(StandardCharsets.US_ASCII);
    private static final String OBJ_SHA256   = sha256(OBJ_BYTES);

    @Mock
    private FileRepository       files;
    @Mock
    private FileFinder           fileFinder;
    @Mock
    private BinaryStorage        storage;
    @Mock
    private SampleModelCatalog   samples;
    @Mock
    private ProjectAccessLookup  projectAccess;
    @Mock
    private DomainEventPublisher eventPublisher;

    private final FakeConverter converter = new FakeConverter();

    private FileApplicationService service;

    @BeforeEach
    void setUp() {
        service = new FileApplicationService(files,
                                             fileFinder,
                                             storage,
                                             converter,
                                             samples,
                                             new ProjectAccessGuard(projectAccess),
                                             eventPublisher,
                                             Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void access(ProjectPermission permission) {
        access(permission, ProjectStatus.ACTIVE);
    }

    private void access(ProjectPermission permission, ProjectStatus status) {
        when(projectAccess.findAccess(new FirebaseUid(UID), PROJECT))
                                                                     .thenReturn(Optional.of(new ProjectAccess(CALLER, PROJECT, status, permission)));
    }

    private void noAccess() {
        when(projectAccess.findAccess(new FirebaseUid(UID), PROJECT)).thenReturn(Optional.empty());
    }

    private static ContentSource sourceOf(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    private StoredFile storedFile(FileId id, ProjectId project) {
        return storedFile(id, project, CALLER);
    }

    private static StoredFile storedFile(FileId id, ProjectId project, UserId uploader) {
        return StoredFile.upload(id,
                                 uploader,
                                 project,
                                 new FileName("cube.stl"),
                                 BYTES.length,
                                 Checksum.ofHex(BYTES_SHA256),
                                 ConvertedObj.of(id, OBJ_BYTES.length, Checksum.ofHex(OBJ_SHA256)),
                                 NOW);
    }

    private static SampleModel sample(String id) {
        return new SampleModel(id,
                               "Cube",
                               "A 20 mm cube",
                               new FileName("cube.obj"),
                               BYTES.length,
                               Checksum.ofHex(BYTES_SHA256),
                               sourceOf(BYTES));
    }

    private AddSampleCommand addSample(String sampleId) {
        return new AddSampleCommand(UID, PROJECT.value(), sampleId);
    }

    private UploadFileCommand upload(String filename, byte[] bytes) {
        return new UploadFileCommand(UID, PROJECT.value(), filename, sourceOf(bytes));
    }

    @Test
    void uploadWritesTheBlobBeforeTheRow() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(upload("cube.obj", BYTES));

        InOrder order = inOrder(storage, files);
        order.verify(storage)
             .write(any(), any(), anyString());
        order.verify(files)
             .save(any());
    }

    @Test
    void uploadLeavesTheBlobBehindWhenTheRowCannotBeWritten() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.upload(upload("cube.obj", BYTES)))
                                                                           .isInstanceOf(IllegalStateException.class);

        verify(storage).write(any(), any(), anyString());
    }

    @Test
    void uploadDigestsWhatWasActuallyReceived() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(upload("cube.obj", BYTES));

        assertThat(view.checksumSha256()).isEqualTo(BYTES_SHA256);
    }

    @Test
    void uploadFilesTheUploadUnderTheProjectFromThePath() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(upload("cube.obj", BYTES));

        assertThat(view.projectId()).isEqualTo(PROJECT.value());
    }

    @Test
    void uploadOpensTheSourceTwiceAndSeesTheSameBytesBothTimes() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicInteger opens = new AtomicInteger();
        ContentSource counting = () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(BYTES);
        };

        service.upload(new UploadFileCommand(
                                             UID,
                                             PROJECT.value(),
                                             "cube.obj",
                                             counting));

        assertThat(opens).hasValue(2);
        ArgumentCaptor<InputStream> written = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).write(any(), written.capture(), eq("model/obj"));
        assertThat(written.getValue()).isNotNull();
    }

    @Test
    void uploadNamesTheBlobAfterTheGeneratedIdAndDerivesTheContentType() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<StorageKey> key = ArgumentCaptor.forClass(StorageKey.class);

        FileView view = service.upload(upload("cube.obj", BYTES));

        verify(storage).write(key.capture(), any(), eq("model/obj"));
        assertThat(key.getValue()).isEqualTo(StorageKey.forFile(new FileId(view.id())));
    }

    @Test
    void anObjUploadIsStoredAsItIsWithoutConversion() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(upload("cube.obj", BYTES));

        assertThat(converter.calls).isZero();
        assertThat(view.format()).isEqualTo(ModelFormat.OBJ);
        assertThat(view.packingFormat()).isEqualTo(ModelFormat.OBJ);
        verify(storage, times(1)).write(any(), any(), anyString());
    }

    @Test
    void anUploadInAnotherFormatStoresTheOriginalAndItsConvertedObj() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        List<StorageKey> keys = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<byte[]> written = new ArrayList<>();
        doAnswer(invocation -> {
            keys.add(invocation.getArgument(0));
            written.add(((InputStream) invocation.getArgument(1)).readAllBytes());
            types.add(invocation.getArgument(2));
            return null;
        }).when(storage)
          .write(any(), any(), anyString());

        FileView view = service.upload(upload("bracket.stl", BYTES));

        FileId id = new FileId(view.id());
        assertThat(keys).containsExactly(StorageKey.forFile(id), StorageKey.forConvertedObj(id));
        assertThat(types).containsExactly("model/stl", "model/obj");
        assertThat(written).containsExactly(BYTES, OBJ_BYTES);
        assertThat(converter.formats).containsExactly(ModelFormat.STL);
        assertThat(converter.closes).isEqualTo(1);
        assertThat(view.format()).isEqualTo(ModelFormat.STL);
        assertThat(view.packingFormat()).isEqualTo(ModelFormat.OBJ);
        assertThat(view.checksumSha256()).isEqualTo(BYTES_SHA256);

        ArgumentCaptor<StoredFile> saved = ArgumentCaptor.forClass(StoredFile.class);
        verify(files).save(saved.capture());
        assertThat(saved.getValue()
                        .convertedObj()).isEqualTo(ConvertedObj.of(id, OBJ_BYTES.length, Checksum.ofHex(OBJ_SHA256)));
    }

    @Test
    void aModelThatCannotBeConvertedStoresNothing() {
        access(ProjectPermission.WRITE);
        converter.failure = new ModelConversionException("The STL file could not be read");

        assertThatThrownBy(() -> service.upload(upload("bracket.stl", BYTES)))
                                                                              .isInstanceOf(ModelConversionException.class)
                                                                              .hasMessage("The STL file could not be read");

        verify(storage, never()).write(any(), any(), anyString());
        verify(files, never()).save(any());
    }

    @Test
    void theConvertedModelIsReleasedEvenWhenTheRowCannotBeWritten() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.upload(upload("bracket.stl", BYTES)))
                                                                              .isInstanceOf(IllegalStateException.class);

        assertThat(converter.closes).isEqualTo(1);
    }

    @Test
    void aConvertedObjOverItsLimitIsRefused() {
        access(ProjectPermission.WRITE);
        converter.content = () -> new FillerStream(ConvertedObj.MAX_SIZE_BYTES + 1);

        assertThatThrownBy(() -> service.upload(upload("bracket.3mf", BYTES)))
                                                                              .isInstanceOf(DomainRuleViolationException.class)
                                                                              .hasMessageContaining("converted OBJ must be at most");

        verify(storage, never()).write(any(), any(), anyString());
        assertThat(converter.closes).isEqualTo(1);
    }

    @Test
    void stepIsRefusedBeforeTheUploadIsRead() {
        access(ProjectPermission.WRITE);
        AtomicInteger opens = new AtomicInteger();
        ContentSource counting = () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(BYTES);
        };

        assertThatThrownBy(() -> service.upload(new UploadFileCommand(UID, PROJECT.value(), "housing.step", counting)))
                                                                                                                       .isInstanceOf(DomainRuleViolationException.class)
                                                                                                                       .hasMessageContaining("STL or 3MF");

        assertThat(opens).hasValue(0);
        assertThat(converter.calls).isZero();
        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void uploadRejectsAnUnsupportedFormatBeforeTouchingStorage() {
        access(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.upload(upload("notes.txt", BYTES)))
                                                                            .isInstanceOf(DomainRuleViolationException.class);

        verify(storage, never()).write(any(), any(), anyString());
        verify(files, never()).save(any());
    }

    @Test
    void uploadRejectsAnEmptyFile() {
        access(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.upload(upload("cube.obj", new byte[0])))
                                                                                 .isInstanceOf(DomainRuleViolationException.class);

        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void uploadRejectsAReadOnlyMember() {
        access(ProjectPermission.READ);

        assertThatThrownBy(() -> service.upload(upload("cube.stl", BYTES)))
                                                                           .isInstanceOf(PermissionDeniedException.class);

        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void uploadIsRefusedWhileTheProjectIsDisabled() {
        access(ProjectPermission.OWNER, ProjectStatus.DISABLED);

        assertThatThrownBy(() -> service.upload(upload("cube.stl", BYTES)))
                                                                           .isInstanceOf(ResourceConflictException.class);

        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void uploadRejectsACallerWithNoAccessToTheProject() {
        noAccess();

        assertThatThrownBy(() -> service.upload(upload("cube.stl", BYTES)))
                                                                           .isInstanceOf(ProjectNotFoundException.class);

        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void addSampleStoresACopyOfTheSampleAsAnOrdinaryFileInTheProject() {
        access(ProjectPermission.WRITE);
        when(samples.find("cube")).thenReturn(Optional.of(sample("cube")));
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<StorageKey> key = ArgumentCaptor.forClass(StorageKey.class);

        FileView view = service.addSample(addSample("cube"));

        verify(storage).write(key.capture(), any(), eq("model/obj"));
        assertThat(key.getValue()).isEqualTo(StorageKey.forFile(new FileId(view.id())));
        assertThat(view.projectId()).isEqualTo(PROJECT.value());
        assertThat(view.filename()).isEqualTo("cube.obj");
        assertThat(view.format()).isEqualTo(ModelFormat.OBJ);
        assertThat(view.checksumSha256()).isEqualTo(BYTES_SHA256);
    }

    @Test
    void addSampleRecordsTheCallerAsTheUploader() {
        access(ProjectPermission.WRITE);
        when(samples.find("cube")).thenReturn(Optional.of(sample("cube")));
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<StoredFile> saved = ArgumentCaptor.forClass(StoredFile.class);

        service.addSample(addSample("cube"));

        verify(files).save(saved.capture());
        assertThat(saved.getValue()
                        .ownerId()).isEqualTo(CALLER);
    }

    @Test
    void addingTheSameSampleTwiceGivesTwoIndependentFiles() {
        access(ProjectPermission.WRITE);
        when(samples.find("cube")).thenReturn(Optional.of(sample("cube")));
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView first = service.addSample(addSample("cube"));
        FileView second = service.addSample(addSample("cube"));

        assertThat(first.id()).isNotEqualTo(second.id());
        ArgumentCaptor<StorageKey> keys = ArgumentCaptor.forClass(StorageKey.class);
        verify(storage, times(2)).write(keys.capture(), any(), anyString());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void addSampleRejectsAnUnknownSampleBeforeTouchingStorage() {
        access(ProjectPermission.WRITE);
        when(samples.find("teapot")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addSample(addSample("teapot")))
                                                                        .isInstanceOf(SampleModelNotFoundException.class);

        verify(storage, never()).write(any(), any(), anyString());
        verify(files, never()).save(any());
    }

    @Test
    void addSampleRejectsAReadOnlyMember() {
        access(ProjectPermission.READ);

        assertThatThrownBy(() -> service.addSample(addSample("cube")))
                                                                      .isInstanceOf(PermissionDeniedException.class);

        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void addSampleIsRefusedWhileTheProjectIsDisabled() {
        access(ProjectPermission.OWNER, ProjectStatus.DISABLED);

        assertThatThrownBy(() -> service.addSample(addSample("cube")))
                                                                      .isInstanceOf(ResourceConflictException.class);

        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void addSampleReportsANonMemberAsAMissingProject() {
        noAccess();

        assertThatThrownBy(() -> service.addSample(addSample("cube")))
                                                                      .isInstanceOf(ProjectNotFoundException.class);

        verify(storage, never()).write(any(), any(), anyString());
    }

    @Test
    void sampleRejectsAnUnknownId() {
        when(samples.find("teapot")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sample("teapot")).isInstanceOf(SampleModelNotFoundException.class);
    }

    @Test
    void prepareDownloadReturnsTheTemporaryUrlForAReadableProject() {
        access(ProjectPermission.READ);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        when(files.findById(id)).thenReturn(Optional.of(file));
        Instant expiry = NOW.plusSeconds(300);
        when(storage.readUrl(file.storageKey(), "cube.stl", "model/stl"))
                                                                         .thenReturn(new TemporaryUrl(
                                                                                                      URI.create("https://blob/cube?sig=x"),
                                                                                                      expiry));

        TemporaryUrl download = service.prepareDownload(
                                                        new PrepareDownloadCommand(UID, PROJECT.value(), id.value()));

        assertThat(download.url()).isEqualTo(URI.create("https://blob/cube?sig=x"));
        assertThat(download.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void prepareDownloadStillWorksWhileTheProjectIsDisabled() {
        access(ProjectPermission.READ, ProjectStatus.DISABLED);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        when(files.findById(id)).thenReturn(Optional.of(file));
        when(storage.readUrl(any(), anyString(), anyString()))
                                                              .thenReturn(new TemporaryUrl(
                                                                                           URI.create("https://blob/cube?sig=x"),
                                                                                           NOW.plusSeconds(300)));

        assertThat(service.prepareDownload(
                                           new PrepareDownloadCommand(UID, PROJECT.value(), id.value()))).isNotNull();
    }

    @Test
    void prepareDownloadHidesAFileFromAnotherProjectBehindTheSameNotFound() {
        access(ProjectPermission.READ);
        FileId id = FileId.generate();
        when(files.findById(id)).thenReturn(Optional.of(storedFile(id, ProjectId.generate())));

        assertThatThrownBy(() -> service.prepareDownload(
                                                         new PrepareDownloadCommand(UID, PROJECT.value(), id.value())))
                                                                                                                       .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void prepareDownloadRejectsATombstonedFile() {
        access(ProjectPermission.READ);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        file.delete(NOW);
        when(files.findById(id)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.prepareDownload(
                                                         new PrepareDownloadCommand(UID, PROJECT.value(), id.value())))
                                                                                                                       .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void prepareDownloadRejectsAnUnknownFile() {
        access(ProjectPermission.READ);
        FileId id = FileId.generate();
        when(files.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareDownload(
                                                         new PrepareDownloadCommand(UID, PROJECT.value(), id.value())))
                                                                                                                       .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void prepareDownloadReportsANonMemberAsAMissingProject() {
        noAccess();

        assertThatThrownBy(() -> service.prepareDownload(
                                                         new PrepareDownloadCommand(UID, PROJECT.value(), UUID.randomUUID())))
                                                                                                                              .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void listFilesPassesThroughThePageFromTheFinder() {
        ProjectId authorizedProject = ProjectId.generate();
        when(projectAccess.findAccess(new FirebaseUid(UID), PROJECT))
                                                                     .thenReturn(Optional.of(new ProjectAccess(
                                                                                                               CALLER,
                                                                                                               authorizedProject,
                                                                                                               ProjectStatus.ACTIVE,
                                                                                                               ProjectPermission.READ)));
        FileListCriteria criteria = new FileListCriteria(
                                                         new PageRequest(2, 20),
                                                         "bracket",
                                                         Set.of(ModelFormat.STL, ModelFormat.OBJ),
                                                         new InstantRange(NOW.minusSeconds(60), NOW.plusSeconds(60)),
                                                         FileListCriteria.SortField.SIZE_BYTES,
                                                         SortDirection.DESC);
        FileId id = FileId.generate();
        Page<FileView> finderPage = new Page<>(
                                               List.of(FileView.from(storedFile(id, PROJECT))),
                                               2,
                                               20,
                                               45L);
        when(fileFinder.listAvailableInProject(authorizedProject, criteria)).thenReturn(finderPage);

        Page<FileView> page = service.listFiles(
                                                new ListFilesCommand(UID, PROJECT.value(), criteria));

        assertThat(page).isSameAs(finderPage);
        verify(fileFinder).listAvailableInProject(authorizedProject, criteria);
    }

    @Test
    void listFilesRejectsACallerWithNoAccessToTheProjectBeforeConsultingTheFinder() {
        noAccess();

        assertThatThrownBy(() -> service.listFiles(
                                                   new ListFilesCommand(UID,
                                                                        PROJECT.value(),
                                                                        new FileListCriteria(new PageRequest(0, 20),
                                                                                             null,
                                                                                             Set.of(),
                                                                                             new InstantRange(null, null),
                                                                                             FileListCriteria.SortField.CREATED_AT,
                                                                                             SortDirection.DESC))))
                                                                                                                   .isInstanceOf(ProjectNotFoundException.class);

        verify(fileFinder, never()).listAvailableInProject(any(), any());
    }

    @Test
    void renameChangesTheNameWithoutTouchingStorage() {
        access(ProjectPermission.WRITE);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        when(files.findById(id)).thenReturn(Optional.of(file));
        when(files.save(file)).thenReturn(file);

        FileView view = service.renameFile(
                                           new RenameFileCommand(UID, PROJECT.value(), id.value(), "chassis.stl"));

        assertThat(view.filename()).isEqualTo("chassis.stl");
        verify(storage, never()).write(any(), any(), anyString());
        verify(storage, never()).delete(any());
    }

    @Test
    void renameDoesNotCareWhoUploadedTheFile() {
        access(ProjectPermission.WRITE);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT, UserId.generate());
        when(files.findById(id)).thenReturn(Optional.of(file));
        when(files.save(file)).thenReturn(file);

        assertThat(service.renameFile(
                                      new RenameFileCommand(UID, PROJECT.value(), id.value(), "chassis.stl"))
                          .filename()).isEqualTo("chassis.stl");
    }

    @Test
    void renameRejectsAReadOnlyMember() {
        access(ProjectPermission.READ);

        assertThatThrownBy(() -> service.renameFile(new RenameFileCommand(
                                                                          UID,
                                                                          PROJECT.value(),
                                                                          UUID.randomUUID(),
                                                                          "chassis.stl")))
                                                                                          .isInstanceOf(PermissionDeniedException.class);

        verify(files, never()).save(any());
    }

    @Test
    void renameIsRefusedWhileTheProjectIsDisabled() {
        access(ProjectPermission.OWNER, ProjectStatus.DISABLED);

        assertThatThrownBy(() -> service.renameFile(new RenameFileCommand(
                                                                          UID,
                                                                          PROJECT.value(),
                                                                          UUID.randomUUID(),
                                                                          "chassis.stl")))
                                                                                          .isInstanceOf(ResourceConflictException.class);

        verify(files, never()).save(any());
    }

    @Test
    void deleteTombstonesTheRowAndPublishesTheEventWithoutTouchingStorage() {
        access(ProjectPermission.WRITE);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        when(files.findById(id)).thenReturn(Optional.of(file));
        when(files.save(file)).thenReturn(file);

        service.deleteFile(new DeleteFileCommand(UID, PROJECT.value(), id.value()));

        assertThat(file.isDeleted()).isTrue();
        verify(storage, never()).delete(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<? extends DomainEvent>> events = ArgumentCaptor.forClass(Collection.class);
        verify(eventPublisher).publishAll(events.capture());
        assertThat(events.getValue()).singleElement()
                                     .isInstanceOfSatisfying(FileDeleted.class,
                                                             event -> assertThat(event.storageKeys()).containsExactly(file.storageKey(),
                                                                                                                      StorageKey.forConvertedObj(id)));
    }

    @Test
    void deleteHidesAFileFromAnotherProjectBehindTheSameNotFound() {
        access(ProjectPermission.WRITE);
        FileId id = FileId.generate();
        when(files.findById(id)).thenReturn(Optional.of(storedFile(id, ProjectId.generate())));

        assertThatThrownBy(() -> service.deleteFile(
                                                    new DeleteFileCommand(UID, PROJECT.value(), id.value())))
                                                                                                             .isInstanceOf(StoredFileNotFoundException.class);

        verify(files, never()).save(any());
    }

    @Test
    void deleteIsANoOpBeyondTheFirstCall() {
        access(ProjectPermission.WRITE);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        file.delete(NOW);
        when(files.findById(id)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.deleteFile(
                                                    new DeleteFileCommand(UID, PROJECT.value(), id.value())))
                                                                                                             .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void deleteRejectsAReadOnlyMember() {
        access(ProjectPermission.READ);

        assertThatThrownBy(() -> service.deleteFile(
                                                    new DeleteFileCommand(UID, PROJECT.value(), UUID.randomUUID())))
                                                                                                                    .isInstanceOf(PermissionDeniedException.class);

        verify(files, never()).save(any());
    }

    @Test
    void deleteRejectsACallerWithNoAccessToTheProject() {
        noAccess();

        assertThatThrownBy(() -> service.deleteFile(
                                                    new DeleteFileCommand(UID, PROJECT.value(), UUID.randomUUID())))
                                                                                                                    .isInstanceOf(ProjectNotFoundException.class);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                                    .digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FillerStream extends InputStream {

        private long remaining;

        private FillerStream(long size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            return remaining-- > 0 ? 'v' : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int count = (int) Math.min(length, remaining);
            Arrays.fill(buffer, offset, offset + count, (byte) 'v');
            remaining -= count;
            return count;
        }
    }

    private static final class FakeConverter implements ModelConverter {

        private final List<ModelFormat> formats = new ArrayList<>();
        private RuntimeException        failure;
        private ContentSource           content = () -> new ByteArrayInputStream(OBJ_BYTES);
        private int                     calls;
        private int                     closes;

        @Override
        public ConvertedModel toObj(ContentSource source, ModelFormat format) {
            calls++;
            formats.add(format);
            if (failure != null) {
                throw failure;
            }
            ContentSource converted = content;
            return new ConvertedModel() {

                @Override
                public InputStream open() throws IOException {
                    return converted.open();
                }

                @Override
                public void close() {
                    closes++;
                }
            };
        }
    }
}
