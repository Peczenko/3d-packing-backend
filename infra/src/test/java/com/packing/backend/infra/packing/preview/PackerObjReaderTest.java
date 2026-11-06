package com.packing.backend.infra.packing.preview;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackerObjReaderTest {

    @Test
    void readsObjectsVerticesAndTrianglesWithZeroBasedIndices() throws IOException {
        Recording recording = read("""
            o Object_0
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3
            o Object_1
            v 2 0 0
            v 3 0 0
            v 2 1 0
            f 4 5 6
            """);

        assertThat(recording.events).containsExactly("o Object_0",
                                                     "v 0.0 0.0 0.0",
                                                     "v 1.0 0.0 0.0",
                                                     "v 0.0 1.0 0.0",
                                                     "f 0 1 2",
                                                     "o Object_1",
                                                     "v 2.0 0.0 0.0",
                                                     "v 3.0 0.0 0.0",
                                                     "v 2.0 1.0 0.0",
                                                     "f 3 4 5");
    }

    @Test
    void parsesTheNumberFormatsAnOstreamWrites() throws IOException {
        Recording recording = read("""
            o a
            v -0.5 123.457 1e-05
            v 1.5e+06 -2.25E-3 +7
            v 6.02214076e23 0.000123456789012345678901 -0
            """);

        assertThat(recording.vertices).containsExactly(-0.5,
                                                       123.457,
                                                       1e-05,
                                                       1.5e+06,
                                                       -2.25e-3,
                                                       7.0,
                                                       6.02214076e23,
                                                       0.000123456789012345678901,
                                                       -0.0);
    }

    @Test
    void fanTriangulatesPolygonsAndAcceptsSlashedAndRelativeIndices() throws IOException {
        Recording recording = read("""
            o quad
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f 1/1/1 2/2/2 3//3 4
            f -4 -3 -2
            """);

        assertThat(recording.events).contains("f 0 1 2", "f 0 2 3", "f 0 1 2");
        assertThat(recording.events.stream()
                                   .filter(event -> event.startsWith("f"))).hasSize(3);
    }

    @Test
    void ignoresCommentsBlankLinesCarriageReturnsAndOtherStatements() throws IOException {
        Recording recording = read("# packer output\r\n\r\nmtllib x.mtl\r\no a\r\ng group\r\ns off\r\nusemtl red\r\n"
                + "v 1 2 3\r\nvn 0 0 1\r\nvt 0.5 0.5\r\nl 1 1\r\nv 4 5 6\r\nv 7 8 9\r\nf 1 2 3\r\n");

        assertThat(recording.events).containsExactly("o a", "v 1.0 2.0 3.0", "v 4.0 5.0 6.0", "v 7.0 8.0 9.0", "f 0 1 2");
    }

    @Test
    void readsLinesThatStraddleEveryReadBoundary() throws IOException {
        StringBuilder obj = new StringBuilder("o big\n");
        int vertices = 120_000;
        for (int i = 0; i < vertices; i++) {
            obj.append("v ")
               .append(i)
               .append(".125 ")
               .append(-i)
               .append(" 3.5\n");
        }
        Recording whole = new Recording();
        PackerObjReader.read(new ByteArrayInputStream(obj.toString()
                                                         .getBytes(StandardCharsets.UTF_8)),
                             whole);
        Recording trickled = new Recording();
        PackerObjReader.read(new Trickle(obj.toString()
                                            .getBytes(StandardCharsets.UTF_8)),
                             trickled);

        assertThat(whole.vertices).hasSize(3 * vertices);
        assertThat(whole.vertices.get(3 * (vertices - 1))).isEqualTo(vertices - 1 + 0.125);
        assertThat(trickled.vertices).isEqualTo(whole.vertices);
    }

    @Test
    void readsAFinalLineWithoutANewline() throws IOException {
        Recording recording = read("o a\nv 1 2 3");

        assertThat(recording.vertices).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void rejectsAFaceOnAVertexThatHasNotBeenDeclared() {
        assertThatThrownBy(() -> read("o a\nv 0 0 0\nf 1 2 3\n")).isInstanceOf(UnrecognizedPackerOutputException.class)
                                                                 .hasMessageContaining("Line 3");
    }

    @Test
    void rejectsMalformedNumbersAndDegenerateFaces() {
        assertThatThrownBy(() -> read("o a\nv 1 2\n")).isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThatThrownBy(() -> read("o a\nv 1 2 x\n")).isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThatThrownBy(() -> read("o a\nv nan 0 0\n")).isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThatThrownBy(() -> read("o a\nv 1e999 0 0\n")).isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThatThrownBy(() -> read("o a\nv 0 0 0\nv 1 0 0\nf 1 2\n")).isInstanceOf(UnrecognizedPackerOutputException.class);
        assertThatThrownBy(() -> read("o a\nv 0 0 0\nf 0 1 1\n")).isInstanceOf(UnrecognizedPackerOutputException.class);
    }

    private static Recording read(String obj) throws IOException {
        Recording recording = new Recording();
        PackerObjReader.read(new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)), recording);
        return recording;
    }

    private static final class Recording implements PackerObjReader.Sink {

        private final List<String> events   = new ArrayList<>();
        private final List<Double> vertices = new ArrayList<>();

        @Override
        public void object(String name) {
            events.add("o " + name);
        }

        @Override
        public void vertex(double x, double y, double z) {
            events.add("v " + x + " " + y + " " + z);
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);
        }

        @Override
        public void triangle(int a, int b, int c) {
            events.add("f " + a + " " + b + " " + c);
        }
    }

    private static final class Trickle extends InputStream {

        private final byte[] bytes;
        private int          position;

        private Trickle(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            return position < bytes.length ? bytes[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (position >= bytes.length) {
                return -1;
            }
            int count = Math.min(length, 1 + position % 7);
            System.arraycopy(bytes, position, target, offset, Math.min(count, bytes.length - position));
            int read = Math.min(count, bytes.length - position);
            position += read;
            return read;
        }
    }
}
