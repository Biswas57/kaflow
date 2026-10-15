package kaflow.consumer;

import java.util.concurrent.atomic.AtomicBoolean;

/** Early transport cancellation flag, safe to inspect without calling transport code on coordination. */
public final class FetchCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
}
