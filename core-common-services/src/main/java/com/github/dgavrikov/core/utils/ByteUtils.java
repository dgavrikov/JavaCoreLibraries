package com.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.io.buffer.DataBuffer;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

@UtilityClass
@Slf4j
public class ByteUtils {
    public static byte[] extractBytesAndReset(final DataBuffer data) {
        final var bytes = new byte[data.readableByteCount()];
        data.read(bytes);
        data.readPosition(0);
        return bytes;
    }

    /**
     * Compresses a byte array using the DEFLATE algorithm.
     *
     * @param input The byte array to be compressed.
     * @return The compressed byte array, or null if input is empty or invalid.
     */
    public static byte @Nullable [] compress(byte @Nullable [] input) {
        if (input == null || input.length == 0)
            return null;

        try (var outputStream = new ByteArrayOutputStream(input.length)) {
            var deflater = new Deflater();
            try {
                deflater.setInput(input);
                deflater.finish();

                var buffer = new byte[4096];
                while (!deflater.finished()) {
                    int compressedSize = deflater.deflate(buffer);
                    outputStream.write(buffer, 0, compressedSize);
                }

                return outputStream.toByteArray();
            } finally {
                deflater.end();
            }
        } catch (Exception e) {
            log.error("Failed to compress data.", e);
            return null;
        }
    }

    /**
     * Decompresses a byte array previously compressed using the DEFLATE algorithm.
     *
     * @param input The compressed byte array.
     * @return The decompressed byte array, or null if input is empty or invalid.
     */
    public static byte @Nullable [] decompress(byte @Nullable [] input) {
        if (input == null || input.length == 0) return null;

        try (var outputStream = new ByteArrayOutputStream(input.length)) {
            var inflater = new Inflater();
            try {
                inflater.setInput(input);

                var buffer = new byte[4096];
                while (!inflater.finished()) {
                    int decompressSize = inflater.inflate(buffer);
                    outputStream.write(buffer, 0, decompressSize);
                }

                return outputStream.toByteArray();
            } finally {
                inflater.end();
            }
        } catch (Exception e) {
            log.error("Failed to decompress data.", e);
            return null;
        }
    }
}
