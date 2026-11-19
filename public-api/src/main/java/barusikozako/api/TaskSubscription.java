// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api;

import java.util.List;
import java.util.Optional;

public interface TaskSubscription extends AutoCloseable {
    List<TaskState> baseline();

    /** Idempotent; never reopens a closed subscription. */
    Status start();

    /** Reads the status without using the event queue. */
    Status status();

    @Override
    void close();

    record Status(State state, Optional<CloseReason> reason, boolean eventGap) {}

    enum State { AWAITING_START, ACTIVE, CLOSED }

    enum CloseReason { CANCELLED, TIMEOUT, OVERFLOW, AUTHORIZATION_CHANGED, HOST_STOPPED, LISTENER_FAILED }
}
