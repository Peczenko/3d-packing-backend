package com.packing.backend.infra.packing.preview;

import java.util.List;

record PreviewPart(int index, String name, float[] positions, int[] indices, List<Instance> instances) {

    PreviewPart {
        instances = List.copyOf(instances);
    }

    int vertexCount() {
        return positions.length / 3;
    }

    int triangleCount() {
        return indices.length / 3;
    }

    record Instance(double qx, double qy, double qz, double qw, double tx, double ty, double tz) {
    }
}
