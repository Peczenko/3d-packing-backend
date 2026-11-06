package com.packing.backend.infra.packing.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

final class Glb {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAGIC        = 0x46546C67;
    private static final int VERSION      = 2;
    private static final int JSON_CHUNK   = 0x4E4F534A;
    private static final int BIN_CHUNK    = 0x004E4942;
    private static final int HEADER_BYTES = 12;
    private static final int CHUNK_HEADER = 8;

    private Glb() {
    }

    static Container open(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer header = read(channel, 0, HEADER_BYTES);
            if (header.getInt() != MAGIC || header.getInt() != VERSION) {
                throw new IOException(path + " is not a glTF 2.0 binary");
            }
            long length = Integer.toUnsignedLong(header.getInt());
            if (length != channel.size()) {
                throw new IOException(path + " declares " + length + " bytes but holds " + channel.size());
            }
            ByteBuffer jsonHeader = read(channel, HEADER_BYTES, CHUNK_HEADER);
            int jsonLength = jsonHeader.getInt();
            if (jsonHeader.getInt() != JSON_CHUNK) {
                throw new IOException(path + " does not start with a JSON chunk");
            }
            ByteBuffer jsonBytes = read(channel, HEADER_BYTES + CHUNK_HEADER, jsonLength);
            JsonNode json = JSON.readTree(Arrays.copyOfRange(jsonBytes.array(), 0, jsonLength));
            if (!(json instanceof ObjectNode object)) {
                throw new IOException(path + " has a JSON chunk that is not an object");
            }
            long binOffset = 0;
            long binLength = 0;
            long next = HEADER_BYTES + CHUNK_HEADER + (long) jsonLength;
            if (next < length) {
                ByteBuffer binHeader = read(channel, next, CHUNK_HEADER);
                binLength = Integer.toUnsignedLong(binHeader.getInt());
                if (binHeader.getInt() != BIN_CHUNK) {
                    throw new IOException(path + " has a second chunk that is not BIN");
                }
                binOffset = next + CHUNK_HEADER;
                if (binOffset + binLength > length) {
                    throw new IOException(path + " has a truncated BIN chunk");
                }
            }
            return new Container(path, object, binOffset, binLength);
        }
    }

    static void write(Path path, ObjectNode json, long binLength, BinWriter bin) throws IOException {
        byte[] jsonBytes = JSON.writeValueAsBytes(json);
        int jsonPadded = pad(jsonBytes.length);
        long binPadded = pad(binLength);
        long total = HEADER_BYTES + CHUNK_HEADER + (long) jsonPadded + (binLength > 0 ? CHUNK_HEADER + binPadded : 0);
        if (total > 0xFFFFFFFFL) {
            throw new IOException("A glTF binary cannot exceed 4 GiB");
        }
        try (FileChannel channel = FileChannel.open(path,
                                                    StandardOpenOption.CREATE,
                                                    StandardOpenOption.TRUNCATE_EXISTING,
                                                    StandardOpenOption.WRITE)) {
            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES + CHUNK_HEADER + jsonPadded)
                                        .order(ByteOrder.LITTLE_ENDIAN);
            head.putInt(MAGIC)
                .putInt(VERSION)
                .putInt((int) total)
                .putInt(jsonPadded)
                .putInt(JSON_CHUNK)
                .put(jsonBytes);
            while (head.position() < head.limit()) {
                head.put((byte) ' ');
            }
            writeFully(channel, head.flip());
            if (binLength == 0) {
                return;
            }
            writeFully(channel,
                       ByteBuffer.allocate(CHUNK_HEADER)
                                 .order(ByteOrder.LITTLE_ENDIAN)
                                 .putInt((int) binPadded)
                                 .putInt(BIN_CHUNK)
                                 .flip());
            long start = channel.position();
            bin.writeTo(channel);
            long written = channel.position() - start;
            if (written != binLength) {
                throw new IOException("Expected " + binLength + " BIN bytes but wrote " + written);
            }
            writeFully(channel, ByteBuffer.allocate((int) (binPadded - binLength)));
        }
    }

    static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    static int pad(int length) {
        return (length + 3) & ~3;
    }

    static long pad(long length) {
        return (length + 3) & ~3L;
    }

    private static ByteBuffer read(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length)
                                      .order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("Unexpected end of glTF binary");
            }
        }
        return buffer.flip();
    }

    record Container(Path path, ObjectNode json, long binOffset, long binLength) {

        void copyBinTo(FileChannel target) throws IOException {
            try (FileChannel source = FileChannel.open(path, StandardOpenOption.READ)) {
                long copied = 0;
                while (copied < binLength) {
                    long moved = source.transferTo(binOffset + copied, binLength - copied, target);
                    if (moved <= 0) {
                        throw new IOException("Could not copy the BIN chunk of " + path);
                    }
                    copied += moved;
                }
            }
        }
    }

    @FunctionalInterface
    interface BinWriter {

        void writeTo(FileChannel channel) throws IOException;
    }
}
