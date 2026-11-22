// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/** 关闭审核的同步保存范围并在阶段之间携带卸载范围 */
final class MoonriseProducerHooks {
    static final String HOLDER = "ca/spottedleaf/moonrise/patches/chunk_system/scheduling/NewChunkHolder";
    static final String UNLOAD = HOLDER + "$UnloadState";
    static final String MANAGER = "ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager";
    static final String CHUNK_MAP = "net/minecraft/server/level/ChunkMap";
    private static final String DIRECT_WRITE =
        "(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;";
    private static final String SIGNAL = "io/github/zct_studio/barusikozako/agent/instrument/MoonriseSaveSignals";
    private static final String TOKEN = "barusikozako$producer";
    private static final String QUEUED = "barusikozako$queuedUnload";
    private static final String REMOVE = "barusikozako$removeQueuedUnload";
    private static final String QUEUE = "ca/spottedleaf/moonrise/patches/chunk_system/queue/ChunkUnloadQueue";
    private static final String PAYLOADS = ";Z[Lca/spottedleaf/concurrentutil/completable/Completable;)Z";
    private static final Map<String, String> SIGNATURES = Map.of(
        "saveChunk", "(Lnet/minecraft/world/level/chunk/ChunkAccess" + PAYLOADS,
        "saveEntities", "(Lca/spottedleaf/moonrise/patches/chunk_system/level/entity/ChunkEntitySlices" + PAYLOADS,
        "savePOI", "(Lca/spottedleaf/moonrise/patches/chunk_system/level/poi/PoiChunk" + PAYLOADS,
        "unloadStage1", "()L" + UNLOAD + ";",
        "unloadStage2", "(L" + UNLOAD + ";)V"
    );

    private MoonriseProducerHooks() {}

    static byte[] transform(String owner, byte[] bytes) {
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        var wrappers = new ArrayList<Method>();
        int[] unloadConstructors = {0};
        int[] producerErrors = {0};
        int[] enqueueSites = {0};
        int[] removeSites = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                boolean directWrite = CHUNK_MAP.equals(owner) && name.equals("write") && descriptor.equals(DIRECT_WRITE);
                if (directWrite || (HOLDER.equals(owner) && descriptor.equals(SIGNATURES.get(name)))) {
                    if ((access & (ACC_STATIC | ACC_SYNCHRONIZED | ACC_ABSTRACT | ACC_NATIVE)) != 0)
                        throw new IllegalStateException("Unexpected Moonrise producer method flags: " + name);
                    var original = new Method(access, name, descriptor, signature, exceptions);
                    wrappers.add(original);
                    int privateAccess = (access & ~(ACC_PUBLIC | ACC_PROTECTED)) | ACC_PRIVATE | ACC_SYNTHETIC;
                    var body = super.visitMethod(privateAccess, original.bodyName(), descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, body) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean iface) {
                            if (owner.equals("org/slf4j/Logger") && name.equals("error") &&
                                    descriptor.equals("(Ljava/lang/String;Ljava/lang/Throwable;)V")) {
                                super.visitInsn(DUP);
                                signal(this, "producerFailure", "(Ljava/lang/Throwable;)V");
                                producerErrors[0]++;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, iface);
                        }
                    };
                }
                var visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (HOLDER.equals(owner) && name.equals("checkUnload") && descriptor.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean iface) {
                            boolean queueMethod = owner.equals(QUEUE) && descriptor.equals("(II)Z");
                            if (queueMethod && name.equals("addChunk")) {
                                super.visitVarInsn(ALOAD, 0);
                                super.visitVarInsn(ALOAD, 0);
                                super.visitFieldInsn(GETFIELD, HOLDER, QUEUED, "J");
                                signal(this, "queueUnload", "(J)J");
                                super.visitFieldInsn(PUTFIELD, HOLDER, QUEUED, "J");
                                enqueueSites[0]++;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, iface);
                            if (queueMethod && name.equals("removeChunk")) {
                                super.visitInsn(DUP);
                                super.visitVarInsn(ALOAD, 0);
                                super.visitInsn(SWAP);
                                super.visitMethodInsn(INVOKEVIRTUAL, HOLDER, REMOVE, "(Z)V", false);
                                removeSites[0]++;
                            }
                        }
                    };
                }
                if (MANAGER.equals(owner) && name.equals("processUnloads") && descriptor.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean iface) {
                            if (owner.equals("org/slf4j/Logger") && name.equals("error") && descriptor.equals("(Ljava/lang/String;)V")) {
                                super.visitVarInsn(ALOAD, 18); // Audited holder dequeued but rejected by isSafeToUnload.
                                super.visitInsn(ICONST_0);
                                super.visitMethodInsn(INVOKEVIRTUAL, HOLDER, REMOVE, "(Z)V", false);
                                removeSites[0]++;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, iface);
                        }
                    };
                }
                if (!UNLOAD.equals(owner) || !name.equals("<init>")) return visitor;
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == RETURN) {
                            super.visitVarInsn(ALOAD, 0);
                            signal(this, "currentProducer", "()J");
                            super.visitFieldInsn(PUTFIELD, UNLOAD, TOKEN, "J");
                            unloadConstructors[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (UNLOAD.equals(owner)) {
                    super.visitField(ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC, TOKEN, "J", null, null).visitEnd();
                } else if (HOLDER.equals(owner)) {
                    super.visitField(ACC_PRIVATE | ACC_SYNTHETIC, QUEUED, "J", null, null).visitEnd();
                    var remove = super.visitMethod(ACC_SYNTHETIC, REMOVE, "(Z)V", null, null);
                    remove.visitCode();
                    remove.visitVarInsn(ILOAD, 1);
                    remove.visitVarInsn(ALOAD, 0);
                    remove.visitFieldInsn(GETFIELD, HOLDER, QUEUED, "J");
                    signal(remove, "removeQueuedUnload", "(ZJ)V");
                    clearQueued(remove);
                    remove.visitInsn(RETURN);
                    remove.visitMaxs(0, 0);
                    remove.visitEnd();
                }
                for (var method : wrappers) {
                    emit(super.visitMethod(method.access, method.name, method.descriptor, method.signature, method.exceptions),
                        owner, method);
                }
                super.visitEnd();
            }
        }, ClassReader.EXPAND_FRAMES);
        boolean complete = switch (owner) {
            case HOLDER -> wrappers.size() == SIGNATURES.size() && producerErrors[0] == 3 &&
                enqueueSites[0] == 1 && removeSites[0] == 1;
            case MANAGER -> removeSites[0] == 1;
            case UNLOAD -> unloadConstructors[0] == 1;
            case CHUNK_MAP -> wrappers.size() == 1;
            default -> false;
        };
        if (!complete)
            throw new IllegalStateException("Unexpected Moonrise producer hooks: " + wrappers.size() + "/" + producerErrors[0]);
        return writer.toByteArray();
    }

    private static void emit(MethodVisitor mv, String owner, Method method) {
        var arguments = Type.getArgumentTypes(method.descriptor);
        int token = arguments.length + 1; // All audited parameters are single-slot references or booleans.
        var start = new Label();
        var end = new Label();
        var error = new Label();
        mv.visitCode();
        mv.visitTryCatchBlock(start, end, error, "java/lang/Throwable");
        if (method.name.equals("unloadStage2")) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, UNLOAD, TOKEN, "J");
            signal(mv, "resumeUnload", "(J)J");
        } else if (method.name.equals("unloadStage1")) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, HOLDER, QUEUED, "J");
            signal(mv, "beginQueuedUnload", "(J)J");
            clearQueued(mv);
        } else signal(mv, "openProducer", "()J");
        mv.visitVarInsn(LSTORE, token);

        mv.visitLabel(start);
        mv.visitVarInsn(ALOAD, 0);
        List<Object> locals = new ArrayList<>();
        locals.add(owner);
        for (int index = 0; index < arguments.length; index++) {
            var argument = arguments[index];
            int load = argument.getOpcode(ILOAD);
            mv.visitVarInsn(load, index + 1);
            locals.add(load == ILOAD ? INTEGER : argument.getInternalName());
        }
        mv.visitMethodInsn(INVOKESPECIAL, owner, method.bodyName(), method.descriptor, false);
        mv.visitLabel(end);
        if (method.name.equals("unloadStage1")) {
            mv.visitInsn(DUP);
            mv.visitVarInsn(LLOAD, token);
            signal(mv, "suspendUnload", "(Ljava/lang/Object;J)V");
        } else {
            mv.visitVarInsn(LLOAD, token);
            mv.visitInsn(ACONST_NULL);
            signal(mv, "finishProducer", "(JLjava/lang/Throwable;)V");
        }
        mv.visitInsn(Type.getReturnType(method.descriptor).getOpcode(IRETURN));

        mv.visitLabel(error);
        locals.add(LONG);
        mv.visitFrame(F_NEW, locals.size(), locals.toArray(), 1, new Object[] {"java/lang/Throwable"});
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, token + 2);
        mv.visitVarInsn(LLOAD, token);
        mv.visitVarInsn(ALOAD, token + 2);
        signal(mv, "finishProducer", "(JLjava/lang/Throwable;)V");
        mv.visitInsn(ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void signal(MethodVisitor mv, String name, String descriptor) {
        mv.visitMethodInsn(INVOKESTATIC, SIGNAL, name, descriptor, false);
    }

    private static void clearQueued(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(LCONST_0);
        mv.visitFieldInsn(PUTFIELD, HOLDER, QUEUED, "J");
    }

    private record Method(int access, String name, String descriptor, String signature, String[] exceptions) {
        String bodyName() { return "barusikozako$" + name; }
    }
}
