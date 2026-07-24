package com.packing.backend.core.file;

import com.packing.backend.core.file.port.in.DeleteFileUseCase.DeleteFileCommand;
import com.packing.backend.core.file.port.in.DownloadFileUseCase.FileDownload;
import com.packing.backend.core.file.port.in.DownloadFileUseCase.PrepareDownloadCommand;
import com.packing.backend.core.file.port.in.ListFilesUseCase.ListFilesCommand;
import com.packing.backend.core.file.port.in.UploadFileUseCase.UploadFileCommand;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileOwnerLookup;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.file.StoredFileNotFoundException;
import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserNotFoundException;
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
    private static final UserId OWNER = UserId.generate();
    private static final byte[] BYTES = "solid cube".getBytes(StandardCharsets.UTF_8);
    /** SHA-256 of "solid cube". */
    private static final String BYTES_SHA256 =
            "d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971";

    @Mock
    private FileRepository files;
    @Mock
    private BinaryStorage storage;
    @Mock
    private FileOwnerLookup ownerLookup;
    @Mock
    private DomainEventPublisher eventPublisher;

    private FileApplicationService service;

    @BeforeEach
    void setUp() {
        service = new FileApplicationService(files, storage, ownerLookup, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void activeOwner() {
        when(ownerLookup.findActiveOwner(new FirebaseUid(UID))).thenReturn(Optional.of(OWNER));
    }

    private static ContentSource sourceOf(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    private StoredFile storedFile(FileId id, UserId owner) {
        return StoredFile.upload(id, owner, new FileName("cube.stl"), BYTES.length,
                Checksum.ofHex(BYTES_SHA256), NOW);
    }

    @Test
    void uploadWritesTheBlobBeforeTheRow() {
        activeOwner();
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(new UploadFileCommand(UID, "cube.stl", BYTES.length, sourceOf(BYTES)));

        InOrder order = inOrder(storage, files);
        order.verify(storage).write(any(), any(), anyLong(), anyString());
        order.verify(files).save(any());
    }

    @Test
    void uploadLeavesTheBlobBehindWhenTheRowCannotBeWritten() {
        activeOwner();
        when(files.save(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.upload(
                new UploadFileCommand(UID, "cube.stl", BYTES.length, sourceOf(BYTES))))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void uploadStoresTheReceivedByteCountNotTheDeclaredOne() {
        activeOwner();
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(
                new UploadFileCommand(UID, "cube.stl", 999_999L, sourceOf(BYTES)));

        assertThat(view.sizeBytes()).isEqualTo(BYTES.length);
    }

    @Test
    void uploadDigestsWhatWasActuallyReceived() {
        activeOwner();
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FileView view = service.upload(
                new UploadFileCommand(UID, "cube.stl", BYTES.length, sourceOf(BYTES)));

        assertThat(view.checksumSha256()).isEqualTo(BYTES_SHA256);
    }

    @Test
    void uploadOpensTheSourceTwiceAndSeesTheSameBytesBothTimes() {
        activeOwner();
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicInteger opens = new AtomicInteger();
        ContentSource counting = () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(BYTES);
        };

        service.upload(new UploadFileCommand(UID, "cube.stl", BYTES.length, counting));

        assertThat(opens).hasValue(2);
        ArgumentCaptor<InputStream> written = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).write(any(), written.capture(), eq((long) BYTES.length), eq("model/stl"));
        assertThat(written.getValue()).isNotNull();
    }

    @Test
    void uploadNamesTheBlobAfterTheGeneratedIdAndDerivesTheContentType() {
        activeOwner();
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<StorageKey> key = ArgumentCaptor.forClass(StorageKey.class);

        FileView view = service.upload(
                new UploadFileCommand(UID, "cube.stl", BYTES.length, sourceOf(BYTES)));

        verify(storage).write(key.capture(), any(), anyLong(), eq("model/stl"));
        assertThat(key.getValue()).isEqualTo(StorageKey.forFile(new FileId(view.id())));
    }

    @Test
    void uploadRejectsAnUnsupportedFormatBeforeTouchingStorage() {
        activeOwner();

        assertThatThrownBy(() -> service.upload(
                new UploadFileCommand(UID, "notes.txt", BYTES.length, sourceOf(BYTES))))
                .isInstanceOf(DomainRuleViolationException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
        verify(files, never()).save(any());
    }

    @Test
    void uploadRejectsAnEmptyFile() {
        activeOwner();

        assertThatThrownBy(() -> service.upload(
                new UploadFileCommand(UID, "cube.stl", 0L, sourceOf(new byte[0]))))
                .isInstanceOf(DomainRuleViolationException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void uploadRejectsACallerWithNoActiveProfile() {
        when(ownerLookup.findActiveOwner(new FirebaseUid(UID))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(
                new UploadFileCommand(UID, "cube.stl", BYTES.length, sourceOf(BYTES))))
                .isInstanceOf(UserNotFoundException.class);

        verify(storage, never()).write(any(), any(), anyLong(), anyString());
    }

    @Test
    void prepareDownloadReturnsTheTemporaryUrlForTheOwnersFile() {
        activeOwner();
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, OWNER);
        when(files.findById(id)).thenReturn(Optional.of(file));
        Instant expiry = NOW.plusSeconds(300);
        when(storage.temporaryReadUrl(file.storageKey(), "cube.stl", "model/stl"))
                .thenReturn(new BinaryStorage.TemporaryUrl(URI.create("https://blob/cube?sig=x"), expiry));

        FileDownload download = service.prepareDownload(
                new PrepareDownloadCommand(UID, id.value()));

        assertThat(download.url()).isEqualTo(URI.create("https://blob/cube?sig=x"));
        assertThat(download.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void prepareDownloadHidesAnotherUsersFileBehindTheSameNotFound() {
        activeOwner();
        FileId id = FileId.generate();
        when(files.findById(id)).thenReturn(Optional.of(storedFile(id, UserId.generate())));

        assertThatThrownBy(() -> service.prepareDownload(
                new PrepareDownloadCommand(UID, id.value())))
                .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void prepareDownloadRejectsATombstonedFile() {
        activeOwner();
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, OWNER);
        file.delete(NOW);
        when(files.findById(id)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.prepareDownload(
                new PrepareDownloadCommand(UID, id.value())))
                .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void prepareDownloadRejectsAnUnknownFile() {
        activeOwner();
        FileId id = FileId.generate();
        when(files.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareDownload(
                new PrepareDownloadCommand(UID, id.value())))
                .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void listFilesPagesWithTheOffsetDerivedFromThePageIndex() {
        activeOwner();
        FileId id = FileId.generate();
        when(files.findAvailableByOwner(OWNER, 40, 20)).thenReturn(List.of(storedFile(id, OWNER)));
        when(files.countAvailableByOwner(OWNER)).thenReturn(45L);

        Page<FileView> page = service.listFiles(new ListFilesCommand(UID, 2, 20));

        assertThat(page.content()).singleElement()
                .satisfies(view -> assertThat(view.id()).isEqualTo(id.value()));
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(45L);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void listFilesCommandRejectsAPageSizeOutsideTheAllowedRange() {
        assertThatThrownBy(() -> new ListFilesCommand(UID, 0, 0))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> new ListFilesCommand(UID, 0, ListFilesCommand.MAX_SIZE + 1))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> new ListFilesCommand(UID, -1, 20))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void deleteTombstonesTheRowAndPublishesTheEventWithoutTouchingStorage() {
        activeOwner();
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, OWNER);
        when(files.findById(id)).thenReturn(Optional.of(file));
        when(files.save(file)).thenReturn(file);

        service.deleteFile(new DeleteFileCommand(UID, id.value()));

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
    void deleteHidesAnotherUsersFileBehindTheSameNotFound() {
        activeOwner();
        FileId id = FileId.generate();
        when(files.findById(id)).thenReturn(Optional.of(storedFile(id, UserId.generate())));

        assertThatThrownBy(() -> service.deleteFile(new DeleteFileCommand(UID, id.value())))
                .isInstanceOf(StoredFileNotFoundException.class);

        verify(files, never()).save(any());
    }

    @Test
    void deleteIsANoOpBeyondTheFirstCall() {
        activeOwner();
        FileId id = FileId.generate();
        StoredFile file = storedFile(id, OWNER);
        file.delete(NOW);
        when(files.findById(id)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.deleteFile(new DeleteFileCommand(UID, id.value())))
                .isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void deleteRejectsACallerWithNoActiveProfile() {
        when(ownerLookup.findActiveOwner(new FirebaseUid(UID))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteFile(
                new DeleteFileCommand(UID, UUID.randomUUID())))
                .isInstanceOf(UserNotFoundException.class);
    }
}
