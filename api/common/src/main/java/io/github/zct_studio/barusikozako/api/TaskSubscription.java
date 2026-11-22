// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

import java.util.List;
import java.util.Optional;

public interface TaskSubscription extends AutoCloseable {
    List<TaskState> baseline();

    /** 具有幂等性，不会重新打开已关闭的订阅 */
    Status start();

    /** 读取状态时不使用事件队列 */
    Status status();

    @Override
    void close();

    record Status(State state, Optional<CloseReason> reason, boolean eventGap) {}

    enum State { AWAITING_START, ACTIVE, CLOSED }

    enum CloseReason { CANCELLED, TIMEOUT, OVERFLOW, AUTHORIZATION_CHANGED, HOST_STOPPED, LISTENER_FAILED }
}
