package com.packing.backend.infra.packing.messaging;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.domain.packing.PackingJobId;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackingDispatchReconcilerTest {

    @Test
    void reconcilesExactlyOneHundredUndispatchedJobsAtStartup() throws NoSuchMethodException {
        PackingJobFinder finder = Mockito.mock(PackingJobFinder.class);
        PackingJobDispatchService dispatcher = Mockito.mock(PackingJobDispatchService.class);
        PackingDispatchReconciler reconciler = new PackingDispatchReconciler(finder, dispatcher);
        PackingJobId jobId = PackingJobId.generate();
        when(finder.findUndispatched(100)).thenReturn(List.of(jobId));

        reconciler.reconcileOnStartup();

        Method method = PackingDispatchReconciler.class.getDeclaredMethod("reconcileOnStartup");
        EventListener annotation = method.getAnnotation(EventListener.class);
        assertThat(annotation.value()).containsExactly(ApplicationReadyEvent.class);
        verify(finder).findUndispatched(100);
        verify(dispatcher).dispatch(jobId);
    }

    @Test
    void reconcilesExactlyOneHundredUndispatchedJobsOnTheSchedule() throws NoSuchMethodException {
        PackingJobFinder finder = Mockito.mock(PackingJobFinder.class);
        PackingJobDispatchService dispatcher = Mockito.mock(PackingJobDispatchService.class);
        PackingDispatchReconciler reconciler = new PackingDispatchReconciler(finder, dispatcher);
        when(finder.findUndispatched(100)).thenReturn(List.of());

        reconciler.reconcilePeriodically();

        Method method = PackingDispatchReconciler.class.getDeclaredMethod("reconcilePeriodically");
        Scheduled annotation = method.getAnnotation(Scheduled.class);
        assertThat(annotation.fixedDelayString()).isEqualTo("${packing.messaging.reconcile-delay:PT30S}");
        verify(finder).findUndispatched(100);
    }

    @Test
    void continuesDispatchingTheBatchAfterAnIndividualFailure() {
        PackingJobFinder finder = Mockito.mock(PackingJobFinder.class);
        PackingJobDispatchService dispatcher = Mockito.mock(PackingJobDispatchService.class);
        PackingDispatchReconciler reconciler = new PackingDispatchReconciler(finder, dispatcher);
        PackingJobId first = PackingJobId.generate();
        PackingJobId second = PackingJobId.generate();
        when(finder.findUndispatched(100)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("queue unavailable")).when(dispatcher).dispatch(first);

        reconciler.reconcilePeriodically();

        InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).dispatch(first);
        order.verify(dispatcher).dispatch(second);
    }
}
