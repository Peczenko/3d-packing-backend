package com.packing.backend.core.file;

import com.packing.backend.core.file.FileApplicationService.DeleteFileCommand;
import com.packing.backend.core.file.FileApplicationService.ListFilesCommand;
import com.packing.backend.core.file.FileApplicationService.PrepareDownloadCommand;
import com.packing.backend.core.file.FileApplicationService.RenameFileCommand;
import com.packing.backend.core.file.FileApplicationService.UploadFileCommand;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileFinder;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
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
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
    private static final String UID = "firebase-uid-1";
    private static final UserId CALLER = UserId.generate();
    private static final ProjectId PROJECT = ProjectId.generate();
    private static final byte[] BYTES = "solid cube".getBytes(StandardCharsets.UTF_8);
    /** SHA-256 of "solid cube". */
    private static final String BYTES_SHA256 =
            "d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971";

    @Mock
    private FileRepository files;
    @Mock
    private FileFinder fileFinder;
    @Mock
    private BinaryStorage storage;
    @Mock
    private ProjectAccessLookup projectAccess;
    @Mock
    private DomainEventPublisher eventPublisher;

    private FileApplicationService service;

    @BeforeEach
    void setUp() {
        service = new FileApplicationService(files, fileFinder, storage, projectAccess,
                eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
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
        return StoredFile.upload(id, CALLER, project, new FileName("cube.stl"), BYTES.length,
                Checksum.ofHex(BYTES_SHA256), NOW);
    }

    private UploadFileCommand upload(String filename, byte[] bytes) {
        return new UploadFileCommand(UID, PROJECT.value(), filename, bytes.length, sourceOf(bytes));
    }

    @Test
    void uploadWritesTheBlobBeforeTheRow() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(upload("cube.stl", BYTES));

        InOrder order = inOrder(storage, files);
        order.verify(storage).write(any(), any(), anyLong(), anyString());
        order.verify(files).save(any());
    }

    @Test
    void uploadLeavesTheBlobBehindWhenTheRowCannotBeWritten() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.upload(upload("cube.stl", BYTES)))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void uploadStoresTheReceivedByteCountNotTheDeclaredOne() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(new UploadFileCommand(
                UID, PROJECT.value(), "cube.stl", 999_999L, sourceOf(BYTES)));

        assertThat(view.sizeBytes()).isEqualTo(BYTES.length);
    }

    @Test
    void uploadDigestsWhatWasActuallyReceived() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(upload("cube.stl", BYTES));

        assertThat(view.checksumSha256()).isEqualTo(BYTES_SHA256);
    }

    @Test
    void uploadFilesTheUploadUnderTheProjectFromThePath() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(upload("cube.stl", BYTES));

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
                UID, PROJECT.value(), "cube.stl", BYTES.length, counting));

        assertThat(opens).hasValue(2);
        ArgumentCaptor<InputStream> written = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).write(any(), written.capture(), eq((long) BYTES.length), eq("model/stl"));
        assertThat(written.getValue()).isNotNull();
    }

    @Test
    void uploadNamesTheBlobAfterTheGeneratedIdAndDerivesTheContentType() {
        access(ProjectPermission.WRITE);
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<StorageKey> key = ArgumentCaptor.forClass(StorageKey.class);

        FileView view = service.upload(upload("cube.stl", BYTES));

        verify(storage).write(key.capture(), any(), anyLong(), eq("model/stl"));
        assertThat(key.getValue()).isEqualTo(StorageKey.forFile(new FileId(view.id())));
    }

    @Test
    void uploadRejectsAnUnsupportedFormatBeforeTouchingStorage() {
        access(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.upload(upload("notes.txt", BYTES)))
                .isInstanceOf(DomainRuleViolationException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
        verify(files, never()).save(any());
    }

    @Test
    void uploadRejectsAnEmptyFile() {
        access(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.upload(upload("cube.stl", new byte[0])))
                .isInstanceOf(DomainRuleViolationException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void uploadRejectsAReadOnlyMember() {
        access(ProjectPermission.READ);

        assertThatThrownBy(() -> service.upload(upload("cube.stl", BYTES)))
                .isInstanceOf(PermissionDeniedException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void uploadIsRefusedWhileTheProjectIsDisabled() {
        access(ProjectPermission.OWNER, ProjectStatus.DISABLED);

        assertThatThrownBy(() -> service.upload(upload("cube.stl", BYTES)))
                .isInstanceOf(ResourceConflictException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void uploadRejectsACallerWithNoAccessToTheProject() {
        noAccess();

        assertThatThrownBy(() -> service.upload(upload("cube.stl", BYTES)))
                .isInstanceOf(ProjectNotFoundException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void prepareDownloadReturnsTheTemporaryUrlForAReadableProject() {
        access(ProjectPermission.READ);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        when(files.findById(id)).thenReturn(Optional.of(file));
        Instant expiry = NOW.plusSeconds(300);
        when(storage.temporaryReadUrl(file.storageKey(), "cube.stl", "model/stl"))
                .thenReturn(new BinaryStorage.TemporaryUrl(
                        URI.create("https://blob/cube?sig=x"), expiry));

        BinaryStorage.TemporaryUrl download = service.prepareDownload(
                new PrepareDownloadCommand(UID, PROJECT.value(), id.value()));

        assertThat(download.url()).isEqualTo(URI.create("https://blob/cube?sig=x"));
        assertThat(download.expiresAt()).isEqualTo(expiry);
    }

    /** Downloads keep working on an archive: DISABLED blocks writes, not reads. */
    @Test
    void prepareDownloadStillWorksWhileTheProjectIsDisabled() {
        access(ProjectPermission.READ, ProjectStatus.DISABLED);
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, PROJECT);
        when(files.findById(id)).thenReturn(Optional.of(file));
        when(storage.temporaryReadUrl(any(), anyString(), anyString()))
                .thenReturn(new BinaryStorage.TemporaryUrl(
                        URI.create("https://blob/cube?sig=x"), NOW.plusSeconds(300)));

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
        access(ProjectPermission.READ);
        FileId id = FileId.generate();
        Page<FileView> finderPage = new Page<>(
                List.of(FileView.from(storedFile(id, PROJECT))), 2, 20, 45L);
        when(fileFinder.listAvailableInProject(PROJECT, new PageRequest(2, 20)))
                .thenReturn(finderPage);

        Page<FileView> page = service.listFiles(
                new ListFilesCommand(UID, PROJECT.value(), new PageRequest(2, 20)));

        assertThat(page).isSameAs(finderPage);
    }

    @Test
    void listFilesRejectsACallerWithNoAccessToTheProjectBeforeConsultingTheFinder() {
        noAccess();

        assertThatThrownBy(() -> service.listFiles(
                new ListFilesCommand(UID, PROJECT.value(), new PageRequest(0, 20))))
                .isInstanceOf(ProjectNotFoundException.class);

        verify(fileFinder, never()).listAvailableInProject(any(), any());
    }

    @Test
    void listFilesCommandRejectsAPageSizeOutsideTheAllowedRange() {
        UUID project = PROJECT.value();

        assertThatThrownBy(() -> new ListFilesCommand(UID, project, new PageRequest(0, 0)))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> new ListFilesCommand(UID, project,
                new PageRequest(0, PageRequest.MAX_SIZE + 1)))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> new ListFilesCommand(UID, project, new PageRequest(-1, 20)))
                .isInstanceOf(DomainRuleViolationException.class);
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
        verify(storage, never()).write(any(), any(), anyLong(), anyString());
        verify(storage, never()).delete(any());
    }

    /** A WRITE member may rename anything in the project, including someone else's upload. */
    @Test
    void renameDoesNotCareWhoUploadedTheFile() {
        access(ProjectPermission.WRITE);
        FileId id = FileId.generate();
        StoredFile file = StoredFile.upload(id, UserId.generate(), PROJECT,
                new FileName("cube.stl"), BYTES.length, Checksum.ofHex(BYTES_SHA256), NOW);
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
                UID, PROJECT.value(), UUID.randomUUID(), "chassis.stl")))
                .isInstanceOf(PermissionDeniedException.class);

        verify(files, never()).save(any());
    }

    @Test
    void renameIsRefusedWhileTheProjectIsDisabled() {
        access(ProjectPermission.OWNER, ProjectStatus.DISABLED);

        assertThatThrownBy(() -> service.renameFile(new RenameFileCommand(
                UID, PROJECT.value(), UUID.randomUUID(), "chassis.stl")))
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
        ArgumentCaptor<Collection<? extends DomainEvent>> events =
                ArgumentCaptor.forClass(Collection.class);
        verify(eventPublisher).publishAll(events.capture());
        assertThat(events.getValue()).singleElement()
                .isInstanceOfSatisfying(FileDeleted.class,
                        event -> assertThat(event.storageKey()).isEqualTo(file.storageKey()));
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
}
