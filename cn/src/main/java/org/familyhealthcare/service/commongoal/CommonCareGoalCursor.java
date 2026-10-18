package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.workflow.WorkflowException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** Scope-bound keyset position, never an authority token. */
final class CommonCareGoalCursor {
    private CommonCareGoalCursor() { }
    static String encode(String scope, long position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((scope+":"+id(position)).getBytes(StandardCharsets.US_ASCII));
    }
    static long position(String cursor, String scope) {
        if(cursor==null)return 0;
        if(cursor.isEmpty()||cursor.length()>160||!cursor.matches("[A-Za-z0-9_-]+"))throw invalidCursor();
        try {
            String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII);
            if(!decoded.startsWith(scope+":"))throw invalidCursor();
            String value=decoded.substring(scope.length()+1);
            if(!value.matches("[1-9][0-9]{0,15}"))throw invalidCursor();
            long position=Long.parseLong(value);if(position<1||position>MAX_ID)throw invalidCursor();
            if(!encode(scope,position).equals(cursor))throw invalidCursor();
            return position;
        } catch(IllegalArgumentException failure){throw invalidCursor();}
    }
    private static WorkflowException invalidCursor(){return new WorkflowException(400,"COMMON_GOAL_INVALID_CURSOR","This page cursor does not match the patient, goal or filter. Start from the first page.");}
}
