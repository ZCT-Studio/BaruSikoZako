// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.apihost;

import barusikozako.api.BackupRequest;
import barusikozako.api.SnapshotRef;
import barusikozako.apihost.BoundAccess.Grant;
import barusikozako.apihost.BoundAccess.Right;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
        System.out.println("Public API access checks passed.");
        RuntimeChecks.exercise();
        DeliveryChecks.exercise();
        SubscriptionChecks.exercise();
    }

    private static Grant grant(Map<String, ? extends Set<Right>> repositories) {
        return new Grant(new HashMap<>(repositories), Set.of("world"), Set.of("world"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
