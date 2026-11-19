// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.zstd;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.tar.TarUtils;

/** Validates the restricted TAR metadata emitted by the snapshot writer before the library applies it. */
final class GuardedTarInputStream extends TarArchiveInputStream {
    private static final int MAX_PAX_BYTES = 64 * 1024;
    private static final int SIZE_OFFSET = TarConstants.NAMELEN + TarConstants.MODELEN + TarConstants.UIDLEN +
        TarConstants.GIDLEN;
    private static final Set<String> PAX_KEYS = Set.of("path", "size", "mtime", "atime", "ctime", "uid", "gid");
    private final PushbackInputStream data;
    private boolean insideEntry;
    private boolean seenExtension;

    GuardedTarInputStream(InputStream source) {
        this(new PushbackInputStream(source, MAX_PAX_BYTES));
    }

    private GuardedTarInputStream(PushbackInputStream source) {
        super(source, "UTF-8");
        data = source;
    }

    @Override
    public TarArchiveEntry getNextEntry() throws IOException {
        if (insideEntry) return super.getNextEntry();
        seenExtension = false;
        insideEntry = true;
        try {
            return super.getNextEntry();
        } finally {
            insideEntry = false;
        }
    }

    @Override
    protected byte[] readRecord() throws IOException {
        byte[] header = super.readRecord();
        if (isEOFRecord(header)) return header;
        if (isAtEOF() || !TarUtils.verifyCheckSum(header)) throw new IOException("invalid TAR header checksum");
        switch (header[TarConstants.LF_OFFSET]) {
            case TarConstants.LF_OLDNORM, TarConstants.LF_NORMAL, TarConstants.LF_DIR -> { }
            case TarConstants.LF_PAX_EXTENDED_HEADER_LC -> examinePax(header);
            default -> throw new IOException("unsupported TAR record type");
        }
        return header;
    }

    private void examinePax(byte[] header) throws IOException {
        if (seenExtension) throw new IOException("multiple TAR extension records for one entry");
        seenExtension = true;
        long size;
        try {
            size = TarUtils.parseOctalOrBinary(header, SIZE_OFFSET, TarConstants.SIZELEN);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid TAR extension size", invalid);
        }
        if (size < 1 || size > MAX_PAX_BYTES) throw new IOException("TAR extension exceeds the metadata budget");
        var fields = data.readNBytes((int) size);
        if (fields.length != size) throw new IOException("truncated TAR extension");
        checkPaxFields(fields);
        data.unread(fields);
    }

    private static void checkPaxFields(byte[] fields) throws IOException {
        var seen = new HashSet<String>();
        for (int offset = 0; offset < fields.length;) {
            int index = offset;
            int length = 0;
            while (index < fields.length && fields[index] >= '0' && fields[index] <= '9') {
                length = length * 10 + fields[index++] - '0';
                if (length > fields.length - offset) throw new IOException("invalid TAR extension field length");
            }
            int end = offset + length;
            if (index >= end - 3 || fields[index++] != ' ' || fields[end - 1] != '\n')
                throw new IOException("invalid TAR extension field boundary");
            int startName = index;
            while (index < end - 1 && fields[index] != '=') index++;
            if (index == end - 1 || index - startName > 8) throw new IOException("invalid TAR extension field name");
            String name = new String(fields, startName, index - startName, StandardCharsets.US_ASCII);
            if (!PAX_KEYS.contains(name) || !seen.add(name)) throw new IOException("unsupported TAR extension field");
            offset = end;
        }
    }
}
