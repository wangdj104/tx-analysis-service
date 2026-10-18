package org.familyhealthcare.service.measurementread;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.CareJourneyService;
import org.familyhealthcare.service.alertworkflow.*;
import org.familyhealthcare.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.util.*;

/** 只复用原入口权限；新读取不能用工作流或旧请求角色扩充授权。 */
@Service
public class OriginalMeasurementReadAuthorization {
    private final AlertWorkflowAuthorizationService workflow;
    private final AlertWorkflowProperties properties;
    private final DataScopeHelper scope;
    private final CareJourneyService journey;
    private final SysRoleMapper roles;
    private final SysMenuMapper menus;
    public OriginalMeasurementReadAuthorization(AlertWorkflowAuthorizationService workflow,AlertWorkflowProperties properties,DataScopeHelper scope,CareJourneyService journey,SysRoleMapper roles,SysMenuMapper menus){
        this.workflow=workflow;this.properties=properties;this.scope=scope;this.journey=journey;this.roles=roles;this.menus=menus;
    }
    public boolean beginRaw(long actor,long patient,boolean self){boolean mode=properties.isEnabled();raw(actor,patient,self,mode);return mode;}
    public void finishRaw(long actor,long patient,boolean self,boolean mode){raw(actor,patient,self,mode);}
    private void raw(long actor,long patient,boolean self,boolean mode){
        try{
            sameActor(actor);if(properties.isEnabled()!=mode)throw unavailable();workflow.requireAccount(actor);
            if(mode)workflow.requireRead(actor,patient);
            else{legacyRoles(actor);if(self)scope.requirePatientAccess(patient,"MEASUREMENTS",false);else journey.requireRead(patient,"MEASUREMENTS");}
            sameActor(actor);if(properties.isEnabled()!=mode)throw unavailable();
        }catch(DataAccessException failure){throw unavailable();}
        catch(AlertWorkflowException failure){if(failure.getStatus()==503)throw unavailable();if(failure.getStatus()==403)throw denied();throw failure;}
        catch(IllegalStateException failure){throw denied();}
    }
    /** 概览保留通用患者范围，与A开关和原值页模块权限无关。 */
    public Patient monitoring(long actor,long patient){
        try{
            sameActor(actor);workflow.requireAccount(actor);boolean admin=legacyRoles(actor);
            Patient result=scope.requirePatientAccess(patient,null,false);
            if(!admin){List<SysMenu> current=menus.selectMenusByUserId(actor);boolean allowed=false;if(current!=null)for(SysMenu menu:current)allowed|=org.familyhealthcare.interceptor.PermissionInterceptor.hasPermission("monitoring:view",menu.getPermission());if(!allowed)throw denied();}
            sameActor(actor);return result;
        }catch(DataAccessException failure){throw unavailable();}
        catch(AlertWorkflowException failure){if(failure.getStatus()==403)throw denied();throw failure;}
        catch(IllegalStateException failure){throw denied();}
    }
    private boolean legacyRoles(long actor){
        List<SysRole> current=roles.selectRolesByUserId(actor);if(current==null)throw unavailable();
        boolean admin=false,doctor=false;for(SysRole role:current){admin|="admin".equals(role.getRoleCode());doctor|="doctor".equals(role.getRoleCode());}
        if(admin!=CurrentUserUtil.isAdmin()||doctor!=CurrentUserUtil.hasRole("doctor"))throw denied();return admin;
    }
    public static long actor(){
        Long actor;try{actor=CurrentUserUtil.getCurrentUserId();}catch(RuntimeException invalid){actor=null;}
        if(actor==null||actor<=0)throw new AlertWorkflowException(401,"AUTHENTICATION_REQUIRED","Sign in to view measurement records.");return actor;
    }
    private static void sameActor(long expected){if(expected<=0)throw invalid();Long current;try{current=CurrentUserUtil.getCurrentUserId();}catch(RuntimeException failure){throw denied();}if(!Long.valueOf(expected).equals(current))throw denied();}
    public static AlertWorkflowException invalid(){return new AlertWorkflowException(400,"INVALID_REQUEST","The measurement query is invalid. Check the patient, filters, dates and page size.");}
    public static AlertWorkflowException denied(){return new AlertWorkflowException(403,"ACCESS_DENIED","Access is unavailable. Refresh your session and patient selection.");}
    public static AlertWorkflowException unavailable(){return new AlertWorkflowException(503,"READ_UNAVAILABLE","Measurement records are temporarily unavailable. Refresh and try again.");}
}
