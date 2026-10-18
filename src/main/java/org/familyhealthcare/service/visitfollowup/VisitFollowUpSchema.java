package org.familyhealthcare.service.visitfollowup;

import java.sql.Connection;
import org.familyhealthcare.service.workflow.HouseholdWorkflowSchema;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.stereotype.Component;

/** 精确完整OLD/A/B完整分型；同连接只读元数据，不缓存、不迁移、不递归调用其它readiness。 */
@Component
public final class VisitFollowUpSchema {
    public enum Availability {
        ABSENT, REMINDER_EXTENSION_REQUIRED, FULLY_READY, INCOMPATIBLE;
        public boolean isReadAvailable(){return this==FULLY_READY||this==REMINDER_EXTENSION_REQUIRED;}
        public boolean isWriteAvailable(boolean enabled){return enabled&&this==FULLY_READY;}
        public void requireReadable(){if(!isReadAvailable())throw unavailable();}
        public void requireWritable(boolean enabled){requireReadable();if(this==REMINDER_EXTENSION_REQUIRED)throw new WorkflowException(503,"VISIT_FOLLOWUP_REMINDER_SCHEMA_REQUIRED","Visit follow-up storage must be upgraded before new changes; existing history remains available.");if(!enabled)throw new WorkflowException(409,"VISIT_FOLLOWUP_DISABLED","Visit follow-up commands are disabled.");}
    }

    public static final class Inspection {
        private final Availability availability;private final HouseholdWorkflowSchema.Compatibility compatibility;
        private Inspection(Availability availability,HouseholdWorkflowSchema.Compatibility compatibility){this.availability=availability;this.compatibility=compatibility;}
        public Availability getAvailability(){return availability;}
        public HouseholdWorkflowSchema.Compatibility getCompatibility(){return compatibility;}
    }
    /** 同一次结构检查及其连接绑定证明；关功能不能隐藏已安装历史。 */
    public Inspection inspectStorage(Connection connection){
        HouseholdWorkflowSchema.Compatibility compatibility=HouseholdWorkflowSchema.inspectCompatibility(connection);
        HouseholdWorkflowSchema.Profile profile=compatibility.getProfile();Availability availability=Availability.INCOMPATIBLE;
        if(profile==HouseholdWorkflowSchema.Profile.EXACT_OLD)availability=Availability.ABSENT;
        else if(profile==HouseholdWorkflowSchema.Profile.FULLY_INSTALLED)availability=Availability.REMINDER_EXTENSION_REQUIRED;
        else if(profile==HouseholdWorkflowSchema.Profile.FULLY_INSTALLED_REMINDER)availability=Availability.FULLY_READY;
        return new Inspection(availability,compatibility);
    }
    public Availability availability(Connection connection,boolean enabled){return inspectStorage(connection).getAvailability();}
    public Availability inspect(Connection connection){return availability(connection,false);}
    private static WorkflowException unavailable(){return new WorkflowException(503,"VISIT_FOLLOWUP_SCHEMA_NOT_READY","Visit follow-up storage is not ready.");}
}
