package org.familyhealthcare.service.alertworkflow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Independent opt-in switch; no startup migration or notification scheduling. */
@Component
public class AlertWorkflowProperties {
    @Value("${alert-workflow.enabled:false}") private boolean enabled;
    private Clock clock=Clock.systemUTC();
    public AlertWorkflowProperties(){}
    public AlertWorkflowProperties(boolean enabled,Clock clock){this.enabled=enabled;this.clock=Objects.requireNonNull(clock);}
    @Autowired(required=false) public void setClock(Clock clock){this.clock=Objects.requireNonNull(clock);}
    public boolean isEnabled(){return enabled;}
    public Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    public void requireEnabled(){if(!enabled)throw new AlertWorkflowException(503,"FEATURE_DISABLED","Alert follow-up is not enabled.");}
}
