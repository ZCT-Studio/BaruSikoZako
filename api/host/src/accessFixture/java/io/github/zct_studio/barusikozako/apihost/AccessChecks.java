// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.apihost.BoundAccess.Grant;
import io.github.zct_studio.barusikozako.apihost.BoundAccess.Right;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class AccessChecks {
    public static void main(String[] args) throws Exception {
        var rights = EnumSet.of(Right.BACKUP, Right.VIEW, Right.SUBSCRIBE, Right.CANCEL);
        var repositories = new HashMap<>(Map.of("local", rights));
        var access = new BoundAccess("editor", grant(repositories));
        rights.add(Right.RESTORE);
        repositories.put("other", rights);

        var metadata = new HashMap<>(Map.of("operation", "edit"));
        var trigger = new BackupRequest.Trigger("another-integration", "before edit", metadata);
        metadata.put("operation", "changed");
        require(trigger.metadata().get("operation").equals("edit"), "trigger metadata was not copied");
        var backup = new BackupRequest.Backup("local", "world", trigger);
        require(access.maySubmit(backup), "assigned backup source rejected");
        require(!access.maySubmit(new BackupRequest.Backup("local", "private-world", trigger)), "ungranted source");
        require(!access.maySubmit(new BackupRequest.Backup("other", "world", trigger)), "ungranted repository");

        var snapshot = new SnapshotRef("local", "snapshot-1");
        var restore = new BackupRequest.Restore(snapshot, "world");
        require(!access.maySubmit(restore), "restore right leaked through caller-owned collection");
        require(!access.maySubmit(new BackupRequest.Delete(snapshot)), "delete right was implicit");
        require(!access.maySubmit(new BackupRequest.Verify(snapshot, BackupRequest.VerificationLevel.FULL)),
            "verify right was implicit");
        require(access.mayViewRepository("local") && !access.mayViewRepository("other"), "repository visibility leaked");
        require(access.mayViewTask("local", "editor", true), "own subscription rejected");
        require(!access.mayViewTask("local", "another-integration", false), "task lookup leaked");
        require(!access.mayViewTask("local", "another-integration", true), "subscription leaked");
        require(access.mayCancel("local", "editor"), "own cancellation rejected");
        require(!access.mayCancel("local", "another-integration"), "foreign cancellation accepted");
        require(!access.mayConfigure(), "configuration permission was implicit");

        var configAccess = new BoundAccess("config", grant(Map.of("local", Set.of(Right.CONFIGURE))));
        require(configAccess.mayConfigure(), "explicit configuration permission rejected");
        configAccess.revoke();
        require(!configAccess.mayConfigure(), "revoked configuration access remained active");

        var operator = new BoundAccess("operator", grant(Map.of("local", Set.of(Right.RESTORE, Right.DELETE,
            Right.VIEW, Right.VIEW_OTHERS))));
        require(operator.maySubmit(restore), "explicit restore permission rejected");
        require(!operator.maySubmit(new BackupRequest.Restore(snapshot, "another-world")), "restore target unchecked");
        require(operator.maySubmit(new BackupRequest.Delete(snapshot)), "explicit delete permission rejected");
        require(operator.mayViewTask("local", "editor", false), "explicit cross-owner view rejected");
        require(!operator.mayViewTask("local", "editor", true), "subscription right was implicit");
        operator.revoke();
        operator.revoke();
        require(!operator.maySubmit(restore), "revoked restore could pass recheck");
        require(!operator.mayViewTask("local", "operator", false), "revoked handle could still view a task");
        worldRuleLifecycle();
        readSurface();
        messagingSurface();
        metadataSurface();
        System.out.println("Public API access checks passed.");
        RuntimeChecks.exercise();
        DeliveryChecks.exercise();
        SubscriptionChecks.exercise();
    }

    /** 任务列表、快照详情、状态与版本应与指令暴露的信息一致 */
    private static void readSurface() throws Exception {
        var one = new SnapshotRef("local", "snap-1");
        var two = new SnapshotRef("other", "snap-2");
        var snapshots = List.of(
            new BackupService.SnapshotInfo(one, Instant.parse("2026-01-01T00:00:00Z"), "world"),
            new BackupService.SnapshotInfo(two, Instant.parse("2026-01-02T00:00:00Z"), "world"));
        var status = new BackupService.ServiceStatus(true, Optional.of("fixture"),
            Optional.of(new BackupService.RepositoryInfo("local", "fixture")),
            Optional.of(java.nio.file.Path.of("repository")), 60, Optional.of(java.nio.file.Path.of("config")));
        var version = new BackupService.VersionInfo("1.0.0", Optional.of("1.0.0"), Map.of("fixture", "1.0.0"));
        var operations = new BackupApiHost.Operations() {
            @Override public BackupService.Availability availability(BackupRequest.Kind kind) {
                return BackupService.Availability.AVAILABLE;
            }
            @Override public Optional<BackupService.Rejection> evaluate(BackupRequest request) {
                return Optional.empty();
            }
            @Override public List<BackupService.RepositoryInfo> repositories() {
                return List.of(new BackupService.RepositoryInfo("local", "fixture"),
                    new BackupService.RepositoryInfo("other", "fixture"));
            }
            @Override public BackupService.Page<BackupService.SnapshotInfo> snapshots(
                    String repository, BackupService.PageRequest page) {
                var items = snapshots.stream().filter(item -> item.snapshot().repositoryId().equals(repository)).toList();
                return new BackupService.Page<>(items, Optional.empty());
            }
            @Override public BackupService.ServiceStatus status() { return status; }
            @Override public BackupService.VersionInfo version() { return version; }
            @Override public Map<String, String> backendConfiguration(String backend) {
                return Map.of("level", "3");
            }
            @Override public void updateBackendConfiguration(String backend, String key, String value) {}
            @Override public TaskResult run(BackupRequest request, BackupApiHost.TaskControl control) {
                require(control.beginCommit(), "fixture delete could not commit");
                return new TaskResult.Deleted(((BackupRequest.Delete) request).snapshot());
            }
        };
        var rights = EnumSet.of(Right.VIEW, Right.DELETE, Right.CONFIGURE);
        try (var host = new BackupApiHost(operations);
             var binding = host.bind("editor", new Grant(Map.of("local", rights, "other", rights),
                 Set.of("world"), Set.of()))) {
            var service = binding.service();
            for (var snapshot : List.of(one, two)) {
                var submission = (BackupService.Submission.Accepted) service.submit(new BackupRequest.Delete(snapshot));
                submission.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            var firstPage = service.tasks(new BackupService.PageRequest(1, Optional.empty()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(firstPage.items().size() == 1 && firstPage.next().isPresent(), "task cursor was not exposed");
            var secondPage = service.tasks(new BackupService.PageRequest(1, firstPage.next()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(secondPage.items().size() == 1 && !secondPage.items().get(0).state().id()
                .equals(firstPage.items().get(0).state().id()), "task cursor did not advance");
            require(service.snapshot(two).toCompletableFuture().get(5, TimeUnit.SECONDS)
                .map(info -> info.snapshot().equals(two)).orElse(false), "snapshot lookup missed its target");
            require(service.snapshot(new SnapshotRef("local", "missing"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty(), "snapshot lookup invented a result");
            require(service.status().toCompletableFuture().get(5, TimeUnit.SECONDS).equals(status),
                "status did not match the host");
            require(service.version().toCompletableFuture().get(5, TimeUnit.SECONDS).equals(version),
                "version did not match the host");
            require(service.backendConfiguration("fixture").toCompletableFuture().get(5, TimeUnit.SECONDS)
                .equals(Map.of("level", "3")), "backend configuration was not readable");
            try (var viewer = host.bind("viewer", new Grant(Map.of("local", Set.of(Right.VIEW)),
                    Set.of("world"), Set.of()))) {
                requireRejected(viewer.service().backendConfiguration("fixture"));
            }
        }
    }

    private static void requireRejected(java.util.concurrent.CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            throw new AssertionError("configuration access was not authorized");
        } catch (java.util.concurrent.ExecutionException expected) {
            require(expected.getCause() instanceof SecurityException, "unexpected configuration failure");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new AssertionError(timeout);
        }
    }

    private static void worldRuleLifecycle() throws Exception {
        var registered = new java.util.HashSet<String>();
        var operations = new BackupApiHost.Operations() {
            @Override public AutoCloseable registerWorldExclusion(String source, String pattern) {
                String rule = source + ":" + pattern;
                if (!registered.add(rule)) throw new IllegalStateException("Duplicate registration");
                return () -> registered.remove(rule);
            }
            @Override public io.github.zct_studio.barusikozako.api.BackupService.Availability availability(BackupRequest.Kind kind) {
                throw new UnsupportedOperationException();
            }
            @Override public java.util.Optional<io.github.zct_studio.barusikozako.api.BackupService.Rejection>
                evaluate(BackupRequest request) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<io.github.zct_studio.barusikozako.api.BackupService.RepositoryInfo>
                repositories() { throw new UnsupportedOperationException(); }
            @Override public io.github.zct_studio.barusikozako.api.BackupService.Page<
                    io.github.zct_studio.barusikozako.api.BackupService.SnapshotInfo> snapshots(
                    String repository, io.github.zct_studio.barusikozako.api.BackupService.PageRequest page) {
                throw new UnsupportedOperationException();
            }
            @Override public io.github.zct_studio.barusikozako.api.TaskResult run(
                    BackupRequest request, BackupApiHost.TaskControl control) { throw new UnsupportedOperationException(); }
        };
        var host = new BackupApiHost(operations);
        try (host; var binding = host.bind("editor", grant(Map.of()))) {
            var service = binding.service();
            var first = service.registerWorldExclusion("*.tmp");
            require(registered.contains("editor:*.tmp"), "source was not attributed to the integration");
            first.close();
            first.close();
            require(registered.isEmpty(), "closing a rule did not unregister it");
            service.registerWorldExclusion("*.tmp");
            binding.close();
            require(registered.isEmpty(), "revoking an integration leaked its registered rule");
            host.bind("another", grant(Map.of())).service().registerWorldExclusion("cache/");
        }
        require(registered.isEmpty(), "closing the host leaked registered world rules");
    }

    /** 日志来源自动注入、消息受众限制、执行者解析与撤销后的行为 */
    private static void messagingSurface() throws Exception {
        var logged = new java.util.ArrayList<String>();
        var messages = new java.util.ArrayList<String>();
        var operations = new BackupApiHost.Operations() {
            @Override public void log(String source, BackupService.LogLevel level, String message) {
                logged.add(source + "|" + level + "|" + message);
            }
            @Override public java.util.concurrent.CompletionStage<Integer> sendMessage(
                    String source, BackupService.MessageTarget target, String message,
                    Optional<BackupService.BackupOperator> executor) {
                messages.add(source + "|" + target + "|" + message + "|"
                    + executor.map(BackupService.BackupOperator::name).orElse("-"));
                return java.util.concurrent.CompletableFuture.completedStage(
                    target == BackupService.MessageTarget.ALL ? 3 : 1);
            }
            @Override public BackupService.Availability availability(BackupRequest.Kind kind) {
                return BackupService.Availability.AVAILABLE;
            }
            @Override public Optional<BackupService.Rejection> evaluate(BackupRequest request) {
                return Optional.empty();
            }
            @Override public List<BackupService.RepositoryInfo> repositories() {
                return List.of(new BackupService.RepositoryInfo("local", "fixture"));
            }
            @Override public BackupService.Page<BackupService.SnapshotInfo> snapshots(
                    String repository, BackupService.PageRequest page) {
                return new BackupService.Page<>(List.of(), Optional.empty());
            }
            @Override public TaskResult run(BackupRequest request, BackupApiHost.TaskControl control) {
                require(control.beginCommit(), "fixture message task could not commit");
                return new TaskResult.Deleted(((BackupRequest.Delete) request).snapshot());
            }
        };
        var rights = EnumSet.of(Right.VIEW, Right.DELETE);
        try (var host = new BackupApiHost(operations);
             var binding = host.bind("messenger", new Grant(Map.of("local", rights),
                 Set.of("world"), Set.of()))) {
            var service = binding.service();
            service.log(BackupService.LogLevel.WARN, "first line");
            require(logged.size() == 1, "log did not reach the host");
            require(logged.get(0).equals("messenger|WARN|first line"),
                "log source was not injected: " + logged.get(0));

            var all = service.sendMessage(BackupService.MessageTarget.ALL, "hello")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(all == 3, "ALL target did not reach every player");
            require(messages.get(0).equals("messenger|ALL|hello|-"),
                "ALL message context was wrong: " + messages.get(0));

            // EXECUTOR 解析同一句柄最近一次提交任务携带的操作人
            var actor = new BackupService.BackupOperator("Steve", Optional.of(java.util.UUID.randomUUID()));
            var accepted = (BackupService.Submission.Accepted) service.submit(
                new BackupRequest.Delete(new SnapshotRef("local", "snap-1")), Optional.of(actor));
            accepted.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            service.sendMessage(BackupService.MessageTarget.EXECUTOR, "done")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(messages.get(1).equals("messenger|EXECUTOR|done|Steve"),
                "executor was not resolved from the last submission: " + messages.get(1));

            binding.close();
            service.log(BackupService.LogLevel.ERROR, "after revoke");
            require(logged.size() == 1, "revoked handle still logged");
            var after = service.sendMessage(BackupService.MessageTarget.ALL, "after revoke")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(after == 0, "revoked handle still delivered messages");
        }
        require(messages.size() == 2, "revoked handle reached the host");
    }

    /** 宿主统一注入版本与操作人；拓展字段保留、伪造的宿主键被覆盖、非备份请求不受影响 */
    private static void metadataSurface() throws Exception {
        var captured = new java.util.concurrent.CopyOnWriteArrayList<BackupRequest>();
        var operations = new BackupApiHost.Operations() {
            @Override public java.util.Map<String, String> runtimeMetadata() {
                return java.util.Map.of("gameVersion", "26.3", "modVersion", "1.2.0", "backendVersion", "3.0.1");
            }
            @Override public BackupService.Availability availability(BackupRequest.Kind kind) {
                return BackupService.Availability.AVAILABLE;
            }
            @Override public Optional<BackupService.Rejection> evaluate(BackupRequest request) {
                return Optional.empty();
            }
            @Override public List<BackupService.RepositoryInfo> repositories() {
                return List.of(new BackupService.RepositoryInfo("local", "fixture"));
            }
            @Override public BackupService.Page<BackupService.SnapshotInfo> snapshots(
                    String repository, BackupService.PageRequest page) {
                return new BackupService.Page<>(List.of(), Optional.empty());
            }
            @Override public TaskResult run(BackupRequest request, BackupApiHost.TaskControl control) {
                captured.add(request);
                require(control.beginCommit(), "fixture commit rejected");
                return request instanceof BackupRequest.Delete delete
                    ? new TaskResult.Deleted(delete.snapshot())
                    : new TaskResult.BackupCommitted(new SnapshotRef("local", "snapshot"));
            }
        };
        var rights = EnumSet.of(Right.BACKUP, Right.VIEW, Right.DELETE);
        try (var host = new BackupApiHost(operations);
             var binding = host.bind("webui", grant(Map.of("local", rights)))) {
            var service = binding.service();

            // A web-style trigger: only an interface marker, a note and a forged version key.
            var metadata = new java.util.LinkedHashMap<String, String>();
            metadata.put("interface", "zakobakui");
            metadata.put("note", "before edit");
            metadata.put("gameVersion", "forged");
            var actor = new BackupService.BackupOperator("web:admin", Optional.of(java.util.UUID.randomUUID()));
            var accepted = (BackupService.Submission.Accepted) service.submit(
                new BackupRequest.Backup("local", "world",
                    new BackupRequest.Trigger("zakobakui", "Web interface backup", metadata)),
                Optional.of(actor));
            accepted.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(captured.size() == 1, "backup did not reach the host");
            var stored = ((BackupRequest.Backup) captured.get(0)).trigger().metadata();
            require("26.3".equals(stored.get("gameVersion")), "forged gameVersion was not overridden");
            require("1.2.0".equals(stored.get("modVersion")), "modVersion was not injected");
            require("3.0.1".equals(stored.get("backendVersion")), "backendVersion was not injected");
            require("web:admin".equals(stored.get("operator")), "operator was not injected");
            require(actor.id().orElseThrow().toString().equals(stored.get("operatorUuid")),
                "operatorUuid was not injected");
            require("zakobakui".equals(stored.get("interface")), "extension marker was dropped");
            require("before edit".equals(stored.get("note")), "extension note was dropped");

            // The metadata budget is still honoured when an extension fills the trigger.
            var full = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < 15; i++) full.put("custom" + i, "value" + i);
            full.put("gameVersion", "forged-again");
            var crowded = (BackupService.Submission.Accepted) service.submit(
                new BackupRequest.Backup("local", "world",
                    new BackupRequest.Trigger("zakobakui", "Web interface backup", full)));
            crowded.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var bounded = ((BackupRequest.Backup) captured.get(1)).trigger().metadata();
            require(bounded.size() <= 16, "metadata budget was exceeded");
            require("26.3".equals(bounded.get("gameVersion")), "host keys lost under a full trigger");
            require("1.2.0".equals(bounded.get("modVersion")), "modVersion lost under a full trigger");
            require("3.0.1".equals(bounded.get("backendVersion")), "backendVersion lost under a full trigger");

            // Non-backup requests are passed through untouched.
            var delete = new BackupRequest.Delete(new SnapshotRef("local", "snap-1"));
            var removed = (BackupService.Submission.Accepted) service.submit(delete);
            removed.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(captured.get(2) == delete, "non-backup request was rewritten");
        }
    }

    private static Grant grant(Map<String, ? extends Set<Right>> repositories) {
        return new Grant(new HashMap<>(repositories), Set.of("world"), Set.of("world"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
