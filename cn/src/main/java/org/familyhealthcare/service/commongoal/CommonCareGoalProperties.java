package org.familyhealthcare.service.commongoal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 默认关闭，只透传配置；没有启动迁移、任务调度或通知。 */
@Component
public final class CommonCareGoalProperties {
    @Value("${common-care-goals.enabled:false}") private boolean enabled;
    private Clock clock = Clock.systemUTC();
    public CommonCareGoalProperties() { }
    public CommonCareGoalProperties(boolean enabled, Clock clock) { this.enabled = enabled; this.clock = Objects.requireNonNull(clock); }
    @Autowired(required=false) public void setClock(Clock clock) { this.clock = Objects.requireNonNull(clock); }
    public boolean isEnabled() { return enabled; }
    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
