// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.apihost;

import barusikozako.api.TaskEvent;
import barusikozako.api.TaskState;
import barusikozako.api.TaskSubscription;
import barusikozako.api.TaskSubscription.CloseReason;
import barusikozako.api.TaskSubscription.State;
import barusikozako.api.TaskSubscription.Status;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns bounded notification buffers. Publishers never execute any listener themselves. */
final class TaskNotifications implements AutoCloseable {
    private static final int DELIVERY_SLICE = 16;
    private final int maximumSubscriptions;
    private final int eventsPerSubscriber;
    private final long acknowledgementNanos;
    private final HashSet<Subscription> subscriptions = new HashSet<>();
    private final ThreadPoolExecutor dispatch;
    private final ScheduledThreadPoolExecutor deadlines;
    private boolean closed;

    TaskNotifications(int workers, int maximumSubscriptions, int eventsPerSubscriber, Duration acknowledgementTimeout) {
        this.maximumSubscriptions = maximumSubscriptions;
        this.eventsPerSubscriber = eventsPerSubscriber;
        acknowledgementNanos = acknowledgementTimeout.toNanos();
        dispatch = new ThreadPoolExecutor(workers, workers, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(maximumSubscriptions), daemon("BaruSikoZako events"),
            new ThreadPoolExecutor.AbortPolicy());
        dispatch.allowCoreThreadTimeOut(true);
        deadlines = new ScheduledThreadPoolExecutor(1, daemon("BaruSikoZako subscription deadlines"));
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setKeepAliveTime(15, TimeUnit.SECONDS);
        deadlines.allowCoreThreadTimeOut(true);
    }

    private static ThreadFactory daemon(String name) {
        return action -> {
            var thread = new Thread(action, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Caller owns the same scheduler boundary that also publishes events. */
    synchronized TaskSubscription register(BoundAccess access, Optional<String> repository, List<TaskState> baseline,
                                             Consumer<TaskEvent> listener) {
        Objects.requireNonNull(listener);
        if (closed || subscriptions.size() == maximumSubscriptions)
            throw new RejectedExecutionException("Task subscription capacity is unavailable");
        if (!access.maySubscribe(repository)) throw new SecurityException("Task subscription is not authorized");
        var subscriber = new Subscription(access, repository, List.copyOf(baseline), listener);
        subscriptions.add(subscriber);
        subscriber.timeout = deadlines.schedule(
            () -> expire(subscriber), acknowledgementNanos, TimeUnit.NANOSECONDS);
        return subscriber;
    }

    private synchronized void expire(Subscription subscriber) {
        if (subscriber.status.state() == State.AWAITING_START) subscriber.close(CloseReason.TIMEOUT);
    }

    synchronized void publish(String repository, String owner, TaskEvent event) {
        if (subscriptions.isEmpty()) return;
        var delivery = new Envelope(repository, owner, event);
        for (var subscriber : List.copyOf(subscriptions)) {
            if (!subscriber.access.maySubscribe(subscriber.repository)) {
                subscriber.close(CloseReason.AUTHORIZATION_CHANGED);
            } else if (subscriber.canSee(delivery)) {
                subscriber.offer(delivery);
            }
        }
    }

    synchronized void revoke(BoundAccess access) {
        for (var subscriber : List.copyOf(subscriptions)) {
            if (subscriber.access == access) subscriber.close(CloseReason.AUTHORIZATION_CHANGED);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var subscriber : List.copyOf(subscriptions)) subscriber.close(CloseReason.HOST_STOPPED);
        deadlines.shutdownNow();
        dispatch.shutdownNow();
    }

    private record Envelope(String repository, String owner, TaskEvent event) {}

    private final class Subscription implements TaskSubscription {
        private final BoundAccess access;
        private final Optional<String> repository;
        private final List<TaskState> baseline;
        private final ArrayDeque<Envelope> queue = new ArrayDeque<>();
        private final Runnable delivery = this::drain;
        private Consumer<TaskEvent> listener;
        private Status status = new Status(State.AWAITING_START, Optional.empty(), false);
        private ScheduledFuture<?> timeout;
        private boolean scheduled;

        private Subscription(BoundAccess access, Optional<String> repository, List<TaskState> baseline,
                             Consumer<TaskEvent> listener) {
            this.access = access;
            this.repository = repository;
            this.baseline = baseline;
            this.listener = listener;
        }

        @Override
        public List<TaskState> baseline() {
            synchronized (TaskNotifications.this) {
                return access.maySubscribe(repository) ? baseline : List.of();
            }
        }

        @Override
        public Status start() {
            synchronized (TaskNotifications.this) {
                if (status.state() != State.AWAITING_START) return status;
                if (!access.maySubscribe(repository)) {
                    close(CloseReason.AUTHORIZATION_CHANGED);
                    return status;
                }
                timeout.cancel(false);
                timeout = null;
                status = new Status(State.ACTIVE, Optional.empty(), false);
                schedule();
                return status;
            }
        }

        @Override
        public Status status() {
            synchronized (TaskNotifications.this) {
                return status;
            }
        }

        @Override
        public void close() {
            synchronized (TaskNotifications.this) {
                close(CloseReason.CANCELLED);
            }
        }

        private boolean canSee(Envelope envelope) {
            return (repository.isEmpty() || repository.orElseThrow().equals(envelope.repository)) &&
                access.mayViewTask(envelope.repository, envelope.owner, true);
        }

        private void offer(Envelope envelope) {
            Envelope tail = queue.peekLast();
            if (tail != null && tail.event instanceof TaskEvent.Progress older &&
                envelope.event instanceof TaskEvent.Progress newer && older.state().id().equals(newer.state().id())) {
                queue.removeLast();
            }
            if (queue.size() >= eventsPerSubscriber) {
                close(CloseReason.OVERFLOW);
                return;
            }
            queue.addLast(envelope);
            schedule();
        }

        private void schedule() {
            if (scheduled || status.state() != State.ACTIVE || queue.isEmpty()) return;
            scheduled = true;
            try {
                dispatch.execute(delivery);
            } catch (RejectedExecutionException unavailable) {
                close(CloseReason.OVERFLOW);
            }
        }

        private void drain() {
            for (int i = 0; i < DELIVERY_SLICE; i++) {
                Consumer<TaskEvent> target;
                Envelope envelope;
                synchronized (TaskNotifications.this) {
                    if (status.state() != State.ACTIVE || queue.isEmpty()) {
                        scheduled = false;
                        return;
                    }
                    envelope = queue.removeFirst();
                    if (!canSee(envelope)) {
                        close(CloseReason.AUTHORIZATION_CHANGED);
                        return;
                    }
                    // This selection is the delivery boundary. A callback selected before a
                    // concurrent close or revocation may have already begun on this worker.
                    target = listener;
                }
                try {
                    target.accept(envelope.event);
                } catch (Throwable failure) {
                    synchronized (TaskNotifications.this) {
                        close(CloseReason.LISTENER_FAILED);
                    }
                    return;
                }
            }
            synchronized (TaskNotifications.this) {
                scheduled = false;
                schedule();
            }
        }

        private void close(CloseReason reason) {
            if (status.state() == State.CLOSED) return;
            boolean gap = !queue.isEmpty() || reason == CloseReason.OVERFLOW ||
                reason == CloseReason.LISTENER_FAILED || reason == CloseReason.AUTHORIZATION_CHANGED;
            status = new Status(State.CLOSED, Optional.of(reason), gap);
            if (timeout != null) timeout.cancel(false);
            timeout = null;
            queue.clear();
            listener = null;
            dispatch.remove(delivery);
            subscriptions.remove(this);
        }
    }
}
