package com.packing.backend.infra.packing.temporal;

import io.grpc.Status;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalWorkerStarterTest {

    private final WorkerFactory         workers = mock(WorkerFactory.class);
    private final TemporalWorkerStarter starter = new TemporalWorkerStarter(workers);

    @Test
    void startsTheWorkersWhenTheApplicationIsReady() {
        starter.startWhenReady();

        verify(workers).start();
    }

    @Test
    void anUnreachableTemporalDoesNotFailTheApplicationAndIsRetried() {
        doThrow(Status.UNAVAILABLE.asRuntimeException()).doNothing()
                                                        .when(workers)
                                                        .start();

        assertThatNoException().isThrownBy(starter::startWhenReady);
        starter.retryUntilStarted();

        verify(workers, times(2)).start();
    }

    @Test
    void doesNothingOnceTheWorkersAreRunning() {
        when(workers.isStarted()).thenReturn(true);

        starter.retryUntilStarted();

        verify(workers, never()).start();
    }

    @Test
    void retriesOnAFixedDelayAfterTheFirstAttempt() throws NoSuchMethodException {
        assertThat(TemporalWorkerStarter.class.getDeclaredMethod("startWhenReady")
                                              .getAnnotation(EventListener.class)
                                              .value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(TemporalWorkerStarter.class.getDeclaredMethod("retryUntilStarted")
                                              .getAnnotation(Scheduled.class)
                                              .fixedDelayString()).isEqualTo("PT5S");
    }
}
