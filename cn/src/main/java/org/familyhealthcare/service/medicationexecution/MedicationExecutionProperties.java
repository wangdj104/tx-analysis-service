package org.familyhealthcare.service.medicationexecution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 业务说明：默认关闭，时钟只负责真实记录时间，不推断治疗或患者时区。 */
@Component
public class MedicationExecutionProperties {
    @Value("${medication-execution.enabled:false}") private boolean enabled;
    private Clock clock=Clock.systemUTC();
    @Value("${medication-execution.schedule-zone:}") private String scheduleZone="";
    public String getScheduleZone(){return scheduleZone;}
    public MedicationExecutionProperties(){}
    public MedicationExecutionProperties(boolean enabled,Clock clock){this.enabled=enabled;this.clock=Objects.requireNonNull(clock);}
    @Autowired(required=false) public void setClock(Clock clock){this.clock=Objects.requireNonNull(clock);}
    public boolean isEnabled(){return enabled;}
    public Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
}
