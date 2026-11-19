// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio
import com.github.luben.zstd.ZstdInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Streams a Zstd frame to stdout for the independent TAR verifier. */
class DecodeZstd {
    public static void main(String[] args) throws Exception {
        try (var source = new ZstdInputStream(Files.newInputStream(Path.of(args[0])))) {
            source.transferTo(System.out);
            System.out.flush();
        }
    }
}
