// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 用于审计的 Paper 和 Purpur 26.3 Moonrise 二进制文件的被动写入生成挂钩 */
final class MoonriseSaveHooks implements ClassFileTransformer {
    private static final String TASK = "ca/spottedleaf/moonrise/patches/chunk_system/io/MoonriseRegionFileIO$ChunkIOTask";
    private static final String WRITE = TASK + "$InProgressWrite";
    private static final String WRITE_ARG = "L" + WRITE + ";";
    private static final String SIGNAL = "io/github/zct_studio/barusikozako/agent/instrument/MoonriseSaveSignals";
    private static final String GENERATION = "barusikozako$generation";
    private static final String PREVIOUS = "barusikozako$previous";
    private static final String PAPER_CHUNK_MAP = "fdcadb97fe3617f135c9986d064551c9d912f31619d4abc5dae1e1ea01263319";
    private static final Map<String, String> DIGESTS = Map.of(
        TASK, "abc4368464dd08b9eea249391313d52cb24fc0dd12dc0db1c418317d23d41318",
        WRITE, "019137114bebc2b6c867419b30e1d00d688b48fc4519e15a66fcabb266566297",
        MoonriseProducerHooks.HOLDER, "910fde886bea4c6f85baeb9b3a2d2f8a9fc3739a1f9365976ed5bdb951208ed0",
        MoonriseProducerHooks.MANAGER, "bb8bbc978f9c2df7adc22676d7e7acdbb945d45ed7216cd4b754318db5e271e6",
        MoonriseProducerHooks.UNLOAD, "faff5bf8d60f2013e8c2b44d7b6622ea47f3cd1acd60dc6427fa52de60e2201d");
    private static final Map<String, String> STATUS = new ConcurrentHashMap<>();

    static Map<String, String> status() { return Map.copyOf(STATUS); }

    static boolean active() {
        return DIGESTS.keySet().stream().allMatch(name -> "observed".equals(STATUS.get(name)));
    }

    static boolean paperDirectWriteObserved() {
        return "observed".equals(STATUS.get(MoonriseProducerHooks.CHUNK_MAP));
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] bytes) {
        if (name == null || redefining != null) return null;
        String expected = MoonriseProducerHooks.CHUNK_MAP.equals(name) ? PAPER_CHUNK_MAP : DIGESTS.get(name);
        if (expected == null) return null;
        try {
            if (MoonriseProducerHooks.CHUNK_MAP.equals(name) && !paper256(domain)) return null;
            if (!expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                STATUS.put(name, "unaudited");
                return null;
            }
            var transformed = name.equals(TASK) || name.equals(WRITE)
                ? transformAudited(name, bytes) : MoonriseProducerHooks.transform(name, bytes);
            STATUS.put(name, "observed");
            return transformed;
        } catch (RuntimeException | NoSuchAlgorithmException | IOException | URISyntaxException failure) {
            STATUS.put(name, "unavailable");
            System.getLogger(MoonriseSaveHooks.class.getName()).log(System.Logger.Level.WARNING,
                "Failed to install Moonrise write observation for " + name, failure);
            return null;
        }
    }

    private static boolean paper256(ProtectionDomain domain) throws IOException, URISyntaxException {
        var source = domain == null ? null : domain.getCodeSource();
        var location = source == null ? null : source.getLocation();
        if (location == null || !location.getProtocol().equals("file") || !location.getPath().endsWith(".jar"))
            return false;
        try (var archive = new JarFile(Path.of(location.toURI()).toFile(), false)) {
            var manifest = archive.getManifest();
            if (manifest == null) return false;
            var attrs = manifest.getMainAttributes();
            return "papermc:paper".equals(attrs.getValue("Brand-Id"))
                && "26.3-256-1eda666".equals(attrs.getValue("Implementation-Version"));
        }
    }

    private static byte[] transformAudited(String owner, byte[] bytes) {
        boolean generationClass = owner.equals(WRITE);
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] sites = new int[7];
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                int flags = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
                if (generationClass) flags |= Opcodes.ACC_FINAL;
                super.visitField(flags, generationClass ? GENERATION : PREVIOUS, "J", null, null).visitEnd();
                super.visitEnd();
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private final boolean compress = !generationClass && name.equals("performWriteCompress") && descriptor.equals("(" + WRITE_ARG + ")V");
                    private final boolean write = !generationClass && name.equals("runWriteIO") && descriptor.equals("(" + WRITE_ARG + ")V");

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (generationClass && name.equals("complete") &&
                                descriptor.equals("(L" + TASK + ";Lnet/minecraft/nbt/CompoundTag;Ljava/lang/Throwable;)V")) {
                            generation(0);
                            super.visitVarInsn(Opcodes.ALOAD, 3);
                            signal("payload", "(JLjava/lang/Throwable;)V");
                            sites[1]++;
                        }
                        if (!generationClass && name.equals("pushPendingWrite") && descriptor.equals("(" + WRITE_ARG + ")V")) {
                            generation(1);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, TASK, PREVIOUS, "J");
                            signal("submitted", "(JJ)V");
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            generation(1);
                            super.visitFieldInsn(Opcodes.PUTFIELD, TASK, PREVIOUS, "J");
                            sites[0]++;
                        }
                        if (compress || write) {
                            generation(1);
                            signal(compress ? "compress" : "write", "(J)V");
                            sites[compress ? 2 : 3]++;
                        }
                    }

                    @Override
                    public void visitVarInsn(int opcode, int variable) {
                        super.visitVarInsn(opcode, variable);
                        if (!generationClass && name.equals("scheduleWriteCompress") && descriptor.equals("()V") &&
                                opcode == Opcodes.ASTORE && variable == 1) {
                            generation(1);
                            signal("compressionScheduled", "(J)V");
                            sites[4]++;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String target, String called, String type, boolean iface) {
                        if ((compress || write) && target.equals("org/slf4j/Logger") && called.equals("error") &&
                                type.equals("(Ljava/lang/String;Ljava/lang/Throwable;)V")) {
                            super.visitInsn(Opcodes.DUP);
                            generation(1);
                            super.visitLdcInsn(compress ? "compression" : "write");
                            signal("failed", "(Ljava/lang/Throwable;JLjava/lang/String;)V");
                            sites[5]++;
                        }
                        super.visitMethodInsn(opcode, target, called, type, iface);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (generationClass && name.equals("<init>") && opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            signal("create", "()J");
                            super.visitFieldInsn(Opcodes.PUTFIELD, WRITE, GENERATION, "J");
                            sites[0]++;
                        }
                        if (!generationClass && name.equals("tryCompleteWrite") && descriptor.equals("(" + WRITE_ARG + "Z)Z") &&
                                opcode == Opcodes.IRETURN) {
                            super.visitInsn(Opcodes.DUP);
                            generation(1);
                            signal("complete", "(ZJ)V");
                            sites[6]++;
                        }
                        super.visitInsn(opcode);
                    }

                    private void generation(int local) {
                        super.visitVarInsn(Opcodes.ALOAD, local);
                        super.visitFieldInsn(Opcodes.GETFIELD, WRITE, GENERATION, "J");
                    }

                    private void signal(String method, String type) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SIGNAL, method, type, false);
                    }
                };
            }
        }, 0);
        var expected = generationClass ? new int[] {1, 1, 0, 0, 0, 0, 0} : new int[] {1, 0, 1, 1, 1, 3, 2};
        if (!java.util.Arrays.equals(sites, expected))
            throw new IllegalStateException("Unexpected Moonrise hook count: " + java.util.Arrays.toString(sites));
        return writer.toByteArray();
    }
}
