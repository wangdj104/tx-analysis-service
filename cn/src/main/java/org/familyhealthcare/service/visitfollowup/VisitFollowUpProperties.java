package org.familyhealthcare.service.visitfollowup;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 工作流默认关闭。服务器时钟仅用于记录时刻，不替代真实发生时间。 */
@Component
public class VisitFollowUpProperties {
    @Value("${visit-followup.enabled:false}") private boolean enabled;
    private Clock clock = Clock.systemUTC();
    public VisitFollowUpProperties() { }
    public VisitFollowUpProperties(boolean enabled, Clock clock) { this.enabled = enabled; setClock(clock); }
    @Autowired(required = false)
    public void setClock(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock").withZone(ZoneOffset.UTC); }
    public boolean isEnabled() { return enabled; }
    public Clock getClock() { return clock; }
    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
