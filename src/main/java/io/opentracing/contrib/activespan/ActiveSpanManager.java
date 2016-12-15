package io.opentracing.contrib.activespan;

import io.opentracing.NoopSpan;
import io.opentracing.Span;
import io.opentracing.contrib.activespan.concurrent.SpanAwareCallable;
import io.opentracing.contrib.activespan.concurrent.SpanAwareRunnable;

import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the <em>active</em> {@link Span}.<br>
 * A {@link Span} becomes active in the current process after a call to {@link #activate(Span)}.
 * <p>
 * The default implementation will use a {@link ThreadLocal ThreadLocal storage} to maintain the active {@link Span}.
 * <p>
 * Custom implementations can be provided by:
 * <ol>
 * <li>calling {@link #setInstance(ActiveSpanManager)} programmatically, or</li>
 * <li>defining a <code>META-INF/services/io.opentracing.contrib.activespan.ActiveSpanManager</code> service file
 * containing the classname of the implementation</li>
 * </ol>
 *
 * @author Sjoerd Talsma
 * @navassoc - activeSpan - io.opentracing.Span
 */
public abstract class ActiveSpanManager {
    private static final Logger LOGGER = Logger.getLogger(ActiveSpanManager.class.getName());

    /**
     * Interface to deactivate an active {@link Span} with.
     */
    public interface SpanDeactivator {
    }

    /**
     * Overridable singleton instance of the active span manager.
     */
    private static final AtomicReference<ActiveSpanManager> INSTANCE = new AtomicReference<ActiveSpanManager>();

    private static ActiveSpanManager getInstance() {
        ActiveSpanManager instance = INSTANCE.get();
        if (instance == null) {
            final ActiveSpanManager singleton = loadSingleton();
            while (instance == null && singleton != null) {
                INSTANCE.compareAndSet(null, singleton);
                instance = INSTANCE.get();
            }
            LOGGER.log(Level.FINE, "Singleton ActiveSpanManager implementation: {0}.", instance);
        }
        return instance;
    }

    /**
     * This method allows explicit registration of a configured <code>ActiveSpanManager</code> implementation
     * to override the behaviour of the default <code>ThreadLocal</code> implementation.
     * <p>
     * The previously active span manager is returned so it can be restored if necessary.
     *
     * @param instance The overridden implementation to use for in-process span management.
     * @return The previous <code>ActiveSpanManager</code> that was initialized before.
     */
    protected static ActiveSpanManager setInstance(ActiveSpanManager instance) {
        return INSTANCE.getAndSet(instance);
    }

    /**
     * Return the active {@link Span}.
     *
     * @return The active Span, or the <code>NoopSpan</code> if there is no active span.
     */
    public static Span activeSpan() {
        try {
            Span activeSpan = getInstance().getActiveSpan();
            if (activeSpan != null) return activeSpan;
        } catch (Exception activeSpanException) {
            LOGGER.log(Level.WARNING, "Could not obtain active span.", activeSpanException);
        }
        return NoopSpan.INSTANCE;
    }

    /**
     * Makes span the <em>active span</em> within the running process.
     * <p>
     * Any exception thrown by the {@link #setActiveSpan(Span) implementation} is logged and will return
     * no {@link SpanDeactivator} (<code>null</code>) because tracing code must not break application functionality.
     *
     * @param span The span to become the active span.
     * @return The object that will restore any currently <em>active</em> deactivated.
     * @see #activeSpan()
     * @see #deactivate(SpanDeactivator)
     */
    public static SpanDeactivator activate(Span span) {
        try {
            if (span == null) span = NoopSpan.INSTANCE;
            return getInstance().setActiveSpan(span);
        } catch (Exception activationException) {
            LOGGER.log(Level.WARNING, "Could not activate {0}.", new Object[]{span, activationException});
            return null;
        }
    }

    /**
     * Invokes the given {@link SpanDeactivator} which should normally reactivate the parent of the <em>active span</em>
     * within the running process.
     * <p>
     * Any exception thrown by the implementation is logged and swallowed because tracing code must not break
     * application functionality.
     *
     * @param deactivator The deactivator that was received upon span activation.
     * @see #activate(Span)
     */
    public static void deactivate(SpanDeactivator deactivator) {
        if (deactivator != null) try {
            getInstance().deactivateSpan(deactivator);
        } catch (Exception deactivationException) {
            LOGGER.log(Level.WARNING, "Could not deactivate {0}.", new Object[]{deactivator, deactivationException});
        }
    }

    /**
     * Clears any active spans.
     * <p>
     * This method allows boundary filters to clear any unclosed active spans before returning the Thread back to
     * the threadpool.
     *
     * @return <code>true</code> if there were active spans that were cleared,
     * or <code>false</code> if there were no active spans left.
     */
    public static boolean clearActiveSpans() {
        try {
            return getInstance().clearAllActiveSpans();
        } catch (Exception clearException) {
            LOGGER.log(Level.WARNING, "Could not clear active spans.", clearException);
            return false;
        }
    }

    /**
     * Wraps the {@link Callable} to execute with the {@link ActiveSpanManager#activeSpan() active span}
     * from the scheduling thread.
     *
     * @param callable The callable to wrap.
     * @param <V>      The return type of the wrapped call.
     * @return The wrapped call executing with the active span of the scheduling process.
     */
    public static <V> SpanAwareCallable<V> spanAware(Callable<V> callable) {
        return SpanAwareCallable.of(callable);
    }

    /**
     * Wraps the {@link Runnable} to execute with the {@link ActiveSpanManager#activeSpan() active span}
     * from the scheduling thread.
     *
     * @param runnable The runnable to wrap.
     * @return The wrapped runnable executing with the active span of the scheduling process.
     */
    public static SpanAwareRunnable spanAware(Runnable runnable) {
        return SpanAwareRunnable.of(runnable);
    }


    // The abstract methods to be implemented by the span manager.
    // TODO JavaDoc

    protected abstract Span getActiveSpan();

    protected abstract SpanDeactivator setActiveSpan(Span span);

    protected abstract void deactivateSpan(SpanDeactivator deactivator);

    protected abstract boolean clearAllActiveSpans();

    /**
     * Loads a single service implementation from {@link ServiceLoader}.
     *
     * @return The single implementation or the ThreadLocalSpanManager.
     */
    private static ActiveSpanManager loadSingleton() {
        ActiveSpanManager foundSingleton = null;
        for (Iterator<ActiveSpanManager> implementations =
             ServiceLoader.load(ActiveSpanManager.class, ActiveSpanManager.class.getClassLoader()).iterator();
             foundSingleton == null && implementations.hasNext(); ) {
            final ActiveSpanManager implementation = implementations.next();
            if (implementation != null) {
                LOGGER.log(Level.FINEST, "Service loaded: {0}.", implementation);
                if (implementations.hasNext()) { // Don't actually load the next implementation, fall-back to default.
                    LOGGER.log(Level.WARNING, "More than one ActiveSpanManager service implementation found. " +
                            "Falling back to default implementation.");
                    break;
                } else {
                    foundSingleton = implementation;
                }
            }
        }
        if (foundSingleton == null) {
            LOGGER.log(Level.FINEST, "No ActiveSpanManager service implementation found. " +
                    "Falling back to default implementation.");
            foundSingleton = new ThreadLocalSpanManager();
        }
        return foundSingleton;
    }

}
