package com.packing.backend.api.packing;

import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.port.out.PackingInputSchema.SchemaDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PackingInputSchemaController.class)
@AutoConfigureMockMvc(addFilters = false)
class PackingInputSchemaControllerTest {

    private static final String ETAG   = "e".repeat(64);
    private static final String SCHEMA = "{\"$id\":\"urn:3d-packing:packing-job-input:1\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackingJobApplicationService jobs;

    @MockitoBean
    private ErrorAlerter alerter;

    @Test
    void servesTheSchemaWithAnEtagThatMustBeRevalidated() throws Exception {
        when(jobs.inputSchema()).thenReturn(new SchemaDocument(1, SCHEMA, ETAG));

        mockMvc.perform(get("/api/v1/packing/input-schema"))
               .andExpect(status().isOk())
               .andExpect(header().string(HttpHeaders.ETAG, "\"" + ETAG + "\""))
               .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
               .andExpect(content().contentType("application/schema+json"))
               .andExpect(content().string(SCHEMA));
    }

    @Test
    void answersAMatchingIfNoneMatchWithNotModified() throws Exception {
        when(jobs.inputSchema()).thenReturn(new SchemaDocument(1, SCHEMA, ETAG));

        mockMvc.perform(get("/api/v1/packing/input-schema").header(HttpHeaders.IF_NONE_MATCH, "\"" + ETAG + "\""))
               .andExpect(status().isNotModified())
               .andExpect(content().string(""));
    }
}
