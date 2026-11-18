package kz.bejiihiu.dimensions.paper.scheduler;

/**
 * Manual, deterministic {@link TaskHandle} for tests. Cancellation prevents execution;
 * a cancelled task pops off the queue and is dropped silently.
 */
public final class FakeTask implements TaskHandle {

    private final Runnable action;
    private volatile boolean cancelled;

    FakeTask(Runnable action) {
        this.action = action;
    }

    void runIfNotCancelled() {
        if (!cancelled) {
            action.run();
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
