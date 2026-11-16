package com.packing.backend.infra.packing.preview;

import com.packing.backend.infra.shared.mesh.ObjReader;

import java.util.Arrays;
import java.util.List;

final class InputMesh implements ObjReader.Sink {

    private final String name;

    private float[] positions = new float[3 * 1024];
    private int[]   indices   = new int[3 * 1024];
    private int     vertices;
    private int     triangles;
    private double  sumX;
    private double  sumY;
    private double  sumZ;

    InputMesh(String name) {
        this.name = name;
    }

    @Override
    public void object(String ignored) {
    }

    @Override
    public void vertex(double x, double y, double z) {
        if (vertices >= InstanceExtractor.MAX_MESH_VERTICES) {
            throw new UnrecognizedPackerOutputException("'" + name + "' has more than " + InstanceExtractor.MAX_MESH_VERTICES + " vertices");
        }
        int at = 3 * vertices;
        if (at + 3 > positions.length) {
            positions = Arrays.copyOf(positions, positions.length * 2);
        }
        positions[at] = (float) x;
        positions[at + 1] = (float) y;
        positions[at + 2] = (float) z;
        vertices++;
        sumX += x;
        sumY += y;
        sumZ += z;
    }

    @Override
    public void triangle(int a, int b, int c) {
        if (triangles >= InstanceExtractor.MAX_MESH_TRIANGLES) {
            throw new UnrecognizedPackerOutputException("'" + name + "' has more than " + InstanceExtractor.MAX_MESH_TRIANGLES + " triangles");
        }
        int at = 3 * triangles;
        if (at + 3 > indices.length) {
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
        indices[at] = a;
        indices[at + 1] = b;
        indices[at + 2] = c;
        triangles++;
    }

    double[] centroid() {
        if (triangles == 0) {
            throw new UnrecognizedPackerOutputException("'" + name + "' has no triangles");
        }
        return new double[] { sumX / vertices, sumY / vertices, sumZ / vertices };
    }

    PreviewPart centered(int index) {
        double[] centroid = centroid();
        float[] centered = Arrays.copyOf(positions, 3 * vertices);
        for (int i = 0; i < centered.length; i++) {
            centered[i] = (float) (centered[i] - centroid[i % 3]);
        }
        return new PreviewPart(index,
                               name,
                               centered,
                               Arrays.copyOf(indices, 3 * triangles),
                               List.of(new PreviewPart.Instance(0, 0, 0, 1, 0, 0, 0)));
    }
}
