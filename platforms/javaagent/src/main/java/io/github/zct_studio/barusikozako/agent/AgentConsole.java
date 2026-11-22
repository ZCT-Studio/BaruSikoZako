// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskId;
import java.util.Map;
import java.util.Optional;

/** 被审计的专用服务器的控制台输入接受的命令 */
final class AgentConsole {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako Agent console");
    private static final String COMMAND = "barusikozako";
    private static final String USAGE = "barusikozako backup | list | verify <snapshot UUID> | status <task ID> | cancel <task ID>";
    private final BackupService service;

    AgentConsole(BackupService service) {
        this.service = service;
    }

    boolean accept(String input) {
        String[] words = input.strip().split("\\s+", 4);
        if (!words[0].equals(COMMAND) && !words[0].equals("/" + COMMAND)) return false;
        String action = words.length >= 2 ? words[1] : "";
        if (words.length == 2 && action.equals("backup")) {
            submit(new BackupRequest.Backup(AgentOperations.REPOSITORY_ID, "worlds",
                new BackupRequest.Trigger("agent-console", "manual backup", Map.of()),
                BackupRequest.BackupMode.FULL, Optional.empty()));
        } else if (words.length == 2 && action.equals("list")) {
            service.snapshots(AgentOperations.REPOSITORY_ID, new BackupService.PageRequest(20, Optional.empty()))
                .whenComplete((page, failure) -> {
                    if (failure == null) report(message("snapshots", page.items()));
                    else LOG.log(System.Logger.Level.WARNING, "Could not list snapshots", failure);
                });
        } else if (words.length == 3) {
            switch (action) {
                case "verify" -> submit(new BackupRequest.Verify(new SnapshotRef(AgentOperations.REPOSITORY_ID, words[2]),
                    BackupRequest.VerificationLevel.FULL));
                case "status" -> report(service.task(new TaskId(words[2])).map(Object::toString).orElse(message("task_unavailable")));
                case "cancel" -> report(message("cancellation", service.cancel(new TaskId(words[2]))));
                default -> report(USAGE);
            }
        } else report(USAGE);
        return true;
    }

    private void submit(BackupRequest request) {
        var submission = service.submit(request);
        if (submission instanceof BackupService.Submission.Accepted task) {
            report(message("accepted", task.id().value()));
            task.completion().whenComplete((outcome, failure) -> {
                if (failure == null) report(message("completed", task.id().value(), outcome));
                else LOG.log(System.Logger.Level.WARNING, "Task notification failed: " + task.id().value(), failure);
            });
        } else report(message("rejected", ((BackupService.Submission.Rejected) submission).reason()));
    }

    private static String message(String key, Object... arguments) {
        String selected = System.getProperty("barusikozako.language", "en_us").toLowerCase(java.util.Locale.ROOT);
        if (!selected.matches("[a-z0-9_]{1,32}")) selected = "en_us";
        var messages = new java.util.Properties();
        try (var defaults = AgentConsole.class.getResourceAsStream("/assets/barusikozako/agent/messages_en_us.properties")) {
            if (defaults != null) messages.load(new java.io.InputStreamReader(defaults, java.nio.charset.StandardCharsets.UTF_8));
            if (!selected.equals("en_us")) {
                try (var translation = AgentConsole.class.getResourceAsStream(
                    "/assets/barusikozako/agent/messages_" + selected + ".properties")) {
                    if (translation != null) messages.load(new java.io.InputStreamReader(translation, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (java.io.IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not load console language", failure);
        }
        return java.text.MessageFormat.format(messages.getProperty(key, key), arguments);
    }

    private static void report(String message) {
        LOG.log(System.Logger.Level.INFO, message);
    }
}
