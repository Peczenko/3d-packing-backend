package com.packing.backend.infra.packing.preview;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

final class PackerOutput {

    static final double[]   TETRAHEDRON_VERTICES = { 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4 };
    static final int[]      TETRAHEDRON_FACES    = { 0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3 };
    static final double[]   CUBE_VERTICES        = { 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1 };
    static final int[]      CUBE_FACES           = {
            0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4,
            1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7 };
    static final double[][] PLACEMENTS           = {
            { 0, 0, 0, 1, 10, 20, 30 },
            { 0, 0, Math.sqrt(0.5), Math.sqrt(0.5), -4.5, 0.25, 7 },
            { 0.3, -0.5, 0.7, 0.4, 123.456, -78.9, 0.001 } };

    private final StringBuilder text = new StringBuilder();
    private int                 objects;
    private int                 vertices;

    static String format(double value) {
        if (value == 0) {
            return "0";
        }
        return new BigDecimal(value).round(new MathContext(6))
                                    .stripTrailingZeros()
                                    .toPlainString();
    }

    PackerOutput place(double[] meshVertices, int[] faces, double[] placement) {
        return place(meshVertices, faces, placement, 1);
    }

    PackerOutput place(double[] meshVertices, int[] faces, double[] placement, double scale) {
        double norm = Math.sqrt(placement[0] * placement[0] + placement[1] * placement[1] + placement[2] * placement[2] + placement[3] * placement[3]);
        text.append("o Object_")
            .append(objects++)
            .append('\n');
        for (int i = 0; i < meshVertices.length; i += 3) {
            double[] p = RigidFit.rotate(placement[0] / norm,
                                         placement[1] / norm,
                                         placement[2] / norm,
                                         placement[3] / norm,
                                         scale * meshVertices[i],
                                         scale * meshVertices[i + 1],
                                         scale * meshVertices[i + 2]);
            text.append("v ")
                .append(format(p[0] + placement[4]))
                .append(' ')
                .append(format(p[1] + placement[5]))
                .append(' ')
                .append(format(p[2] + placement[6]))
                .append('\n');
        }
        for (int i = 0; i < faces.length; i += 3) {
            text.append("f ")
                .append(faces[i] + vertices + 1)
                .append(' ')
                .append(faces[i + 1] + vertices + 1)
                .append(' ')
                .append(faces[i + 2] + vertices + 1)
                .append('\n');
        }
        vertices += meshVertices.length / 3;
        return this;
    }

    PackerOutput raw(String lines) {
        text.append(lines);
        return this;
    }

    String text() {
        return text.toString();
    }

    static List<InstanceExtractor.Item> layout(Object... namesAndCopies) {
        InstanceExtractor.Item[] items = new InstanceExtractor.Item[namesAndCopies.length / 2];
        for (int i = 0; i < items.length; i++) {
            items[i] = new InstanceExtractor.Item((String) namesAndCopies[2 * i], (Integer) namesAndCopies[2 * i + 1]);
        }
        return List.of(items);
    }
}
