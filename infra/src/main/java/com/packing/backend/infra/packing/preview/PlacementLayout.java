package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.packing.backend.domain.packing.PackingItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

record PlacementLayout(List<double[]> binOffsets, List<Placement> placements) {

    static final int VERSION = 1;

    private static final double ROTATION_TOLERANCE = 1e-6;

    static PlacementLayout parse(byte[] json, List<PackingItem> items, int binCount) {
        JsonNode root;
        try {
            root = Glb.JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("they are not JSON");
        }
        if (root == null || root.path("version")
                                .asInt()
                != VERSION) {
            throw new IllegalArgumentException("they are not version " + VERSION);
        }
        List<double[]> offsets = new ArrayList<>();
        for (JsonNode offset : root.path("binOffsets")) {
            offsets.add(numbers(offset, 3));
        }
        if (binCount < 1 || offsets.size() != binCount) {
            throw new IllegalArgumentException("they describe " + offsets.size() + " containers and the result has " + binCount);
        }
        List<Integer> expected = new ArrayList<>();
        for (int item = 0; item < items.size(); item++) {
            for (int copy = 0; copy < items.get(item)
                                           .copies();
                    copy++) {
                expected.add(item);
            }
        }
        JsonNode listed = root.path("placements");
        if (listed.size() != expected.size()) {
            throw new IllegalArgumentException("they place " + listed.size() + " objects and the job has " + expected.size());
        }
        boolean[] filled = new boolean[binCount];
        List<Placement> placements = new ArrayList<>();
        for (int i = 0; i < listed.size(); i++) {
            JsonNode entry = listed.get(i);
            int bin = entry.path("bin")
                           .asInt();
            if (entry.path("item")
                     .asInt(-1)
                    != expected.get(i) || bin < 1 || bin > binCount) {
                throw new IllegalArgumentException("object " + (i + 1) + " is not the job's object " + (i + 1));
            }
            double[] matrix = numbers(entry.path("matrix"), 12);
            requireRotation(matrix, i);
            filled[bin - 1] = true;
            placements.add(new Placement(expected.get(i), bin, matrix));
        }
        for (int bin = 0; bin < binCount; bin++) {
            if (!filled[bin]) {
                throw new IllegalArgumentException("container " + (bin + 1) + " holds no objects");
            }
        }
        return new PlacementLayout(List.copyOf(offsets), List.copyOf(placements));
    }

    List<PreviewPart.Instance> instances(int item, int bin, double[] centroid) {
        List<PreviewPart.Instance> instances = new ArrayList<>();
        for (Placement placement : placements) {
            if (placement.item() != item || bin != 0 && placement.bin() != bin) {
                continue;
            }
            double[] m = placement.matrix();
            double[] offset = bin == 0 ? binOffsets.get(placement.bin() - 1) : new double[3];
            double[] q = quaternion(m);
            instances.add(new PreviewPart.Instance(q[0],
                                                   q[1],
                                                   q[2],
                                                   q[3],
                                                   m[0] * centroid[0] + m[1] * centroid[1] + m[2] * centroid[2] + m[3] + offset[0],
                                                   m[4] * centroid[0] + m[5] * centroid[1] + m[6] * centroid[2] + m[7] + offset[1],
                                                   m[8] * centroid[0] + m[9] * centroid[1] + m[10] * centroid[2] + m[11] + offset[2]));
        }
        return instances;
    }

    static double[] quaternion(double[] m) {
        double m00 = m[0];
        double m01 = m[1];
        double m02 = m[2];
        double m10 = m[4];
        double m11 = m[5];
        double m12 = m[6];
        double m20 = m[8];
        double m21 = m[9];
        double m22 = m[10];
        double x;
        double y;
        double z;
        double w;
        double trace = m00 + m11 + m22;
        if (trace > 0) {
            double s = 2 * Math.sqrt(trace + 1);
            w = s / 4;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = 2 * Math.sqrt(1 + m00 - m11 - m22);
            w = (m21 - m12) / s;
            x = s / 4;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = 2 * Math.sqrt(1 + m11 - m00 - m22);
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = s / 4;
            z = (m12 + m21) / s;
        } else {
            double s = 2 * Math.sqrt(1 + m22 - m00 - m11);
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = s / 4;
        }
        double norm = Math.sqrt(x * x + y * y + z * z + w * w);
        return new double[] { x / norm, y / norm, z / norm, w / norm };
    }

    private static void requireRotation(double[] m, int index) {
        for (int a = 0; a < 3; a++) {
            for (int b = a; b < 3; b++) {
                double dot = m[4 * a] * m[4 * b] + m[4 * a + 1] * m[4 * b + 1] + m[4 * a + 2] * m[4 * b + 2];
                if (Math.abs(dot - (a == b ? 1 : 0)) > ROTATION_TOLERANCE) {
                    throw new IllegalArgumentException("object " + (index + 1) + " is not placed by a rotation");
                }
            }
        }
        double determinant = m[0] * (m[5] * m[10] - m[6] * m[9])
                - m[1] * (m[4] * m[10] - m[6] * m[8])
                + m[2] * (m[4] * m[9] - m[5] * m[8]);
        if (determinant < 0) {
            throw new IllegalArgumentException("object " + (index + 1) + " is mirrored");
        }
    }

    private static double[] numbers(JsonNode values, int count) {
        if (!values.isArray() || values.size() != count) {
            throw new IllegalArgumentException("a list of " + count + " numbers is missing");
        }
        double[] numbers = new double[count];
        for (int i = 0; i < count; i++) {
            if (!values.get(i)
                       .isNumber()
                    || !Double.isFinite(values.get(i)
                                              .asDouble())) {
                throw new IllegalArgumentException("a list of " + count + " numbers holds something else");
            }
            numbers[i] = values.get(i)
                               .asDouble();
        }
        return numbers;
    }

    record Placement(int item, int bin, double[] matrix) {
    }
}
