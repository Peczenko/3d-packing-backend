package com.packing.backend.core.file;

import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentDigestTest {

    @Test
    void countsAndHashesEveryByte() {
        byte[] bytes = "solid cube".getBytes(StandardCharsets.UTF_8);

        ContentDigest digest = ContentDigest.of(() -> new ByteArrayInputStream(bytes));

        assertThat(digest.sizeBytes()).isEqualTo(bytes.length);
        assertThat(digest.checksum()
                         .value()).isEqualTo("d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971");
    }

    @Test
    void stopsReadingAsSoonAsTheContentExceedsTheFileLimit() {
        ContentSource endless = () -> new InputStream() {

            private long served;

            @Override
            public int read() {
                return served++ > StoredFile.MAX_SIZE_BYTES + 1 ? -1 : 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (served > StoredFile.MAX_SIZE_BYTES * 2) {
                    throw new AssertionError("kept reading past the limit");
                }
                served += length;
                return length;
            }
        };

        assertThatThrownBy(() -> ContentDigest.of(endless)).isInstanceOf(DomainRuleViolationException.class)
                                                           .hasMessageContaining(String.valueOf(StoredFile.MAX_SIZE_BYTES));
    }

    @Test
    void reportsAnUnreadableSourceAsAnIoFailure() {
        ContentSource broken = () -> {
            throw new IOException("disk gone");
        };

        assertThatThrownBy(() -> ContentDigest.of(broken)).isInstanceOf(UncheckedIOException.class)
                                                          .hasRootCauseMessage("disk gone");
    }
}
