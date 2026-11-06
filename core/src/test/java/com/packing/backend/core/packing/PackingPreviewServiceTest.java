package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.packing.port.out.PackingPreviewRenderer;
import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.PackingPreview;
import com.packing.backend.domain.packing.PreviewModel;
import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.domain.packing.PreviewStatus;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingPreviewServiceTest {

    private static final Instant            NOW    = Instant.parse("2026-11-06T10:15:30Z");
    private static final PackingJobId       JOB_ID = PackingJobId.generate();
    private static final List<PreviewModel> MODELS = List.of(new PreviewModel(PreviewQuality.LOW, 10, 100),
                                                             new PreviewModel(PreviewQuality.HIGH, 40, 400));

    @Mock
    private PackingJobRepository   jobs;
    @Mock
    private PackingPreviewRenderer renderer;

    private PackingPreviewService service;

    @BeforeEach
    void setUp() {
        service = new PackingPreviewService(jobs, renderer);
    }

    @Test
    void buildRendersThePendingPreviewFromTheJobItemsAndStoresItsModels() {
        PackingJob job = job(PackingPreview.PENDING);
        when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job), Optional.of(job(PackingPreview.PENDING)));
        when(renderer.render(JOB_ID,
                             job.input()
                                .items())).thenReturn(MODELS);

        service.build(JOB_ID);

        ArgumentCaptor<PackingJob> saved = ArgumentCaptor.forClass(PackingJob.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue()
                        .preview()).isEqualTo(PackingPreview.ready(MODELS));
    }

    @Test
    void buildSkipsAPreviewThatIsAlreadySettled() {
        when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job(PackingPreview.ready(MODELS))));

        service.build(JOB_ID);

        verifyNoInteractions(renderer);
        verify(jobs, never()).save(any());
    }

    @Test
    void buildDoesNotOverwriteAPreviewSettledWhileItWasRendering() {
        when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job(PackingPreview.PENDING)), Optional.of(job(PackingPreview.FAILED)));
        when(renderer.render(any(), any())).thenReturn(MODELS);

        service.build(JOB_ID);

        verify(jobs, never()).save(any());
    }

    @Test
    void aRenderingFailurePropagatesSoTheActivityCanRetryIt() {
        when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job(PackingPreview.PENDING)));
        when(renderer.render(any(), any())).thenThrow(new IllegalStateException("gltfpack exited with 3"));

        assertThatThrownBy(() -> service.build(JOB_ID)).isInstanceOf(IllegalStateException.class);
        verify(jobs, never()).save(any());
    }

    @Test
    void failMarksAPendingPreviewFailedOnce() {
        PackingJob pending = job(PackingPreview.PENDING);
        when(jobs.findById(JOB_ID)).thenReturn(Optional.of(pending), Optional.of(job(PackingPreview.FAILED)));

        service.fail(JOB_ID);
        service.fail(JOB_ID);

        verify(jobs).save(pending);
        assertThat(pending.preview()
                          .status()).isEqualTo(PreviewStatus.FAILED);
    }

    @Test
    void anUnknownJobIsReportedAsNotFound() {
        when(jobs.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.build(JOB_ID)).isInstanceOf(PackingJobNotFoundException.class);
        assertThatThrownBy(() -> service.fail(JOB_ID)).isInstanceOf(PackingJobNotFoundException.class);
    }

    private static PackingJob job(PackingPreview preview) {
        return PackingJob.rehydrate(JOB_ID,
                                    ProjectId.generate(),
                                    UserId.generate(),
                                    PackingInputs.of(PackingInputs.item("bunny.obj", 3), PackingInputs.item("cube.obj", 1)),
                                    60,
                                    PackingJobStatus.SUCCEEDED,
                                    "packer 0.0.3",
                                    "a".repeat(64),
                                    NOW,
                                    NOW,
                                    NOW,
                                    "packed.obj",
                                    "model/obj",
                                    "b".repeat(64),
                                    12L,
                                    null,
                                    preview,
                                    1,
                                    NOW);
    }
}
