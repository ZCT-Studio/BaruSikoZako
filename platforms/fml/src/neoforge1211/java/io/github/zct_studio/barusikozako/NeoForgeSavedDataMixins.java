// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

public final class NeoForgeSavedDataMixins implements IMixinConfigPlugin {
    enum Mode { UNKNOWN, SYNCHRONOUS, ASYNCHRONOUS }

    private static final String IO = "net/neoforged/neoforge/common/IOUtilities";
    private static final String SAVE = "(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V";
    private static final String WRITE = "(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V";
    private static final String ERROR = "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static volatile Mode mode = Mode.UNKNOWN;

    static Mode mode() {
        return mode;
    }

    @Override
    public List<String> getMixins() {
        try {
            mode = detectMode();
        } catch (ClassNotFoundException | IOException exception) {
            throw new IllegalStateException("Cannot inspect NeoForge SavedData save path", exception);
        }
        return switch (mode) {
            case SYNCHRONOUS -> List.of("SavedDataObservation");
            case ASYNCHRONOUS -> List.of("NeoForgeAsyncSavedDataObservation", "NeoForgeSaveQueue");
            case UNKNOWN -> throw new IllegalStateException("Unrecognized NeoForge SavedData save path");
        };
    }

    private static Mode detectMode() throws ClassNotFoundException, IOException {
        var bytecode = MixinService.getService().getBytecodeProvider();
        ClassNode savedData = bytecode.getClassNode("net.minecraft.world.level.saveddata.SavedData");
        MethodNode save = method(savedData, "save", SAVE);
        if (save == null) {
            return Mode.UNKNOWN;
        }
        boolean submits = invokes(save, IO, "withIOWorker", "(Ljava/lang/Runnable;)V");
        if (!submits) {
            return observesWrite(save) ? Mode.SYNCHRONOUS : Mode.UNKNOWN;
        }
        MethodNode write = method(savedData, "lambda$save$0", "(Lnet/minecraft/nbt/CompoundTag;Ljava/io/File;)V");
        ClassNode io = bytecode.getClassNode(IO.replace('/', '.'));
        boolean hasQueue = io.fields.stream().anyMatch(field -> field.name.equals("saveDataTasks")
            && field.desc.equals("Ljava/util/concurrent/CompletableFuture;") && (field.access & Opcodes.ACC_STATIC) != 0);
        return write != null && observesWrite(write) && hasQueue ? Mode.ASYNCHRONOUS : Mode.UNKNOWN;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst().orElse(null);
    }

    private static boolean observesWrite(MethodNode method) {
        return invokes(method, IO, "writeNbtCompressed", WRITE)
            && invokes(method, "org/slf4j/Logger", "error", ERROR);
    }

    private static boolean invokes(MethodNode method, String owner, String name, String descriptor) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
