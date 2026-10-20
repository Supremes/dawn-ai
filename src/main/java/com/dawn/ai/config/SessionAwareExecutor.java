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
 * ExecutorService 的装饰器：提交任务时把 Micrometer {@link io.micrometer.context.ContextRegistry}
 * 里注册的<em>所有</em> ThreadLocal 拍一份快照，任务在 worker 线程跑之前再恢复回去。
 * 结合 {@link AgentConfig#enableReactorContextPropagation()} 注册的 accessor，目前会一起带过去的有：
 * <ul>
 *   <li>当前 Micrometer {@code Observation}（trace / span）——下游 observation 仍挂在原 trace 下当子节点；</li>
 *   <li>{@link com.dawn.ai.agent.trace.StepCollectorContext}——worker 上记录的 step 落到同一个请求级收集器；</li>
 *   <li>{@link AiInteractionContext}（sessionId 等）——HTTP 拦截器能把请求归因回原 session。</li>
 * </ul>
 * 用它包住那些接收 chat 线程派发过来的工作的线程池（比如 RAG 检索、embedding 查询）。
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
        delegate.execute(propagating(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(propagating(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(propagating(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(propagating(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(propagatingAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(propagatingAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(propagatingAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(propagatingAll(tasks), timeout, unit);
    }

    private <T> Collection<? extends Callable<T>> propagatingAll(Collection<? extends Callable<T>> tasks) {
        return tasks.stream().map(this::propagating).collect(Collectors.toList());
    }

    /**
     * 在<em>提交线程</em>上把当前上下文拍成快照，再交给库的
     * {@link io.micrometer.context.ContextSnapshot#wrap} 生成一个带上下文的任务：
     * 它会在 worker 线程执行前 {@code setThreadLocals()}、执行后 {@code close()} 复原。
     *
     * <p>命名上刻意避开 {@code wrap}，免得和库的 {@code ContextSnapshot.wrap} 混淆 —— 这里
     * 是「装配一个会自己传播上下文的任务」，真正的包装动作由库完成。
     *
     * <p>AiInteractionContext 已经通过注册的 ThreadLocalAccessor 随快照恢复，不用再套一层
     * {@code AiInteractionContext.wrap}。
     */
    private Runnable propagating(Runnable task) {
        return CONTEXT_SNAPSHOT_FACTORY.captureAll().wrap(task);
    }

    /** @see #propagating(Runnable) */
    private <T> Callable<T> propagating(Callable<T> task) {
        return CONTEXT_SNAPSHOT_FACTORY.captureAll().wrap(task);
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
