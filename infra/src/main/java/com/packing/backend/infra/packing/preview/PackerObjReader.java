package com.packing.backend.infra.packing.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PackerObjReader {

    private static final int      INITIAL_BUFFER  = 1 << 20;
    private static final int      MAX_FAST_DIGITS = 18;
    private static final double[] POWERS_OF_TEN   = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
            1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22 };

    private final InputStream in;
    private final Sink        sink;

    private byte[] buffer  = new byte[INITIAL_BUFFER];
    private int    position;
    private int    lineEnd;
    private long   lineNumber;
    private long   vertexCount;
    private int[]  polygon = new int[8];

    private PackerObjReader(InputStream in, Sink sink) {
        this.in = in;
        this.sink = sink;
    }

    static void read(InputStream in, Sink sink) throws IOException {
        new PackerObjReader(in, sink).run();
    }

    private void run() throws IOException {
        int start = 0;
        int end = 0;
        int scan = 0;
        while (true) {
            int newline = indexOfNewline(scan, end);
            if (newline >= 0) {
                line(start, newline);
                start = newline + 1;
                scan = start;
                continue;
            }
            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, end - start);
                end -= start;
                start = 0;
            }
            if (end == buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            scan = end;
            int read = in.read(buffer, end, buffer.length - end);
            if (read < 0) {
                if (end > start) {
                    line(start, end);
                }
                return;
            }
            end += read;
        }
    }

    private int indexOfNewline(int from, int to) {
        for (int i = from; i < to; i++) {
            if (buffer[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void line(int start, int end) {
        lineNumber++;
        position = start;
        lineEnd = end;
        skipSpaces();
        if (position >= lineEnd) {
            return;
        }
        byte first = buffer[position];
        byte second = position + 1 < lineEnd ? buffer[position + 1] : (byte) ' ';
        if (!isSpace(second)) {
            return;
        }
        position++;
        switch (first) {
            case 'v' -> vertex();
            case 'f' -> face();
            case 'o' -> object();
            default -> {
            }
        }
    }

    private void vertex() {
        double x = number();
        double y = number();
        double z = number();
        vertexCount++;
        sink.vertex(x, y, z);
    }

    private void face() {
        int corners = 0;
        while (true) {
            skipSpaces();
            if (position >= lineEnd) {
                break;
            }
            if (corners == polygon.length) {
                polygon = Arrays.copyOf(polygon, polygon.length * 2);
            }
            polygon[corners++] = vertexIndex();
            while (position < lineEnd && !isSpace(buffer[position])) {
                position++;
            }
        }
        if (corners < 3) {
            throw malformed("a face needs at least three vertices");
        }
        for (int i = 1; i + 1 < corners; i++) {
            sink.triangle(polygon[0], polygon[i], polygon[i + 1]);
        }
    }

    private void object() {
        skipSpaces();
        int end = lineEnd;
        while (end > position && isSpace(buffer[end - 1])) {
            end--;
        }
        sink.object(new String(buffer, position, end - position, StandardCharsets.UTF_8));
    }

    private int vertexIndex() {
        boolean negative = consume('-');
        long value = 0;
        int digits = 0;
        while (position < lineEnd && isDigit(buffer[position])) {
            value = value * 10 + (buffer[position++] - '0');
            if (++digits > MAX_FAST_DIGITS) {
                throw malformed("a vertex index is too large");
            }
        }
        if (digits == 0 || value == 0) {
            throw malformed("a face refers to vertex 0 or to no vertex at all");
        }
        long resolved = negative ? vertexCount - value : value - 1;
        if (resolved < 0 || resolved >= vertexCount || resolved > Integer.MAX_VALUE) {
            throw malformed("a face refers to a vertex that has not been declared");
        }
        return (int) resolved;
    }

    private double number() {
        skipSpaces();
        int start = position;
        boolean negative = consume('-');
        if (!negative) {
            consume('+');
        }
        long mantissa = 0;
        int significant = 0;
        int exponent = 0;
        boolean any = false;
        while (position < lineEnd && isDigit(buffer[position])) {
            any = true;
            if (significant < MAX_FAST_DIGITS) {
                mantissa = mantissa * 10 + (buffer[position] - '0');
                if (mantissa != 0) {
                    significant++;
                }
            } else {
                exponent++;
            }
            position++;
        }
        if (consume('.')) {
            while (position < lineEnd && isDigit(buffer[position])) {
                any = true;
                if (significant < MAX_FAST_DIGITS) {
                    mantissa = mantissa * 10 + (buffer[position] - '0');
                    if (mantissa != 0) {
                        significant++;
                    }
                    exponent--;
                }
                position++;
            }
        }
        if (!any) {
            throw malformed("expected a number");
        }
        if (position < lineEnd && (buffer[position] == 'e' || buffer[position] == 'E')) {
            position++;
            boolean negativeExponent = consume('-');
            if (!negativeExponent) {
                consume('+');
            }
            int written = 0;
            int digits = 0;
            while (position < lineEnd && isDigit(buffer[position])) {
                if (written < 10_000) {
                    written = written * 10 + (buffer[position] - '0');
                }
                digits++;
                position++;
            }
            if (digits == 0) {
                throw malformed("expected an exponent");
            }
            exponent += negativeExponent ? -written : written;
        }
        if (position < lineEnd && !isSpace(buffer[position])) {
            throw malformed("expected a number");
        }
        double value;
        if (mantissa < (1L << 53) && Math.abs(exponent) < POWERS_OF_TEN.length) {
            value = exponent >= 0 ? mantissa * POWERS_OF_TEN[exponent] : mantissa / POWERS_OF_TEN[-exponent];
            value = negative ? -value : value;
        } else {
            value = Double.parseDouble(new String(buffer, start, position - start, StandardCharsets.US_ASCII));
        }
        if (!Double.isFinite(value)) {
            throw malformed("a coordinate is not finite");
        }
        return value;
    }

    private boolean consume(char expected) {
        if (position < lineEnd && buffer[position] == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void skipSpaces() {
        while (position < lineEnd && isSpace(buffer[position])) {
            position++;
        }
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\r';
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private UnrecognizedPackerOutputException malformed(String problem) {
        return new UnrecognizedPackerOutputException("Line " + lineNumber + " of the packing result: " + problem);
    }

    interface Sink {

        void object(String name);

        void vertex(double x, double y, double z);

        void triangle(int a, int b, int c);
    }
}
