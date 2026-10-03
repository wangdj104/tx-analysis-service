package org.familyhealthcare.service.careplan;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** One opt-in flag for authorization and all collaboration services. */
@Component
public class CarePlanProperties {
    @Value("${care-plan.enabled:false}") private boolean enabled;
    private Clock clock = Clock.systemUTC();
    public CarePlanProperties() { }
    public CarePlanProperties(boolean enabled, Clock clock) { this.enabled = enabled; this.clock = java.util.Objects.requireNonNull(clock); }
    @Autowired(required=false) public void setClock(Clock clock) { this.clock = clock; }
    public boolean isEnabled() { return enabled; }
    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    public void requireEnabled() {
        if (!enabled) throw new CarePlanException(503,"FEATURE_DISABLED","照护计划协作功能尚未启用。");
    }
}
