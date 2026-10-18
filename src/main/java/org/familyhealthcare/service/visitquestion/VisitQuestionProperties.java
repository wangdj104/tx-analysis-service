package org.familyhealthcare.service.visitquestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 问题跟踪默认关闭；开关不安装结构，也不授权访问任何患者。 */
@Component
public final class VisitQuestionProperties {
    @Value("${visit-question.enabled:false}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }
}
