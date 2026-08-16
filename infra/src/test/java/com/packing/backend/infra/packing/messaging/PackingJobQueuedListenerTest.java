package com.packing.backend.infra.packing.messaging;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.event.PackingJobQueued;
import com.packing.backend.domain.project.ProjectId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class PackingJobQueuedListenerTest {

    @Test
    void runsAfterCommitAndDispatchesTheQueuedJob() throws NoSuchMethodException {
        PackingJobDispatchService dispatcher = Mockito.mock(PackingJobDispatchService.class);
        PackingJobQueuedListener listener = new PackingJobQueuedListener(dispatcher);
        PackingJobQueued event = event();

        listener.on(event);

        Method method = PackingJobQueuedListener.class.getDeclaredMethod("on", PackingJobQueued.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        verify(dispatcher).dispatch(event.jobId());
    }

    @Test
    void logsAndSwallowsDispatchFailuresAfterTheCommit() {
        PackingJobDispatchService dispatcher = Mockito.mock(PackingJobDispatchService.class);
        PackingJobQueuedListener listener = new PackingJobQueuedListener(dispatcher);
        PackingJobQueued event = event();
        doThrow(new IllegalStateException("queue unavailable")).when(dispatcher)
                                                               .dispatch(event.jobId());

        listener.on(event);

        verify(dispatcher).dispatch(event.jobId());
    }

    private static PackingJobQueued event() {
        return new PackingJobQueued(PackingJobId.generate(), ProjectId.generate(), Instant.now());
    }
}
