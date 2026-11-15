package com.packing.backend.infra.conversion;

import com.packing.backend.core.file.ModelConversionException;
import com.packing.backend.infra.shared.mesh.ObjReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Arrays;

final class PackableObjWriter implements ObjReader.Sink {

    static final double COLLINEAR_TOLERANCE = 1e-28;

    private static final int MAX_VERTICES = (Integer.MAX_VALUE - 8) / 3;

    private final Writer out;
    private final double scale;

    private float[] coordinates = new float[3 * 1024];
    private int     vertexCount;
    private long    triangles;
    private long    dropped;

    PackableObjWriter(Writer out, double scale) {
        this.out = out;
        this.scale = scale;
    }

    @Override
    public void object(String name) {
    }

    @Override
    public void vertex(double x, double y, double z) {
        if (vertexCount == MAX_VERTICES) {
            throw new ModelConversionException("The model has more than " + MAX_VERTICES + " vertices");
        }
        float sx = scaled(x);
        float sy = scaled(y);
        float sz = scaled(z);
        int offset = 3 * vertexCount;
        if (offset + 3 > coordinates.length) {
            coordinates = Arrays.copyOf(coordinates, (int) Math.min(2L * coordinates.length, 3L * MAX_VERTICES));
        }
        coordinates[offset] = sx;
        coordinates[offset + 1] = sy;
        coordinates[offset + 2] = sz;
        vertexCount++;
        write("v " + sx + " " + sy + " " + sz + "\n");
    }

    @Override
    public void triangle(int a, int b, int c) {
        if (isDegenerate(a, b, c)) {
            dropped++;
            return;
        }
        triangles++;
        write("f " + (a + 1) + " " + (b + 1) + " " + (c + 1) + "\n");
    }

    void finish() {
        if (triangles == 0) {
            throw new ModelConversionException("The model contains no triangles that can be packed");
        }
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    long triangles() {
        return triangles;
    }

    long dropped() {
        return dropped;
    }

    private boolean isDegenerate(int a, int b, int c) {
        double ux = coordinates[3 * b] - (double) coordinates[3 * a];
        double uy = coordinates[3 * b + 1] - (double) coordinates[3 * a + 1];
        double uz = coordinates[3 * b + 2] - (double) coordinates[3 * a + 2];
        double vx = coordinates[3 * c] - (double) coordinates[3 * a];
        double vy = coordinates[3 * c + 1] - (double) coordinates[3 * a + 1];
        double vz = coordinates[3 * c + 2] - (double) coordinates[3 * a + 2];
        double u2 = ux * ux + uy * uy + uz * uz;
        double v2 = vx * vx + vy * vy + vz * vz;
        double cx = uy * vz - uz * vy;
        double cy = uz * vx - ux * vz;
        double cz = ux * vy - uy * vx;
        return u2 == 0 || v2 == 0 || cx * cx + cy * cy + cz * cz <= COLLINEAR_TOLERANCE * u2 * v2;
    }

    private float scaled(double value) {
        float result = (float) (value * scale);
        if (!Float.isFinite(result)) {
            throw new ModelConversionException("The model has a coordinate too large to store");
        }
        return result;
    }

    private void write(String line) {
        try {
            out.write(line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
