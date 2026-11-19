// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio
import com.github.luben.zstd.ZstdOutputStream;
import java.nio.file.*;
import java.util.zip.*;
class StaticCompression {
 public static void main(String[] args) throws Exception {
  Path input=Path.of(args[0]); Path output=Path.of(args[1]);
  for (int i=0;i<6;i++) for (String mode : i%2==0 ? new String[]{"zstd","zip"} : new String[]{"zip","zstd"}) {
   Path target=output.resolve(mode+"-"+i+(mode.equals("zip")?".zip":".tar.zst"));
   long start=System.nanoTime();
   if (mode.equals("zstd")) {
    try (var source=Files.newInputStream(input); var compressed=new ZstdOutputStream(Files.newOutputStream(target),3)) {source.transferTo(compressed);}
   } else {
    try (var source=Files.newInputStream(input); var compressed=new ZipOutputStream(Files.newOutputStream(target))) {
     compressed.setLevel(6); compressed.putNextEntry(new ZipEntry("world.tar")); source.transferTo(compressed); compressed.closeEntry();
    }
   }
   System.out.printf("%s,%d,%d,%d%n",mode,i,(System.nanoTime()-start)/1000000,Files.size(target));
  }
 }
}
