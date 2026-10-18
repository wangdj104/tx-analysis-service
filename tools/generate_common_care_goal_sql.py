#!/usr/bin/env python3
"""确定性生成共同照护目标三表、有限结构签名及独立SQL；只写源码，不连接数据库。"""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
MAX = 9007199254740991
GOAL, COMMAND, EVENT = 'common_care_goal', 'common_care_goal_command', 'common_care_goal_event'
TABLES = [GOAL, COMMAND, EVENT]
OPERATIONS = ['CREATE','REVISE','ACCEPT','DECLINE','STEP_BACK','ADD_PROGRESS','ADD_FEEDBACK','COMPLETE','CANCEL','REOPEN']
STATES = ['PROPOSED','ACTIVE','COMPLETED','CANCELLED']
SOURCES = ['ACCOUNT_STATEMENT','REPORTED_PATIENT','REPORTED_PARTICIPANT','NOT_OBTAINED']
GUARDS = []

def q(value): return "'" + str(value).replace("'", "''") + "'"
def names(values): return ','.join(q(v) for v in values)
def eq(a,b): return f'(BINARY {a} <=> BINARY {b})'
def unchanged(fields): return ' AND '.join(eq('NEW.'+f,'OLD.'+f) for f in fields)
def reject(condition,code): return f"  IF COALESCE(({condition}),TRUE) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '{code}';\n  END IF;"
def guard(name,table,event,body,invariant,positive,negative):
    GUARDS.append(dict(name='ccg57_'+name,table=table,event=event,body='BEGIN\n'+body+'\nEND',invariant=invariant,positive=positive,negative=negative))
def col(name,typ,nullable=False,charset=None,auto=False): return dict(name=name,type=typ,nullable=nullable,charset=charset,auto=auto)
def big(name,nullable=False,auto=False): return col(name,'bigint(20)',nullable,auto=auto)
def tech(name,size,nullable=False): return col(name,f'varchar({size})',nullable,'ascii')
def text(name,size): return col(name,f'varchar({size})',False,'utf8mb4')
def time(name,nullable=False): return col(name,'datetime(6)',nullable)
def fk(name,columns,parent,parent_columns,parent_index='PRIMARY'): return dict(name='ccg57_'+name,columns=columns,parent=parent,parentColumns=parent_columns,parentIndex=parent_index)
SCHEMA = {
 GOAL: dict(columns=[big('id',auto=True),big('patient_id'),big('creator_id'),text('title',120),text('goal_text',1000),text('progress_criterion',500),text('responsibility',500),tech('review_date',10,True),big('nominee_id',True),tech('state',16),big('version'),big('cycle'),big('accepted_by',True),time('accepted_at',True),time('created_at'),time('updated_at')],
 indexes=[('PRIMARY',True,['id']),('ccg57_patient_goal',True,['patient_id','id']),('ccg57_patient_state',False,['patient_id','state','id']),('ccg57_creator',False,['creator_id']),('ccg57_nominee',False,['nominee_id']),('ccg57_accepted',False,['accepted_by'])],
 fks=[fk('goal_patient',['patient_id'],'patient',['id']),fk('goal_creator',['creator_id'],'sys_user',['id']),fk('goal_nominee',['nominee_id'],'sys_user',['id']),fk('goal_accepted',['accepted_by'],'sys_user',['id'])]),
 COMMAND: dict(columns=[big('actor_id'),tech('command_key',36),big('patient_id'),big('goal_id',True),big('expected_version'),tech('operation',20),tech('payload_hash',64),col('result_json','varchar(512)',True,'utf8mb4'),time('created_at')],
 indexes=[('PRIMARY',True,['actor_id','command_key']),('ccg57_patient_actor_key',True,['patient_id','actor_id','command_key']),('ccg57_command_goal',False,['patient_id','goal_id'])],
 fks=[fk('command_actor',['actor_id'],'sys_user',['id']),fk('command_patient',['patient_id'],'patient',['id']),fk('command_goal_scope',['patient_id','goal_id'],GOAL,['patient_id','id'],'ccg57_patient_goal')]),
 EVENT: dict(columns=[big('patient_id'),big('goal_id'),big('sequence'),big('cycle'),tech('operation',20),big('actor_id'),tech('command_key',36),col('event_json','mediumtext',False,'utf8mb4'),time('recorded_at')],
 indexes=[('PRIMARY',True,['goal_id','sequence']),('ccg57_event_command',True,['actor_id','command_key']),('ccg57_event_goal_scope',False,['patient_id','goal_id']),('ccg57_event_command_scope',False,['patient_id','actor_id','command_key']),('ccg57_event_latest',False,['goal_id','cycle','operation','sequence'])],
 fks=[fk('event_goal_scope',['patient_id','goal_id'],GOAL,['patient_id','id'],'ccg57_patient_goal'),fk('event_command_scope',['patient_id','actor_id','command_key'],COMMAND,['patient_id','actor_id','command_key'],'ccg57_patient_actor_key')])
}
# 日期按填报的ISO日历文字保存，避免SQL会话时区补日/补时刻；显式核对闰年及每月天数。
def invalid_date(value):
    year=f'CAST(SUBSTRING({value},1,4) AS UNSIGNED)'; month=f'CAST(SUBSTRING({value},6,2) AS UNSIGNED)'; day=f'CAST(SUBSTRING({value},9,2) AS UNSIGNED)'
    return f"({value} IS NOT NULL AND (OCTET_LENGTH({value})<>10 OR BINARY {value} NOT REGEXP '^[0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}}$' OR {year}<1 OR {month} NOT BETWEEN 1 AND 12 OR {day}<1 OR {day}>CASE WHEN {month}=2 THEN IF(MOD({year},400)=0 OR (MOD({year},4)=0 AND MOD({year},100)<>0),29,28) WHEN {month} IN (4,6,9,11) THEN 30 ELSE 31 END))"
def valid_text(value,size): return f"({value} IS NOT NULL AND CHAR_LENGTH({value}) BETWEEN 1 AND {size} AND {value} REGEXP '[^[:space:]]')"
def scalar(path):
    x=f"JSON_EXTRACT(NEW.event_json,{q(path)})"
    return f"IF(JSON_TYPE({x})='NULL',NULL,JSON_UNQUOTE({x}))"
def jt(path): return f"JSON_TYPE(JSON_EXTRACT(NEW.event_json,{q(path)}))"
def j(path): return f"JSON_UNQUOTE(JSON_EXTRACT(NEW.event_json,{q(path)}))"
def is_type(path,kind): return f"({jt(path)} <=> {q(kind)})"
CONTENT = [('title','title'),('goalText','goal_text'),('progressCriterion','progress_criterion'),('responsibility','responsibility'),('reviewDate','review_date'),('nomineeId','nominee_id')]
FIELDS = [('goalId','id'),('patientId','patient_id'),('creatorId','creator_id'),('state','state'),('version','version'),('cycle','cycle'),('acceptedBy','accepted_by')]
TIMES = [('acceptedAt','accepted_at'),('createdAt','created_at'),('updatedAt','updated_at')]
def matches_snapshot(path,alias):
    checks=[eq(scalar(path+'.'+field),f'{alias}.{column}') for field,column in FIELDS]
    checks += [eq(scalar(path+'.content.'+field),f'{alias}.{column}') for field,column in CONTENT]
    checks += [eq(scalar(path+'.'+field),f"DATE_FORMAT({alias}.{column},'%Y-%m-%dT%H:%i:%s.%fZ')") for field,column in TIMES]
    return ' AND '.join(checks)
def snapshot_shape(path):
    checks=[is_type(path,'OBJECT'),f'JSON_LENGTH(JSON_EXTRACT(NEW.event_json,{q(path)}))=11',is_type(path+'.content','OBJECT'),f'JSON_LENGTH(JSON_EXTRACT(NEW.event_json,{q(path+".content")}))=6']
    for field in ['goalId','patientId','creatorId','version','cycle']:
        checks += [is_type(path+'.'+field,'INTEGER'),f'{j(path+"."+field)} BETWEEN 1 AND {MAX}']
    for field in ['acceptedBy','content.nomineeId']:
        checks += [f"({jt(path+'.'+field)} IN ('INTEGER','NULL'))",f'({scalar(path+"."+field)} IS NULL OR {scalar(path+"."+field)} BETWEEN 1 AND {MAX})']
    checks += [is_type(path+'.state','STRING'),f'BINARY {j(path+".state")} IN ({names(STATES)})']
    for field,size in [('title',120),('goalText',1000),('progressCriterion',500),('responsibility',500)]: checks += [is_type(path+'.content.'+field,'STRING'),valid_text(j(path+'.content.'+field),size)]
    checks += [f"({jt(path+'.content.reviewDate')} IN ('STRING','NULL'))",'NOT '+invalid_date(scalar(path+'.content.reviewDate'))]
    for field in ['createdAt','updatedAt']:
        checks += [is_type(path+'.'+field,'STRING'),f"BINARY {j(path+'.'+field)} REGEXP '^[0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}}T[0-9]{{2}}:[0-9]{{2}}:[0-9]{{2}}[.][0-9]{{6}}Z$'"]
    checks += [f"({jt(path+'.acceptedAt')} IN ('STRING','NULL'))",f"({scalar(path+'.acceptedAt')} IS NULL OR BINARY {j(path+'.acceptedAt')} REGEXP '^[0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}}T[0-9]{{2}}:[0-9]{{2}}:[0-9]{{2}}[.][0-9]{{6}}Z$')"]
    checks += [f'CAST({j(path+".cycle")} AS UNSIGNED)<=CAST({j(path+".version")} AS UNSIGNED)',f'(({scalar(path+".acceptedBy")} IS NULL)=({scalar(path+".acceptedAt")} IS NULL))',f'({scalar(path+".acceptedBy")} IS NULL OR {eq(scalar(path+".acceptedBy"),scalar(path+".content.nomineeId"))})',f"(BINARY {j(path+'.state')}<>'PROPOSED' OR {scalar(path+'.acceptedBy')} IS NULL)",f"(BINARY {j(path+'.state')} NOT IN ('ACTIVE','COMPLETED') OR {scalar(path+'.acceptedBy')} IS NOT NULL)"]
    return ' AND '.join(checks)
def goal_shape():
    checks=[f'NEW.patient_id BETWEEN 1 AND {MAX}',f'NEW.creator_id BETWEEN 1 AND {MAX}',f'NEW.version BETWEEN 1 AND {MAX}',f'NEW.cycle BETWEEN 1 AND NEW.version',f"BINARY NEW.state IN ({names(STATES)})"]
    for field,size in [('title',120),('goal_text',1000),('progress_criterion',500),('responsibility',500)]:checks.append(valid_text('NEW.'+field,size))
    for field in ['nominee_id','accepted_by']:checks.append(f'(NEW.{field} IS NULL OR NEW.{field} BETWEEN 1 AND {MAX})')
    checks += ['NOT '+invalid_date('NEW.review_date'),'(NEW.accepted_by IS NULL)=(NEW.accepted_at IS NULL)','(NEW.accepted_by IS NULL OR NEW.accepted_by <=> NEW.nominee_id)',"(BINARY NEW.state<>'PROPOSED' OR NEW.accepted_by IS NULL)","(BINARY NEW.state NOT IN ('ACTIVE','COMPLETED') OR NEW.accepted_by IS NOT NULL)"]
    return ' AND '.join('('+c+')' for c in checks)

guard('goal_insert',GOAL,'INSERT','\n'.join([
 reject('NOT ('+goal_shape()+')','CCG57_GOAL_SHAPE'),
 reject(f"NEW.id<0 OR NEW.id>{MAX} OR NEW.version<>1 OR NEW.cycle<>1 OR BINARY NEW.state<>'PROPOSED' OR NEW.accepted_by IS NOT NULL OR NOT (NEW.created_at <=> NEW.updated_at)",'CCG57_GOAL_INITIAL')]),'初始目标是周期1版本1的未接手提议；文字和状态有界','明确文字及可空提名的新提议','预填接手、终态、非法日期、越界字段')
guard('goal_update',GOAL,'UPDATE','\n'.join([
 reject('NOT ('+unchanged(['id','patient_id','creator_id','created_at'])+') OR NEW.version<>OLD.version+1 OR NOT ('+goal_shape()+')','CCG57_GOAL_ADVANCE'),
 # 事件正文是唯一允许的快照；这里将JSON列换成已查到的事件局部变量。
 '  SELECT event_json INTO v_event FROM common_care_goal_event WHERE goal_id=OLD.id AND sequence=NEW.version AND patient_id=OLD.patient_id;',
 reject('v_event IS NULL OR NOT ('+matches_snapshot('$.after','NEW').replace('NEW.event_json','v_event')+')','CCG57_GOAL_EVENT')]),'目标只推进一个版本且必须逐字段等于先追加事件的after','合法事件后推进匹配状态','无事件、跨患者、跳版本或与事件正文不符')
# 事件局部文字显式使用utf8mb4，不能继承整个数据库的默认字符集。
GUARDS[-1]['body']=GUARDS[-1]['body'].replace('BEGIN\n','BEGIN\n  DECLARE v_event MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL;\n',1)
guard('goal_delete',GOAL,'DELETE',reject('TRUE','CCG57_GOAL_RETAINED'),'保留目标及历史归属','整个未提交事务回滚','物理删除目标')

guard('command_insert',COMMAND,'INSERT','\n'.join([
 reject(f"NEW.actor_id NOT BETWEEN 1 AND {MAX} OR NEW.patient_id NOT BETWEEN 1 AND {MAX} OR NEW.expected_version NOT BETWEEN 0 AND {MAX-1} OR (NEW.goal_id IS NOT NULL AND NEW.goal_id NOT BETWEEN 1 AND {MAX}) OR NOT (BINARY NEW.operation IN ({names(OPERATIONS)}))",'CCG57_COMMAND_SHAPE'),
 reject("OCTET_LENGTH(NEW.command_key)<>36 OR BINARY NEW.command_key NOT REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' OR OCTET_LENGTH(NEW.payload_hash)<>64 OR BINARY NEW.payload_hash NOT REGEXP '^[0-9a-f]{64}$' OR NEW.result_json IS NOT NULL",'CCG57_COMMAND_RESERVATION'),
 reject("(BINARY NEW.operation='CREATE')<>(NEW.goal_id IS NULL) OR (BINARY NEW.operation='CREATE')<>(NEW.expected_version=0)",'CCG57_COMMAND_CREATE')]),'唯一actor/key保留不可变患者、操作及请求摘要；仅CREATE可暂缺目标','CREATE空goal/version0或普通命令真实goal','预填回执、普通命令空goal或换信封')
receipt_shape=["JSON_TYPE(NEW.result_json) <=> 'OBJECT'","JSON_LENGTH(NEW.result_json)=5"]
for field,value in [('goalId','NEW.goal_id'),('version','NEW.expected_version+1')]:receipt_shape += [f"JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.{field}')) <=> 'INTEGER'",eq(f"JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.{field}'))",f'CAST({value} AS CHAR)')]
receipt_shape += ["JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.cycle')) <=> 'INTEGER'",eq("JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.operation'))",'NEW.operation')]
guard('command_update',COMMAND,'UPDATE','\n'.join([
 reject('OLD.result_json IS NOT NULL OR NEW.result_json IS NULL OR NEW.goal_id IS NULL OR NOT ('+unchanged(['actor_id','command_key','patient_id','expected_version','operation','payload_hash','created_at'])+') OR (OLD.goal_id IS NOT NULL AND NOT (OLD.goal_id <=> NEW.goal_id))','CCG57_COMMAND_IMMUTABLE'),
 reject('OCTET_LENGTH(NEW.result_json)>512 OR JSON_VALID(NEW.result_json)<>1','CCG57_RECEIPT_LIMIT'),
 reject('NOT ('+' AND '.join('('+c+')' for c in receipt_shape)+')','CCG57_RECEIPT_SHAPE'),
 reject(f"NOT EXISTS(SELECT 1 FROM {GOAL} g JOIN {EVENT} e ON e.patient_id=g.patient_id AND e.goal_id=g.id AND e.sequence=g.version WHERE g.patient_id=NEW.patient_id AND g.id=NEW.goal_id AND g.version=NEW.expected_version+1 AND e.actor_id=NEW.actor_id AND BINARY e.command_key=BINARY NEW.command_key AND BINARY e.operation=BINARY NEW.operation AND (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.cycle')) <=> BINARY CAST(g.cycle AS CHAR)) AND (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.state')) <=> BINARY g.state))",'CCG57_RECEIPT_TRANSITION')]),'只一次绑定真实同患者goal并完成最小回执，回执对应当前事件版本','目标/事件闭合后完成五字段回执','空goal提交、回执泄露正文、错actor/版本/操作或已完成回执改写')
guard('command_delete',COMMAND,'DELETE',reject('TRUE','CCG57_COMMAND_IMMUTABLE'),'已保留的幂等事实不可删除','未提交事务回滚','删除命令后重复执行')

# 事件全部JSON先校验完整大小及类型，后读取内容；无CHECK、会话授权开关或任意SQL。
event_shape=["JSON_TYPE(NEW.event_json) <=> 'OBJECT'","JSON_LENGTH(NEW.event_json)=14"]
for field,column in [('patientId','patient_id'),('goalId','goal_id'),('sequence','sequence'),('cycle','cycle'),('actorId','actor_id')]:event_shape += [is_type('$.'+field,'INTEGER'),eq(j('$.'+field),'CAST(NEW.'+column+' AS CHAR)')]
event_shape += [eq(j('$.operation'),'NEW.operation'),eq(j('$.recordedAt'),"DATE_FORMAT(NEW.recorded_at,'%Y-%m-%dT%H:%i:%s.%fZ')"),snapshot_shape('$.after'),eq(j('$.after.goalId'),'CAST(NEW.goal_id AS CHAR)'),eq(j('$.after.patientId'),'CAST(NEW.patient_id AS CHAR)'),eq(j('$.after.version'),'CAST(NEW.sequence AS CHAR)'),eq(j('$.after.cycle'),'CAST(NEW.cycle AS CHAR)'),eq(j('$.after.updatedAt'),j('$.recordedAt'))]
for field in ['reason','note','reportedDate','feedbackSource','result']:event_shape.append(f"{jt('$.'+field)} IN ('STRING','NULL')")
body=[reject(f"NEW.patient_id NOT BETWEEN 1 AND {MAX} OR NEW.goal_id NOT BETWEEN 1 AND {MAX} OR NEW.actor_id NOT BETWEEN 1 AND {MAX} OR NEW.sequence NOT BETWEEN 1 AND {MAX} OR NEW.cycle NOT BETWEEN 1 AND NEW.sequence OR NOT (BINARY NEW.operation IN ({names(OPERATIONS)}))",'CCG57_EVENT_RANGE'),reject('OCTET_LENGTH(NEW.event_json)>61440 OR JSON_VALID(NEW.event_json)<>1','CCG57_EVENT_LIMIT'),reject('NOT ('+' AND '.join('('+c+')' for c in event_shape)+')','CCG57_EVENT_SHAPE'),reject(f"NOT EXISTS(SELECT 1 FROM {COMMAND} c WHERE c.patient_id=NEW.patient_id AND c.actor_id=NEW.actor_id AND BINARY c.command_key=BINARY NEW.command_key AND c.expected_version=NEW.sequence-1 AND BINARY c.operation=BINARY NEW.operation AND c.result_json IS NULL AND c.created_at=NEW.recorded_at AND ((BINARY c.operation='CREATE' AND c.goal_id IS NULL) OR c.goal_id=NEW.goal_id))",'CCG57_EVENT_COMMAND'),
 "  IF BINARY NEW.operation='CREATE' THEN",
 reject("NEW.sequence<>1 OR NEW.cycle<>1 OR NOT ("+is_type('$.before','NULL')+") OR NOT EXISTS(SELECT 1 FROM common_care_goal g WHERE g.id=NEW.goal_id AND g.patient_id=NEW.patient_id AND g.creator_id=NEW.actor_id AND g.version=1 AND "+matches_snapshot('$.after','g')+')','CCG57_EVENT_CREATE'),
 '  ELSE',reject('NOT ('+snapshot_shape('$.before')+') OR NOT EXISTS(SELECT 1 FROM common_care_goal g WHERE g.id=NEW.goal_id AND g.patient_id=NEW.patient_id AND g.version=NEW.sequence-1 AND '+matches_snapshot('$.before','g')+')','CCG57_EVENT_BEFORE')]
identity=['goalId','patientId','creatorId','createdAt'];body.append(reject('NOT ('+' AND '.join(eq(j('$.before.'+f),j('$.after.'+f)) for f in identity)+')','CCG57_EVENT_IDENTITY'))
body += ["  IF BINARY NEW.operation='REVISE' THEN", reject(' AND '.join(eq(scalar('$.before.content.'+f),scalar('$.after.content.'+f)) for f,c in CONTENT),'CCG57_EVENT_NO_CHANGE'),'  END IF;']
# 周期只因内容/责任安排变化或重开递增；终态必须显式重开。
body.append(reject(f"CAST({j('$.after.cycle')} AS UNSIGNED)<>CAST({j('$.before.cycle')} AS UNSIGNED)+IF(BINARY NEW.operation IN ('REVISE','DECLINE','STEP_BACK','REOPEN'),1,0)",'CCG57_EVENT_CYCLE'))
allowed={'REVISE':(['PROPOSED','ACTIVE'],'PROPOSED'),'ACCEPT':(['PROPOSED'],'ACTIVE'),'DECLINE':(['PROPOSED'],'PROPOSED'),'STEP_BACK':(['ACTIVE'],'PROPOSED'),'ADD_PROGRESS':(['ACTIVE'],'ACTIVE'),'ADD_FEEDBACK':(['ACTIVE'],'ACTIVE'),'COMPLETE':(['ACTIVE'],'COMPLETED'),'CANCEL':(['PROPOSED','ACTIVE'],'CANCELLED'),'REOPEN':(['COMPLETED','CANCELLED'],'PROPOSED')}
state_cases=[f"(BINARY NEW.operation={q(op)} AND BINARY {j('$.before.state')} IN ({names(before)}) AND BINARY {j('$.after.state')}={q(after)})" for op,(before,after) in allowed.items()]
body.append(reject('NOT ('+' OR '.join(state_cases)+')','CCG57_EVENT_STATE'))
body += ["  IF BINARY NEW.operation<>'REVISE' THEN",reject('NOT ('+' AND '.join(eq(scalar('$.before.content.'+f),scalar('$.after.content.'+f)) for f,c in CONTENT if f!='nomineeId')+')','CCG57_EVENT_CONTENT'),'  END IF;',"  IF BINARY NEW.operation IN ('DECLINE','STEP_BACK','REOPEN') THEN",reject(scalar('$.after.content.nomineeId')+' IS NOT NULL','CCG57_EVENT_UNASSIGNED'),"  ELSEIF BINARY NEW.operation<>'REVISE' THEN",reject('NOT '+eq(scalar('$.before.content.nomineeId'),scalar('$.after.content.nomineeId')),'CCG57_EVENT_NOMINEE'),'  END IF;',"  IF BINARY NEW.operation='ACCEPT' THEN",reject('NOT '+eq(scalar('$.before.content.nomineeId'),'CAST(NEW.actor_id AS CHAR)')+' OR NOT '+eq(scalar('$.after.acceptedBy'),'CAST(NEW.actor_id AS CHAR)')+' OR NOT '+eq(scalar('$.after.acceptedAt'),j('$.recordedAt')),'CCG57_EVENT_ACCEPT'),"  ELSEIF BINARY NEW.operation IN ('ADD_PROGRESS','ADD_FEEDBACK','COMPLETE','CANCEL') THEN",reject('NOT '+eq(scalar('$.before.acceptedBy'),scalar('$.after.acceptedBy'))+' OR NOT '+eq(scalar('$.before.acceptedAt'),scalar('$.after.acceptedAt')),'CCG57_EVENT_ACCEPTANCE'),'  END IF;',"  IF BINARY NEW.operation='DECLINE' THEN",reject('NOT '+eq(scalar('$.before.content.nomineeId'),'CAST(NEW.actor_id AS CHAR)'),'CCG57_EVENT_SELF'),"  ELSEIF BINARY NEW.operation='STEP_BACK' THEN",reject('NOT '+eq(scalar('$.before.acceptedBy'),'CAST(NEW.actor_id AS CHAR)'),'CCG57_EVENT_SELF'),'  END IF;','  END IF;']
for field,operations in [('reason',['REVISE','DECLINE','STEP_BACK','CANCEL','REOPEN']),('note',['ADD_PROGRESS','ADD_FEEDBACK']),('result',['COMPLETE'])]:
 body += [f'  IF BINARY NEW.operation IN ({names(operations)}) THEN',reject('NOT '+valid_text(scalar('$.'+field),1000),'CCG57_EVENT_'+field.upper()),'  ELSE',reject('NOT '+is_type('$.'+field,'NULL'),'CCG57_EVENT_'+field.upper()),'  END IF;']
body += ["  IF BINARY NEW.operation IN ('ADD_PROGRESS','ADD_FEEDBACK') THEN",reject(invalid_date(scalar('$.reportedDate')),'CCG57_EVENT_DATE'),'  ELSE',reject('NOT '+is_type('$.reportedDate','NULL'),'CCG57_EVENT_DATE'),'  END IF;',"  IF BINARY NEW.operation='ADD_FEEDBACK' THEN",reject('NOT ('+is_type('$.feedbackSource','STRING')+' AND BINARY '+j('$.feedbackSource')+' IN ('+names(SOURCES)+'))','CCG57_EVENT_FEEDBACK'),'  ELSE',reject('NOT '+is_type('$.feedbackSource','NULL'),'CCG57_EVENT_FEEDBACK'),'  END IF;']
guard('event_insert',EVENT,'INSERT','\n'.join(body),'事件仅可绑定同患者同actor/key的下一版本，保留明确前后值和有界来源文字','合法CREATE或连续版本的真实转换','跨患者、错actor/key、伪前值、跳周期、漏理由、未知字段或超过60KiB')
guard('event_update',EVENT,'UPDATE',reject('TRUE','CCG57_EVENT_IMMUTABLE'),'历史事件不可改写','追加下一条事件','UPDATE任何已有事件')
guard('event_delete',EVENT,'DELETE',reject('TRUE','CCG57_EVENT_IMMUTABLE'),'历史事件不可删除','未提交事务整体回滚','DELETE已有事件')

def create_table(table):
 s=SCHEMA[table]; lines=[]
 for c in s['columns']:
  value=f"  `{c['name']}` {re.sub(r'bigint\(20\)', 'BIGINT', c['type']).upper()}"
  if c['charset']:value+=f" CHARACTER SET {c['charset']} COLLATE {c['charset']}_bin"
  value+=' NULL DEFAULT NULL' if c['nullable'] else ' NOT NULL'
  if c['auto']:value+=' AUTO_INCREMENT'
  lines.append(value)
 for name,unique,columns in s['indexes']:
  kind='PRIMARY KEY' if name=='PRIMARY' else ('UNIQUE KEY ' if unique else 'KEY ')+f'`{name}`'
  lines.append('  '+kind+' ('+','.join('`'+v+'`' for v in columns)+')')
 for f in s['fks']:lines.append(f"  CONSTRAINT `{f['name']}` FOREIGN KEY ("+','.join('`'+v+'`' for v in f['columns'])+f") REFERENCES `{f['parent']}` ("+','.join('`'+v+'`' for v in f['parentColumns'])+') ON UPDATE RESTRICT ON DELETE RESTRICT')
 return 'CREATE TABLE `'+table+'` (\n'+',\n'.join(lines)+'\n) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;'

def trigger_sql():
 return 'DELIMITER $$\n'+'\n\n'.join('-- '+g['invariant']+'。允许：'+g['positive']+'；拒绝：'+g['negative']+'。\n'+f"CREATE TRIGGER `{g['name']}` BEFORE {g['event']} ON `{g['table']}` FOR EACH ROW\n{g['body']}$$" for g in GUARDS)+'\nDELIMITER ;\n'

VERSION="DATABASE() IS NOT NULL AND (VERSION()='5.7.18' OR VERSION() LIKE '5.7.18-%') AND LOWER(VERSION()) NOT LIKE '%mariadb%'"
SESSION="(FIND_IN_SET('STRICT_TRANS_TABLES',@@SESSION.sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@SESSION.sql_mode)>0) AND @@SESSION.foreign_key_checks=1 AND @@SESSION.unique_checks=1"
PARENTS=[]
for table in ['patient','sys_user']:
 PARENTS += [f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)} AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')=1",f"(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)} AND BINARY COLUMN_NAME='id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT IS NULL AND LOWER(EXTRA)='auto_increment')=1",f"(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)} AND BINARY INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND BINARY COLUMN_NAME='id' AND SUB_PART IS NULL AND INDEX_TYPE='BTREE')=1"]
for table in ['patient','sys_user']:
 PARENTS.append(f"(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)} AND BINARY INDEX_NAME='PRIMARY')=1")
PREREQUISITE=VERSION+' AND '+SESSION+' AND\n  '+' AND\n  '.join(PARENTS)
# 同名前缀包含三张表及未知后缀，避免把部分安装误判为空。
RESERVED="BINARY LEFT(TABLE_NAME,16)=BINARY 'common_care_goal'"
PRESENCE="SELECT CASE WHEN DATABASE() IS NULL THEN NULL ELSE (SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND "+RESERVED+")+(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND (BINARY EVENT_OBJECT_TABLE IN ("+names(TABLES)+") OR BINARY LEFT(TRIGGER_NAME,6)=BINARY 'ccg57_'))+(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE BINARY ROUTINE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(ROUTINE_NAME,6)=BINARY 'ccg57_')+(SELECT COUNT(*) FROM information_schema.EVENTS WHERE BINARY EVENT_SCHEMA=BINARY DATABASE() AND BINARY LEFT(EVENT_NAME,6)=BINARY 'ccg57_')+(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(INDEX_NAME,6)=BINARY 'ccg57_')+(SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE BINARY CONSTRAINT_SCHEMA=BINARY DATABASE() AND BINARY LEFT(CONSTRAINT_NAME,6)=BINARY 'ccg57_') END AS present"

def metadata():
 expr=[PREREQUISITE,f'(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND {RESERVED})=3']
 for table,s in SCHEMA.items():
  expr.append(f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)} AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND ROW_FORMAT='Dynamic' AND TABLE_COLLATION='utf8mb4_bin')=1")
  cases=[]
  for i,c in enumerate(s['columns'],1):
   values=[f'BINARY COLUMN_NAME={q(c["name"])}',f'LOWER(COLUMN_TYPE)={q(c["type"])}',f'IS_NULLABLE={q("YES" if c["nullable"] else "NO")}',f'(BINARY COLUMN_DEFAULT <=> NULL)',f'LOWER(EXTRA)={q("auto_increment" if c["auto"] else "")}',f'ORDINAL_POSITION={i}']
   values += [f'CHARACTER_SET_NAME={q(c["charset"])}',f'COLLATION_NAME={q(c["charset"]+"_bin")}'] if c['charset'] else ['CHARACTER_SET_NAME IS NULL AND COLLATION_NAME IS NULL']
   cases.append('('+' AND '.join(values)+')')
  expr.append(f'(SELECT COUNT(*)={len(cases)} AND SUM(CASE WHEN '+' OR '.join(cases)+f' THEN 1 ELSE 0 END)={len(cases)} FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)})')
  indexes=[]
  for name,unique,cols in s['indexes']:
   for i,c in enumerate(cols,1):indexes.append(f"(BINARY INDEX_NAME={q(name)} AND NON_UNIQUE={0 if unique else 1} AND SEQ_IN_INDEX={i} AND BINARY COLUMN_NAME={q(c)} AND SUB_PART IS NULL AND INDEX_TYPE='BTREE' AND COLLATION='A')")
  expr.append(f'(SELECT COUNT(*)={len(indexes)} AND SUM(CASE WHEN '+' OR '.join(indexes)+f' THEN 1 ELSE 0 END)={len(indexes)} FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)})')
  count=0
  for f in s['fks']:
   for i,(child,parent) in enumerate(zip(f['columns'],f['parentColumns']),1):
    count+=1;expr.append(f"(SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON BINARY r.CONSTRAINT_SCHEMA=BINARY k.CONSTRAINT_SCHEMA AND BINARY r.CONSTRAINT_NAME=BINARY k.CONSTRAINT_NAME AND BINARY r.TABLE_NAME=BINARY k.TABLE_NAME WHERE BINARY k.TABLE_SCHEMA=BINARY DATABASE() AND BINARY k.TABLE_NAME={q(table)} AND BINARY k.CONSTRAINT_NAME={q(f['name'])} AND BINARY k.COLUMN_NAME={q(child)} AND k.ORDINAL_POSITION={i} AND k.POSITION_IN_UNIQUE_CONSTRAINT={i} AND BINARY k.REFERENCED_TABLE_SCHEMA=BINARY DATABASE() AND BINARY k.REFERENCED_TABLE_NAME={q(f['parent'])} AND BINARY k.REFERENCED_COLUMN_NAME={q(parent)} AND BINARY r.UNIQUE_CONSTRAINT_NAME={q(f['parentIndex'])} AND r.DELETE_RULE='RESTRICT' AND r.UPDATE_RULE='RESTRICT')=1")
  expr.append(f'(SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)} AND REFERENCED_TABLE_NAME IS NOT NULL)={count}')
  constraints=[(name,'PRIMARY KEY' if name=='PRIMARY' else 'UNIQUE') for name,u,c in s['indexes'] if u]+[(f['name'],'FOREIGN KEY') for f in s['fks']]
  expr.append(f'(SELECT COUNT(*)={len(constraints)} AND SUM(CASE WHEN '+' OR '.join(f'(BINARY CONSTRAINT_NAME={q(n)} AND CONSTRAINT_TYPE={q(t)})' for n,t in constraints)+f' THEN 1 ELSE 0 END)={len(constraints)} FROM information_schema.TABLE_CONSTRAINTS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={q(table)})')
 expr.append(f"(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND (BINARY EVENT_OBJECT_TABLE IN ({names(TABLES)}) OR BINARY LEFT(TRIGGER_NAME,6)=BINARY 'ccg57_'))={len(GUARDS)}")
 for g in GUARDS:expr.append(f"(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND BINARY TRIGGER_NAME={q(g['name'])} AND BINARY EVENT_OBJECT_TABLE={q(g['table'])} AND EVENT_MANIPULATION={q(g['event'])} AND ACTION_TIMING='BEFORE' AND ACTION_ORDER=1 AND ACTION_ORIENTATION='ROW' AND ACTION_CONDITION IS NULL AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION LIKE 'utf8mb4\\_%' AND (FIND_IN_SET('STRICT_TRANS_TABLES',SQL_MODE)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',SQL_MODE)>0) AND BINARY ACTION_STATEMENT=BINARY {q(g['body'])})=1")
 for t,p in [('ROUTINES','ROUTINE'),('EVENTS','EVENT')]:expr.append(f"(SELECT COUNT(*) FROM information_schema.{t} WHERE BINARY {p}_SCHEMA=BINARY DATABASE() AND BINARY LEFT({p}_NAME,6)=BINARY 'ccg57_')=0")
 for t,colname,scope in [('STATISTICS','INDEX_NAME','TABLE_SCHEMA'),('TABLE_CONSTRAINTS','CONSTRAINT_NAME','CONSTRAINT_SCHEMA')]:expr.append(f"(SELECT COUNT(*) FROM information_schema.{t} WHERE BINARY {scope}=BINARY DATABASE() AND BINARY LEFT({colname},6)=BINARY 'ccg57_' AND BINARY TABLE_NAME NOT IN ({names(TABLES)}))=0")
 return 'SELECT CASE WHEN\n  '+' AND\n  '.join(expr)+'\nTHEN 1 ELSE 0 END AS compatible'

def header(edition,purpose):return f'''-- 中文操作说明：共同照护目标独立三表；适用{edition}版，MySQL 5.7.18（允许供应商后缀，不支持MariaDB）。
-- 实际目标库由操作者明确选择并核对selected_schema；同一库root/cn只选一套，不交叉执行。
-- 前提：已有patient/sys_user的有符号BIGINT主键和InnoDB基础结构；普通家庭权限沿用现有服务。
-- 既有库：停写并验证备份可恢复 → 单独只读01 → 仅EXACT_OLD经批准执行02 → 单独只读03。
-- FULLY_INSTALLED跳过02；PARTIAL_OR_UNKNOWN或任何错误立即停止，不能盲重跑、补齐或删除审计模拟回滚。
-- 空库使用独立init successor，不能把init当升级，也不能在02之后再执行init；不重跑已安装旧SQL。
-- 核实SELECT/CREATE/TRIGGER/REFERENCES权限、可用空间、表大小和恢复方案；DDL隐式提交，可能等待元数据锁。
-- 不承诺在线无锁、原子回滚或固定耗时；失败保持停写，人工恢复或审定向前修复；禁止--force或忽略错误。
-- 不回填旧数值目标、临床数据、历史、关系或患者同意；功能开关默认false，不替操作者开启。
-- 使用已批准连接参数和交互密码：mysql --batch --default-character-set=utf8mb4 --host=HOST --port=PORT --user=USER --password --database=DB < SCRIPT.sql
-- 本文件：{purpose}。只交付源文，原生MySQL运行/EXPLAIN/耗时及部署均未验证。
'''
def stop_guard(condition,name):return f"SET @ccg57_{name}=({condition});\nSET @ccg57_{name}_sql=IF(@ccg57_{name},'SELECT 1 AS precondition_passed','SELECT * FROM information_schema.CCG57_STOP_{name.upper()}');\nPREPARE ccg57_guard FROM @ccg57_{name}_sql;\nEXECUTE ccg57_guard;\nDEALLOCATE PREPARE ccg57_guard;\n\n"
def build(edition):
 resources=ROOT/edition/'src/main/resources'; d=resources/'sql'; label=edition or 'root'; ready=metadata();old='('+PREREQUISITE+') AND ('+PRESENCE+')=0'
 ddl='\n\n'.join(create_table(t) for t in TABLES)+'\n\n'+trigger_sql()
 (d/'common_care_goal_01_precheck_20261016.sql').write_text(header(label,'只读预检，仅有限命名结构元数据，不扫描临床表')+"SELECT DATABASE() AS selected_schema,VERSION() AS server_version,CASE WHEN ("+old+") THEN 'EXACT_OLD' WHEN ("+ready+")=1 THEN 'FULLY_INSTALLED' ELSE 'PARTIAL_OR_UNKNOWN' END AS common_care_goal_state;\n")
 (d/'common_care_goal_02_upgrade_20261016.sql').write_text(header(label,'仅EXACT_OLD新增三表/守卫，不迁移旧数据')+stop_guard(old,'exact_old')+'SET NAMES utf8mb4;\n\n'+ddl)
 (d/'common_care_goal_03_postcheck_20261016.sql').write_text(header(label,'只读后检，必须输出FULLY_INSTALLED')+"SELECT DATABASE() AS selected_schema,VERSION() AS server_version,CASE WHEN ("+ready+")=1 THEN 'FULLY_INSTALLED' ELSE 'PARTIAL_OR_UNKNOWN' END AS common_care_goal_state;\n")
 old_init=d/'init_with_medical_record_corrections_20261016.sql';prior=old_init.read_text();prior=prior[prior.index('SET @mrc57_empty='):]
 empty=VERSION+' AND '+SESSION+' AND '+' AND '.join(f'(SELECT COUNT(*) FROM information_schema.{t} WHERE {s}=DATABASE())=0' for t,s in [('TABLES','TABLE_SCHEMA'),('TRIGGERS','TRIGGER_SCHEMA'),('ROUTINES','ROUTINE_SCHEMA'),('EVENTS','EVENT_SCHEMA')])
 (d/'init_with_common_care_goals_20261016.sql').write_text(header(label,'只在确认空库后完整初始化，最后单独执行本组03')+stop_guard(empty,'empty')+prior+'\n\n-- 已接受旧初始化结束，仅在本新文件追加共同目标三表；旧文件不改。\n'+ddl)
 manifest=dict(contractVersion=1,tables=TABLES,presenceSql=PRESENCE,metadataSql=ready,schema=SCHEMA,guards=GUARDS,
   transition=['current WRITE; ordered authority locks; locked goal identities revalidated','reserve or exact replay before new eligibility/version/feature checks','CREATE: real goal version1; event1; real same-patient receipt binding','other NEW: immutable next event; exact matching goal update; minimal receipt','beforeCommit verifies goal/event/receipt and current authority; rollback all on failure'],inputs={old_init.name:hashlib.sha256(old_init.read_bytes()).hexdigest()})
 (resources/'common-care-goal-schema.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':
 for edition in ['', 'cn']:build(edition)
