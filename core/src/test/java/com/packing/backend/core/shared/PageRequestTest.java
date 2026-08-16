package com.packing.backend.core.shared;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRequestTest {

    @Test
    void derivesTheOffsetFromThePageIndex() {
        assertThat(new PageRequest(0, 20).offset()).isZero();
        assertThat(new PageRequest(2, 20).offset()).isEqualTo(40L);
    }

    @Test
    void doesNotOverflowOnALargePageIndex() {
        assertThat(new PageRequest(Integer.MAX_VALUE, 100).offset())
                                                                    .isEqualTo(214_748_364_700L);
    }

    @Test
    void rejectsANegativePage() {
        assertThatThrownBy(() -> new PageRequest(-1, 20))
                                                         .isInstanceOf(DomainRuleViolationException.class)
                                                         .hasMessage("Page must not be negative");
    }

    @Test
    void rejectsASizeOutsideTheAllowedRange() {
        assertThatThrownBy(() -> new PageRequest(0, 0))
                                                       .isInstanceOf(DomainRuleViolationException.class)
                                                       .hasMessage("Page size must be between 1 and 100");
        assertThatThrownBy(() -> new PageRequest(0, PageRequest.MAX_SIZE + 1))
                                                                              .isInstanceOf(DomainRuleViolationException.class)
                                                                              .hasMessage("Page size must be between 1 and 100");
    }
}
