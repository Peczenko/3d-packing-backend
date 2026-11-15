package com.packing.backend.infra.conversion;

import com.packing.backend.core.file.ModelConversionException;
import com.packing.backend.infra.shared.mesh.ObjReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackableObjWriterTest {

    @Test
    void keepsOnlyPositionsAndTriangles() throws IOException {
        String written = convert("""
            # exported
            mtllib x.mtl
            o cube
            g part
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vn 0 0 1
            vt 0 0
            usemtl red
            f 1/1/1 2/1/1 3/1/1
            l 1 2
            """, 1.0);

        assertThat(written).isEqualTo("v 0.0 0.0 0.0\nv 1.0 0.0 0.0\nv 0.0 1.0 0.0\nf 1 2 3\n");
    }

    @Test
    void fansAPolygonIntoTriangles() throws IOException {
        String written = convert("v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3 4\n", 1.0);

        assertThat(written).endsWith("f 1 2 3\nf 1 3 4\n");
    }

    @Test
    void scalesEveryCoordinate() throws IOException {
        String written = convert("v 0.05 0.0500000007 -0.025\nv 1 0 0\nv 0 1 0\nf 1 2 3\n", 1000.0);

        assertThat(written).startsWith("v 50.0 50.0 -25.0\nv 1000.0 0.0 0.0\n");
    }

    @Test
    void dropsTrianglesThePackerWouldRejectAsDegenerate() throws IOException {
        StringWriter out = new StringWriter();
        PackableObjWriter writer = new PackableObjWriter(out, 1.0);

        ObjReader.read(stream("""
            v 0 0 0
            v 10 0 0
            v 0 10 0
            v 5 0 0
            f 1 2 3
            f 1 1 3
            f 1 4 2
            f 2 2 2
            """), writer);
        writer.finish();

        assertThat(out.toString()).endsWith("v 5.0 0.0 0.0\nf 1 2 3\n");
        assertThat(writer.triangles()).isEqualTo(1);
        assertThat(writer.dropped()).isEqualTo(3);
    }

    @Test
    void keepsAThinButNotCollinearTriangle() throws IOException {
        String written = convert("v 0 0 0\nv 100 0 0\nv 50 0.001 0\nf 1 2 3\n", 1.0);

        assertThat(written).endsWith("f 1 2 3\n");
    }

    @Test
    void refusesAModelWithNothingLeftToPack() {
        assertThatThrownBy(() -> convert("v 0 0 0\nv 1 0 0\nv 2 0 0\nf 1 2 3\n", 1.0)).isInstanceOf(ModelConversionException.class)
                                                                                      .hasMessageContaining("no triangles");
        assertThatThrownBy(() -> convert("g empty\n", 1.0)).isInstanceOf(ModelConversionException.class);
    }

    @Test
    void refusesACoordinateThatOverflowsOnceScaled() {
        assertThatThrownBy(() -> convert("v 3e38 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n", 1000.0)).isInstanceOf(ModelConversionException.class)
                                                                                            .hasMessageContaining("too large");
    }

    @Test
    void growsPastItsInitialCapacity() throws IOException {
        StringBuilder obj = new StringBuilder();
        int quads = 2_000;
        for (int i = 0; i < quads; i++) {
            obj.append("v ")
               .append(i)
               .append(" 0 0\nv ")
               .append(i)
               .append(" 1 0\nv ")
               .append(i)
               .append(" 0 1\n");
            int base = 3 * i + 1;
            obj.append("f ")
               .append(base)
               .append(' ')
               .append(base + 1)
               .append(' ')
               .append(base + 2)
               .append('\n');
        }
        StringWriter out = new StringWriter();
        PackableObjWriter writer = new PackableObjWriter(out, 1.0);

        ObjReader.read(stream(obj.toString()), writer);
        writer.finish();

        assertThat(writer.triangles()).isEqualTo(quads);
    }

    private static String convert(String obj, double scale) throws IOException {
        StringWriter out = new StringWriter();
        PackableObjWriter writer = new PackableObjWriter(out, scale);
        ObjReader.read(stream(obj), writer);
        writer.finish();
        return out.toString();
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }
}
