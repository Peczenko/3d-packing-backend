package com.packing.backend.core.packing;

import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingJobDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");

    @Mock
    private PackingJobRepository    jobs;
    @Mock
    private PackingJobArtifactStore artifacts;
    @Mock
    private PackingDispatchSender   dispatch;

    private PackingJobDispatchService service;

    @BeforeEach
    void setUp() {
        service = new PackingJobDispatchService(jobs,
                                                artifacts,
                                                dispatch,
                                                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void writesTheRequestThenSendsThenPersistsTheDispatchedMarker() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.dispatch(job.id());

        InOrder order = inOrder(artifacts, dispatch, jobs);
        order.verify(artifacts)
             .writeRequestIfAbsent(job.id(),
                                   PackingRequestEnvelope.versionOne(60, "{\"testField\":true}"));
        order.verify(dispatch)
             .send(PackingDispatchMessage.versionOne(job.id()));
        order.verify(jobs)
             .save(job);
        assertThat(job.dispatchedAt()).isEqualTo(NOW);
    }

    @Test
    void skipsAJobAlreadyMarkedAsDispatched() {
        PackingJob job = dispatchedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.dispatch(job.id());

        verify(artifacts, never()).writeRequestIfAbsent(org.mockito.ArgumentMatchers.any(),
                                                        org.mockito.ArgumentMatchers.any());
        verify(dispatch, never()).send(org.mockito.ArgumentMatchers.any());
        verify(jobs, never()).save(job);
    }

    @Test
    void skipsAJobThatIsNoLongerQueued() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.dispatch(job.id());

        verify(artifacts, never()).writeRequestIfAbsent(org.mockito.ArgumentMatchers.any(),
                                                        org.mockito.ArgumentMatchers.any());
        verify(dispatch, never()).send(org.mockito.ArgumentMatchers.any());
        verify(jobs, never()).save(job);
    }

    @Test
    void leavesTheJobUndispatchedAndUnsavedWhenQueueingFails() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));
        doThrow(new IllegalStateException("queue unavailable")).when(dispatch)
                                                               .send(PackingDispatchMessage.versionOne(job.id()));

        assertThatThrownBy(() -> service.dispatch(job.id()))
                                                            .isInstanceOf(IllegalStateException.class)
                                                            .hasMessage("queue unavailable");

        assertThat(job.dispatchedAt()).isNull();
        verify(jobs, never()).save(job);
    }

    @Test
    void retriesAfterACrashWithTheSameMessageAndRequestEnvelope() {
        PackingJob initialAttempt = queuedJob();
        PackingJob retry = queuedJob(initialAttempt.id());
        when(jobs.findById(initialAttempt.id())).thenReturn(Optional.of(initialAttempt), Optional.of(retry));
        doAnswer(invocation -> {
            throw new IllegalStateException("database unavailable");
        }).when(jobs)
          .save(same(initialAttempt));

        assertThatThrownBy(() -> service.dispatch(initialAttempt.id()))
                                                                       .isInstanceOf(IllegalStateException.class)
                                                                       .hasMessage("database unavailable");

        service.dispatch(initialAttempt.id());

        ArgumentCaptor<PackingRequestEnvelope> envelopes = ArgumentCaptor.forClass(
                                                                                   PackingRequestEnvelope.class);
        ArgumentCaptor<PackingDispatchMessage> messages = ArgumentCaptor.forClass(
                                                                                  PackingDispatchMessage.class);
        verify(artifacts, org.mockito.Mockito.times(2)).writeRequestIfAbsent(
                                                                             org.mockito.ArgumentMatchers.eq(initialAttempt.id()),
                                                                             envelopes.capture());
        verify(dispatch, org.mockito.Mockito.times(2)).send(messages.capture());
        assertThat(envelopes.getAllValues()).containsExactlyElementsOf(
                                                                       java.util.List.of(envelopes.getAllValues()
                                                                                                  .getFirst(),
                                                                                         envelopes.getAllValues()
                                                                                                  .getFirst()));
        assertThat(messages.getAllValues()).containsOnly(PackingDispatchMessage.versionOne(initialAttempt.id()));
        verify(jobs).save(same(retry));
    }

    @Test
    void suspendsAnyCallerTransactionWhileDispatching() {
        Transactional transactional = PackingJobDispatchService.class.getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    private static PackingJob queuedJob() {
        return queuedJob(PackingJobId.generate());
    }

    private static PackingJob queuedJob(PackingJobId id) {
        return PackingJob.queue(id,
                                ProjectId.generate(),
                                UserId.generate(),
                                "{\"testField\":true}",
                                60,
                                NOW.minusSeconds(30));
    }

    private static PackingJob dispatchedJob() {
        PackingJob job = queuedJob();
        job.markDispatched(NOW.minusSeconds(1));
        return job;
    }

    private static PackingJob runningJob() {
        PackingJob job = queuedJob();
        job.markRunning("packer 0.1.0", "a".repeat(64), NOW.minusSeconds(1));
        assertThat(job.status()).isEqualTo(PackingJobStatus.RUNNING);
        return job;
    }
}
