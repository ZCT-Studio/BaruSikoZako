// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.probe;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Test-only failure observation at audited 1.21.10 save call sites. */
public final class SaveObservationAgent {
    private static final String PROBE = "barusikozako/probe/bootstrap/SaveHooks";
    private static final String REPLACE = "(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;Z)Z";
    private static final String RETRY = "(ILjava/lang/String;[Ljava/util/function/BooleanSupplier;)Z";
    private static final Set<String> REPLACE_CLASSES = Set.of("net/minecraft/Util", "net/minecraft/SystemUtils");
    private static final Set<String> SAVED_DATA_CLASSES = Set.of(
        "net/minecraft/world/level/storage/DimensionDataStorage", "net/minecraft/world/level/storage/WorldPersistentData");
    private static final Set<String> AUDITED_CLASSES = Set.of(
        "bc15dcc00b9660e953fcd1237b1520dfd7996e53346dd5578f57299bfc4b7c39",
        "5dd1942bbf602135ad2ea1db5330e34d2829c35eb860632f0bb7722744e43cfa",
        "9d6542cf54f89ab92e48cedcb222dda76205d06d8bdc14639e87ede8772e37ce",
        "abe8653aeff490ac1bc19890326f296cc71cbd97c0645685838e6651490b1240");

    private SaveObservationAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        try {
            Class.forName(PROBE.replace('/', '.'), false, null);
        } catch (ClassNotFoundException missingHooks) {
            System.err.println("ZAKO_BARRIER_PROBE_UNAVAILABLE save-hooks.jar must be next to the agent JAR");
            return;
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                                    ProtectionDomain domain, byte[] bytes) {
                if (name == null || redefining != null) return null;
                boolean replacement = REPLACE_CLASSES.contains(name);
                if (!replacement && !SAVED_DATA_CLASSES.contains(name)) return null;
                try {
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!AUDITED_CLASSES.contains(digest)) return null;
                    return instrument(name, bytes, replacement);
                } catch (Exception failure) {
                    System.err.println("ZAKO_BARRIER_PROBE_UNAVAILABLE " + name + ": " + failure);
                    return null;
                }
            }
        });
    }

    private static byte[] instrument(String name, byte[] bytes, boolean replacement) {
        var reader = new ClassReader(bytes);
        var output = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] sites = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, output) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                                             String[] exceptions) {
                var visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (replacement && (!descriptor.equals(REPLACE) || (access & Opcodes.ACC_STATIC) == 0)) return visitor;
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    private int retries;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                        // The first three calls are backup, removal and install. The fourth can restore the original.
                        if (replacement && opcode == Opcodes.INVOKESTATIC && owner.equals(name)
                            && desc.equals(RETRY) && ++retries <= 3) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "replacementResult",
                                "(ZLjava/nio/file/Path;)V", false);
                            sites[0]++;
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (!replacement && "Could not save data to {}".equals(value)) {
                            super.visitVarInsn(Opcodes.ALOAD, 3); // Path local in both audited tryWrite bodies.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, "savedDataFailure",
                                "(Ljava/nio/file/Path;)V", false);
                            sites[0]++;
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }
        }, 0);
        if (sites[0] != (replacement ? 3 : 1)) throw new IllegalStateException("Unexpected audited hook count");
        System.out.println("ZAKO_BARRIER_PROBE_BOUND " + name + " sites=" + sites[0]);
        return output.toByteArray();
    }
}
