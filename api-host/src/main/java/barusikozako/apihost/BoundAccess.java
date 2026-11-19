// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.apihost;

import barusikozako.api.BackupRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Host-side policy for a single integration handle, independent of request diagnostics. */
public final class BoundAccess {
    public enum Right {
        BACKUP, VERIFY, RESTORE, DELETE, VIEW, VIEW_OTHERS, SUBSCRIBE, CANCEL, CANCEL_OTHERS
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

    /** Evaluated at admission and again before modifications that can still safely be denied. */
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

    /** Applies to task lookup and to both the baseline and events of a subscription. */
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

    /** Revocation is permanent for this handle. Issuing a fresh grant requires a fresh handle. */
    synchronized void revoke() {
        policy.set(null);
    }

    String owner() {
        return integrationId;
    }

    boolean isActive() {
        return policy.get() != null;
    }
}
