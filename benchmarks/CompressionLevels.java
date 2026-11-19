// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio
import com.github.luben.zstd.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
class CompressionLevels {
 public static void main(String[] args) throws Exception {
  String action=args[0], mode=args[1]; int level=Integer.parseInt(args[2]);
  Path source=Path.of(args[3]), target=Path.of(args[4]); long start=System.nanoTime();
  if(action.equals("compress")) {
   try(var input=Files.newInputStream(source); var file=Files.newOutputStream(target)) {
    if(mode.equals("zstd")) {try(var output=new ZstdOutputStream(file,level)){input.transferTo(output);}}
    else {try(var output=new ZipOutputStream(file)){output.setLevel(level);output.putNextEntry(new ZipEntry("world.tar"));input.transferTo(output);output.closeEntry();}}
   }
   System.out.printf("bytes=%d ms=%d%n",Files.size(target),(System.nanoTime()-start)/1000000);
  } else {
   MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536];
   try(var file=Files.newInputStream(target); var input=mode.equals("zstd") ? new ZstdInputStream(file) : new ZipInputStream(file)) {
    if(input instanceof ZipInputStream zip && zip.getNextEntry()==null) throw new IOException("ZIP entry missing");
    for(int n;(n=input.read(buffer))!=-1;) digest.update(buffer,0,n);
   }
   System.out.printf("sha256=%s ms=%d%n",HexFormat.of().formatHex(digest.digest()),(System.nanoTime()-start)/1000000);
  }
 }
}
