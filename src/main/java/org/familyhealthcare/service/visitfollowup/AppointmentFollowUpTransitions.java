package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.util.*;

/** Pure finite attendance rules. Current authorization, event identity and slot admission belong to the service. */
final class AppointmentFollowUpTransitions {
    private AppointmentFollowUpTransitions() { }

    static Map<String,Object> decide(String kind, String nativeStatus, String attendance, String origin,
            boolean hasFact, String operation, String requested, boolean selected, boolean sameFactDoctor) {
        boolean modern = "CARE_APPOINTMENT".equals(kind);
        if (!modern && !"CARE_ITEM_APPOINTMENT".equals(kind)) throw conflict();
        String active = modern ? "BOOKED" : "OPEN", finished = modern ? "COMPLETED" : "DONE";
        boolean unknown = "UNVERIFIED".equals(attendance) && "UNKNOWN".equals(origin) && !hasFact;
        boolean reported = "REPORTED".equals(origin) && hasFact;
        boolean professional = modern && "SELECTED_CLINICIAN_RECORDED".equals(origin) && hasFact;
        boolean attended = "ATTENDED".equals(attendance), missed = "NO_SHOW".equals(attendance);
        boolean valid = unknown && Arrays.asList(active, finished, "NO_SHOW", "CANCELLED").contains(nativeStatus)
                || reported && (attended && (modern ? active : finished).equals(nativeStatus) || missed && "NO_SHOW".equals(nativeStatus))
                || professional && (attended && finished.equals(nativeStatus) || missed && "NO_SHOW".equals(nativeStatus));
        if (!valid) throw conflict();
        String nextStatus = nativeStatus, nextAttendance = attendance, nextOrigin = origin;
        switch (operation) {
            case "RESCHEDULE":
                if (!unknown || !active.equals(nativeStatus)) throw conflict();
                break;
            case "CANCEL":
                if (!unknown || !active.equals(nativeStatus)) throw conflict();
                nextStatus = "CANCELLED"; break;
            case "RECORD_ATTENDANCE":
                if (!unknown || !active.equals(nativeStatus) || !Arrays.asList("ATTENDED", "NO_SHOW").contains(requested)) throw conflict();
                nextAttendance = requested; nextOrigin = "REPORTED";
                nextStatus = "NO_SHOW".equals(requested) ? "NO_SHOW" : modern ? active : finished; break;
            case "COMPLETE_SELECTED_APPOINTMENT":
                if (!modern || !selected || !active.equals(nativeStatus) || !(unknown || reported && attended)) throw conflict();
                nextStatus = finished; nextAttendance = "ATTENDED"; nextOrigin = "SELECTED_CLINICIAN_RECORDED"; break;
            case "CORRECT_ATTENDANCE":
                if ((!reported && !professional) || professional && (!selected || !sameFactDoctor)
                        || !Arrays.asList("UNVERIFIED", "ATTENDED", "NO_SHOW").contains(requested)) throw conflict();
                nextAttendance = requested;
                nextOrigin = "UNVERIFIED".equals(requested) ? "UNKNOWN" : origin;
                nextStatus = "UNVERIFIED".equals(requested) ? active : "NO_SHOW".equals(requested) ? "NO_SHOW"
                        : professional || !modern ? finished : active;
                break;
            default: throw conflict();
        }
        return WorkflowData.map("nativeStatus", nextStatus, "attendance", nextAttendance, "origin", nextOrigin);
    }
    private static WorkflowException conflict() {
        return new WorkflowException(409, "APPOINTMENT_TRANSITION_NOT_ALLOWED", "This attendance fact cannot be changed by this command. Refresh and compare the current record.");
    }
}
