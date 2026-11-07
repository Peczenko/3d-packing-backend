package com.packing.backend.api.file;

import com.packing.backend.core.file.FileApplicationService;
import com.packing.backend.core.file.SampleModel;
import com.packing.backend.core.file.SampleModelNotFoundException;
import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SampleModelController.class)
@AutoConfigureMockMvc(addFilters = false)
class SampleModelControllerTest {

    private static final byte[] BYTES    = "o cube\nv 0 0 0\n".getBytes(StandardCharsets.UTF_8);
    private static final String CHECKSUM = "c".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileApplicationService files;

    @MockitoBean
    private ErrorAlerter alerter;

    private static SampleModel cube(ContentSource content) {
        return new SampleModel("cube",
                               "Cube",
                               "A 20 mm cube.",
                               new FileName("cube.obj"),
                               BYTES.length,
                               Checksum.ofHex(CHECKSUM),
                               content);
    }

    @Test
    void listsEverySampleWithWhatThePickerNeeds() throws Exception {
        when(files.listSamples()).thenReturn(List.of(cube(() -> new ByteArrayInputStream(BYTES))));

        mockMvc.perform(get("/api/v1/sample-models"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value("cube"))
               .andExpect(jsonPath("$[0].name").value("Cube"))
               .andExpect(jsonPath("$[0].description").value("A 20 mm cube."))
               .andExpect(jsonPath("$[0].filename").value("cube.obj"))
               .andExpect(jsonPath("$[0].format").value("OBJ"))
               .andExpect(jsonPath("$[0].sizeBytes").value(BYTES.length));
    }

    @Test
    void servesTheSampleBytesWithAnEtagThatMustBeRevalidated() throws Exception {
        when(files.sample("cube")).thenReturn(cube(() -> new ByteArrayInputStream(BYTES)));

        mockMvc.perform(get("/api/v1/sample-models/{id}/content", "cube"))
               .andExpect(status().isOk())
               .andExpect(header().string(HttpHeaders.ETAG, "\"" + CHECKSUM + "\""))
               .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
               .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, BYTES.length))
               .andExpect(content().contentType("model/obj"))
               .andExpect(content().bytes(BYTES));
    }

    @Test
    void answersAMatchingIfNoneMatchWithoutOpeningTheSample() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        when(files.sample("cube")).thenReturn(cube(() -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(BYTES);
        }));

        mockMvc.perform(get("/api/v1/sample-models/{id}/content", "cube").header(HttpHeaders.IF_NONE_MATCH,
                                                                                 "\"" + CHECKSUM + "\""))
               .andExpect(status().isNotModified())
               .andExpect(content().string(""));

        assertThat(opens).hasValue(0);
    }

    @Test
    void anUnknownSampleGets404() throws Exception {
        when(files.sample("teapot")).thenThrow(SampleModelNotFoundException.byId("teapot"));

        mockMvc.perform(get("/api/v1/sample-models/{id}/content", "teapot"))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.title").value("Resource not found"));
    }
}
