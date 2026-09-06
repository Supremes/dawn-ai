package com.dawn.ai.config;

import io.micrometer.context.ContextSnapshotFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ExecutorService decorator that captures the current Micrometer and
 * {@link AiInteractionContext} state on submission and restores it on the worker
 * thread before the task runs. Use to wrap pools that handle work delegated from
 * a chat thread (e.g. RAG retrieval, embedding lookups) so downstream observations
 * remain children of the originating trace.
 */
public class SessionAwareExecutor implements ExecutorService {

    private static final ContextSnapshotFactory CONTEXT_SNAPSHOT_FACTORY =
            ContextSnapshotFactory.builder().build();

    private final ExecutorService delegate;

    public SessionAwareExecutor(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    private <T> Collection<? extends Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        return tasks.stream().map(this::wrap).collect(Collectors.toList());
    }

    private Runnable wrap(Runnable task) {
        return CONTEXT_SNAPSHOT_FACTORY.captureAll().wrap(AiInteractionContext.wrap(task));
    }

    private <T> Callable<T> wrap(Callable<T> task) {
        return CONTEXT_SNAPSHOT_FACTORY.captureAll().wrap(AiInteractionContext.wrap(task));
    }

    @Override
    public void shutdown() { delegate.shutdown(); }

    @Override
    public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }

    @Override
    public boolean isShutdown() { return delegate.isShutdown(); }

    @Override
    public boolean isTerminated() { return delegate.isTerminated(); }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
