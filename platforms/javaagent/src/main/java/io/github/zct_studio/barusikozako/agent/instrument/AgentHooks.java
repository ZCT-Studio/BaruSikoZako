// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 被动保存失败指示器。这些挂钩不授予在线捕获功能 */
public final class AgentHooks {
    private static final String SIGNALS = "io/github/zct_studio/barusikozako/agent/instrument/AgentWriteSignals";
    private static final String CAPTURE = "io/github/zct_studio/barusikozako/agent/instrument/AgentCaptureSignals";
    private static final String SERVER_SIGNALS = "io/github/zct_studio/barusikozako/agent/instrument/AgentServerSignals";
    private static final String NAMED_SERVER = "net/minecraft/server/MinecraftServer";
    private static final String NAMED_UTIL = "net/minecraft/util/Util";
    private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
    private static final String NAMED_CHUNK_MAP = "net/minecraft/server/level/ChunkMap";
    private static final String NAMED_WORKER = "net/minecraft/world/level/chunk/storage/IOWorker";
    private static final String NAMED_SAVED_DATA = "net/minecraft/world/level/storage/SavedDataStorage";
    private static final String SERVER_12111 = "ab06f0176cbc8d2b7466332e2a3a5f6ae93af170ccad47e33622e9bc9ce069c7";
    private static final String DEDICATED = "net/minecraft/server/dedicated/DedicatedServer";
    private static final Map<String, String> OBSERVATIONS = new ConcurrentHashMap<>();
    private static final Map<ClassLoader, Set<String>> LOADER_OBSERVATIONS = new java.util.WeakHashMap<>();
    private static JarFile bootstrapJar;
    private static boolean installed;

    private AgentHooks() {}

    public static synchronized void install(Instrumentation instrumentation) throws IOException {
        if (installed) throw new IllegalStateException("Save observation is already installed");
        try {
            Class.forName(SIGNALS.replace('/', '.'), true, null);
        } catch (ClassNotFoundException missingSharedPayload) {
            appendStandaloneSignals(instrumentation);
        }
        instrumentation.addTransformer(new SaveTransformer());
        if (Boolean.getBoolean("barusikozako.agent.observeMoonrise")) {
            try {
                Class.forName("io.github.zct_studio.barusikozako.agent.instrument.MoonriseSaveSignals", true, null);
            } catch (ClassNotFoundException failure) {
                throw new IOException("Moonrise observation requires agent bootstrap contracts", failure);
            }
            instrumentation.addTransformer(new MoonriseSaveHooks());
        }
        installed = true;
    }

    private static void appendStandaloneSignals(Instrumentation instrumentation) throws IOException {
        var archive = Files.createTempFile("barusikozako-bootstrap-", ".jar");
        archive.toFile().deleteOnExit();
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            for (String type : List.of(SIGNALS, CAPTURE, CAPTURE + "$State", SERVER_SIGNALS)) {
                String entry = type + ".class";
                try (var input = AgentHooks.class.getResourceAsStream("/" + entry)) {
                    if (input == null) throw new IOException("Missing bootstrap save signal class: " + entry);
                    output.putNextEntry(new JarEntry(entry));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        // 在引导搜索路径的生命周期内保持 JarFile 打开
        bootstrapJar = new JarFile(archive.toFile());
        instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJar);
        try {
            Class.forName(SIGNALS.replace('/', '.'), true, null);
        } catch (ClassNotFoundException failure) {
            throw new IOException("Could not install bootstrap save signals", failure);
        }
    }

    public static synchronized long writeFailures() {
        return installed ? AgentWriteSignals.failures() : 0;
    }

    public static synchronized Optional<String> firstWriteFailure() {
        return installed ? Optional.ofNullable(AgentWriteSignals.firstFailure()) : Optional.empty();
    }

    /** 描述转换后的类和未经审核的输入。它不证明队列耗尽或文件稳定性 */
    public static Map<String, String> observations() {
        return Map.copyOf(OBSERVATIONS);
    }

    public static boolean observed(ClassLoader loader, String name) {
        synchronized (LOADER_OBSERVATIONS) {
            return LOADER_OBSERVATIONS.getOrDefault(loader, Set.of()).contains(name);
        }
    }

    public static String legacyQueueState() {
        return queueState(queueCut());
    }

    public static long queueCut() {
        return installed ? AgentCaptureSignals.cut() : -1;
    }

    public static String queueState(long cut) {
        return installed ? AgentCaptureSignals.state(cut).name() : "UNKNOWN";
    }

    public static String moonriseStatus() {
        return MoonriseSaveSignals.snapshot() + " hooks=" + MoonriseSaveHooks.status();
    }

    public static long moonriseCut() {
        return MoonriseSaveHooks.active() ? MoonriseSaveSignals.cut() : -1;
    }

    public static String moonriseState(long cut) {
        return MoonriseSaveHooks.active() ? MoonriseSaveSignals.state(cut) : "UNKNOWN";
    }

    /** Paper #256 诊断还要求生产者在 ChunkMap 的供应商之前启动 */
    public static String paperMoonriseState(long cut) {
        return MoonriseSaveHooks.paperDirectWriteObserved() ? moonriseState(cut) : "UNKNOWN";
    }

    /** 对审核后的 SavedData 未来以及吞没的世界/玩家写入错误进行诊断 */
    public static String paperWorldDataState(long cut) {
        if (!"observed".equals(OBSERVATIONS.get(NAMED_SAVED_DATA))) return "UNKNOWN";
        if (AgentWriteSignals.failures() != 0) return "FAILED";
        return AgentCaptureSignals.stateForAuditedHooks(cut).name();
    }

    private static final class SaveTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                                ProtectionDomain domain, byte[] bytes) {
            if (name == null || redefining != null || !AUDITS.containsKey(name)) return null;
            try {
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                var audit = AUDITS.get(name).stream().filter(candidate -> candidate.hashes().contains(digest))
                    .findFirst().orElse(null);
                if (audit == null) {
                    OBSERVATIONS.put(name, "unaudited");
                    return null;
                }
                var output = instrument(bytes, audit, name, MODERN_HASHES.contains(digest), SERVER_12111.equals(digest));
                synchronized (LOADER_OBSERVATIONS) {
                    LOADER_OBSERVATIONS.computeIfAbsent(loader, ignored -> new java.util.HashSet<>()).add(name);
                }
                OBSERVATIONS.put(name, "observed");
                return output;
            } catch (NoSuchAlgorithmException | RuntimeException failure) {
                OBSERVATIONS.put(name, "unavailable");
                System.getLogger(AgentHooks.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not install save observations for " + name, failure);
                return null;
            }
        }
    }

    private static byte[] instrument(byte[] bytes, Audit audit, String ownerClass, boolean modern, boolean legacyServer) {
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] sites = {0};
        int[] queueSites = {0};
        int[] lifecycleSites = {0};
        boolean lifecycle = ownerClass.equals(NAMED_SERVER);
        boolean console = ownerClass.equals(DEDICATED) || ownerClass.equals("ary");
        boolean replacements = ownerClass.equals(NAMED_UTIL) || ownerClass.equals("bhs");
        boolean chunkMap = ownerClass.equals("ahr") || ownerClass.equals("net/minecraft/server/level/PlayerChunkMap")
            || modern && (ownerClass.equals(NAMED_CHUNK_MAP) || ownerClass.equals("awj"));
        boolean ioWorker = ownerClass.equals("dfb") || ownerClass.equals(NAMED_WORKER) || ownerClass.equals("ert");
        boolean savedData = modern && (ownerClass.equals(NAMED_SAVED_DATA) || ownerClass.equals("fnc"));
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (audit.logCalls() && !(name.equals("run") && descriptor.equals("()V"))) {
                    return delegate;
                }
                boolean unload = chunkMap && name.equals("a") && (descriptor.equals("(JLahp;)V") ||
                    descriptor.equals("(JLnet/minecraft/server/level/PlayerChunk;)V")) ||
                    modern && chunkMap && name.equals("scheduleUnload") &&
                        descriptor.equals("(JLnet/minecraft/server/level/ChunkHolder;)V")
                    || ownerClass.equals("awj") && name.equals("a") && descriptor.equals("(JLawg;)V");
                boolean store = ioWorker && name.equals("a") && (descriptor.equals("(Lclt;Lqr;)" + FUTURE) ||
                    descriptor.equals("(Lnet/minecraft/world/level/ChunkCoordIntPair;Lnet/minecraft/nbt/NBTTagCompound;)" + FUTURE)) ||
                    modern && ioWorker && name.equals("store") &&
                        descriptor.equals("(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)" + FUTURE) ||
                    ownerClass.equals("ert") && name.equals("a") &&
                        descriptor.equals("(Ldvu;Ljava/util/function/Supplier;)" + FUTURE) ||
                    savedData && name.equals(ownerClass.equals("fnc") ? "a" : "scheduleSave") && descriptor.equals("()" + FUTURE);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (console && (ownerClass.equals("ary")
                                ? name.equals("a") && descriptor.equals("(Ljava/lang/String;Led;)V")
                                : name.equals("handleConsoleInput") &&
                                    descriptor.equals("(Ljava/lang/String;Lnet/minecraft/commands/CommandSourceStack;)V"))) {
                            var normal = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_SIGNALS, "console", "(Ljava/lang/String;)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, normal);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(normal);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            lifecycleSites[0]++;
                        }
                        if (lifecycle && name.equals(legacyServer ? "x" : "stopServer") && descriptor.equals("()V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_SIGNALS, "stopping", "()V", false);
                            lifecycleSites[0]++;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (lifecycle && name.equals(legacyServer ? "a" : "tickServer") &&
                            descriptor.equals("(Ljava/util/function/BooleanSupplier;)V") && opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_SIGNALS, "tick", "(Ljava/lang/Object;)V", false);
                            lifecycleSites[0]++;
                        }
                        if (replacements && name.equals(ownerClass.equals("bhs") ? "a" : "safeReplaceOrMoveFile") &&
                            descriptor.equals("(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;Z)Z") &&
                            opcode == Opcodes.IRETURN) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SIGNALS, "fileReplaced", "(Z)V", false);
                            lifecycleSites[0]++;
                        }
                        if (store && opcode == Opcodes.ARETURN) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, CAPTURE, "observeStore", "(" + FUTURE + ")V", false);
                            queueSites[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (audit.literals().contains(value)) observe();
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                                       Object... arguments) {
                        if (bootstrap.getOwner().equals("java/lang/invoke/StringConcatFactory")
                                && arguments.length > 0 && audit.literals().contains(arguments[0])) {
                            observe();
                        }
                        super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                boolean isInterface) {
                        String callback = modern ? "Ljava/lang/Runnable;" : "Ljava/util/function/Consumer;";
                        if (unload && owner.equals("java/util/concurrent/CompletableFuture") &&
                            name.equals(modern ? "thenRunAsync" : "thenAcceptAsync") &&
                            descriptor.equals("(" + callback + "Ljava/util/concurrent/Executor;)" + FUTURE)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, CAPTURE, "observeUnload",
                                "(" + FUTURE + descriptor.substring(1), false);
                            queueSites[0]++;
                            return;
                        }
                        // Paper 的区域任务在读取和写入 catch 分支中使用串联消息
                        if (audit.logCalls() && owner.equals("org/slf4j/Logger") && name.equals("error")) {
                            observe();
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }

                    private void observe() {
                        super.visitLdcInsn(audit.domain());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SIGNALS, "failed", "(Ljava/lang/String;)V", false);
                        sites[0]++;
                    }
                };
            }
        }, 0);
        if (sites[0] != audit.siteCount()) throw new IllegalStateException("Unexpected save observation site count");
        if (queueSites[0] != (savedData ? 2 : chunkMap || ioWorker ? 1 : 0))
            throw new IllegalStateException("Unexpected save queue observation site count");
        if (lifecycleSites[0] != (lifecycle ? 3 : replacements ? 4 : console ? 1 : 0))
            throw new IllegalStateException("Unexpected lifecycle or replacement site count");
        return writer.toByteArray();
    }

    private record Audit(String domain, Set<String> literals, boolean logCalls, int siteCount, Set<String> hashes) {}

    private static final String[] MODERN_CHUNK_MAPS = {
        "1af132e3e099c5ef0c38e24a6230d89ab48b53f4662adb22ff883854df5d115f",
        "cad61f1989874214608540cd4eb0a391e61849e8a37838878a280e6a5e3a2dd9",
        "ed08d2da74483bb7ef742a9c9142923369cd9487a2bb640872f4531bd0b66fd6"
    };
    private static final String[] MODERN_WORKERS = {
        "b368ea1467b32c20d4e9e53c5bfac56f31eb03735a732062fc7de2ce30622c35",
        "40880647b4a65e9f16e7140d1f9a5f444df5262149e549b2a212fc66d00c6863",
        "6f99d8a6ca554ba41c08c881cf38f419118013c15975f10884e2e901cd8d89f7"
    };
    private static final String MODERN_SAVED_DATA = "4dbeaaf7b017694eeaeb2c3c33495627750f3179c40de3b9a04c04c459f7510e";
    private static final String PAPER_263_SAVED_DATA = "b54531227b5ff91150e17905f6ef736ce7447a7262b81986bab79840a8d0f878";
    private static final String CHUNK_12111 = "42954e6bacb0ce8e2391ad2545bf2bc98173ab7f10c1382864a55c843b1d06ae";
    private static final String WORKER_12111 = "417085b378827bd2f1e7e10f45a18fe0014d802c00d255f704cb18d227d50a27";
    private static final String DATA_12111 = "9fd278028c21bb690cb0fcb1b7801c88a8e76d53cc257d2d95179e0fc7ccd50d";
    private static final Set<String> MODERN_HASHES;

    static {
        var hashes = new java.util.HashSet<>(List.of(MODERN_CHUNK_MAPS));
        hashes.addAll(List.of(MODERN_WORKERS));
        hashes.add(MODERN_SAVED_DATA);
        hashes.add(PAPER_263_SAVED_DATA);
        hashes.addAll(List.of(CHUNK_12111, WORKER_12111, DATA_12111));
        MODERN_HASHES = Set.copyOf(hashes);
    }
    private static final Map<String, List<Audit>> AUDITS = audits();

    private static Map<String, List<Audit>> audits() {
        var table = new HashMap<String, List<Audit>>();
        String replacement = "Failed to {}, aborting, progress might be lost";
        register(table, "ac", "file replacement", replacement, 1,
            "68ccae5c83d8323be7ea29a09be31951189bd8d2b4d00c7156be7763f2905c95");
        register(table, "net/minecraft/SystemUtils", "file replacement", replacement, 1,
            "9596f49cfedb426d151a74b30e8f165ac05ebb340f398c273ae798d74bd05b07",
            "f0d01250b47b3ff7390f056fa81a170d01207012fa592204ccb74af85efabc11");
        register(table, "dfb", "region storage", "Failed to store chunk {}", 1,
            "3467039f29f5cab1f3e1470d7591696e56ba76ab5145ec8bf8ec2f69e1afb997");
        register(table, "net/minecraft/world/level/chunk/storage/IOWorker", "region storage",
            "Failed to store chunk {}", 1,
            "5981a74bfb0deee0363e6fc1f0af00f7f9aa748d2da354dcc526d282c46a97e6",
            "223062163577e9a2615eaae3a6bff1dd29ec592ad24223c1fd4092ec5d899c2b");
        register(table, "dyj", "saved data", "Could not save data {}", 1,
            "b500971a6236ebcf5dbd5429ebb9c96d2ea43af0e548442cec303662f65ba697");
        register(table, "net/minecraft/world/level/saveddata/PersistentBase", "saved data", "Could not save data {}", 1,
            "fd785466ea42f318acb1b126b023fcd78c2b4d257a888202f9c94fa0a5f2c1f0",
            "0eaf5cf826d43302d78ca7eb12a9cbcf29d9fd658f91ee48c607b62e95babc13");
        register(table, "dyy$c", "level metadata", "Failed to save level {}", 1,
            "8a44f1f5647c5961c8e284da0313a7576017a239634b77aca454e885b259e47a");
        register(table, "net/minecraft/world/level/storage/Convertable$ConversionSession", "level metadata",
            "Failed to save level {}", 1,
            "e23e6dbc09e29f6f3e1112d784f647bf3326b73e65431485673d1085b44c6ddc",
            "79f6c2a2b8cb81356165ccb1aa5d91610122048f29cb954e29e7761a3302376a");
        register(table, "dzb", "player data", "Failed to save player data for {}", 1,
            "a6e458aadafdd41bc580e042dd0ee573ebc2a70e346c909f5699c7b53131dac2");
        register(table, "net/minecraft/world/level/storage/WorldNBTStorage", "player data",
            "Failed to save player data for {}", 1,
            "1d388923ad65ecdac492348f471c456cf7647fcae949f6e857d7c80a94d0a1a1",
            "45bd21d12bef44ba09c9ab17107e88bd683f10a125f157f260fd09d76afe3ce2",
            "7632a7e8573325c83e6f50e0a415b47b41a57707d82e7ac5ab540480e7334241");
        register(table, "io/papermc/paper/chunk/system/io/RegionFileIOThread$ChunkDataTask", "paper region I/O",
            null, 3, "4c870b8c959989e48b4e500ae7f4659fb15845e5a33591e2b4a09f2dd800cc2c");
        register(table, "net/minecraft/world/level/chunk/storage/RegionFileCache", "paper region storage",
            "Failed to save chunk \1", 1, "67815086c6583f003b714f04268d2c42f306acbf3b85d051e097a9c66d86f8e1");
        var chunkSaveErrors = Set.of("Failed to save chunk {},{}", "Failed to save chunk {}");
        table.put("ahr", List.of(new Audit("chunk save", chunkSaveErrors, false, 2,
            Set.of("57c43ba245052d54f2755dc7343eec6e7a4b7ba65ac60d431da8e5cee624da2d"))));
        table.put("net/minecraft/server/level/PlayerChunkMap", List.of(new Audit("chunk save", chunkSaveErrors, false, 2,
            Set.of("9abab5a4baafdeab25bef80b270f7556fc71abc3ef8af5a5990dbf79b316d75d",
                "1bb4a159c86c4dca92ccde8dc1f8684ca71ec7c8bf07d31c1f5c1fa27942f00f"))));
        String poiEncoding = "Expected compound tag, got {}";
        register(table, "dfg", "POI encoding", poiEncoding, 1,
            "9f43843f2c0d226187da1d8a445139df9f8a663dd4ee7b2bbf3764bedd7717f6");
        table.put("net/minecraft/world/level/chunk/storage/RegionFileSection", List.of(
            new Audit("POI storage", Set.of(poiEncoding), false, 1,
                Set.of("2ef81d7bd8d17827034e0387260280f8af90c5b6f32b1e45353bf08879e9b5a1")),
            new Audit("POI storage", Set.of(poiEncoding, "Error writing data to disk"), false, 2,
                Set.of("e822d54aa56cbf509d81207d94099a2b49fe9ee2dd8c362a438885c17e11d194"))));
        String stats = "Couldn't save stats";
        register(table, "amn", "player statistics", stats, 1,
            "a46a32d4c69eeda63b4dd6903fb05b1a5052b7a159572f7e153c99a095b8723a");
        register(table, "net/minecraft/stats/ServerStatisticManager", "player statistics", stats, 1,
            "fe479459207440f8d03731be1857cead885abc68455c68e7927398e4e5b5aa35",
            "b54c08ec83d17b049a8c7d7c54acb1ae03507ee240ace03e9a5e7eee09b9653c",
            "4232871b095e89a1060eeda51ad69a147b0a92773c8d0ed3945437ed5cc57ed1");
        String advancements = "Couldn't save player advancements to {}";
        register(table, "acy", "player advancements", advancements, 1,
            "5f9a86a6e68f67e3ccc9a3813f429465f8dc1ec63e326ede82dfcfd917240682");
        register(table, "net/minecraft/server/AdvancementDataPlayer", "player advancements", advancements, 1,
            "9af4e9876e7dd43906be2ab48d5ea1e981c7254c59074338fc329300824e0bf9",
            "4487c5bbb409a925177f1dbaa8c3aecd9e9ada21830bb6e150dd61b4dbdfe74a",
            "124a8ac5149a772706213fc80a25e3f2eee4de66e557f092688766740f6ad731",
            "faca91a2d702f0433289b84d80fe5e9eaf1cfe4ee5ff835c62a244686d4f646c");
        register(table, NAMED_CHUNK_MAP, "chunk save", "Failed to save chunk {}", 1, MODERN_CHUNK_MAPS);
        var workerAudits = new java.util.ArrayList<>(table.get(NAMED_WORKER));
        workerAudits.add(new Audit("region storage", Set.of("Failed to store chunk {}"), false, 1, Set.of(MODERN_WORKERS)));
        table.put(NAMED_WORKER, List.copyOf(workerAudits));
        register(table, NAMED_SAVED_DATA, "saved data", "Could not save data to {}", 1, MODERN_SAVED_DATA, PAPER_263_SAVED_DATA);
        register(table, NAMED_SERVER, "region save", "Failed to save chunk {},{}", 1,
            SERVER_12111,
            "2b2359b3adaaef0b5e9ae96417ce55b6dedb11e1db57039b0afeb6b95b53a857",
            "61d107869098170aec04c53da169db136b119fa4f541be776310d7f85e925c17",
            "527d416a4317b9e76d8bbe260afb3af08bdbeb632b4978b47d08ee31ec954402");
        table.put(NAMED_UTIL, List.of(new Audit("file replacement", Set.of(), false, 0,
            Set.of("71dd586deeb1643ccec9cfb9721f96ddfc5a4557c16bc4983d92c6e1d845e5bf",
                "2d353d89981bd9ee54b8c75853959aed7d8534759e6386e459499f5128a549af",
                "bc3cbb2492fc4083b432fd146ad2b162c9f58a09eb52574a2d5afeea7bac0614",
                "60c478a2cfbb0c82b5f9770ab62e2cca55847dd9a1f9aa1ddb6d07226a7d4a73"))));
        register(table, "net/minecraft/world/level/storage/PlayerDataStorage", "player data",
            "Failed to save player data for {}", 1, "1adf0ec2a504ef5587860fc1c074dd7c8281a11e6e5e9b1656ef53bffe6b8724",
            "df692d938de9ef6034cbd7c4e96f61962c5ba59d0d267e9639fdb791de41de98");
        register(table, "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess", "level metadata",
            "Failed to save level {}", 1,
            "03d059410c9e81ca419e2cd864c27fd108fe25a06d1e3dec76bcc95d1e0a42f8",
            "a8bc9b401a185daf13e75aee5e46675a7e73e4962e4437df4ee4b6b7073ee619",
            "31cebebad06277d0f376694359cb5d48406b9624c04476ecf7ed146a8e3ad9c9");
        register(table, "net/minecraft/stats/ServerStatsCounter", "player statistics", "Couldn't save stats to {}", 1,
            "7307888268627be28062db62f5dcbc8b26d13dcb6948533c21ed87eb6f01b6d8",
            "0c41647004b7ae135cb803dc21c9024ec4ebfeeec66c95ed3e2c32b08578c32f");
        register(table, "net/minecraft/server/PlayerAdvancements", "player advancements", "Couldn't save player advancements to {}", 1,
            "77d52c080128c3f07bdadf12a8d79771dd6ecddced3ea78ce339069893a4373f",
            "52f0839928fd1bf1dd6538d2141e8e18b7167931c8ef6bc0e2b32b08c1222af0",
            "15d2a4066a3c16a0d17db96a22ce5b76201ab619c6b7b964ffb9fa6f117407aa",
            "c9ef97ffbf43a10c32bf1a57ff275bde524cb6eb14322f33114820b1ac229655",
            "75b63c3462d9aa42a869c773ba128c354c0a478c8577f3a39fc6f52da80f1545");
        register(table, "awj", "chunk save", "Failed to save chunk {}", 1, CHUNK_12111);
        register(table, "ert", "region storage", "Failed to store chunk {}", 1, WORKER_12111);
        register(table, "fnc", "saved data", "Could not save data to {}", 1, DATA_12111);
        table.put("bhs", List.of(new Audit("file replacement", Set.of(), false, 0,
            Set.of("e18963e800459ecfac234bc643d6d66e8b057bb6e4b368cdc6935a3832ccb5b0"))));
        register(table, "fnl", "player data", "Failed to save player data for {}", 1,
            "4b722e6fd3ea77b5c7d700fc6aecdec211ca584dcfb082856992c76a0c0675c5");
        register(table, "fni$c", "level metadata", "Failed to save level {}", 1,
            "7f081d976c5b552013f8fad54c83bb7c4fa5f57057c0518c8c60bbc3bd80a808");
        register(table, "bdg", "player statistics", "Couldn't save stats to {}", 1,
            "e7ea560ee127de90e76e9527aca8a526be3b2660676950032bffc0200dc6e55f");
        register(table, "anb", "player advancements", "Couldn't save player advancements to {}", 1,
            "011e1d58148c4e76fd980df1f26280d9c7a5c9f4fb353aab13a25b95b1cfb6f1");
        table.put(DEDICATED, List.of(new Audit("console", Set.of(), false, 0, Set.of(
            "39ae30f302f8c714b6ef301e7cd2e59eeb2b0598d3c84b21c2fb290ca192d079",
            "d838934c810ce338731e7cee5e83707a18af33056ae9d9323f8a1c9140ba3293",
            "336383141a5e9b0d21ae00d37f30dd23133a44ffc0e21aca455493281e88669c"))));
        table.put("ary", List.of(new Audit("console", Set.of(), false, 0,
            Set.of("e55eef9ca66dc0cf92698a9adb5ef58c392affa92831ddaa3eace6dd6a5c29e7"))));
        return Map.copyOf(table);
    }

    private static void register(Map<String, List<Audit>> table, String name, String domain, String literal,
                                 int siteCount, String... fingerprints) {
        table.put(name, List.of(new Audit(domain, literal == null ? Set.of() : Set.of(literal), literal == null,
            siteCount, Set.of(fingerprints))));
    }
}
