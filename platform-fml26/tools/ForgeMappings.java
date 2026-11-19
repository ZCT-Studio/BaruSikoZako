// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

import java.io.File;
import java.nio.file.Path;
import net.minecraftforge.srgutils.IMappingFile;

final class ForgeMappings {
    public static void main(String[] args) throws Exception {
        var officialToObfuscated = IMappingFile.load(new File(args[0]));
        var obfuscatedToSrg = IMappingFile.load(new File(args[1]));
        officialToObfuscated.chain(obfuscatedToSrg).write(Path.of(args[2]), IMappingFile.Format.TSRG, false);
    }
}
