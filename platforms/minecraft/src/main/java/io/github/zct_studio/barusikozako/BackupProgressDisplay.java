// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.backend.ConfigUnits;
import io.github.zct_studio.barusikozako.runtime.BackupRequest;
import io.github.zct_studio.barusikozako.runtime.TaskId;
import io.github.zct_studio.barusikozako.runtime.TaskResult;
import io.github.zct_studio.barusikozako.runtime.TaskState;
import io.github.zct_studio.barusikozako.runtime.host.BackupApiHost.TimedTask;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * 在游戏内展示备份与校验任务进度。无法取得总量时使用每 tick 推进 1% 的线性动画，到 100% 后回到 0。
 * 备份与校验各自拥有独立的显示通道，BossBar 的颜色由当前阶段决定。
 */
final class BackupProgressDisplay {
    private static final System.Logger LOG = System.getLogger(BackupProgressDisplay.class.getName());
    /** 未知进度时每 1 个游戏刻推进一步，101 级覆盖 0%–100% */
    private static final int STEPS = 100;
    private static final float STEP = 1f / STEPS;
    private static final int TICKS_PER_STEP = 1;

    private static final class View {
        final UUID initiator;
        final Instant submittedAt;
        TaskState state;
        int step;
        int ticks;
        long completionSince = -1;

        View(UUID initiator, Instant submittedAt) {
            this.initiator = initiator;
            this.submittedAt = submittedAt;
        }
    }

    /** 单种操作（备份 / 校验）的独立显示通道 */
    private final class Channel {
        final BackupRequest.Kind kind;
        final ProgressConfig.Operation settings;
        final Map<String, ServerBossEvent> bars = new HashMap<>();
        long lastRenderNanos;

        Channel(BackupRequest.Kind kind, ProgressConfig.Operation settings) {
            this.kind = kind;
            this.settings = settings;
        }
    }

    private final ProgressConfig config;
    private final Supplier<List<TimedTask>> tasks;
    private final Predicate<String> relaxedCapture;
    private final Instant createdAt = Instant.now();
    private final Map<BackupRequest.Kind, Channel> channels = new LinkedHashMap<>();
    private final Map<TaskId, UUID> initiators = new HashMap<>();
    private final Map<TaskId, View> views = new HashMap<>();
    private boolean closed;

    BackupProgressDisplay(ProgressConfig config, Supplier<List<TimedTask>> tasks, Predicate<String> relaxedCapture) {
        this.config = config;
        this.tasks = tasks;
        this.relaxedCapture = relaxedCapture;
        channels.put(BackupRequest.Kind.BACKUP, new Channel(BackupRequest.Kind.BACKUP, config.backup()));
        channels.put(BackupRequest.Kind.VERIFY, new Channel(BackupRequest.Kind.VERIFY, config.verify()));
    }

    /** 记录由玩家主动发起的任务，用于区分 initiated 与 non-initiated 受众 */
    void track(TaskId id, ServerPlayer player) {
        if (player != null) initiators.put(id, player.getUUID());
    }

    void close() {
        closed = true;
        for (var channel : channels.values()) {
            for (var bar : channel.bars.values()) {
                bar.removeAllPlayers();
                bar.setVisible(false);
            }
            channel.bars.clear();
        }
        views.clear();
        initiators.clear();
    }

    void tick(MinecraftServer server) {
        if (closed) return;
        if (!config.enabled()) {
            hideAll();
            views.clear();
            return;
        }
        long now = System.nanoTime();
        boolean anyPlayers = server != null && server.getPlayerList().getPlayerCount() > 0;
        List<TimedTask> all;
        try {
            all = tasks.get();
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not read task state for the progress display", failure);
            return;
        }
        synchronize(all);
        for (var channel : channels.values()) {
            if (anyPlayers) {
                tickChannel(server, channel, now, all);
            } else if (intervalDue(now, channel.lastRenderNanos)) {
                channel.lastRenderNanos = now;
                hide(channel);
            }
        }
    }

    private void tickChannel(MinecraftServer server, Channel channel, long now, List<TimedTask> all) {
        boolean intervalDue = intervalDue(now, channel.lastRenderNanos);
        var mine = new ArrayList<TimedTask>();
        for (var task : all) if (task.state().operation() == channel.kind) mine.add(task);
        var active = newest(mine, true);
        var finished = newest(mine, false);
        TaskId displayed;
        boolean completion;
        if (active != null) {
            displayed = active.state().id();
            completion = false;
        } else if (finished != null && beginCompletion(channel.settings, finished, now)) {
            displayed = finished.state().id();
            completion = true;
        } else {
            if (intervalDue) {
                channel.lastRenderNanos = now;
                hide(channel);
            }
            return;
        }
        var view = views.get(displayed);
        if (view == null) {
            hide(channel);
            return;
        }
        float fraction;
        if (completion) {
            if (!intervalDue) return;
            fraction = completionFraction(view.state.result().orElse(null));
        } else {
            float real = realFraction(view.state);
            if (real >= 0) {
                if (!intervalDue) return;
                fraction = real;
            } else {
                // 未知进度：每 1 个游戏刻推进 1%，100% 后回到 0
                view.ticks++;
                if (view.ticks % TICKS_PER_STEP != 0) return;
                fraction = Math.min(1f, view.step * STEP);
                view.step = (view.step + 1) % (STEPS + 1);
            }
        }
        channel.lastRenderNanos = now;
        render(server, channel, view, completion, fraction);
    }

    private boolean intervalDue(long now, long lastRenderNanos) {
        return now - lastRenderNanos >= config.updateIntervalMillis() * 1_000_000L;
    }

    private void synchronize(List<TimedTask> all) {
        var present = new HashSet<TaskId>();
        for (var task : all) {
            var kind = task.state().operation();
            if (kind != BackupRequest.Kind.BACKUP && kind != BackupRequest.Kind.VERIFY) continue;
            var state = task.state();
            present.add(state.id());
            var view = views.get(state.id());
            if (view == null) {
                view = new View(initiators.get(state.id()), task.submittedAt());
                views.put(state.id(), view);
            }
            view.state = state;
        }
        views.keySet().retainAll(present);
        initiators.keySet().retainAll(present);
    }

    private static TimedTask newest(List<TimedTask> tasks, boolean running) {
        TimedTask newest = null;
        for (var task : tasks) {
            if ((task.state().phase() != TaskState.Phase.TERMINAL) != running) continue;
            if (newest == null || task.submittedAt().isAfter(newest.submittedAt())) newest = task;
        }
        return newest;
    }

    private boolean beginCompletion(ProgressConfig.Operation settings, TimedTask task, long now) {
        if (!settings.completion().enabled()) return false;
        var view = views.get(task.state().id());
        if (view == null) return false;
        if (view.completionSince < 0) {
            if (view.submittedAt.isBefore(createdAt)) return false;
            view.completionSince = now;
        }
        return now - view.completionSince < settings.completion().durationMillis() * 1_000_000L;
    }

    private void render(MinecraftServer server, Channel channel, View view, boolean completion, float fraction) {
        var settings = channel.settings;
        var stage = stage(view, completion);
        var bossColor = bossColor(settings.colors().forStage(stage));
        var bossPlayers = new HashMap<String, List<ServerPlayer>>();
        for (var player : server.getPlayerList().getPlayers()) {
            var language = language(player);
            switch (channel(player, view, settings)) {
                case BOSS_BAR -> bossPlayers.computeIfAbsent(language, ignored -> new ArrayList<>()).add(player);
                case NONE -> { }
            }
        }
        for (var entry : channel.bars.entrySet()) {
            var players = bossPlayers.get(entry.getKey());
            var bar = entry.getValue();
            if (players == null || players.isEmpty()) {
                if (!bar.getPlayers().isEmpty()) bar.removeAllPlayers();
                bar.setVisible(false);
                continue;
            }
            applyBossState(bar, entry.getKey(), settings, view, completion, fraction, stage);
            var desired = new HashSet<>(players);
            for (var existing : List.copyOf(bar.getPlayers()))
                if (!desired.contains(existing)) bar.removePlayer(existing);
            var current = new HashSet<>(bar.getPlayers());
            for (var player : players)
                if (current.add(player)) bar.addPlayer(player);
        }
        bossPlayers.forEach((language, players) -> {
            if (channel.bars.containsKey(language)) return;
            var bar = createBar(settings, bossColor);
            applyBossState(bar, language, settings, view, completion, fraction, stage);
            for (var player : players) bar.addPlayer(player);
            channel.bars.put(language, bar);
        });
    }

    private void applyBossState(ServerBossEvent bar, String language, ProgressConfig.Operation settings,
                                View view, boolean completion, float fraction, ProgressConfig.Stage stage) {
        bar.setName(component(completion
            ? completionText(language, settings, view.state, settings.bossbar().textColor())
            : progressText(language, settings, view.state, settings.templates().bossbar(),
                settings.bossbar().textColor())));
        bar.setColor(bossColor(settings.colors().forStage(stage)));
        bar.setProgress(Math.max(0f, Math.min(1f, fraction)));
        bar.setVisible(true);
    }

    private static ServerBossEvent createBar(ProgressConfig.Operation settings, BossEvent.BossBarColor color) {
        var style = bossStyle(settings.bossbar().style());
//? if mc >= 26
        var bar = new ServerBossEvent(UUID.randomUUID(), Component.empty(), color, style);
//? else
//?~        var bar = new ServerBossEvent(Component.empty(), color, style);
//? end
        bar.setVisible(true);
        return bar;
    }

    private void hide(Channel channel) {
        for (var bar : channel.bars.values()) {
            if (!bar.getPlayers().isEmpty()) bar.removeAllPlayers();
            bar.setVisible(false);
        }
    }

    private void hideAll() {
        for (var channel : channels.values()) hide(channel);
    }

    private ProgressConfig.Channel channel(ServerPlayer player, View view, ProgressConfig.Operation settings) {
        if (view.initiator != null && view.initiator.equals(player.getUUID())) return settings.initiated().display();
        return switch (settings.nonInitiated().audience()) {
            case NONE -> ProgressConfig.Channel.NONE;
            case OPERATORS -> GameCommandAccess.isAdministrator(player.createCommandSourceStack())
                ? settings.nonInitiated().display() : ProgressConfig.Channel.NONE;
            case ALL -> settings.nonInitiated().display();
        };
    }

    private static String language(ServerPlayer player) {
        var language = PlayerLanguage.get(player);
        return language == null ? "en_us" : language;
    }

    private static float realFraction(TaskState state) {
        if (state.totalBytes().isEmpty()) return -1f;
        long total = state.totalBytes().getAsLong();
        if (total <= 0) return -1f;
        return Math.max(0f, Math.min(1f, (float) ((double) state.processedBytes() / total)));
    }

    private static float completionFraction(TaskResult result) {
        if (success(result)) return 1f;
        if (result instanceof TaskResult.Cancelled) return 0.5f;
        if (result instanceof TaskResult.CommitUncertain || result == null) return 1f;
        return 0f;
    }

    private ProgressConfig.Stage stage(View view, boolean completion) {
        if (!completion) {
            return switch (view.state.phase()) {
                case QUEUED, PREPARING -> ProgressConfig.Stage.PREPARING;
                case RUNNING, COMMITTING -> ProgressConfig.Stage.RUNNING;
                case TERMINAL -> completionStage(view.state.result().orElse(null));
            };
        }
        return completionStage(view.state.result().orElse(null));
    }

    private ProgressConfig.Stage completionStage(TaskResult result) {
        if (result == null || result instanceof TaskResult.CommitUncertain) return ProgressConfig.Stage.UNCERTAIN;
        if (result instanceof TaskResult.Cancelled || result instanceof TaskResult.Failed)
            return ProgressConfig.Stage.FAILURE;
        if (!success(result)) return ProgressConfig.Stage.UNCERTAIN;
        // 宽松模式下接受过时间点差异的备份：成功但需要提示
        if (result instanceof TaskResult.BackupCommitted committed
            && relaxedCapture.test(committed.snapshot().snapshotId()))
            return ProgressConfig.Stage.UNCERTAIN;
        return ProgressConfig.Stage.SUCCESS;
    }

    private static boolean success(TaskResult result) {
        return result instanceof TaskResult.BackupCommitted || result instanceof TaskResult.VerificationComplete;
    }

    private List<Segment> progressText(String language, ProgressConfig.Operation settings, TaskState state,
                                       String template, String base) {
        return ProgressTemplate.render(template, base,
            name -> progressValue(language, settings, state, name, base));
    }

    private List<Segment> completionText(String language, ProgressConfig.Operation settings, TaskState state,
                                         String base) {
        var result = state.result().orElse(null);
        return ProgressTemplate.render(completionTemplate(settings, result), base,
            name -> completionValue(language, settings, state, result, name, base));
    }

    private Segment progressValue(String language, ProgressConfig.Operation settings, TaskState state,
                                  String name, String base) {
        var info = settings.info();
        long processed = state.processedBytes();
        long total = state.totalBytes().isPresent() ? state.totalBytes().getAsLong() : -1;
        double seconds = state.elapsed().toMillis() / 1000.0;
        double speed = seconds > 0.05 ? processed / seconds : 0;
        long remaining = info.estimated() && total > processed && speed >= 1
            ? (long) ((total - processed) / speed) : -1;
        return switch (name) {
            case "action" -> info.stage() ? new Segment(stage(language, state.phase()), base) : null;
            case "action_raw" -> info.stage()
                ? new Segment(state.phase().name().toLowerCase(Locale.ROOT), base) : null;
            case "percent" -> info.percentage() && total > 0
                ? new Segment(String.format(Locale.ROOT, "%.0f%%", 100.0 * processed / total), base) : null;
            case "percent_raw" -> info.percentage() && total > 0
                ? new Segment(Long.toString(Math.round(100.0 * processed / total)), base) : null;
            case "progress" -> progressSize(info, processed, total, base);
            case "progress_raw" -> progressSizeRaw(info, processed, total, base);
            case "progress_simple" -> progressSimple(info, processed, total, base);
            case "progress_simple_raw" -> progressSimpleRaw(info, processed, total, base);
            case "speed" -> info.speed() && speed >= 1
                ? new Segment(config.size().format((long) speed) + "/s", base) : null;
            case "speed_raw" -> info.speed() && speed >= 1
                ? new Segment(Long.toString((long) speed), base) : null;
            case "elapsed" -> info.elapsed() ? new Segment(Translations.languageText(language, "progress.elapsed")
                .formatted(ConfigUnits.formatDuration(state.elapsed())), base) : null;
            case "elapsed_raw" -> info.elapsed()
                ? new Segment(Long.toString(Math.max(0, state.elapsed().toSeconds())), base) : null;
            case "remaining" -> remaining < 0 ? null : new Segment(
                Translations.languageText(language, "progress.remaining")
                    .formatted(ConfigUnits.formatDuration(Duration.ofSeconds(remaining))), base);
            case "remaining_raw" -> remaining < 0 ? null : new Segment(Long.toString(remaining), base);
            default -> null;
        };
    }

    private Segment progressSize(ProgressConfig.Info info, long processed, long total, String base) {
        if (info.processedSize() && info.totalSize() && total > 0) {
            return new Segment(config.size().format(processed) + " / " + config.size().format(total), base);
        }
        if (info.processedSize()) return new Segment(config.size().format(processed), base);
        if (info.totalSize() && total > 0) return new Segment(config.size().format(total), base);
        return null;
    }

    private Segment progressSizeRaw(ProgressConfig.Info info, long processed, long total, String base) {
        if (info.processedSize() && info.totalSize() && total > 0) {
            return new Segment(processed + "/" + total, base);
        }
        if (info.processedSize()) return new Segment(Long.toString(processed), base);
        if (info.totalSize() && total > 0) return new Segment(Long.toString(total), base);
        return null;
    }

    /** 与总量使用同一单位，例如 0.2 / 1.2 GiB */
    private Segment progressSimple(ProgressConfig.Info info, long processed, long total, String base) {
        if (info.processedSize() && info.totalSize() && total > 0)
            return new Segment(config.size().formatPair(processed, total), base);
        return progressSize(info, processed, total, base);
    }

    private Segment progressSimpleRaw(ProgressConfig.Info info, long processed, long total, String base) {
        if (info.processedSize() && info.totalSize() && total > 0)
            return new Segment(processed + "/" + total, base);
        return progressSizeRaw(info, processed, total, base);
    }

    private Segment completionValue(String language, ProgressConfig.Operation settings, TaskState state,
                                    TaskResult result, String name, String color) {
        var completion = settings.completion();
        if (result instanceof TaskResult.BackupCommitted committed) {
            long logical = committed.logicalBytes().isPresent() ? committed.logicalBytes().getAsLong() : -1;
            long archive = committed.archiveBytes().isPresent() ? committed.archiveBytes().getAsLong() : -1;
            long saved = logical >= 0 && archive >= 0 && logical > archive ? logical - archive : -1;
            long percent = saved > 0 && logical > 0 ? Math.round(100.0 * saved / logical) : -1;
            var elapsed = committed.elapsed().isPresent() ? committed.elapsed().get() : state.elapsed();
            long elapsedSeconds = Math.max(0, elapsed.toSeconds());
            return switch (name) {
                case "result" -> new Segment(Translations.languageText(language, "completion.backup"), color);
                case "result_raw" -> new Segment("success", color);
                case "original" -> completion.originalSize() && logical >= 0 ? new Segment(
                    Translations.languageText(language, "completion.original")
                        .formatted(config.size().format(logical)), color) : null;
                case "original_raw" -> completion.originalSize() && logical >= 0
                    ? new Segment(Long.toString(logical), color) : null;
                case "archive" -> completion.backupSize() && archive >= 0 ? new Segment(
                    Translations.languageText(language, "completion.backup_size")
                        .formatted(config.size().format(archive)), color) : null;
                case "archive_raw" -> completion.backupSize() && archive >= 0
                    ? new Segment(Long.toString(archive), color) : null;
                case "saved" -> saved < 0 ? null : savedSegment(language, saved, percent, completion, color);
                case "saved_raw" -> completion.savedSpace() && saved >= 0
                    ? new Segment(Long.toString(saved), color) : null;
                case "saved_percent_raw" -> completion.savedPercentage() && percent >= 0
                    ? new Segment(Long.toString(percent), color) : null;
                case "elapsed" -> completion.elapsed() && elapsedSeconds >= 0 ? new Segment(
                    Translations.languageText(language, "progress.elapsed")
                        .formatted(ConfigUnits.formatDuration(elapsed)), color) : null;
                case "elapsed_raw" -> completion.elapsed()
                    ? new Segment(Long.toString(elapsedSeconds), color) : null;
                default -> null;
            };
        }
        if (result instanceof TaskResult.VerificationComplete verified) {
            long elapsedSeconds = Math.max(0, state.elapsed().toSeconds());
            return switch (name) {
                case "result" -> new Segment(Translations.languageText(language, "completion.verify"), color);
                case "result_raw" -> new Segment("success", color);
                case "entries" -> new Segment(Translations.languageText(language, "completion.entries")
                    .formatted(verified.entries()), color);
                case "entries_raw" -> new Segment(Long.toString(verified.entries()), color);
                case "verified" -> new Segment(Translations.languageText(language, "completion.verified_bytes")
                    .formatted(config.size().format(verified.bytes())), color);
                case "verified_raw" -> new Segment(Long.toString(verified.bytes()), color);
                case "elapsed" -> completion.elapsed() ? new Segment(
                    Translations.languageText(language, "progress.elapsed")
                        .formatted(ConfigUnits.formatDuration(state.elapsed())), color) : null;
                case "elapsed_raw" -> completion.elapsed()
                    ? new Segment(Long.toString(elapsedSeconds), color) : null;
                default -> null;
            };
        }
        if (result instanceof TaskResult.Cancelled) {
            var key = state.operation() == BackupRequest.Kind.VERIFY
                ? "completion.verify_cancelled" : "completion.cancelled";
            return switch (name) {
                case "result" -> new Segment(Translations.languageText(language, key), color);
                case "result_raw" -> new Segment("cancelled", color);
                default -> null;
            };
        }
        var text = result instanceof TaskResult.Failed failed
            ? Translations.languageText(language, state.operation() == BackupRequest.Kind.VERIFY
                    ? "completion.verify_failed" : "completion.failed")
                .formatted(Translations.languageText(language,
                    "failure." + failed.reason().name().toLowerCase(Locale.ROOT)))
            : Translations.languageText(language, "completion.uncertain");
        var raw = result instanceof TaskResult.Failed ? "failure" : "uncertain";
        return switch (name) {
            case "result" -> new Segment(text, color);
            case "result_raw" -> new Segment(raw, color);
            default -> null;
        };
    }

    private Segment savedSegment(String language, long saved, long percent, ProgressConfig.Completion settings,
                                 String color) {
        if (settings.savedSpace() && settings.savedPercentage()) {
            return new Segment(Translations.languageText(language, "completion.saved")
                .formatted(config.size().format(saved), percent), color);
        }
        if (settings.savedSpace()) {
            return new Segment(Translations.languageText(language, "completion.saved_space")
                .formatted(config.size().format(saved)), color);
        }
        if (settings.savedPercentage() && percent >= 0) {
            return new Segment(Translations.languageText(language, "completion.saved_percentage")
                .formatted(percent), color);
        }
        return null;
    }

    private String completionTemplate(ProgressConfig.Operation settings, TaskResult result) {
        if (success(result)) return settings.templates().success();
        if (result instanceof TaskResult.Cancelled) return settings.templates().cancelled();
        return settings.templates().failure();
    }

    private static Component component(List<Segment> segments) {
        var result = Component.empty();
        for (var segment : segments) {
            result.append(Component.literal(segment.text())
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb(segment.color())))));
        }
        return result;
    }

    private static int rgb(String color) {
        try {
            var hex = hex(color);
            return Integer.parseInt(hex.substring(1), 16);
        } catch (RuntimeException invalid) {
            return 0xFFFFFF;
        }
    }

    /** 颜色名对应到 HEX，HEX 原样返回 */
    private static String hex(String color) {
        if (color.startsWith("#")) return color;
        return switch (color) {
            case "pink" -> "#FF55FF";
            case "blue" -> "#5555FF";
            case "red" -> "#FF5555";
            case "green" -> "#55FF55";
            case "yellow" -> "#FFFF55";
            case "purple" -> "#AA00AA";
            default -> "#FFFFFF";
        };
    }

    private static String stage(String language, TaskState.Phase phase) {
        return Translations.languageText(language, "task.phase." + phase.name().toLowerCase(Locale.ROOT));
    }

    /** BossBar 只支持固定颜色：HEX 取最接近的原版颜色 */
    private static BossEvent.BossBarColor bossColor(String color) {
        if (!color.startsWith("#")) return namedBossColor(color);
        int target;
        try {
            target = Integer.parseInt(color.substring(1), 16);
        } catch (NumberFormatException invalid) {
            return BossEvent.BossBarColor.WHITE;
        }
        int red = (target >> 16) & 0xFF;
        int green = (target >> 8) & 0xFF;
        int blue = target & 0xFF;
        BossEvent.BossBarColor best = BossEvent.BossBarColor.WHITE;
        int bestDistance = Integer.MAX_VALUE;
        for (var candidate : BossEvent.BossBarColor.values()) {
            int value = bossColorRgb(candidate);
            int dr = red - ((value >> 16) & 0xFF);
            int dg = green - ((value >> 8) & 0xFF);
            int db = blue - (value & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static BossEvent.BossBarColor namedBossColor(String color) {
        return switch (color) {
            case "pink" -> BossEvent.BossBarColor.PINK;
            case "blue" -> BossEvent.BossBarColor.BLUE;
            case "red" -> BossEvent.BossBarColor.RED;
            case "green" -> BossEvent.BossBarColor.GREEN;
            case "yellow" -> BossEvent.BossBarColor.YELLOW;
            case "purple" -> BossEvent.BossBarColor.PURPLE;
            default -> BossEvent.BossBarColor.WHITE;
        };
    }

    private static int bossColorRgb(BossEvent.BossBarColor color) {
        return switch (color) {
            case PINK -> 0xFF55FF;
            case BLUE -> 0x5555FF;
            case RED -> 0xFF5555;
            case GREEN -> 0x55FF55;
            case YELLOW -> 0xFFFF55;
            case PURPLE -> 0xAA00AA;
            case WHITE -> 0xFFFFFF;
        };
    }

    private static BossEvent.BossBarOverlay bossStyle(String style) {
        return switch (style) {
            case "notched_6" -> BossEvent.BossBarOverlay.NOTCHED_6;
            case "notched_10" -> BossEvent.BossBarOverlay.NOTCHED_10;
            case "notched_12" -> BossEvent.BossBarOverlay.NOTCHED_12;
            case "notched_20" -> BossEvent.BossBarOverlay.NOTCHED_20;
            default -> BossEvent.BossBarOverlay.PROGRESS;
        };
    }
}
