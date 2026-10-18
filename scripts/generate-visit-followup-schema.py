#!/usr/bin/env python3
"""Generate the reviewed visit-followup SQL and exact metadata profiles.

Usage: python3 scripts/generate-visit-followup-schema.py [--check | --write]
The default is --check. Requires Python 3.8+ and its standard library only.
This tool only reads pinned baseline inputs and generates source files. It never
connects to a database, executes SQL, enables a feature, or changes old scripts.
业务说明：root/CN一次校验全部旧输入后生成；旧输入漂移立即停止，禁止自动更新基线hash。
"""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import re

W = Path(__file__).resolve().parent.parent
# Reviewed immutable baseline accb3c87; update only after a separately reviewed baseline change.
INPUT_SHA256 = {'': {'household-workflow-schema.json': '1027b6dca4e158fe9e41cc73f308bc0c2d2d037958f5206926d974ae5abf488e', 'sql/init.sql': 'e3e69907a0d3ad6b6cd48aac1bcbf0120779db8aa37797ed82931338c58b12e4', 'sql/init_with_common_care_goals_20261016.sql': '278e607867a8c0f295300682229f2fb90f541a6a2e089bca4273c4028584913c'}, 'cn': {'household-workflow-schema.json': '1027b6dca4e158fe9e41cc73f308bc0c2d2d037958f5206926d974ae5abf488e', 'sql/init.sql': '0b9f82586970d8379a369e8b19f0b2217ea35534806f9a6ca89e1e48885ff651', 'sql/init_with_common_care_goals_20261016.sql': '42442f44d30ffe9b78bdb8489f060b097939eea055ee4f55bd0d73c8c7e4d706'}}
INPUT_BYTES = {}
GENERATED = {}
SQL_FILES = (
    'visit_followup_01_precheck_20261017.sql',
    'visit_followup_02_upgrade_20261017.sql',
    'visit_followup_03_postcheck_20261017.sql',
    'init_with_visit_followup_20261017.sql',
)

def verify_inputs():
    for edition, expected in INPUT_SHA256.items():
        for relative, signature in expected.items():
            path = W / edition / 'src/main/resources' / relative
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() != signature:
                raise ValueError('Immutable baseline input mismatch: ' + path.relative_to(W).as_posix())
            INPUT_BYTES[(edition, relative)] = data

def read_input(edition, relative):
    return INPUT_BYTES[(edition, relative)].decode('utf-8')

def emit(path, value):
    if path in GENERATED:
        raise ValueError('Duplicate generated path: ' + path.relative_to(W).as_posix())
    GENERATED[path] = value.encode('utf-8')

MAX=9007199254740991
S,E,C,L='visit_followup_state','visit_followup_event','visit_followup_command','visit_followup_action_link'
TABLES=[S,C,E,L]
OPS=['TRACK_APPOINTMENT','RESCHEDULE','CANCEL','RECORD_ATTENDANCE','COMPLETE_SELECTED_APPOINTMENT','CORRECT_ATTENDANCE','SET_NEXT_DECISION','CREATE_NEXT','LINK_NEXT','REPLACE_NEXT','CREATE_VISIT','PUBLISH_VISIT','SET_DISPOSITION','REVISE_DISPOSITION']
SUCCESSOR_OPS=['CREATE_NEXT','LINK_NEXT','REPLACE_NEXT']
NATIVE_OPS=['RESCHEDULE','CANCEL','RECORD_ATTENDANCE','COMPLETE_SELECTED_APPOINTMENT','CORRECT_ATTENDANCE','CREATE_VISIT','PUBLISH_VISIT']
SQLMODE='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION'
def q(s):return "'"+str(s).replace("'","''")+"'"
def names(a):return ','.join(q(v) for v in a)
def eq(a,b):return '(BINARY '+a+' <=> BINARY '+b+')'
def unchanged(fields):return ' AND '.join(eq('NEW.'+f,'OLD.'+f) for f in fields)
def reject(cond,msg):return f"  IF COALESCE(({cond}),TRUE) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='{msg}';\n  END IF;"
def col(n,t,nullable=False,charset=None,auto=False):return dict(name=n,type=t,nullable=nullable,charset=charset,auto=auto)
def big(n,nullable=False,auto=False):return col(n,'bigint',nullable,auto=auto)
def token(n,size,nullable=False):return col(n,f'varchar({size})',nullable,'ascii')
def txt(n,size,nullable=False):return col(n,f'varchar({size})',nullable,'utf8mb4')
def tm(n,nullable=False):return col(n,'datetime(6)',nullable)
def fk(n,cs,t,tc=['id'],ti='PRIMARY'):return dict(name='vf57_'+n+'_fk',columns=cs,target=t,targetColumns=tc,update='RESTRICT',delete='RESTRICT',targetIndex=ti)
def ix(n,cs,unique=False):return dict(name='PRIMARY' if n=='PRIMARY' else 'vf57_'+n,columns=cs,unique=unique)
SCHEMA={
S:dict(columns=[big('id',auto=True),big('patient_id'),token('source_kind',24),big('care_item_id',True),big('care_appointment_id',True),big('visit_record_id',True),big('origin_appointment_state_id',True),big('lock_version'),big('source_revision'),token('source_fingerprint',64),token('native_status',30,True),tm('native_start_wall',True),tm('native_end_wall',True),tm('native_notify_wall',True),big('native_doctor_id',True),big('native_schedule_id',True),big('last_native_event_id',True),token('attendance',16),token('attendance_origin',32),token('actual_time_kind',8),tm('actual_at_utc',True),token('actual_offset',6,True),big('current_fact_event_id',True),token('next_decision',24),big('responsible_user_id',True),tm('decision_due_at',True),token('successor_kind',24,True),big('successor_care_item_id',True),big('successor_care_appointment_id',True),big('successor_bound_version',True),token('successor_bound_fingerprint',64,True),big('reminder_attempt_source_revision',True),token('reminder_attempt_id',36,True),tm('reminder_attempted_at_utc',True),token('disposition',20),big('disposition_revision'),big('decision_event_id',True),big('created_by'),tm('created_at'),tm('updated_at')],keys=[ix('PRIMARY',['id'],True),ix('state_patient_id',['patient_id','id'],True),ix('state_item',['care_item_id'],True),ix('state_appointment',['care_appointment_id'],True),ix('state_visit',['visit_record_id'],True),ix('state_due',['patient_id','next_decision','decision_due_at','id']),ix('state_kind',['patient_id','source_kind','id']),ix('state_successor_item',['successor_care_item_id'],True),ix('state_successor_appointment',['successor_care_appointment_id'],True),ix('state_origin',['origin_appointment_state_id']),ix('state_owner',['responsible_user_id']),ix('state_creator',['created_by']),ix('state_native_event',['last_native_event_id']),ix('state_fact_event',['current_fact_event_id']),ix('state_decision_event',['decision_event_id'])],fks=[fk('state_patient',['patient_id'],'patient'),fk('state_item',['care_item_id'],'care_item'),fk('state_appointment',['care_appointment_id'],'care_appointment'),fk('state_visit',['visit_record_id'],'visit_record'),fk('state_origin',['origin_appointment_state_id'],S),fk('state_owner',['responsible_user_id'],'sys_user'),fk('state_creator',['created_by'],'sys_user'),fk('state_successor_item',['successor_care_item_id'],'care_item'),fk('state_successor_appointment',['successor_care_appointment_id'],'care_appointment'),fk('state_native_event',['last_native_event_id'],E),fk('state_fact_event',['current_fact_event_id'],E),fk('state_decision_event',['decision_event_id'],E)]),
C:dict(columns=[big('actor_id'),token('command_key',36),big('patient_id'),big('state_id',True),big('expected_version'),token('operation',32),token('payload_hash',64),txt('result_json',512,True),tm('created_at')],keys=[ix('PRIMARY',['actor_id','command_key'],True),ix('command_patient',['patient_id','state_id'])],fks=[fk('command_actor',['actor_id'],'sys_user'),fk('command_patient',['patient_id'],'patient'),fk('command_state',['patient_id','state_id'],S,['patient_id','id'],'vf57_state_patient_id')]),
E:dict(columns=[big('id',auto=True),big('state_id'),big('patient_id'),big('state_version'),token('event_type',32),big('actor_id'),txt('actor_name',100),token('actor_relation',40),token('command_key',36),tm('occurred_at_utc',True),tm('recorded_at'),txt('reason',1000,True),token('before_source_fingerprint',64,True),token('after_source_fingerprint',64),col('payload_json','mediumtext',False,'utf8mb4'),token('native_status',30,True),tm('native_start_wall',True),tm('native_end_wall',True),tm('native_notify_wall',True),big('native_doctor_id',True),big('native_schedule_id',True)],keys=[ix('PRIMARY',['id'],True),ix('event_version',['state_id','state_version'],True),ix('event_state',['state_id','id']),ix('event_scope',['patient_id','state_id','id'],True),ix('event_actor_command',['actor_id','command_key'])],fks=[fk('event_state',['patient_id','state_id'],S,['patient_id','id'],'vf57_state_patient_id'),fk('event_patient',['patient_id'],'patient'),fk('event_actor',['actor_id'],'sys_user')]),
L:dict(columns=[big('id',auto=True),big('state_id'),big('patient_id'),big('disposition_revision'),big('plan_id'),big('revision_id'),big('action_id'),big('binding_event_id'),big('bound_after_action_event_id'),token('kind',10)],keys=[ix('PRIMARY',['id'],True),ix('link_action',['state_id','disposition_revision','action_id'],True),ix('link_current',['state_id','disposition_revision','id']),ix('link_plan',['plan_id','revision_id','action_id']),ix('link_revision',['revision_id']),ix('link_action_fk',['action_id']),ix('link_state_scope',['patient_id','state_id']),ix('link_event_scope',['patient_id','state_id','binding_event_id'])],fks=[fk('link_state',['patient_id','state_id'],S,['patient_id','id'],'vf57_state_patient_id'),fk('link_patient',['patient_id'],'patient'),fk('link_plan',['plan_id'],'doctor_care_plan'),fk('link_revision',['revision_id'],'care_plan_revision'),fk('link_action_id',['action_id'],'care_plan_action'),fk('link_binding',['patient_id','state_id','binding_event_id'],E,['patient_id','state_id','id'],'vf57_event_scope')])}
STATE_IDENTITY=['id','patient_id','source_kind','care_item_id','care_appointment_id','visit_record_id','origin_appointment_state_id','created_by','created_at']
MARKERS=['reminder_attempt_source_revision','reminder_attempt_id','reminder_attempted_at_utc']
AFTER=[c['name'] for c in SCHEMA[S]['columns'] if c['name'] not in STATE_IDENTITY+['lock_version']]
FACT=['attendance','attendance_origin','actual_time_kind','actual_at_utc','actual_offset','current_fact_event_id']
NATIVE=['native_status','native_start_wall','native_end_wall','native_notify_wall','native_doctor_id','native_schedule_id']
DISPOSITION=['disposition','disposition_revision','decision_event_id']
KINDS={'CARE_ITEM_APPOINTMENT':('care_item_id','care_item'), 'CARE_APPOINTMENT':('care_appointment_id','care_appointment'), 'VISIT_RECORD':('visit_record_id','visit_record')}
UUID="'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'"
HASH="'^[0-9a-f]{64}$'"
def state_shape():
 a=[f'NEW.patient_id BETWEEN 1 AND {MAX}',f'NEW.created_by BETWEEN 1 AND {MAX}',f'NEW.lock_version BETWEEN 0 AND {MAX}',f'NEW.source_revision BETWEEN 0 AND {MAX}',f'NEW.disposition_revision BETWEEN 0 AND {MAX}',f'OCTET_LENGTH(NEW.source_fingerprint)=64 AND BINARY NEW.source_fingerprint REGEXP {HASH}']
 for c in SCHEMA[S]['columns']:
  if c['type']=='bigint' and c['nullable']:a.append(f'(NEW.{c["name"]} IS NULL OR NEW.{c["name"]} BETWEEN 1 AND {MAX})')
 kinds=[]
 for k,(field,table) in KINDS.items():
  kinds.append(f"(BINARY NEW.source_kind={q(k)} AND NEW.{field} IS NOT NULL AND "+' AND '.join('NEW.'+o+' IS NULL' for o,t in KINDS.values() if o!=field)+f' AND EXISTS(SELECT 1 FROM {table} n WHERE n.id=NEW.{field} AND n.patient_id=NEW.patient_id'+(" AND BINARY n.kind='APPOINTMENT'" if table=='care_item' else '')+'))')
 a.append('('+' OR '.join(kinds)+')')
 a+= ["BINARY NEW.attendance IN ('UNVERIFIED','ATTENDED','NO_SHOW')","BINARY NEW.attendance_origin IN ('UNKNOWN','REPORTED','SELECTED_CLINICIAN_RECORDED')","BINARY NEW.actual_time_kind IN ('KNOWN','UNKNOWN')","BINARY NEW.next_decision IN ('NEEDS_DECISION','SCHEDULED','NOT_ARRANGED','CLOSED_UNRESOLVED')","BINARY NEW.disposition IN ('UNASSESSED','NO_FOLLOW_UP','ACTIONS_REQUIRED')", "((BINARY NEW.actual_time_kind='UNKNOWN' AND NEW.actual_at_utc IS NULL AND NEW.actual_offset IS NULL) OR (BINARY NEW.actual_time_kind='KNOWN' AND NEW.actual_at_utc IS NOT NULL AND NEW.actual_at_utc<=NEW.updated_at AND NEW.actual_offset IS NOT NULL AND (BINARY NEW.actual_offset='Z' OR (OCTET_LENGTH(NEW.actual_offset)=6 AND BINARY NEW.actual_offset REGEXP '^[+-]((0[0-9]|1[0-7]):[0-5][0-9]|18:00)$'))))", "((BINARY NEW.attendance='UNVERIFIED' AND BINARY NEW.attendance_origin='UNKNOWN' AND NEW.current_fact_event_id IS NULL) OR (BINARY NEW.attendance<>'UNVERIFIED' AND BINARY NEW.attendance_origin<>'UNKNOWN' AND NEW.current_fact_event_id IS NOT NULL))", "(BINARY NEW.attendance_origin<>'SELECTED_CLINICIAN_RECORDED' OR BINARY NEW.source_kind='CARE_APPOINTMENT')", "(BINARY NEW.source_kind='VISIT_RECORD' OR BINARY NEW.attendance<>'UNVERIFIED' OR BINARY NEW.actual_time_kind='UNKNOWN')", "((NEW.reminder_attempt_source_revision IS NULL AND NEW.reminder_attempt_id IS NULL AND NEW.reminder_attempted_at_utc IS NULL) OR (NEW.reminder_attempt_source_revision IS NOT NULL AND NEW.reminder_attempt_source_revision<=NEW.source_revision AND NEW.reminder_attempt_id IS NOT NULL AND OCTET_LENGTH(NEW.reminder_attempt_id)=36 AND BINARY NEW.reminder_attempt_id REGEXP "+UUID+" AND NEW.reminder_attempted_at_utc IS NOT NULL))", "(NEW.successor_bound_fingerprint IS NULL OR (OCTET_LENGTH(NEW.successor_bound_fingerprint)=64 AND BINARY NEW.successor_bound_fingerprint REGEXP "+HASH+"))", "((NEW.successor_kind IS NULL AND NEW.successor_care_item_id IS NULL AND NEW.successor_care_appointment_id IS NULL AND NEW.successor_bound_version IS NULL AND NEW.successor_bound_fingerprint IS NULL AND BINARY NEW.next_decision<>'SCHEDULED') OR (BINARY NEW.successor_kind=BINARY NEW.source_kind AND NEW.successor_bound_version IS NOT NULL AND NEW.successor_bound_fingerprint IS NOT NULL AND BINARY NEW.next_decision='SCHEDULED' AND ((BINARY NEW.source_kind='CARE_ITEM_APPOINTMENT' AND NEW.successor_care_item_id IS NOT NULL AND NEW.successor_care_item_id<>NEW.care_item_id AND NEW.successor_care_appointment_id IS NULL AND EXISTS(SELECT 1 FROM care_item n WHERE n.id=NEW.successor_care_item_id AND n.patient_id=NEW.patient_id AND BINARY n.kind='APPOINTMENT')) OR (BINARY NEW.source_kind='CARE_APPOINTMENT' AND NEW.successor_care_appointment_id IS NOT NULL AND NEW.successor_care_appointment_id<>NEW.care_appointment_id AND NEW.successor_care_item_id IS NULL AND EXISTS(SELECT 1 FROM care_appointment n WHERE n.id=NEW.successor_care_appointment_id AND n.patient_id=NEW.patient_id)))))", "((BINARY NEW.source_kind='VISIT_RECORD' AND BINARY NEW.attendance='UNVERIFIED' AND BINARY NEW.next_decision='NEEDS_DECISION' AND NEW.responsible_user_id IS NULL AND NEW.decision_due_at IS NULL AND NEW.successor_kind IS NULL AND NEW.reminder_attempt_id IS NULL) OR (BINARY NEW.source_kind<>'VISIT_RECORD' AND NEW.origin_appointment_state_id IS NULL AND BINARY NEW.disposition='UNASSESSED' AND NEW.disposition_revision=0 AND NEW.decision_event_id IS NULL AND NEW.responsible_user_id IS NOT NULL AND NEW.decision_due_at IS NOT NULL))", "((BINARY NEW.disposition='UNASSESSED' AND NEW.disposition_revision=0 AND NEW.decision_event_id IS NULL) OR (BINARY NEW.disposition<>'UNASSESSED' AND NEW.disposition_revision>0 AND NEW.decision_event_id IS NOT NULL AND BINARY NEW.native_status='PUBLISHED'))", "(BINARY NEW.source_kind<>'CARE_ITEM_APPOINTMENT' OR (NEW.native_end_wall IS NULL AND NEW.native_doctor_id IS NULL AND NEW.native_schedule_id IS NULL))", "(BINARY NEW.source_kind<>'CARE_APPOINTMENT' OR (NEW.native_doctor_id IS NOT NULL AND NEW.native_start_wall IS NOT NULL AND NEW.native_end_wall>NEW.native_start_wall AND NEW.native_notify_wall IS NULL))", "(NEW.origin_appointment_state_id IS NULL OR EXISTS(SELECT 1 FROM visit_followup_state o JOIN visit_record v ON v.id=NEW.visit_record_id WHERE o.id=NEW.origin_appointment_state_id AND o.patient_id=NEW.patient_id AND ((BINARY o.source_kind='CARE_ITEM_APPOINTMENT' AND v.appointment_id IS NULL) OR (BINARY o.source_kind='CARE_APPOINTMENT' AND v.appointment_id=o.care_appointment_id))))"]
 a.append("(BINARY NEW.source_kind='VISIT_RECORD' OR (BINARY NEW.source_kind='CARE_ITEM_APPOINTMENT' AND BINARY NEW.native_status IN ('OPEN','DONE','NO_SHOW','CANCELLED') AND (BINARY NEW.attendance='UNVERIFIED' OR (BINARY NEW.attendance='ATTENDED' AND BINARY NEW.native_status='DONE') OR (BINARY NEW.attendance='NO_SHOW' AND BINARY NEW.native_status='NO_SHOW'))) OR (BINARY NEW.source_kind='CARE_APPOINTMENT' AND BINARY NEW.native_status IN ('BOOKED','COMPLETED','NO_SHOW','CANCELLED') AND (BINARY NEW.attendance='UNVERIFIED' OR (BINARY NEW.attendance='NO_SHOW' AND BINARY NEW.native_status='NO_SHOW') OR (BINARY NEW.attendance='ATTENDED' AND ((BINARY NEW.attendance_origin='REPORTED' AND BINARY NEW.native_status='BOOKED') OR (BINARY NEW.attendance_origin='SELECTED_CLINICIAN_RECORDED' AND BINARY NEW.native_status='COMPLETED'))))))")
 return ' AND '.join('('+x+')' for x in a)
GUARDS=[]
def guard(n,t,event,body,reason):
 body='BEGIN\n'+body+'\nEND';GUARDS.append(dict(name='vf57_'+n,table=t,event=event,timing='BEFORE',body=body,sha256=hashlib.sha256(body.encode()).hexdigest(),sqlMode=SQLMODE,invariant=reason))
def jscalar(expr,path):
 value=f'JSON_EXTRACT({expr},{q(path)})';return f"IF(JSON_TYPE({value})='NULL',NULL,JSON_UNQUOTE({value}))"
def format_value(alias,c):
 value=alias+'.'+c['name']
 if c['type']=='datetime(6)':return "DATE_FORMAT("+value+",'%Y-%m-%dT%H:%i:%s.%f"+('' if c['name'].endswith('_wall') else 'Z')+"')"
 if c['type']=='bigint':return 'CAST('+value+' AS CHAR)'
 return value
SELF_POINTERS=['last_native_event_id','current_fact_event_id','decision_event_id']
def after_match(expr,alias):
 terms=[]
 for c in SCHEMA[S]['columns']:
  if c['name'] not in AFTER:continue
  raw=jscalar(expr,'$.after.'+c['name'])
  if c['name'] in SELF_POINTERS:terms.append("((BINARY "+raw+"='SELF' AND "+alias+'.'+c['name']+"=e.id) OR (NOT (BINARY "+raw+" <=> BINARY 'SELF') AND "+eq(raw,format_value(alias,c))+"))")
  else:terms.append(eq(raw,format_value(alias,c)))
 return ' AND '.join(terms)
# A skeleton exists only in the enclosing transaction; the command store checks it cannot commit unfinished.
guard('state_insert',S,'INSERT','\n'.join([reject(f'NEW.id<0 OR NEW.id>{MAX} OR NOT ({state_shape()})','VF57_STATE_SHAPE'),reject("NEW.lock_version<>0 OR NEW.source_revision<>0 OR NEW.last_native_event_id IS NOT NULL OR NEW.current_fact_event_id IS NOT NULL OR BINARY NEW.attendance<>'UNVERIFIED' OR BINARY NEW.actual_time_kind<>'UNKNOWN' OR NEW.disposition_revision<>0 OR NEW.reminder_attempt_id IS NOT NULL OR NEW.successor_kind IS NOT NULL OR NOT (NEW.created_at <=> NEW.updated_at)",'VF57_STATE_SKELETON')]),'仅同患者typed原生来源的version0事务内骨架；提交前必须推进至真实event版本')
body=[reject(f'NOT ({unchanged(STATE_IDENTITY)}) OR NOT ({state_shape()})','VF57_STATE_IDENTITY'), '  IF NEW.lock_version=OLD.lock_version THEN',reject(f"OLD.lock_version=0 OR NOT ({unchanged([c['name'] for c in SCHEMA[S]['columns'] if c['name'] not in MARKERS])}) OR (OLD.reminder_attempt_source_revision IS NOT NULL AND OLD.reminder_attempt_source_revision>=OLD.source_revision) OR NEW.reminder_attempt_source_revision<>OLD.source_revision OR NEW.reminder_attempt_id IS NULL OR NEW.reminder_attempted_at_utc IS NULL OR BINARY OLD.source_kind='VISIT_RECORD' OR BINARY OLD.attendance<>'UNVERIFIED' OR BINARY OLD.native_status NOT IN ('OPEN','BOOKED')",'VF57_REMINDER_ONCE'),'  ELSE',reject('NEW.lock_version<>OLD.lock_version+1 OR NEW.source_revision<OLD.source_revision OR NEW.source_revision>OLD.source_revision+1 OR NOT ('+unchanged(MARKERS)+')','VF57_STATE_ADVANCE'), reject(f"NOT EXISTS(SELECT 1 FROM {E} e WHERE e.state_id=OLD.id AND e.patient_id=OLD.patient_id AND e.state_version=NEW.lock_version AND e.recorded_at=NEW.updated_at AND {eq('e.before_source_fingerprint','OLD.source_fingerprint')} AND {eq('e.after_source_fingerprint','NEW.source_fingerprint')} AND {eq('e.occurred_at_utc','NEW.actual_at_utc')} AND "+' AND '.join(eq('e.'+f,'NEW.'+f) for f in NATIVE)+' AND '+after_match('e.payload_json','NEW')+')','VF57_STATE_EVENT'),reject("OLD.lock_version=0 AND NEW.source_revision<>1",'VF57_SOURCE_INITIAL'),reject('NOT ('+unchanged(NATIVE+['source_fingerprint'])+") AND NEW.source_revision<>OLD.source_revision+1",'VF57_SOURCE_REVISION'),reject(f"NOT ({unchanged(NATIVE+['source_fingerprint'])}) AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.state_id=NEW.id AND e.state_version=NEW.lock_version AND ((e.id=NEW.last_native_event_id AND BINARY e.event_type IN ({names(NATIVE_OPS+['TRACK_APPOINTMENT','CREATE_NEXT','REPLACE_NEXT'])})) OR (BINARY NEW.source_kind='VISIT_RECORD' AND BINARY e.event_type IN ('SET_DISPOSITION','REVISE_DISPOSITION') AND {unchanged(NATIVE+['last_native_event_id'])} AND NEW.source_revision=OLD.source_revision+1)))",'VF57_NATIVE_EVENT'),reject(f"NOT ({unchanged(DISPOSITION)}) AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.id=NEW.decision_event_id AND e.state_id=NEW.id AND e.state_version=NEW.lock_version AND BINARY e.event_type IN ('SET_DISPOSITION','REVISE_DISPOSITION'))",'VF57_DECISION_EVENT'),reject("NEW.disposition_revision<>OLD.disposition_revision AND NEW.disposition_revision<>OLD.disposition_revision+1",'VF57_DECISION_REVISION'),reject(f"BINARY OLD.attendance_origin='SELECTED_CLINICIAN_RECORDED' AND NOT ({unchanged(FACT)}) AND NOT EXISTS(SELECT 1 FROM {E} prior JOIN {E} e ON e.state_id=prior.state_id AND e.state_version=NEW.lock_version WHERE prior.id=OLD.current_fact_event_id AND prior.actor_id=e.actor_id AND e.actor_id=NEW.native_doctor_id AND BINARY e.event_type='CORRECT_ATTENDANCE')",'VF57_PROFESSIONAL_FACT'),reject(f"BINARY NEW.attendance_origin='SELECTED_CLINICIAN_RECORDED' AND NOT ({unchanged(FACT)}) AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.id=NEW.current_fact_event_id AND e.state_id=NEW.id AND e.state_version=NEW.lock_version AND e.actor_id=NEW.native_doctor_id AND BINARY e.event_type IN ('COMPLETE_SELECTED_APPOINTMENT','CORRECT_ATTENDANCE'))",'VF57_SELECTED_FACT'),reject(f"NEW.current_fact_event_id IS NOT NULL AND NOT ({eq('NEW.current_fact_event_id','OLD.current_fact_event_id')}) AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.id=NEW.current_fact_event_id AND e.state_id=NEW.id AND e.state_version=NEW.lock_version AND BINARY e.event_type IN ('RECORD_ATTENDANCE','COMPLETE_SELECTED_APPOINTMENT','CORRECT_ATTENDANCE'))",'VF57_FACT_EVENT'), '  END IF;']
body.insert(-1,reject(f"BINARY NEW.source_kind<>'VISIT_RECORD' AND NOT ({unchanged(FACT)}) AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.state_id=NEW.id AND e.state_version=NEW.lock_version AND BINARY e.event_type IN ('RECORD_ATTENDANCE','COMPLETE_SELECTED_APPOINTMENT','CORRECT_ATTENDANCE') AND ((BINARY NEW.attendance='UNVERIFIED' AND NEW.current_fact_event_id IS NULL AND BINARY e.event_type='CORRECT_ATTENDANCE') OR NEW.current_fact_event_id=e.id))",'VF57_FACT_OPERATION'))
body.insert(-1,reject(f"BINARY OLD.attendance_origin<>'SELECTED_CLINICIAN_RECORDED' AND BINARY NEW.attendance_origin='SELECTED_CLINICIAN_RECORDED' AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.id=NEW.current_fact_event_id AND e.state_version=NEW.lock_version AND BINARY e.event_type='COMPLETE_SELECTED_APPOINTMENT' AND BINARY OLD.native_status='BOOKED' AND BINARY NEW.native_status='COMPLETED' AND BINARY NEW.attendance='ATTENDED')",'VF57_FACT_UPGRADE'))
body.insert(-1,reject(f"EXISTS(SELECT 1 FROM {E} e WHERE e.state_id=NEW.id AND e.state_version=NEW.lock_version AND ((BINARY e.event_type IN ('RESCHEDULE','CANCEL','RECORD_ATTENDANCE') AND (BINARY OLD.attendance<>'UNVERIFIED' OR BINARY OLD.native_status NOT IN ('OPEN','BOOKED'))) OR (BINARY e.event_type='CORRECT_ATTENDANCE' AND OLD.current_fact_event_id IS NULL) OR (BINARY e.event_type='COMPLETE_SELECTED_APPOINTMENT' AND (BINARY OLD.source_kind<>'CARE_APPOINTMENT' OR BINARY OLD.native_status<>'BOOKED' OR BINARY OLD.attendance='NO_SHOW'))))",'VF57_FACT_PRESTATE'))
body.insert(-1,reject(f"OLD.lock_version>0 AND NEW.source_revision<>OLD.source_revision AND ({unchanged(NATIVE+['source_fingerprint'])})",'VF57_SOURCE_NOOP'))
body.insert(-1,reject(f"NEW.last_native_event_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.id=NEW.last_native_event_id AND e.state_id=NEW.id AND e.patient_id=NEW.patient_id AND e.state_version<=NEW.lock_version)",'VF57_NATIVE_EVENT_SCOPE'))
body.insert(-1,reject(f"NEW.current_fact_event_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.id=NEW.current_fact_event_id AND e.state_id=NEW.id AND e.patient_id=NEW.patient_id AND e.state_version<=NEW.lock_version)",'VF57_FACT_EVENT_SCOPE'))
body.insert(-1,reject(f"NEW.decision_event_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM {E} e WHERE e.id=NEW.decision_event_id AND e.state_id=NEW.id AND e.patient_id=NEW.patient_id AND e.state_version<=NEW.lock_version)",'VF57_DECISION_EVENT_SCOPE'))
guard('state_update',S,'UPDATE','\n'.join(body),'人类版本严格+1且与当前不可变event完整after快照一致；系统仅一次三个marker CAS，不改人类事实')
guard('state_delete',S,'DELETE',reject('TRUE','VF57_STATE_RETAINED'),'纳管身份不可删除退出，失败事务仍整体回滚')
# Event insertion requires the exact outstanding command but never keeps copied clinical text.
shape=["JSON_TYPE(NEW.payload_json)='OBJECT'", "JSON_TYPE(JSON_EXTRACT(NEW.payload_json,'$.after'))='OBJECT'",f"JSON_LENGTH(JSON_EXTRACT(NEW.payload_json,'$.after'))={len(AFTER)}"]
for c in SCHEMA[S]['columns']:
 if c['name'] not in AFTER:continue
 jt="JSON_TYPE(JSON_EXTRACT(NEW.payload_json,'$.after."+c['name']+"'))"
 shape.append("("+jt+" IN ('INTEGER','NULL') OR ("+jt+"='STRING' AND BINARY "+jscalar('NEW.payload_json','$.after.'+c['name'])+"='SELF'))" if c['name'] in SELF_POINTERS else '('+jt+(' IN (\'INTEGER\',\'NULL\')' if c['type']=='bigint' else " IN ('STRING','NULL')")+')' if c['nullable'] else jt+"='"+('INTEGER' if c['type']=='bigint' else 'STRING')+"'")
body=[reject(f'NEW.id<0 OR NEW.id>{MAX} OR NEW.state_id NOT BETWEEN 1 AND {MAX} OR NEW.patient_id NOT BETWEEN 1 AND {MAX} OR NEW.state_version NOT BETWEEN 1 AND {MAX} OR NEW.actor_id NOT BETWEEN 1 AND {MAX} OR BINARY NEW.event_type NOT IN ({names(OPS)})','VF57_EVENT_SHAPE'),reject('OCTET_LENGTH(NEW.payload_json)>16384 OR JSON_VALID(NEW.payload_json)<>1','VF57_EVENT_PAYLOAD'),reject('NOT ('+' AND '.join('('+x+')' for x in shape)+')','VF57_EVENT_AFTER'),reject(f"OCTET_LENGTH(NEW.command_key)<>36 OR BINARY NEW.command_key NOT REGEXP {UUID} OR OCTET_LENGTH(NEW.after_source_fingerprint)<>64 OR BINARY NEW.after_source_fingerprint NOT REGEXP {HASH} OR (NEW.before_source_fingerprint IS NOT NULL AND (OCTET_LENGTH(NEW.before_source_fingerprint)<>64 OR BINARY NEW.before_source_fingerprint NOT REGEXP {HASH})) OR CHAR_LENGTH(NEW.actor_name) NOT BETWEEN 1 AND 100 OR CHAR_LENGTH(NEW.actor_relation) NOT BETWEEN 1 AND 40 OR (NEW.occurred_at_utc IS NOT NULL AND NEW.occurred_at_utc>NEW.recorded_at)",'VF57_EVENT_FACT'),reject(f"NOT EXISTS(SELECT 1 FROM {S} s WHERE s.id=NEW.state_id AND s.patient_id=NEW.patient_id AND s.lock_version=NEW.state_version-1 AND {eq('s.source_fingerprint','NEW.before_source_fingerprint')})",'VF57_EVENT_PREVIOUS'),reject(f"NOT EXISTS(SELECT 1 FROM {C} c WHERE c.actor_id=NEW.actor_id AND BINARY c.command_key=BINARY NEW.command_key AND c.patient_id=NEW.patient_id AND c.result_json IS NULL AND BINARY c.operation=BINARY NEW.event_type AND ((c.state_id=NEW.state_id AND c.expected_version=NEW.state_version-1) OR (NEW.state_version=1 AND ((c.state_id IS NULL AND c.expected_version=0 AND BINARY c.operation IN ('TRACK_APPOINTMENT','CREATE_VISIT','SET_DISPOSITION')) OR (c.state_id IS NOT NULL AND c.state_id<>NEW.state_id AND BINARY c.operation IN ({names(SUCCESSOR_OPS)}) AND EXISTS(SELECT 1 FROM {S} p JOIN {S} t ON t.id=NEW.state_id AND t.patient_id=p.patient_id AND BINARY t.source_kind=BINARY p.source_kind WHERE p.id=c.state_id AND p.patient_id=c.patient_id AND BINARY t.source_kind IN ('CARE_ITEM_APPOINTMENT','CARE_APPOINTMENT')))))))",'VF57_EVENT_COMMAND'),reject("(NEW.reason IS NOT NULL AND CHAR_LENGTH(NEW.reason)>IF(BINARY NEW.event_type IN ('SET_DISPOSITION','REVISE_DISPOSITION'),1000,500)) OR (BINARY NEW.event_type IN ('RESCHEDULE','CANCEL','RECORD_ATTENDANCE','CORRECT_ATTENDANCE','REVISE_DISPOSITION') AND (NEW.reason IS NULL OR CHAR_LENGTH(TRIM(NEW.reason))=0))",'VF57_EVENT_REASON')]
guard('event_insert',E,'INSERT','\n'.join(body),'事件绑定当前state前版本、真实actor及同患者未完成命令；有界机器after快照，无旧event重复授权')
for op in ['UPDATE','DELETE']:guard('event_'+op.lower(),E,op,reject('TRUE','VF57_EVENT_IMMUTABLE'),'人类事实与更正只追加，历史不可更新或删除')
guard('command_insert',C,'INSERT','\n'.join([reject(f"NEW.actor_id NOT BETWEEN 1 AND {MAX} OR NEW.patient_id NOT BETWEEN 1 AND {MAX} OR NEW.expected_version NOT BETWEEN 0 AND {MAX-1} OR (NEW.state_id IS NOT NULL AND NEW.state_id NOT BETWEEN 1 AND {MAX}) OR BINARY NEW.operation NOT IN ({names(OPS)}) OR OCTET_LENGTH(NEW.command_key)<>36 OR BINARY NEW.command_key NOT REGEXP {UUID} OR OCTET_LENGTH(NEW.payload_hash)<>64 OR BINARY NEW.payload_hash NOT REGEXP {HASH}",'VF57_COMMAND_SHAPE'),reject("NEW.result_json IS NOT NULL OR (NEW.state_id IS NULL AND (NEW.expected_version<>0 OR BINARY NEW.operation NOT IN ('TRACK_APPOINTMENT','CREATE_VISIT','SET_DISPOSITION'))) OR (NEW.state_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM visit_followup_state s WHERE s.id=NEW.state_id AND s.patient_id=NEW.patient_id))",'VF57_COMMAND_RESERVATION')]),'actor/key唯一保留患者、目标、操作、版本和完整信封hash；结果必须为空')
receipt=[]
for f in ['workflowId','sourceId','version','eventId']:receipt += [f"JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.{f}'))='INTEGER'"]
for f in ['sourceKind','operation']:receipt += [f"JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.{f}'))='STRING'"]
next_operation=f"BINARY NEW.operation IN ({names(SUCCESSOR_OPS)})"
receipt += ["JSON_TYPE(NEW.result_json)='OBJECT'",f"JSON_LENGTH(NEW.result_json)=IF({next_operation},8,6)",eq(jscalar('NEW.result_json','$.workflowId'),'CAST(NEW.state_id AS CHAR)'),eq(jscalar('NEW.result_json','$.version'),'CAST(NEW.expected_version+1 AS CHAR)'),eq(jscalar('NEW.result_json','$.operation'),'NEW.operation'),f"(NOT ({next_operation}) OR (JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.successorKind'))='STRING' AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.successorKind')) IN ('CARE_ITEM_APPOINTMENT','CARE_APPOINTMENT') AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.successorId'))='INTEGER' AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.successorId')) AS UNSIGNED) BETWEEN 1 AND {MAX}))"]
primary_event=f"e.state_id=NEW.state_id AND e.patient_id=NEW.patient_id AND e.state_version=NEW.expected_version+1 AND e.actor_id=NEW.actor_id AND BINARY e.command_key=BINARY NEW.command_key AND BINARY e.event_type=BINARY NEW.operation AND {eq(jscalar('NEW.result_json','$.eventId'),'CAST(e.id AS CHAR)')}"
def successor_identity(kind,identifier):
 return eq(jscalar('e.payload_json','$.after.successor_kind'),kind)+" AND ((BINARY "+kind+"='CARE_ITEM_APPOINTMENT' AND "+eq(jscalar('e.payload_json','$.after.successor_care_item_id'),identifier)+") OR (BINARY "+kind+"='CARE_APPOINTMENT' AND "+eq(jscalar('e.payload_json','$.after.successor_care_appointment_id'),identifier)+"))"
receipt_successor=successor_identity(jscalar('NEW.result_json','$.successorKind'),jscalar('NEW.result_json','$.successorId'))
# Target-first ordering is allowed. Closing the command must bind every secondary
# event to the primary immutable after snapshot at its final version/fingerprint.
secondary_target=successor_identity('t.source_kind','CAST(COALESCE(t.care_item_id,t.care_appointment_id) AS CHAR)')
secondary_closure=f"NOT EXISTS(SELECT 1 FROM {E} x WHERE x.actor_id=NEW.actor_id AND BINARY x.command_key=BINARY NEW.command_key AND x.state_id<>NEW.state_id AND NOT EXISTS(SELECT 1 FROM {E} e JOIN {S} t ON t.id=x.state_id AND t.patient_id=NEW.patient_id WHERE {primary_event} AND {next_operation} AND x.patient_id=NEW.patient_id AND BINARY x.event_type=BINARY NEW.operation AND x.state_version=1 AND t.lock_version=x.state_version AND {eq('t.source_fingerprint','x.after_source_fingerprint')} AND {secondary_target} AND {eq(jscalar('e.payload_json','$.after.successor_bound_version'),'CAST(x.state_version AS CHAR)')} AND {eq(jscalar('e.payload_json','$.after.successor_bound_fingerprint'),'x.after_source_fingerprint')}))"
body=[reject('OLD.result_json IS NOT NULL OR NEW.result_json IS NULL OR NEW.state_id IS NULL OR NOT ('+unchanged(['actor_id','command_key','patient_id','expected_version','operation','payload_hash','created_at'])+') OR (OLD.state_id IS NOT NULL AND NOT (NEW.state_id <=> OLD.state_id))','VF57_COMMAND_IMMUTABLE'),reject('OCTET_LENGTH(NEW.result_json)>512 OR JSON_VALID(NEW.result_json)<>1','VF57_RECEIPT_LIMIT'),reject('NOT ('+' AND '.join('('+x+')' for x in receipt)+')','VF57_RECEIPT_SHAPE'),reject(f"NOT EXISTS(SELECT 1 FROM {S} s JOIN {E} e ON e.state_id=s.id AND e.patient_id=s.patient_id AND e.state_version=s.lock_version WHERE s.id=NEW.state_id AND s.patient_id=NEW.patient_id AND s.lock_version=NEW.expected_version+1 AND {primary_event} AND {eq(jscalar('NEW.result_json','$.sourceKind'),'s.source_kind')} AND {eq(jscalar('NEW.result_json','$.sourceId'),'CAST(COALESCE(s.care_item_id,s.care_appointment_id,s.visit_record_id) AS CHAR)')})",'VF57_RECEIPT_EVENT'),reject(f"({next_operation}) AND NOT EXISTS(SELECT 1 FROM {E} e WHERE {primary_event} AND {receipt_successor})",'VF57_RECEIPT_SUCCESSOR'),reject('NOT ('+secondary_closure+')','VF57_SECONDARY_CLOSURE')]
guard('command_update',C,'UPDATE','\n'.join(body),'仅NULL回执一次关闭；最小回执及全部新增后继event精确对应主event不可变after快照')
guard('command_delete',C,'DELETE',reject('TRUE','VF57_COMMAND_RETAINED'),'幂等事务事实不可删除后重放')
guard('link_insert',L,'INSERT','\n'.join([reject(f"NEW.id<0 OR NEW.id>{MAX} OR NEW.state_id NOT BETWEEN 1 AND {MAX} OR NEW.patient_id NOT BETWEEN 1 AND {MAX} OR NEW.disposition_revision NOT BETWEEN 1 AND {MAX} OR NEW.plan_id NOT BETWEEN 1 AND {MAX} OR NEW.revision_id NOT BETWEEN 1 AND {MAX} OR NEW.action_id NOT BETWEEN 1 AND {MAX} OR NEW.binding_event_id NOT BETWEEN 1 AND {MAX} OR NEW.bound_after_action_event_id NOT BETWEEN 0 AND {MAX} OR BINARY NEW.kind NOT IN ('CHECK','REVISIT','REFERRAL')",'VF57_LINK_SHAPE'),reject(f"NOT EXISTS(SELECT 1 FROM {S} s JOIN {E} e ON e.id=NEW.binding_event_id AND e.state_id=s.id AND e.patient_id=s.patient_id JOIN doctor_care_plan p ON p.id=NEW.plan_id AND p.patient_id=s.patient_id JOIN care_plan_revision r ON r.id=NEW.revision_id AND r.plan_id=p.id JOIN care_plan_action a ON a.id=NEW.action_id AND a.plan_id=p.id AND a.revision_id=r.id AND a.patient_id=s.patient_id WHERE s.id=NEW.state_id AND s.patient_id=NEW.patient_id AND BINARY s.source_kind='VISIT_RECORD' AND BINARY s.native_status='PUBLISHED' AND BINARY s.disposition='ACTIONS_REQUIRED' AND s.disposition_revision=NEW.disposition_revision AND s.decision_event_id=e.id AND e.state_version=s.lock_version AND BINARY e.event_type IN ('SET_DISPOSITION','REVISE_DISPOSITION') AND p.workflow_version=1 AND p.current_revision_id=r.id AND BINARY p.lifecycle='ACTIVE' AND BINARY r.status='PUBLISHED' AND BINARY r.plan_type='FOLLOW_UP' AND BINARY a.status IN ('OPEN','NEEDS_HELP') AND a.assigned_user_id IS NOT NULL AND a.due_at IS NOT NULL)",'VF57_LINK_CURRENT'),reject(f"NEW.bound_after_action_event_id<>COALESCE((SELECT MAX(e.id) FROM care_plan_event e WHERE e.action_id=NEW.action_id),0)",'VF57_LINK_WATERMARK'),reject(f'(SELECT COUNT(*) FROM {L} l WHERE l.state_id=NEW.state_id AND l.disposition_revision=NEW.disposition_revision)>=10','VF57_LINK_LIMIT')]),'仅当前已发布同患者visit决定关联当前FOLLOW_UP行动；真实绑定事件及action水位，不借旧CONFIRMED事实')
for op in ['UPDATE','DELETE']:guard('link_'+op.lower(),L,op,reject('TRUE','VF57_LINK_IMMUTABLE'),'关联整版追加，旧版及真实水位不可改删')
# Narrow native replacements retain the original guard body except its one B freeze branch.
def native_match(alias,kind):
 columns=[('status','native_status'),('event_at','native_start_wall'),('notify_at','native_notify_wall')] if kind=='CARE_ITEM_APPOINTMENT' else [('status','native_status'),('start_at','native_start_wall'),('end_at','native_end_wall'),('doctor_user_id','native_doctor_id'),('schedule_id','native_schedule_id')]
 # 原生 DATETIME(0) 与快照 DATETIME(6) 按时间值比较，不能转成不同精度的字符串。
 return ' AND '.join('('+alias+'.'+a+' <=> s.'+b+')' if b.endswith('_wall') else eq(alias+'.'+a,'s.'+b) for a,b in columns)
def native_gate(kind):
 field=KINDS[kind][0]
 op=NATIVE_OPS[:5] if kind=='CARE_APPOINTMENT' else ['RESCHEDULE','CANCEL','RECORD_ATTENDANCE','CORRECT_ATTENDANCE']
 exact=' AND '.join(eq('e.'+c,'s.'+c) for c in NATIVE)
 event=f"EXISTS(SELECT 1 FROM {E} e WHERE e.id=s.last_native_event_id AND e.state_id=s.id AND e.patient_id=s.patient_id AND e.state_version=s.lock_version AND BINARY e.event_type IN ({names(op)}) AND {exact})"
 columns=['status','event_at','notify_at'] if kind=='CARE_ITEM_APPOINTMENT' else ['status','start_at','end_at','doctor_user_id','schedule_id']
 markers=['notified_at','escalated_at'] if kind=='CARE_ITEM_APPOINTMENT' else ['notified_at']
 j=lambda p: "IF(JSON_VALID(n.detail),JSON_UNQUOTE(JSON_EXTRACT(n.detail,'$."+p+"')),NULL)"
 delivered=f"s.reminder_attempt_source_revision=s.source_revision AND s.reminder_attempt_id IS NOT NULL AND s.reminder_attempted_at_utc IS NOT NULL AND BINARY s.attendance='UNVERIFIED' AND BINARY s.native_status IN ('OPEN','BOOKED') AND EXISTS(SELECT 1 FROM notification_delivery_log n WHERE n.patient_id=s.patient_id AND BINARY n.event_type='VISIT_FOLLOWUP_REMINDER' AND BINARY n.delivery_status='DELIVERED' AND {eq(j('attemptId'),'s.reminder_attempt_id')} AND {eq(j('sourceKind'),'s.source_kind')} AND {eq(j('sourceId'),'CAST(s.'+field+' AS CHAR)')} AND {eq(j('sourceRevision'),'CAST(s.source_revision AS CHAR)')})"
 # A completed marker is not a new attempt; delivered log and exact candidate identity are required.
 marker=f"({unchanged(columns)} AND NEW.notified_at IS NOT NULL AND (OLD.notified_at IS NULL OR NEW.notified_at=OLD.notified_at) AND "+('NEW.escalated_at <=> OLD.escalated_at AND ' if kind=='CARE_ITEM_APPOINTMENT' else '')+delivered+')'
 resetting=' AND '.join('NEW.'+f+' IS NULL' for f in markers)
 target=f"s.{field}=OLD.id AND s.patient_id=OLD.patient_id AND BINARY s.source_kind={q(kind)} AND s.lock_version>0 AND {native_match('NEW',kind)}"
 # A matching current native event can change only its target values and reset old markers.
 return f"EXISTS(SELECT 1 FROM {S} s WHERE {target} AND (({unchanged(columns+markers)}) OR ({event} AND ({unchanged(markers)} OR ({resetting}))) OR ({marker})))"
def replacements(hh):
 out=[]
 for name,kind in [('hh_nci_bu','CARE_ITEM_APPOINTMENT'),('hh_nca_bu','CARE_APPOINTMENT')]:
  old=next(g for g in hh['guards'] if g['name']==name);g=copy.deepcopy(old);body=old['body'];gate=native_gate(kind)
  if kind=='CARE_ITEM_APPOINTMENT':
   line=next(line for line in body.splitlines() if 'OLD.family_workflow_version=1 AND NOT ((BINARY NEW.id' in line)
   # Preserve every original comparison in the default branch, add only the explicit APPOINTMENT exception.
   prefix='    OR ((OLD.family_workflow_version=1 AND NOT ('
   assert line.startswith(prefix) and line.endswith(')))')
   old_frozen=line[len(prefix):-3]
   stable=['id','patient_id','user_id','kind','title','assigned_user_id','actor_id','actor_name','data_json','created_at','medication_workflow_version','medication_execution_id','medication_reconciliation_id','family_workflow_version']
   exception="BINARY OLD.kind='APPOINTMENT' AND BINARY NEW.kind='APPOINTMENT' AND "+unchanged(stable)+' AND '+gate
   new_line=prefix+'('+old_frozen+') OR ('+exception+'))))'
   body=body.replace(line,new_line)
   # 首次 B 纳管可把 0 改为 1；上面的 OLD=1 例外仍保留原标记冻结。
   immutable=[field for field in stable if field!='family_workflow_version']
  else:
   immutable=['id','patient_id','created_by','created_at','appointment_type','consultation_mode','recurrence_days','next_follow_up_at']
  tracked=f"EXISTS(SELECT 1 FROM {S} s WHERE s.{KINDS[kind][0]}=OLD.id)"
  extra=reject(f'{tracked} AND (NOT ({unchanged(immutable)}) OR NOT ({gate}))','VF57_NATIVE_TARGET')
  assert body.endswith('\nEND');body=body[:-4]+'\n'+extra+'\nEND'
  g.update(body=body,sha256=hashlib.sha256(body.encode()).hexdigest(),invariant='原HH条件保留；仅当前同患者state/version及精确event目标允许纳管预约变更，提醒仅当前attempt真实DELIVERED完成')
  out.append(g)
 return out

def create_table(table):
 s=SCHEMA[table];lines=[]
 for c in s['columns']:
  line='  `'+c['name']+'` '+c['type'].upper()
  if c['charset']:line+=' CHARACTER SET '+c['charset']+' COLLATE '+c['charset']+'_bin'
  line+=' NULL DEFAULT NULL' if c['nullable'] else ' NOT NULL'
  if c['auto']:line+=' AUTO_INCREMENT'
  lines.append(line)
 for key in s['keys']:
  line='PRIMARY KEY' if key['name']=='PRIMARY' else ('UNIQUE KEY ' if key['unique'] else 'KEY ')+'`'+key['name']+'`'
  lines.append('  '+line+' ('+','.join('`'+x+'`' for x in key['columns'])+')')
 for f in s['fks']:
  if table==S and f['target']==E:continue
  lines.append('  '+fk_sql(f))
 return 'CREATE TABLE `'+table+'` (\n'+',\n'.join(lines)+'\n) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;'
def fk_sql(f):return f"CONSTRAINT `{f['name']}` FOREIGN KEY ("+','.join('`'+x+'`' for x in f['columns'])+f") REFERENCES `{f['target']}` ("+','.join('`'+x+'`' for x in f['targetColumns'])+') ON UPDATE RESTRICT ON DELETE RESTRICT'
def trigger_sql(gs,replace=False):
 a=['SET @vf57_prior_sql_mode=@@SESSION.sql_mode;',f'SET SESSION sql_mode={q(SQLMODE)};','SET NAMES utf8mb4 COLLATE utf8mb4_bin;','DELIMITER $$']
 for i,g in enumerate(gs,1):
  a.append('-- '+str(i)+'. '+g['invariant'])
  if replace:a.append('DROP TRIGGER `'+g['name']+'`$$')
  a.append(f"CREATE TRIGGER `{g['name']}` BEFORE {g['event']} ON `{g['table']}` FOR EACH ROW\n{g['body']}$$")
 a+=['DELIMITER ;','SET SESSION sql_mode=@vf57_prior_sql_mode;']
 return '\n\n'.join(a)+'\n'
def signatures():
 out={}
 for t,s in SCHEMA.items():
  cs=[]
  for i,c in enumerate(s['columns'],1):
   typ=c['type'].split('(')[0];size=int(re.search(r'\((\d+)\)',c['type']).group(1)) if '(' in c['type'] else None
   cs.append(dict(column_name=c['name'],ordinal_position=i,data_type=typ,column_type=c['type'],is_nullable='YES' if c['nullable'] else 'NO',column_default=None,extra='auto_increment' if c['auto'] else '',character_set_name=c['charset'],collation_name=c['charset']+'_bin' if c['charset'] else None,character_maximum_length=size if typ=='varchar' else 16777215 if typ=='mediumtext' else None,numeric_precision=None,numeric_scale=None,datetime_precision=6 if typ=='datetime' else None))
  out[t]=dict(name=t,engine='InnoDB',collation='utf8mb4_bin',rowFormat='Dynamic',columns=cs,keys=s['keys'],fks=s['fks'])
 return out
VERSION="DATABASE() IS NOT NULL AND (VERSION()='5.7.18' OR VERSION() LIKE '5.7.18-%') AND LOWER(VERSION()) NOT LIKE '%mariadb%'"
SESSION="(FIND_IN_SET('STRICT_TRANS_TABLES',@@SESSION.sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@SESSION.sql_mode)>0) AND @@SESSION.foreign_key_checks=1 AND @@SESSION.unique_checks=1"
PRESENCE="SELECT CASE WHEN DATABASE() IS NULL THEN NULL ELSE (SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND LOWER(LEFT(TABLE_NAME,15))='visit_followup_')+(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND (LOWER(LEFT(EVENT_OBJECT_TABLE,15))='visit_followup_' OR LOWER(LEFT(TRIGGER_NAME,5))='vf57_'))+(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE BINARY ROUTINE_SCHEMA=BINARY DATABASE() AND LOWER(LEFT(ROUTINE_NAME,5))='vf57_')+(SELECT COUNT(*) FROM information_schema.EVENTS WHERE BINARY EVENT_SCHEMA=BINARY DATABASE() AND LOWER(LEFT(EVENT_NAME,5))='vf57_')+(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND LOWER(LEFT(INDEX_NAME,5))='vf57_')+(SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE BINARY CONSTRAINT_SCHEMA=BINARY DATABASE() AND LOWER(LEFT(CONSTRAINT_NAME,5))='vf57_') END AS present"
def native_signatures(init):
 out={}
 for table in ['visit_record','notification_delivery_log']:
  match=re.search(r'CREATE TABLE IF NOT EXISTS `'+table+r'` \((.*?)\n\) ENGINE=InnoDB[^;]+;',init,re.S)
  if not match:raise ValueError(table)
  columns=[];keys=[]
  for line in match.group(1).splitlines():
   m=re.match(r'\s*`([^`]+)` ([A-Z]+(?:\(\d+\))?)(.*?)(?:,)?$',line)
   if m:
    name,typ,tail=m.groups();typ=typ.lower();base=typ.split('(')[0];size=int(re.search(r'\((\d+)\)',typ).group(1)) if '(' in typ else None
    default=re.search(r"DEFAULT (NULL|CURRENT_TIMESTAMP|'[^']*'|[0-9]+)",tail)
    value=None if not default or default.group(1)=='NULL' else default.group(1).strip("'")
    charset='utf8mb4' if base in ['varchar','text','longtext'] else None
    columns.append(dict(column_name=name,ordinal_position=len(columns)+1,data_type=base,column_type=typ,is_nullable='NO' if 'NOT NULL' in tail else 'YES',column_default=value,extra='auto_increment' if 'AUTO_INCREMENT' in tail else 'on update CURRENT_TIMESTAMP' if 'ON UPDATE CURRENT_TIMESTAMP' in tail else '',character_set_name=charset,collation_name='utf8mb4_general_ci' if charset else None,character_maximum_length=size if base=='varchar' else 65535 if base=='text' else 4294967295 if base=='longtext' else None,numeric_precision=None,numeric_scale=None,datetime_precision=0 if base=='datetime' else None))
   m=re.match(r'\s*(PRIMARY KEY|KEY `([^`]+)`|UNIQUE KEY `([^`]+)`) \((.*?)\)',line)
   if m:keys.append(dict(name=m.group(2) or m.group(3) or 'PRIMARY',unique=m.group(1).startswith(('PRIMARY','UNIQUE')),columns=re.findall(r'`([^`]+)`',m.group(4))))
  out[table]=dict(name=table,engine='InnoDB',collation='utf8mb4_general_ci',rowFormat=None,columns=columns,keys=keys,fks=[])
 return out

def metadata_assertions(sig,guards):
 out=[]
 def add(reason,predicate):out.append(dict(reason=reason,sql='SELECT CASE WHEN ('+predicate+') THEN 1 ELSE 0 END AS compatible',phase='metadata'))
 add('SERVER:mysql5718',VERSION+' AND '+SESSION)
 add('TABLE_COUNT:visit_followup',f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND LOWER(LEFT(TABLE_NAME,15))='visit_followup_')={len(TABLES)}")
 for t,s in sig.items():
  scope=f'BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(t)}'
  row_format=" AND ROW_FORMAT='Dynamic'" if s['rowFormat'] else ''
  add('TABLE:'+t,f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE {scope} AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND TABLE_COLLATION={q(s['collation'])}{row_format})=1")
  cases=[]
  for c in s['columns']:
   fields=['column_name','ordinal_position','data_type','is_nullable','column_default','extra','character_set_name','collation_name','character_maximum_length','datetime_precision']
   terms=[f'({f} IS NULL)' if c[f] is None else f'(BINARY {f}=BINARY {q(c[f])})' for f in fields]
   terms.append("LOWER(column_type) REGEXP '^bigint([(][0-9]+[)])?$'" if c['data_type']=='bigint' else f'BINARY column_type=BINARY {q(c["column_type"])}')
   cases.append('('+' AND '.join(terms)+')')
  add('COLUMNS:'+t,f'(SELECT COUNT(*)={len(cases)} AND SUM(CASE WHEN '+ ' OR '.join(cases)+f' THEN 1 ELSE 0 END)={len(cases)} FROM information_schema.COLUMNS WHERE {scope})')
  keys=[]
  for k in s['keys']:
   for i,c in enumerate(k['columns'],1):keys.append(f"(BINARY INDEX_NAME={q(k['name'])} AND NON_UNIQUE={0 if k['unique'] else 1} AND SEQ_IN_INDEX={i} AND BINARY COLUMN_NAME={q(c)} AND SUB_PART IS NULL AND COLLATION='A' AND INDEX_TYPE='BTREE')")
  add('INDEXES:'+t,f'(SELECT COUNT(*)={len(keys)} AND SUM(CASE WHEN '+' OR '.join(keys)+f' THEN 1 ELSE 0 END)={len(keys)} FROM information_schema.STATISTICS WHERE {scope})')
  for f in s['fks']:
   a=[]
   for i,(child,parent) in enumerate(zip(f['columns'],f['targetColumns']),1):a.append(f"(BINARY k.COLUMN_NAME={q(child)} AND k.ORDINAL_POSITION={i} AND k.POSITION_IN_UNIQUE_CONSTRAINT={i} AND BINARY k.REFERENCED_COLUMN_NAME={q(parent)})")
   where=f"BINARY k.CONSTRAINT_SCHEMA=BINARY DATABASE() AND BINARY k.TABLE_NAME={q(t)} AND BINARY k.CONSTRAINT_NAME={q(f['name'])} AND BINARY k.REFERENCED_TABLE_SCHEMA=BINARY DATABASE() AND BINARY k.REFERENCED_TABLE_NAME={q(f['target'])} AND BINARY r.UNIQUE_CONSTRAINT_SCHEMA=BINARY DATABASE() AND BINARY r.UNIQUE_CONSTRAINT_NAME={q(f['targetIndex'])} AND r.UPDATE_RULE='RESTRICT' AND r.DELETE_RULE='RESTRICT'"
   add('FK:'+f['name'],f'(SELECT COUNT(*)={len(a)} AND SUM(CASE WHEN '+' OR '.join(a)+f' THEN 1 ELSE 0 END)={len(a)} FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON BINARY r.CONSTRAINT_SCHEMA=BINARY k.CONSTRAINT_SCHEMA AND BINARY r.TABLE_NAME=BINARY k.TABLE_NAME AND BINARY r.CONSTRAINT_NAME=BINARY k.CONSTRAINT_NAME WHERE {where})')
  constraints=[(k['name'],'PRIMARY KEY' if k['name']=='PRIMARY' else 'UNIQUE') for k in s['keys'] if k['unique']]+[(f['name'],'FOREIGN KEY') for f in s['fks']]
  add('CONSTRAINTS:'+t,f'(SELECT COUNT(*)={len(constraints)} AND SUM(CASE WHEN '+' OR '.join(f'(BINARY CONSTRAINT_NAME={q(n)} AND BINARY CONSTRAINT_TYPE={q(typ)})' for n,typ in constraints)+f' THEN 1 ELSE 0 END)={len(constraints)} FROM information_schema.TABLE_CONSTRAINTS WHERE {scope})')
 for g in guards:
  checks=f"BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND BINARY EVENT_OBJECT_SCHEMA=BINARY DATABASE() AND BINARY TRIGGER_NAME={q(g['name'])} AND BINARY EVENT_OBJECT_TABLE={q(g['table'])} AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION={q(g['event'])} AND ACTION_ORIENTATION='ROW' AND ACTION_ORDER=1 AND ACTION_CONDITION IS NULL AND LOWER(SHA2(ACTION_STATEMENT,256))={q(g['sha256'])} AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION='utf8mb4_bin' AND DATABASE_COLLATION=(SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE BINARY SCHEMA_NAME=BINARY DATABASE()) AND "+' AND '.join("FIND_IN_SET("+q(v)+",SQL_MODE)>0" for v in SQLMODE.split(','))+" AND LENGTH(SQL_MODE)-LENGTH(REPLACE(SQL_MODE,',',''))=5"
  add('GUARD:'+g['name'],f'(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE {checks})=1')
 add('UNKNOWN:guards',f"(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND (LOWER(LEFT(EVENT_OBJECT_TABLE,15))='visit_followup_' OR BINARY EVENT_OBJECT_TABLE='visit_record' OR LOWER(LEFT(TRIGGER_NAME,5))='vf57_'))={len(guards)}")
 for t,p in [('ROUTINES','ROUTINE'),('EVENTS','EVENT')]:add('UNKNOWN:'+t.lower(),f"(SELECT COUNT(*) FROM information_schema.{t} WHERE BINARY {p}_SCHEMA=BINARY DATABASE() AND LOWER(LEFT({p}_NAME,5))='vf57_')=0")
 for t,field,scope in [('STATISTICS','INDEX_NAME','TABLE_SCHEMA'),('TABLE_CONSTRAINTS','CONSTRAINT_NAME','CONSTRAINT_SCHEMA')]:add('UNKNOWN:'+t.lower(),f"NOT EXISTS(SELECT 1 FROM information_schema.{t} WHERE BINARY {scope}=BINARY DATABASE() AND LOWER(LEFT({field},5))='vf57_' AND BINARY TABLE_NAME NOT IN ({names(TABLES)}))")
 return out

def snapshot_queries(hh):
 queries=copy.deepcopy(hh['snapshotQueries'])
 for k,v in queries.items():
  # All existing targeted metadata remains, plus complete finite new/native objects and reserved namespace discoveries.
  if k in ['tables','columns']:v=v.replace(' AND table_name IN ('," AND (LOWER(LEFT(table_name,15))='visit_followup_' OR table_name IN ('visit_record','notification_delivery_log') OR table_name IN (")+')'
  elif k in ['statistics','key_column_usage','referential_constraints','table_constraints']:v=v.replace(' AND (table_name IN ('," AND (LOWER(LEFT(table_name,15))='visit_followup_' OR table_name IN ('visit_record','notification_delivery_log') OR LOWER(LEFT("+('index_name' if k=='statistics' else 'constraint_name')+",5))='vf57_' OR table_name IN (")
  elif k=='triggers':v=v.replace(' AND (event_object_table IN ('," AND (LOWER(LEFT(event_object_table,15))='visit_followup_' OR event_object_table='visit_record' OR LOWER(LEFT(trigger_name,5))='vf57_' OR event_object_table IN (");v=v.replace('action_statement,sql_mode','action_statement,action_order,action_condition,sql_mode')
  elif k=='routines':v="SELECT routine_schema,routine_name FROM information_schema.routines WHERE BINARY routine_schema=BINARY DATABASE() AND (LEFT(routine_name,3)='hh_' OR LOWER(LEFT(routine_name,5))='vf57_') UNION ALL SELECT event_schema AS routine_schema,event_name AS routine_name FROM information_schema.events WHERE BINARY event_schema=BINARY DATABASE() AND LOWER(LEFT(event_name,5))='vf57_'"
  elif k=='schema':v=v.replace('AS server_version,','AS server_version,VERSION() AS raw_server_version,@@SESSION.sql_mode AS session_sql_mode,@@SESSION.foreign_key_checks AS foreign_key_checks,@@SESSION.unique_checks AS unique_checks,')
  if k=='referential_constraints':v=v.replace('unique_constraint_schema,','unique_constraint_schema,unique_constraint_name,')
  queries[k]=v
 return queries

def rules(sig):
 out=[]
 for t,s in sig.items():
  out.append(dict(kind='TABLE',identity=t,reason='TABLE:'+t));out.append(dict(kind='COLUMNS',identity=t,reason='COLUMNS:'+t))
  for c in s['columns']:out.append(dict(kind='COLUMN',identity=t+'.'+c['column_name'],table=t,reason='COLUMN:'+t+'.'+c['column_name']))
  for k in s['keys']:out.append(dict(kind='INDEX',identity=t+'.'+k['name'],table=t,reason='INDEX:'+t+'.'+k['name']))
  for f in s['fks']:out.append(dict(kind='FK',identity=t+'.'+f['name'],table=t,reason='FK:'+t+'.'+f['name']))
  out.append(dict(kind='COUNTS',identity=t,reason='COUNTS:'+t))
 for g in GUARDS:out.append(dict(kind='GUARD',identity=g['name'],reason='GUARD:'+g['name']))
 for identity in ['guards','routines','index','constraint','tables']:out.append(dict(kind='UNKNOWN',identity=identity,reason='UNKNOWN:'+identity))
 out.append(dict(kind='SESSION',identity='mysql5718',reason='SERVER:mysql5718'))
 return out

def header(edition,purpose):return f'''-- 中文操作说明：原生预约事实与诊后行动四表；适用{edition}版，MySQL 5.7.18（允许真实供应商后缀，拒绝MariaDB）。
-- 01 选库：必须由操作者明确选择并核对selected_schema；root/CN仅选对应一套，不能混跑。
-- 02 停写：排空所有旧请求并保持维护停写，两个护理assign的新代码必须一起部署；不可混跑旧writer。
-- 03 备份：先取得并验证可恢复完整备份，另保存现有hh_nci_bu/hh_nca_bu完整定义、SQL_MODE及字符集。
-- 04 既有库顺序：单独只读01 → 仅EXACT_OLD经批准执行02 → 单独只读03；FULLY_INSTALLED跳过02。
-- PARTIAL_OR_UNKNOWN、元数据不可读或任何错误立即停止；不能盲重跑补齐，不使用--force或忽略错误。
-- 05 空库：只能用完整独立init_with_visit_followup_20261017.sql；它不能升级旧库，不重复执行旧SQL。
-- 06 DDL非原子且隐式提交，可能等待元数据锁；两trigger替换窗口继续停写，不承诺在线/耗时/原子回滚。
-- 07 失败恢复：保持停写，依已保存定义和完整备份恢复或独审后的前进修复；接受父15数据后不能删表回滚。
-- 恢复旧trigger前须确认没有已接收父15事实并已禁用新写。不得将有限家庭归档当完整数据库备份。
-- 08 不回填历史、实际时间、专业结论或关系；VISIT_FOLLOWUP_ENABLED默认false，本SQL不自动开flag。
-- 09 权限/空间/恢复方案由操作者核实；须支持DELIMITER的mysql客户端，禁止RunSqlMigration分号splitter。
-- 使用已批准连接参数与交互密码；不把凭据写进脚本。原生MySQL、真实provider和生产部署均NOT_RUN。
-- 本文件：{purpose}。
'''
def predicate(assertion):
 s=assertion['sql'];m=re.match(r'SELECT CASE WHEN (.*) THEN 1 ELSE 0 END AS compatible$',s,re.S)
 if not m:raise ValueError(assertion['reason'])
 return m.group(1)
def and_all(assertions):return '('+' AND\n'.join('('+predicate(a)+')' for a in assertions)+')'
def hh_assertions(hh,overrides):
 byname={g['name']:g for g in overrides};out=[]
 for a in hh['assertions']:
  if a['phase']=='rows':continue
  a=copy.deepcopy(a)
  if a['reason'].startswith('GUARD:') and a['reason'][6:] in byname:
   old=next(g for g in hh['guards'] if g['name']==a['reason'][6:]);a['sql']=a['sql'].replace(old['sha256'],byname[a['reason'][6:]]['sha256'])
  # Existing regex literals overescape parentheses when raw SQL is copied; use character classes in new scripts only.
  a['sql']=a['sql'].replace("(\\\\([0-9]+\\\\))?", "([(][0-9]+[)])?")
  if a['reason']=='SERVER:mysql5718':a['sql']='SELECT CASE WHEN ('+VERSION+') THEN 1 ELSE 0 END AS compatible'
  out.append(a)
 return out

def build(edition):
 r=W/edition/'src/main/resources';d=r/'sql';hh=json.loads(read_input(edition,'household-workflow-schema.json'));overrides=replacements(hh);sig=signatures();native=native_signatures(read_input(edition,'sql/init.sql'));sig.update(native);assertions=metadata_assertions(sig,GUARDS)
 olda=hh_assertions(hh,[]);fulla=hh_assertions(hh,overrides);native_assertions=[a for a in assertions if any(a['reason'].endswith(':'+t) for t in native)];olda+=native_assertions
 # The old/full profile always covers the complete unchanged Household contract; these are whole alternatives.
 old='('+and_all(olda)+') AND ('+PRESENCE+')=0 AND ('+VERSION+') AND ('+SESSION+')'
 full='('+and_all(fulla)+') AND ('+and_all(assertions)+')'
 common=header(edition or 'root','只读有限结构预检；不是数据审计，不扫描临床正文')
 emit(d/'visit_followup_01_precheck_20261017.sql',common+'SELECT DATABASE() AS selected_schema,VERSION() AS server_version,CASE WHEN ('+old+") THEN 'EXACT_OLD' WHEN ("+full+") THEN 'FULLY_INSTALLED' ELSE 'PARTIAL_OR_UNKNOWN' END AS visit_followup_state;\n")
 # Postcheck lists exact mismatching object identities, not business rows.
 checks='\nUNION ALL\n'.join('SELECT '+q(a['reason'])+' AS object_difference WHERE NOT COALESCE(('+predicate(a)+'),FALSE)' for a in fulla+assertions)
 emit(d/'visit_followup_03_postcheck_20261017.sql',header(edition or 'root','只读后检，完整profile必须FULLY_INSTALLED；后续结果仅列精确结构差异')+'SELECT DATABASE() AS selected_schema,VERSION() AS server_version,CASE WHEN ('+full+") THEN 'FULLY_INSTALLED' ELSE 'PARTIAL_OR_UNKNOWN' END AS visit_followup_state;\n"+checks+';\n')
 ddl='\n\n'.join('-- 步骤 '+str(i)+'. 新增 '+t+'\n'+create_table(t) for i,t in enumerate(TABLES,1))
 ddl+='\n\n-- 步骤 5. 补入event环的真实RESTRICT外键；业务事务不得在安装中运行。\nALTER TABLE `'+S+'`\n  '+',\n  '.join('ADD '+fk_sql(f) for f in SCHEMA[S]['fks'] if f['target']==E)+';\n'
 ddl+='\n-- 步骤 6. 新四表必要执行守卫（MySQL5.7不依赖CHECK）。\n'+trigger_sql(GUARDS)
 ddl+='\n-- 步骤 7. 最窄替换两个原生更新守卫；保持维护停写，保存旧定义以便人工恢复。\n'+trigger_sql(overrides,True)
 guard="SET @vf57_exact_old=("+old+");\nSET @vf57_gate_sql=IF(@vf57_exact_old,'SELECT 1 AS exact_old_precondition','SELECT * FROM information_schema.VF57_STOP_EXACT_OLD_REQUIRED');\nPREPARE vf57_gate FROM @vf57_gate_sql;\nEXECUTE vf57_gate;\nDEALLOCATE PREPARE vf57_gate;\n"
 emit(d/'visit_followup_02_upgrade_20261017.sql',header(edition or 'root','只接受EXACT_OLD；其它状态在任何DDL前停止，FULLY_INSTALLED按01说明跳过本文件')+guard+'\n'+ddl)
 prior=read_input(edition,'sql/init_with_common_care_goals_20261016.sql');prior=prior[prior.index('SET @ccg57_empty='):]
 emit(d/'init_with_visit_followup_20261017.sql',header(edition or 'root','完整独立空库初始化，先验证空库；含全部已接受功能，不适用于升级')+prior+'\n\n-- 步骤 父15. 已接受空库版完成后，仅本新文件追加四表及两个最窄守卫版本。\n'+ddl)
 m=dict(contractVersion=1,sourceKinds=list(KINDS),tables={t:SCHEMA[t] for t in TABLES},native={},originalNativeColumns={},prerequisiteTables=list(native),signatures=sig,guards=GUARDS,canonicalPrerequisiteGuards=[],householdGuardOverrides=overrides,presenceSql=PRESENCE,snapshotQueries=snapshot_queries(hh),metadataRules=rules(sig),assertions=assertions,guardSqlMode=SQLMODE,afterFields=AFTER,payloadContract=dict(limitBytes=16384,nullable='explicit JSON null',ids='JSON integers',utcFormat="yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",wallFormat="yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),profileContract='EXACT_OLD requires complete original HH and zero p15 objects; FULLY_INSTALLED requires complete HH with exactly two replacements and full p15; no mixtures; one connection snapshot; no cache or migration',inputs={Path(relative).name:signature for relative,signature in INPUT_SHA256[edition].items()})
 emit(r/'visit-followup-schema.json',json.dumps(m,ensure_ascii=False,indent=2)+'\n')
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true', help='verify generated bytes without writing (default)')
    mode.add_argument('--write', action='store_true', help='rewrite only the ten new SQL/profile source files')
    args = parser.parse_args()
    verify_inputs()  # All editions must pass before any output is rendered or written.
    for edition in INPUT_SHA256:
        build(edition)
    allowed = set()
    for edition in INPUT_SHA256:
        resources = W / edition / 'src/main/resources'
        allowed.add(resources / 'visit-followup-schema.json')
        allowed.update(resources / 'sql' / name for name in SQL_FILES)
    if set(GENERATED) != allowed or any(path.is_symlink() for path in allowed):
        raise ValueError('Generated output set is outside the ten reviewed regular source files')
    if args.write:
        for path, value in GENERATED.items():
            path.write_bytes(value)
        print('Wrote 10 visit-followup SQL/profile files; all 6 baseline input signatures verified.')
    else:
        differences = [path.relative_to(W).as_posix() for path, value in GENERATED.items()
                       if not path.is_file() or path.read_bytes() != value]
        if differences:
            raise ValueError('Generated source differs: ' + ', '.join(differences))
        print('Verified 10 byte-identical visit-followup SQL/profile files and 6 baseline inputs.')

if __name__ == '__main__':
    main()
