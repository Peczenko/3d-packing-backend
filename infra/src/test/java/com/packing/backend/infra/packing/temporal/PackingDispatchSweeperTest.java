package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.domain.packing.PackingJobId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingDispatchSweeperTest {

    @Mock
    private PackingJobFinder finder;

    @Mock
    private PackingJobDispatchService dispatcher;

    @InjectMocks
    private PackingDispatchSweeper sweeper;

    @Test
    void dispatchesEveryUndispatchedJobEvenWhenOneFails() {
        PackingJobId first = PackingJobId.generate();
        PackingJobId second = PackingJobId.generate();
        when(finder.findUndispatched(PackingDispatchSweeper.BATCH_SIZE)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("temporal unavailable")).when(dispatcher)
                                                                  .dispatch(first);

        sweeper.sweepPeriodically();

        verify(dispatcher).dispatch(first);
        verify(dispatcher).dispatch(second);
    }

    @Test
    void sweepsOnStartupAndOnASchedule() throws NoSuchMethodException {
        when(finder.findUndispatched(PackingDispatchSweeper.BATCH_SIZE)).thenReturn(List.of());

        sweeper.sweepOnStartup();

        verify(finder).findUndispatched(PackingDispatchSweeper.BATCH_SIZE);
        assertThat(PackingDispatchSweeper.class.getDeclaredMethod("sweepOnStartup")
                                               .getAnnotation(EventListener.class)
                                               .value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(PackingDispatchSweeper.class.getDeclaredMethod("sweepPeriodically")
                                               .getAnnotation(Scheduled.class)
                                               .fixedDelayString()).isEqualTo("${packing.dispatch.sweep-delay:PT30S}");
    }
}
