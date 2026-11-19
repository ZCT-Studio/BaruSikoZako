// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio
import com.github.luben.zstd.ZstdInputStream;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
class VerifyStatic {
 static byte[] digest(java.io.InputStream stream) throws Exception {
  try (stream) {var hash=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[65536]; for(int n;(n=stream.read(b))!=-1;) hash.update(b,0,n);return hash.digest();}
 }
 public static void main(String[] args) throws Exception {
  byte[] expected=digest(Files.newInputStream(Path.of(args[0])));
  for (int i=0;i<6;i++) {
   try(var z=new ZipFile(Path.of(args[1],"zip-"+i+".zip").toFile())) {
    if(!Arrays.equals(expected,digest(z.getInputStream(z.getEntry("world.tar"))))) throw new Exception("ZIP mismatch");
   }
   if(!Arrays.equals(expected,digest(new ZstdInputStream(Files.newInputStream(Path.of(args[1],"zstd-"+i+".tar.zst")))))) throw new Exception("Zstd mismatch");
  }
  System.out.println(HexFormat.of().formatHex(expected)+"  all 12 decompressions match");
 }
}
