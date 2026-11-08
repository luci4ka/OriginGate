package io.github.origingate.core.lookup.maxmind;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Takes one file out of a .tar.gz archive (ustar or GNU format, as MaxMind sends). Other entries are skipped. */
final class TarGz {
    private static final int BLOCK = 512;

    private TarGz() { }

    /** Copies the first regular file whose name ends with {@code suffix} to {@code target}. */
    static void extract(InputStream input, String suffix, Path target, long maxBytes) throws IOException {
        try (InputStream tar = new GZIPInputStream(input)) {
            byte[] header = new byte[BLOCK];
            while (readHeader(tar, header)) {
                String name = field(header, 0, 100);
                // Only POSIX ustar ("ustar" then a zero byte) uses the prefix field; GNU stores other data there.
                if (field(header, 257, 6).equals("ustar") && header[262] == 0) {
                    String prefix = field(header, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                if ((type == '0' || type == '\0') && name.endsWith(suffix)) {
                    if (size > maxBytes) throw new IOException("The archive entry " + name + " is larger than " + maxBytes + " bytes");
                    try (OutputStream output = Files.newOutputStream(target)) {
                        copy(tar, output, size);
                    }
                    return;
                }
                tar.skipNBytes((size + BLOCK - 1) / BLOCK * BLOCK);
            }
        }
        throw new IOException("The archive has no " + suffix + " file");
    }

    /** Reads the next header. Returns false at the end of the archive (an all-zero block or a clean end of stream). */
    private static boolean readHeader(InputStream tar, byte[] header) throws IOException {
        int read = tar.readNBytes(header, 0, BLOCK);
        if (read == 0) return false;
        if (read < BLOCK) throw new EOFException("The archive ends in the middle of a header");
        for (byte value : header) {
            if (value != 0) return true;
        }
        return false;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] header, int offset, int length) throws IOException {
        if ((header[offset] & 0x80) != 0) throw new IOException("Archive entries this large are not supported");
        long value = 0;
        boolean started = false;
        for (int i = offset; i < offset + length; i++) {
            byte c = header[i];
            if (c == ' ' || c == 0) {
                if (started) break;
                continue;
            }
            if (c < '0' || c > '7') throw new IOException("The archive has an invalid size field");
            value = value * 8 + (c - '0');
            started = true;
        }
        return value;
    }

    private static void copy(InputStream input, OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = size;
        while (left > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) throw new EOFException("The archive ends in the middle of a file");
            output.write(buffer, 0, read);
            left -= read;
        }
    }
}
