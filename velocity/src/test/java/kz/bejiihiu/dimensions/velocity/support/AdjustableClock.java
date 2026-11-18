package kz.bejiihiu.dimensions.velocity.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Test clock that only moves when told to. */
public final class AdjustableClock extends Clock {

    private Instant now;

    public AdjustableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.systemDefault();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public long millis() {
        return now.toEpochMilli();
    }
}
