// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class CaptureSignalsChecks {
    private CaptureSignalsChecks() {}

    public static void main(String[] arguments) {
        check(AgentCaptureSignals.cut(), AgentCaptureSignals.State.UNKNOWN);
        AgentCaptureSignals.observeStore(CompletableFuture.completedFuture(null));
        var first = new CompletableFuture<Void>();
        var replacement = new CompletableFuture<Void>();
        var committedWrite = new CompletableFuture<Void>();
        var work = new ArrayDeque<Runnable>();
        Executor tick = work::add;
        var original = AgentCaptureSignals.observeUnload(first, ignored ->
            AgentCaptureSignals.observeUnload(replacement, ready -> AgentCaptureSignals.observeStore(committedWrite), tick), tick);
        long cut = AgentCaptureSignals.cut();
        var subsequent = new CompletableFuture<Void>();
        AgentCaptureSignals.observeStore(subsequent);
        check(cut, AgentCaptureSignals.State.PENDING);
        first.complete(null);
        work.remove().run();
        if (!original.isDone()) throw new AssertionError("Original unload result was delayed");
        check(cut, AgentCaptureSignals.State.PENDING);
        replacement.complete(null);
        work.remove().run();
        check(cut, AgentCaptureSignals.State.PENDING);
        committedWrite.complete(null);
        check(cut, AgentCaptureSignals.State.QUIESCENT);
        long includingSubsequent = AgentCaptureSignals.cut();
        check(includingSubsequent, AgentCaptureSignals.State.PENDING);
        subsequent.completeExceptionally(new IOException("simulated write failure"));
        check(cut, AgentCaptureSignals.State.QUIESCENT);
        check(includingSubsequent, AgentCaptureSignals.State.FAILED);
        AgentCaptureSignals.observeStore(CompletableFuture.completedFuture(null));
        check(AgentCaptureSignals.cut(), AgentCaptureSignals.State.FAILED);

        var unresolved = new CompletableFuture<Void>();
        for (int i = 0; i < 65_537; i++) AgentCaptureSignals.observeStore(unresolved);
        check(AgentCaptureSignals.cut(), AgentCaptureSignals.State.UNKNOWN);
        unresolved.complete(null);
        check(AgentCaptureSignals.cut(), AgentCaptureSignals.State.UNKNOWN);
        System.out.println("Legacy capture fence checks passed");
        checkMoonriseProducer();
        checkMoonriseReplacement();
    }

    private static void checkMoonriseProducer() {
        long producer = MoonriseSaveSignals.openProducer();
        MoonriseSaveSignals.suspendUnload(new Object(), producer);
        long cut = MoonriseSaveSignals.cut();
        if (!MoonriseSaveSignals.state(cut).equals("PENDING"))
            throw new AssertionError("The cut failed to retain a suspended unload");
        MoonriseSaveSignals.resumeUnload(producer);
        long child = MoonriseSaveSignals.create();
        MoonriseSaveSignals.submitted(child, 0);
        MoonriseSaveSignals.finishProducer(producer, null);
        if (!MoonriseSaveSignals.state(cut).equals("PENDING"))
            throw new AssertionError("The cut lost a write registered after the cut");
        MoonriseSaveSignals.payload(child, null);
        MoonriseSaveSignals.complete(true, child);
        if (!MoonriseSaveSignals.state(cut).equals("DRAINED"))
            throw new AssertionError("The cut retained already closed work");
    }

    private static void check(long cut, AgentCaptureSignals.State expected) {
        var actual = AgentCaptureSignals.state(cut);
        if (actual != expected) throw new AssertionError("Expected " + expected + " but found " + actual);
    }

    private static void checkMoonriseReplacement() {
        long active = MoonriseSaveSignals.create();
        MoonriseSaveSignals.submitted(active, 0);
        MoonriseSaveSignals.payload(active, null);
        MoonriseSaveSignals.compressionScheduled(active);
        long skipped = MoonriseSaveSignals.create();
        MoonriseSaveSignals.submitted(skipped, active);
        long replacement = MoonriseSaveSignals.create();
        MoonriseSaveSignals.submitted(replacement, skipped);
        MoonriseSaveSignals.payload(replacement, null);
        MoonriseSaveSignals.compressionScheduled(replacement);
        MoonriseSaveSignals.complete(false, active);
        if (!MoonriseSaveSignals.state(skipped).equals("PENDING"))
            throw new AssertionError("The skipped generation needs its replacement and its payload");
        MoonriseSaveSignals.failed(new IOException("test replacement failed"), replacement, "write");
        MoonriseSaveSignals.complete(true, replacement);
        if (!MoonriseSaveSignals.state(skipped).equals("FAILED"))
            throw new AssertionError("A replacement failure must affect the earlier skipped generation's cut");
        MoonriseSaveSignals.payload(skipped, null);
        if (!MoonriseSaveSignals.snapshot().contains("pending=0"))
            throw new AssertionError("The upstream payload did not retire the skipped generation");
        System.out.println("Moonrise replacement fence checks passed");
    }
}
