package io.opentracing.contrib.global.concurrent;

import io.opentracing.Span;
import io.opentracing.contrib.activespan.ActiveSpanManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

/**
 * {@link ExecutorService} wrapper that will propagate the {@link ActiveSpanManager#activeSpan() active span}
 * into the calls that are scheduled.
 *
 * @author Sjoerd Talsma
 */
public class SpanAwareExecutorService implements ExecutorService {
    protected final ExecutorService delegate;

    protected SpanAwareExecutorService(ExecutorService delegate) {
        if (delegate == null) throw new NullPointerException("Delegate executor service is <null>.");
        this.delegate = delegate;
    }

    public static ExecutorService traced(final ExecutorService delegate) {
        return delegate instanceof SpanAwareExecutorService ? (SpanAwareExecutorService) delegate
                : new SpanAwareExecutorService(delegate);
    }

    public void execute(Runnable command) {
        delegate.execute(SpanAwareRunnable.of(command));
    }

    public Future<?> submit(Runnable task) {
        return delegate.submit(SpanAwareRunnable.of(task));
    }

    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(SpanAwareRunnable.of(task), result);
    }

    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(SpanAwareCallable.of(task));
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tracedTasks(tasks));
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(tracedTasks(tasks), timeout, unit);
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tracedTasks(tasks));
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tracedTasks(tasks), timeout, unit);
    }

    /**
     * Obtains the currently active span and returns {@link SpanAwareCallable} objects that run with this active span as
     * global parent.
     *
     * @param tasks The tasks to be scheduled.
     * @param <T>   The common type of all scheduled tasks.
     * @return A new collection of 'traced' callable objects that manage the parent span.
     */
    protected <T> Collection<? extends Callable<T>> tracedTasks(final Collection<? extends Callable<T>> tasks) {
        if (tasks == null) throw new NullPointerException("Collection of scheduled tasks is <null>.");
        final Collection<Callable<T>> result = new ArrayList<Callable<T>>(tasks.size());
        final Span activeSpan = ActiveSpanManager.activeSpan();
        for (Callable<T> task : tasks) {
            result.add(new SpanAwareCallable<T>(task, activeSpan));
        }
        return result;
    }

    public void shutdown() {
        delegate.shutdown();
    }

    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

}
