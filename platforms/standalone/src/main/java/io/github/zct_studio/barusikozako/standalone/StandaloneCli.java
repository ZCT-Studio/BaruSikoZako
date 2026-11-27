// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

import io.github.zct_studio.barusikozako.backend.ConfigUnits;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 命令行入口，语义与游戏内命令保持一致。 */
public final class StandaloneCli {
    private static final DateTimeFormatter SNAPSHOT_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private StandaloneCli() {}

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            var parsed = parse(args);
            if (parsed.help || parsed.command == null) {
                out.print(usage());
                return parsed.help ? 0 : 1;
            }
            return dispatch(parsed, out, err);
        } catch (UsageException usage) {
            err.println("错误 / Error: " + usage.getMessage());
            err.println(usage());
            return 2;
        } catch (Exception failure) {
            err.println("失败 / Failed: " + failure.getMessage());
            return 1;
        }
    }

    private static final class Parsed {
        Path world;
        Path config;
        boolean yes;
        boolean help;
        String command;
        List<String> args = List.of();
    }

    private static final class UsageException extends Exception {
        UsageException(String message) { super(message); }
    }

    private static Parsed parse(String[] args) throws UsageException {
        var parsed = new Parsed();
        var rest = new java.util.ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-w", "--world" -> parsed.world = Path.of(next(args, ++i, arg));
                case "-c", "--config" -> parsed.config = Path.of(next(args, ++i, arg));
                case "-y", "--yes" -> parsed.yes = true;
                case "-h", "--help" -> parsed.help = true;
                default -> {
                    if (arg.startsWith("-") && parsed.command == null)
                        throw new UsageException("Unknown option: " + arg);
                    rest.add(arg);
                }
            }
        }
        if (!rest.isEmpty()) {
            parsed.command = rest.get(0);
            parsed.args = rest.subList(1, rest.size());
        }
        return parsed;
    }

    private static String next(String[] args, int index, String option) throws UsageException {
        if (index >= args.length) throw new UsageException("Missing value for " + option);
        return args[index];
    }

    private static int dispatch(Parsed parsed, PrintStream out, PrintStream err) throws Exception {
        switch (parsed.command) {
            case "help" -> { out.print(usage()); return 0; }
            case "version" -> { out.println("BaruSikoZako standalone workstation"); return 0; }
            default -> { }
        }
        Path world = requireWorld(parsed);
        Path gameRoot = parsed.config != null ? parsed.config : Workstation.deriveGameRoot(world);
        var workstation = new Workstation(world, gameRoot);
        var progress = cliProgress(out, err);
        switch (parsed.command) {
            case "status" -> { status(workstation, out); return 0; }
            case "backup" -> { backup(workstation, parsed.args, progress, out); return 0; }
            case "list" -> { list(workstation, out); return 0; }
            case "detail" -> { detail(workstation, parsed.args, out); return 0; }
            case "verify" -> { verify(workstation, parsed.args, progress, out); return 0; }
            case "rollback" -> { rollback(workstation, parsed.args, parsed.yes, progress, out, err); return 0; }
            case "delete" -> { delete(workstation, parsed.args, parsed.yes, progress, out, err); return 0; }
            case "config" -> { config(workstation.configDir(), parsed.args, out); return 0; }
            default -> throw new UsageException("Unknown command: " + parsed.command);
        }
    }

    private static Path requireWorld(Parsed parsed) throws UsageException {
        if (parsed.world == null)
            throw new UsageException("A world directory is required; pass --world <dir>");
        if (!Files.isDirectory(parsed.world))
            throw new UsageException("World directory does not exist: " + parsed.world);
        return parsed.world.toAbsolutePath().normalize();
    }

    private static Workstation.Progress cliProgress(PrintStream out, PrintStream err) {
        return new Workstation.Progress() {
            @Override public void message(String line) { out.println(line); }
            @Override public void progress(long processed, long total) {
                if (total > 0) err.print("\r" + ConfigUnits.formatBytes(processed, ConfigUnits.SizeUnit.BINARY, 1)
                    + " / " + ConfigUnits.formatBytes(total, ConfigUnits.SizeUnit.BINARY, 1) + "    ");
            }
        };
    }

    private static void status(Workstation workstation, PrintStream out) throws IOException {
        out.println("world: " + workstation.world());
        out.println("game root: " + workstation.gameRoot());
        out.println("config dir: " + workstation.configDir());
        out.println("backend: " + workstation.config().backendKeyword());
        out.println("repository: " + workstation.repositoryPath());
        var snapshots = workstation.list();
        out.println("snapshots: " + snapshots.size());
    }

    private static void backup(Workstation workstation, List<String> args, Workstation.Progress progress,
                               PrintStream out) throws Exception {
        Workstation.BackupMode mode = Workstation.BackupMode.CONFIGURED;
        String note = null;
        UUID baseline = null;
        int i = 0;
        if (!args.isEmpty() && !args.get(0).equals("note")) {
            mode = switch (args.get(0)) {
                case "full" -> Workstation.BackupMode.FULL;
                case "incremental" -> Workstation.BackupMode.INCREMENTAL;
                case "differential" -> Workstation.BackupMode.DIFFERENTIAL;
                case "rebuild" -> Workstation.BackupMode.REBUILD;
                case "fixed-chunks" -> Workstation.BackupMode.FIXED_CHUNKS;
                default -> throw new UsageException("Unknown backup mode: " + args.get(0));
            };
            i = 1;
        }
        if (i < args.size() && args.get(i).equals("note")) {
            note = String.join(" ", args.subList(i + 1, args.size()));
        } else if (mode == Workstation.BackupMode.DIFFERENTIAL || mode == Workstation.BackupMode.REBUILD) {
            if (i >= args.size()) throw new UsageException("Backup mode " + args.get(0) + " requires a baseline snapshot");
            baseline = workstation.select(List.of(args.get(i))).id();
        }
        var snapshot = workstation.backup(mode, note, baseline, progress);
        out.println();
        out.println("Backup complete: " + snapshot.folderName());
        out.println("  layout: " + snapshot.layout() + " | logical: " + (snapshot.logicalBytes().isPresent()
            ? ConfigUnits.formatBytes(snapshot.logicalBytes().getAsLong(), ConfigUnits.SizeUnit.BINARY, 1) : "unknown"));
    }

    private static void list(Workstation workstation, PrintStream out) throws IOException {
        var snapshots = workstation.list();
        if (snapshots.isEmpty()) {
            out.println("No snapshots.");
            return;
        }
        for (var snapshot : snapshots) {
            out.println(snapshot.folderName());
            out.println("  " + snapshot.layout() + " | " + snapshot.consistency()
                + (snapshot.archiveBytes().isPresent() ? " | archive "
                    + ConfigUnits.formatBytes(snapshot.archiveBytes().getAsLong(), ConfigUnits.SizeUnit.BINARY, 1) : "")
                + (snapshot.logicalBytes().isPresent() ? " | logical "
                    + ConfigUnits.formatBytes(snapshot.logicalBytes().getAsLong(), ConfigUnits.SizeUnit.BINARY, 1) : ""));
        }
    }

    private static void detail(Workstation workstation, List<String> args, PrintStream out) throws Exception {
        var snapshot = workstation.select(args);
        out.println("id: " + snapshot.id());
        out.println("time (UTC): " + SNAPSHOT_TIME.format(snapshot.createdAt()));
        out.println("folder: " + snapshot.folderName());
        out.println("layout: " + snapshot.layout());
        out.println("consistency: " + snapshot.consistency());
        snapshot.archiveBytes().ifPresent(b -> out.println("archive: " +
            ConfigUnits.formatBytes(b, ConfigUnits.SizeUnit.BINARY, 1)));
        snapshot.logicalBytes().ifPresent(b -> out.println("logical: " +
            ConfigUnits.formatBytes(b, ConfigUnits.SizeUnit.BINARY, 1)));
        snapshot.elapsed().ifPresent(d -> out.println("elapsed: " + ConfigUnits.formatDuration(d)));
    }

    private static void verify(Workstation workstation, List<String> args, Workstation.Progress progress,
                               PrintStream out) throws Exception {
        var snapshot = workstation.select(args);
        BackupEngine.Verification result = workstation.verify(snapshot.id(), progress);
        out.println();
        out.println("Verification passed: " + result.entries() + " entries | "
            + ConfigUnits.formatBytes(result.bytes(), ConfigUnits.SizeUnit.BINARY, 1));
    }

    private static void rollback(Workstation workstation, List<String> args, boolean yes, Workstation.Progress progress,
                                 PrintStream out, PrintStream err) throws Exception {
        var snapshot = workstation.select(args);
        if (!yes && !confirm(out, err, "回滚将用快照 " + snapshot.folderName() + " 替换当前存档，且不可逆。继续？(y/N)"))
            return;
        var result = workstation.rollback(snapshot.id(), progress);
        out.println();
        out.println("Rollback " + result.status() + " to " + snapshot.folderName());
        result.previousWorld().ifPresent(p -> out.println("Previous world kept at: " + p));
    }

    private static void delete(Workstation workstation, List<String> args, boolean yes, Workstation.Progress progress,
                               PrintStream out, PrintStream err) throws Exception {
        var snapshot = workstation.select(args);
        if (!yes && !confirm(out, err, "确认删除快照 " + snapshot.folderName() + "？(y/N)")) return;
        workstation.delete(snapshot.id(), progress);
        out.println("Deleted snapshot " + snapshot.folderName());
    }

    private static boolean confirm(PrintStream out, PrintStream err, String prompt) {
        out.print(prompt + " ");
        out.flush();
        try {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            var line = reader.readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (IOException failure) {
            err.println("无法读取确认输入，已取消 / Could not read confirmation, cancelled");
            return false;
        }
    }

    private static void config(Path configDir, List<String> args, PrintStream out) throws Exception {
        if (args.isEmpty()) throw new UsageException("config requires 'server' or 'backend'");
        var target = args.get(0);
        var rest = args.subList(1, args.size());
        boolean backend;
        if (target.equals("server")) backend = false;
        else if (target.equals("backend")) backend = true;
        else throw new UsageException("config expects 'server' or 'backend'");
        if (rest.isEmpty()) {
            var values = backend ? StandaloneConfig.backendValues(configDir) : StandaloneConfig.serverValues(configDir);
            values.forEach((key, value) -> out.println(key + "=" + value));
            return;
        }
        String action = rest.get(0);
        if (action.equals("get")) {
            if (rest.size() < 2) throw new UsageException("config get requires a key");
            var values = backend ? StandaloneConfig.backendValues(configDir) : StandaloneConfig.serverValues(configDir);
            out.println(values.getOrDefault(rest.get(1), ""));
            return;
        }
        if (action.equals("set")) {
            if (rest.size() < 3) throw new UsageException("config set requires a key and value");
            String key = rest.get(1);
            String value = String.join(" ", rest.subList(2, rest.size()));
            if (backend) StandaloneConfig.setBackendValue(configDir, key, value);
            else StandaloneConfig.setServerValue(configDir, key, value);
            out.println(key + "=" + value);
            return;
        }
        throw new UsageException("config expects 'get <key>' or 'set <key> <value>'");
    }

    private static String usage() {
        return """
            用法 / Usage:
              java -jar barusikozako.jar [选项] <命令> [参数]

            选项 / Options:
              -w, --world <dir>    存档目录（世界目录）/ world directory
              -c, --config <dir>   游戏根目录 / 工作目录（默认自动从存档推导）
              -y, --yes            回滚/删除跳过确认 / skip confirmation
              -h, --help           帮助 / help

            命令 / Commands:
              status                           查看备份服务状态
              version                          查看版本
              backup [note <文本>]              按配置模式备份
              backup full|incremental|fixed-chunks
              backup differential|rebuild <快照>
              list                             列出快照
              detail <时间|fromuuid <uuid>|fullname <名称>>
              verify  <时间|fromuuid <uuid>|fullname <名称>>
              rollback <时间|fromuuid <uuid>|fullname <名称>>
              delete   <时间|fromuuid <uuid>|fullname <名称>>
              config server|backend [get <键>|set <键> <值>]

            无参数双击运行将打开图形界面 / Double-click launches the GUI.
            """;
    }
}
