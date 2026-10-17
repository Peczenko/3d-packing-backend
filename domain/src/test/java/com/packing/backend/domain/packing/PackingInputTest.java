package com.packing.backend.domain.packing;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static com.packing.backend.domain.packing.PackingInputs.NO_PARAMETERS;
import static com.packing.backend.domain.packing.PackingInputs.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackingInputTest {

    @Test
    void acceptsDistinctFilesUpToTheObjectBudget() {
        PackingInput input = new PackingInput(1, List.of(item("a.obj", 100), item("b.obj", 100)), NO_PARAMETERS);

        assertThat(input.items()).hasSize(2);
        assertThat(input.parameters()).isEqualTo(new PackingParameters("{}"));
    }

    @Test
    void rejectsAnEmptyItemList() {
        assertThatThrownBy(() -> new PackingInput(1, List.of(), NO_PARAMETERS))
                                                                               .isInstanceOf(DomainRuleViolationException.class)
                                                                               .hasMessage("A packing job needs between 1 and 50 files");
    }

    @Test
    void rejectsMoreThanFiftyFiles() {
        List<PackingItem> items = IntStream.range(0, 51)
                                           .mapToObj(i -> item("part-" + i + ".obj", 1))
                                           .toList();

        assertThatThrownBy(() -> new PackingInput(1, items, NO_PARAMETERS))
                                                                           .isInstanceOf(DomainRuleViolationException.class)
                                                                           .hasMessage("A packing job needs between 1 and 50 files");
    }

    @Test
    void rejectsTheSameFileTwice() {
        PackingItem first = item("a.obj", 1);
        PackingItem again = new PackingItem(first.fileId(),
                                            first.fileName(),
                                            first.format(),
                                            first.checksum(),
                                            first.storageKey(),
                                            2);

        assertThatThrownBy(() -> new PackingInput(1, List.of(first, again), NO_PARAMETERS))
                                                                                           .isInstanceOf(DomainRuleViolationException.class)
                                                                                           .hasMessage("File a.obj is listed more than once");
    }

    @Test
    void rejectsMoreThanTwoHundredObjectsInTotal() {
        List<PackingItem> items = List.of(item("a.obj", 100), item("b.obj", 100), item("c.obj", 1));

        assertThatThrownBy(() -> new PackingInput(1, items, NO_PARAMETERS))
                                                                           .isInstanceOf(DomainRuleViolationException.class)
                                                                           .hasMessage("A packing job may hold at most 200 objects; this one has 201");
    }

    @Test
    void rejectsASchemaVersionBelowOne() {
        assertThatThrownBy(() -> new PackingInput(0, List.of(item("a.obj", 1)), NO_PARAMETERS))
                                                                                               .isInstanceOf(DomainRuleViolationException.class)
                                                                                               .hasMessage("Input schema version must be at least 1");
    }

    @Test
    void keepsItsOwnCopyOfTheItems() {
        List<PackingItem> items = new ArrayList<>(List.of(item("a.obj", 1)));
        PackingInput input = new PackingInput(1, items, NO_PARAMETERS);

        items.add(item("b.obj", 1));

        assertThat(input.items()).hasSize(1);
    }

    @Test
    void rejectsBlankParameters() {
        assertThatThrownBy(() -> new PackingParameters(" "))
                                                            .isInstanceOf(DomainRuleViolationException.class)
                                                            .hasMessage("Packing parameters must not be blank");
    }
}
