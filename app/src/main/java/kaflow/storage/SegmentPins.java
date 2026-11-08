package kaflow.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** No I/O under this monitor. Retirement and pre-open admission share this ordering point. */
final class SegmentPins {
    private final Map<Long, Integer> pins = new HashMap<>();
    private long floor;
    SegmentPins(long floor) { this.floor = floor; }
    synchronized void acquire(long start, List<Long> bases) {
        if (start < floor) throw new DurableException(DurableException.Code.OUT_OF_RANGE);
        for (long base : bases) pins.merge(base, 1, Math::addExact);
    }
    synchronized void release(List<Long> bases) {
        for (long base : bases) {
            int n = pins.get(base);
            if (n == 1) pins.remove(base); else pins.put(base, n - 1);
        }
    }
    synchronized void advance(long start) {
        if (start < floor) throw new IllegalStateException("retention regression");
        floor = start;
    }
    synchronized boolean pinned(long base) { return pins.containsKey(base); }
    synchronized long floor() { return floor; }
    synchronized int count() { return pins.values().stream().mapToInt(Integer::intValue).sum(); }
}
