package kz.bejiihiu.dimensions.paper.scheduler;

/**
 * Handle to a task scheduled through {@link Schedulers}.
 *
 * <p>Wraps the platform scheduler's task object without leaking it, so downstream
 * modules and tests do not depend on platform classes. Cancellation is best effort:
 * a task that has already started executing cannot be stopped.
 */
public interface TaskHandle {

    /** Request cancellation. No-op when the task is already running or finished. */
    void cancel();

    /** True once the task was cancelled before running. */
    boolean isCancelled();
}
