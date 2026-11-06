package com.packing.backend.infra.packing.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

final class InstanceExtractor implements PackerObjReader.Sink {

    static final double RELATIVE_SHAPE_TOLERANCE    = 1e-3;
    static final double RELATIVE_ROUNDING_TOLERANCE = 1e-5;
    static final int    MAX_MESH_TRIANGLES          = 10_000_000;
    static final int    MAX_MESH_VERTICES           = 10_000_000;

    private final List<Item>            items;
    private final Consumer<PreviewPart> parts;

    private int itemIndex = -1;
    private int copyIndex;

    private boolean inObject;
    private long    globalVertices;
    private long    objectBase;
    private int     objectVertices;
    private int     objectTriangles;

    private float[]                    positions = new float[3 * 1024];
    private int[]                      indices   = new int[3 * 1024];
    private int                        canonicalVertices;
    private int                        canonicalTriangles;
    private double                     extentMinX;
    private double                     extentMinY;
    private double                     extentMinZ;
    private double                     extentMaxX;
    private double                     extentMaxY;
    private double                     extentMaxZ;
    private double                     largestCoordinate;
    private List<PreviewPart.Instance> instances = new ArrayList<>();

    private RigidFit fit;
    private double   copyLargestCoordinate;

    InstanceExtractor(List<Item> items, Consumer<PreviewPart> parts) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A packing result needs at least one item");
        }
        this.items = List.copyOf(items);
        this.parts = parts;
    }

    @Override
    public void object(String name) {
        finishObject();
        if (itemIndex >= 0 && copyIndex + 1 < items.get(itemIndex)
                                                   .copies()) {
            copyIndex++;
        } else {
            if (itemIndex >= 0) {
                emitItem();
            }
            itemIndex++;
            copyIndex = 0;
            if (itemIndex >= items.size()) {
                throw mismatch("it holds more objects than the job placed");
            }
        }
        inObject = true;
        objectBase = globalVertices;
        objectVertices = 0;
        objectTriangles = 0;
        if (copyIndex == 0) {
            startCanonical();
        } else {
            fit = new RigidFit();
            copyLargestCoordinate = 0;
        }
    }

    @Override
    public void vertex(double x, double y, double z) {
        requireObject();
        globalVertices++;
        if (copyIndex == 0) {
            appendCanonicalVertex(x, y, z);
        } else {
            if (objectVertices >= canonicalVertices) {
                throw mismatch(copyLabel() + " has more vertices than its first copy");
            }
            int at = 3 * objectVertices;
            fit.add(positions[at], positions[at + 1], positions[at + 2], x, y, z);
            copyLargestCoordinate = Math.max(copyLargestCoordinate, Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))));
        }
        objectVertices++;
    }

    @Override
    public void triangle(int a, int b, int c) {
        requireObject();
        int ra = local(a);
        int rb = local(b);
        int rc = local(c);
        if (copyIndex == 0) {
            if (canonicalTriangles >= MAX_MESH_TRIANGLES) {
                throw mismatch(copyLabel() + " has more than " + MAX_MESH_TRIANGLES + " triangles");
            }
            int at = 3 * canonicalTriangles;
            if (at + 3 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[at] = ra;
            indices[at + 1] = rb;
            indices[at + 2] = rc;
            canonicalTriangles++;
        } else {
            int at = 3 * objectTriangles;
            if (objectTriangles >= canonicalTriangles || indices[at] != ra || indices[at + 1] != rb || indices[at + 2] != rc) {
                throw mismatch(copyLabel() + " is not connected like its first copy");
            }
        }
        objectTriangles++;
    }

    void finish() {
        finishObject();
        if (itemIndex < 0) {
            throw mismatch("it holds no objects");
        }
        if (itemIndex != items.size() - 1 || copyIndex != items.get(itemIndex)
                                                               .copies()
                - 1) {
            throw mismatch("it holds fewer objects than the job placed");
        }
        emitItem();
    }

    private void startCanonical() {
        canonicalVertices = 0;
        canonicalTriangles = 0;
        extentMinX = Double.POSITIVE_INFINITY;
        extentMinY = Double.POSITIVE_INFINITY;
        extentMinZ = Double.POSITIVE_INFINITY;
        extentMaxX = Double.NEGATIVE_INFINITY;
        extentMaxY = Double.NEGATIVE_INFINITY;
        extentMaxZ = Double.NEGATIVE_INFINITY;
        largestCoordinate = 0;
        instances = new ArrayList<>();
    }

    private void appendCanonicalVertex(double x, double y, double z) {
        if (canonicalVertices >= MAX_MESH_VERTICES) {
            throw mismatch(copyLabel() + " has more than " + MAX_MESH_VERTICES + " vertices");
        }
        int at = 3 * canonicalVertices;
        if (at + 3 > positions.length) {
            positions = Arrays.copyOf(positions, positions.length * 2);
        }
        positions[at] = (float) x;
        positions[at + 1] = (float) y;
        positions[at + 2] = (float) z;
        canonicalVertices++;
        extentMinX = Math.min(extentMinX, x);
        extentMinY = Math.min(extentMinY, y);
        extentMinZ = Math.min(extentMinZ, z);
        extentMaxX = Math.max(extentMaxX, x);
        extentMaxY = Math.max(extentMaxY, y);
        extentMaxZ = Math.max(extentMaxZ, z);
        largestCoordinate = Math.max(largestCoordinate, Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))));
    }

    private void finishObject() {
        if (!inObject) {
            return;
        }
        inObject = false;
        if (copyIndex == 0) {
            if (canonicalTriangles == 0) {
                throw mismatch(copyLabel() + " has no triangles");
            }
            centerCanonical();
            return;
        }
        if (objectVertices != canonicalVertices || objectTriangles != canonicalTriangles) {
            throw mismatch(copyLabel() + " does not have the same geometry as its first copy");
        }
        RigidFit.Solution solution = fit.solve();
        double diagonal = Math.sqrt(square(extentMaxX - extentMinX) + square(extentMaxY - extentMinY) + square(extentMaxZ - extentMinZ));
        double tolerance = RELATIVE_SHAPE_TOLERANCE * diagonal
                + RELATIVE_ROUNDING_TOLERANCE * Math.max(largestCoordinate, copyLargestCoordinate)
                + Double.MIN_NORMAL;
        if (solution.rmsError() > tolerance) {
            throw mismatch(copyLabel() + " is not a rotated and moved copy of its first copy");
        }
        instances.add(new PreviewPart.Instance(solution.qx(),
                                               solution.qy(),
                                               solution.qz(),
                                               solution.qw(),
                                               solution.tx(),
                                               solution.ty(),
                                               solution.tz()));
        fit = null;
    }

    private void centerCanonical() {
        double cx = 0;
        double cy = 0;
        double cz = 0;
        for (int i = 0; i < canonicalVertices; i++) {
            cx += positions[3 * i];
            cy += positions[3 * i + 1];
            cz += positions[3 * i + 2];
        }
        cx /= canonicalVertices;
        cy /= canonicalVertices;
        cz /= canonicalVertices;
        for (int i = 0; i < canonicalVertices; i++) {
            positions[3 * i] = (float) (positions[3 * i] - cx);
            positions[3 * i + 1] = (float) (positions[3 * i + 1] - cy);
            positions[3 * i + 2] = (float) (positions[3 * i + 2] - cz);
        }
        instances.add(new PreviewPart.Instance(0, 0, 0, 1, cx, cy, cz));
    }

    private void emitItem() {
        Item item = items.get(itemIndex);
        parts.accept(new PreviewPart(itemIndex,
                                     item.name(),
                                     Arrays.copyOf(positions, 3 * canonicalVertices),
                                     Arrays.copyOf(indices, 3 * canonicalTriangles),
                                     instances));
    }

    private int local(int global) {
        long local = global - objectBase;
        if (local < 0 || local >= objectVertices) {
            throw mismatch(copyLabel() + " refers to a vertex outside its own object");
        }
        return (int) local;
    }

    private void requireObject() {
        if (!inObject) {
            throw mismatch("geometry appears before the first object");
        }
    }

    private String copyLabel() {
        return "copy " + (copyIndex + 1) + " of '" + items.get(itemIndex)
                                                          .name()
                + "'";
    }

    private static double square(double value) {
        return value * value;
    }

    private static UnrecognizedPackerOutputException mismatch(String problem) {
        return new UnrecognizedPackerOutputException("The packing result does not match the job: " + problem);
    }

    record Item(String name, int copies) {

        Item {
            if (copies < 1) {
                throw new IllegalArgumentException("copies must be positive");
            }
        }
    }
}
