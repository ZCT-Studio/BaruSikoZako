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

    private static Grant grant(Map<String, ? extends Set<Right>> repositories) {
        return new Grant(new HashMap<>(repositories), Set.of("world"), Set.of("world"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
