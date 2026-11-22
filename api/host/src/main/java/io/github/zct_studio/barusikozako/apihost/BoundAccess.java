// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** 单个集成句柄的主机端策略，独立于请求诊断 */
public final class BoundAccess {
    public enum Right {
        BACKUP, VERIFY, RESTORE, DELETE, VIEW, VIEW_OTHERS, SUBSCRIBE, CANCEL, CANCEL_OTHERS, CONFIGURE
    }

    public record Grant(Map<String, Set<Right>> repositories, Set<String> backupSources, Set<String> restoreTargets) {
        public Grant {
            var rights = new HashMap<String, Set<Right>>();
            repositories.forEach((repository, values) -> rights.put(repository, Set.copyOf(values)));
            repositories = Map.copyOf(rights);
            backupSources = Set.copyOf(backupSources);
            restoreTargets = Set.copyOf(restoreTargets);
        }

        boolean allows(String repository, Right right) {
            return repositories.getOrDefault(repository, Set.of()).contains(right);
        }
    }

    private final String integrationId;
    private final AtomicReference<Grant> policy;

    BoundAccess(String trustedIntegrationId, Grant initialPolicy) {
        if (trustedIntegrationId.isBlank()) throw new IllegalArgumentException("missing trusted integration identifier");
        integrationId = trustedIntegrationId;
        policy = new AtomicReference<>(java.util.Objects.requireNonNull(initialPolicy));
    }

    /** 在入院时进行评估，并在仍可安全拒绝的修改之前再次进行评估 */
    boolean maySubmit(BackupRequest request) {
        Grant grant = policy.get();
        if (grant == null) return false;
        String repository = request.repositoryId();
        return switch (request.kind()) {
            case BACKUP -> grant.allows(repository, Right.BACKUP) &&
                grant.backupSources().contains(((BackupRequest.Backup) request).sourceId());
            case VERIFY -> grant.allows(repository, Right.VERIFY);
            case RESTORE -> grant.allows(repository, Right.RESTORE) &&
                grant.restoreTargets().contains(((BackupRequest.Restore) request).targetId());
            case DELETE -> grant.allows(repository, Right.DELETE);
        };
    }

    boolean mayViewRepository(String repository) {
        Grant grant = policy.get();
        return grant != null && grant.allows(repository, Right.VIEW);
    }

    boolean maySubscribe(Optional<String> repository) {
        var grant = policy.get();
        if (grant == null) return false;
        if (repository.isPresent()) {
            var id = repository.orElseThrow();
            return grant.allows(id, Right.VIEW) && grant.allows(id, Right.SUBSCRIBE);
        }
        return grant.repositories().values().stream().anyMatch(set -> set.contains(Right.VIEW) && set.contains(Right.SUBSCRIBE));
    }

    /** 适用于任务查找以及订阅的基线和事件 */
    boolean mayViewTask(String repository, String owner, boolean subscription) {
        Grant grant = policy.get();
        return grant != null && grant.allows(repository, Right.VIEW) &&
            (!subscription || grant.allows(repository, Right.SUBSCRIBE)) &&
            (integrationId.equals(owner) || grant.allows(repository, Right.VIEW_OTHERS));
    }

    boolean mayCancel(String repository, String owner) {
        Grant grant = policy.get();
        return grant != null && grant.allows(repository, Right.CANCEL) &&
            (integrationId.equals(owner) || grant.allows(repository, Right.CANCEL_OTHERS));
    }

    /** 该句柄的撤销是永久性的。发放新的拨款需要新的处理方式 */
    synchronized void revoke() {
        policy.set(null);
    }

    String owner() {
        return integrationId;
    }

    boolean isActive() {
        return policy.get() != null;
    }

    boolean mayConfigure() {
        var grant = policy.get();
        return grant != null && grant.repositories().values().stream().anyMatch(rights -> rights.contains(Right.CONFIGURE));
    }
}
