/** Narrow deterministic emitter for the reviewed canonical household schema resource.
 * Usage: node scripts/generate-household-workflow.mjs --check (default) or --write.
 * 业务说明：只生成源码，不连接数据库。旧迁移字节必须保持不变；现有库不能借 fresh init 自动补旧前提。
 */
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const read=p=>readFileSync(path.join(root,p),'utf8');
const hash=s=>createHash('sha256').update(s).digest('hex');
const literal=s=>"'"+String(s).replaceAll("'","''")+"'";
const columns=xs=>xs.map(x=>'`'+x+'`').join(',');
const key=k=>(k.name==='PRIMARY'?'PRIMARY KEY':(k.unique?'UNIQUE KEY ':'KEY ')+'`'+k.name+'`')+' ('+columns(k.columns)+') USING BTREE';
const fk=f=>'CONSTRAINT `'+f.name+'` FOREIGN KEY ('+columns(f.columns)+') REFERENCES `'+f.target+'` ('+columns(f.targetColumns)+') ON UPDATE '+f.update+' ON DELETE '+f.delete;
const header=`-- 家庭健康闭环 B 结构版本 20261014；适用人工选择并核对的应用库，目标原生 MySQL 5.7.18。
-- 本文件由 household-workflow-schema.json 生成；不假定 txdata 的实际基线，源码表数不是生产事实。
-- 先执行 health_upgrade_01_precheck_20261015.sql；A 未安装与已安装 A→B 路径分开，未知/部分签名立即停止。
-- B 的执行顺序：01 只读 household_workflow_preflight_20261014.sql
-- → 02 仅在 B=ABSENT 且 prerequisite_errors=0 时执行 household_workflow_upgrade_20261014.sql
-- → 03 只读 household_workflow_postcheck_20261014.sql；预期 READY 且 prerequisite/metadata/row_errors 全为 0。
-- 01 在 B 缺失时 metadata_errors/row_errors 可非零；只能用 prerequisite_errors=0 及完全 ABSENT 准入，不能把 ABSENT 当 READY。
-- A 已安装时先只读核对 alert_workflow_postcheck_20261013.sql 的完整元数据/触发器签名及零阻断；不得重复 A DDL。
-- cp57 缺失时先按原字节的 mysql57_preflight_20261012.sql → mysql57_schema_upgrade_20261012.sql → mysql57_postcheck_20261012.sql 独立评估。
-- fresh init 独立：仅新建空库使用 init.sql，之后分别只读 postcheck；已有库禁止用 init.sql 修补。
-- 执行前停写、核对库名/版本/权限，完成全库与附件存储备份，并在隔离库验证可恢复及历史基线。
-- MySQL5.7 DDL 非原子且隐式提交；中途错误立即停止，保存原始输出，按已提交对象与备份由操作者评估恢复，不盲重跑，不用 --force。
-- 不自动修复前提、不改服务器安全配置、不激活功能、不发通知；四个 B 开关默认关闭，排程时区保持空白直到人工明确。
-- 限定家庭归档不含协作历史，不能代替全库备份或宣称全库恢复。原生数据库执行/全库恢复均 NOT_RUN，由用户手工验证。
-- Read-only assertions never print patient prose. Published A/20261012 migration bytes remain unchanged.
`
// Validate the finite derived representations before emitting anything. No database is contacted.
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
function columnSignature(name,definition,ordinal,collation){
 const match=definition.match(/^(\w+)(?:\(([^)]+)\))?/);if(!match)throw Error('Unknown column declaration '+name);
 const type=match[1].toLowerCase(),args=match[2],nullable=!/NOT NULL|PRIMARY KEY/.test(definition);
 let value=definition.match(/\bDEFAULT\s+('(?:''|[^'])*'|[\w.()+-]+)/i)?.[1]??null;
 if(value==='NULL')value=null;else if(value?.startsWith("'"))value=value.slice(1,-1).replaceAll("''","'");else if(value?.toUpperCase().startsWith('CURRENT_TIMESTAMP'))value='CURRENT_TIMESTAMP';
 const text=['char','varchar','text','longtext'].includes(type),extra=[];if(definition.includes('AUTO_INCREMENT'))extra.push('auto_increment');if(definition.includes('ON UPDATE CURRENT_TIMESTAMP'))extra.push('on update CURRENT_TIMESTAMP');
 return {column_name:name,ordinal_position:ordinal,data_type:type,column_type:type+(args?'('+args+')':''),is_nullable:nullable?'YES':'NO',column_default:value,extra:extra.join(' '),character_set_name:text?(definition.match(/CHARACTER SET (\w+)/i)?.[1]||'utf8mb4'):null,collation_name:text?(definition.match(/COLLATE (\w+)/i)?.[1]||collation):null,character_maximum_length:['char','varchar'].includes(type)?Number(args):type==='text'?65535:type==='longtext'?4294967295:null,numeric_precision:type==='decimal'?Number(args.split(',')[0]):null,numeric_scale:type==='decimal'?Number(args.split(',')[1]):null,datetime_precision:['datetime','time'].includes(type)?Number(args||0):null};
}
function declarationParts(body){let quote=null,depth=0,start=0,parts=[];for(let i=0;i<body.length;i++){const c=body[i];if(quote){if(c===quote&&body[i-1]!=='\\')quote=null;}else if("'\"`".includes(c))quote=c;else if(c==='(')depth++;else if(c===')')depth--;else if(c===','&&depth===0){parts.push(body.slice(start,i).trim());start=i+1;}}parts.push(body.slice(start).trim());return parts.filter(Boolean);}
function nativeDeclaration(p){
 const source=p.declaration,body=source.slice(source.indexOf('(')+1,source.lastIndexOf(') ENGINE=')).replace(/\s+COMMENT\s+'(?:''|\\.|[^'])*'/gi,'').replace(/\/\*[\s\S]*?\*\//g,'').replaceAll('`','');
 const result={columns:{},keys:[],fks:[]};
 for(const part of declarationParts(body)){
  let f=part.match(/^(?:CONSTRAINT (\w+) )?FOREIGN KEY\s*\((.*?)\) REFERENCES (\w+)\s*\((.*?)\)([\s\S]*)/i);
  if(f){result.fks.push({name:f[1]||null,columns:f[2].split(',').map(s=>s.trim()),target:f[3],targetColumns:f[4].split(',').map(s=>s.trim()),update:f[5].match(/ON UPDATE (CASCADE|SET NULL|RESTRICT|NO ACTION)/i)?.[1]||'RESTRICT',delete:f[5].match(/ON DELETE (CASCADE|SET NULL|RESTRICT|NO ACTION)/i)?.[1]||'RESTRICT'});continue;}
  const k=part.match(/^(PRIMARY KEY|UNIQUE KEY (\w+)|KEY (\w+))\s*\((.*?)\)/i);
  if(k){result.keys.push({name:k[2]||k[3]||'PRIMARY',unique:!k[3],columns:k[4].split(',').map(s=>s.trim())});continue;}
  const c=part.match(/^(\w+)\s+([\s\S]+)/);if(!c)throw Error('Unsupported prerequisite declaration');result.columns[c[1]]=c[2].replace(/\s+/g,' ');if(c[2].includes('PRIMARY KEY'))result.keys.unshift({name:'PRIMARY',unique:true,columns:[c[1]]});
 }
 return result;
}
// Each expression owns its complete identifier. No post-generation text rewriting.
const column=(name,alias)=>{
 if(!/^[a-z_][a-z_0-9]*$/i.test(name)||(alias!==undefined&&!/^[a-z][a-z0-9]*$/i.test(alias)))throw Error('Invalid metadata identifier');
 return alias===undefined?name:alias+'.'+name;
};
const binaryColumn=(name,alias)=>'BINARY '+column(name,alias);
const sqlEq=(name,value,alias)=>value===null?column(name,alias)+' IS NULL':typeof value==='string'?binaryColumn(name,alias)+'='+literal(value):column(name,alias)+'='+value;
const schemaIsDatabase=(name,alias)=>binaryColumn(name,alias)+'=BINARY DATABASE()';
const sqlScope=table=>schemaIsDatabase('table_schema')+' AND '+sqlEq('table_name',table);
const cpKeys=['uk_care_plan_revision_no','uk_care_plan_action_ordinal','uk_care_plan_notification_dispatch','uk_care_plan_command_actor_key'];
const constraintIdentity=(left,right)=>[
 binaryColumn('constraint_schema',left)+'='+binaryColumn('constraint_schema',right),
 ...['table_name','constraint_name'].map(name=>binaryColumn(name,left)+'='+binaryColumn(name,right))
].join(' AND ');
export function ruleSql(m,rule,phase){
 const kind=rule.kind,id=rule.identity,table=rule.table||id,sig=m.signatures[table];
 let condition;
 if(kind==='TABLE'){
  let where=sqlScope(table)+" AND table_type='BASE TABLE' AND engine='InnoDB' AND table_collation="+literal(sig.collation);
  if(m.tables[table])where+=" AND row_format='Dynamic'";
  condition='(SELECT COUNT(*) FROM information_schema.tables WHERE '+where+')=1';
 }else if(kind==='COLUMNS'){
  condition='(SELECT COUNT(*) FROM information_schema.columns WHERE '+sqlScope(table)+')='+sig.columns.length;
 }else if(kind==='NATIVE_COLUMNS'){
  condition='(SELECT COUNT(*) FROM information_schema.columns WHERE '+sqlScope(table)+' AND '+binaryColumn('column_name')+' NOT IN ('+Object.keys(m.native[table].columns).map(literal).join(',')+'))='+(sig.columns.length-Object.keys(m.native[table].columns).length);
 }else if(kind==='COLUMN'){
  const expected=sig.columns.find(c=>c.column_name===id.split('.')[1]);
  const parts=[sqlScope(table),sqlEq('column_name',expected.column_name)];
  for(const field of ['ordinal_position','data_type','is_nullable','character_set_name','collation_name','character_maximum_length','datetime_precision'])parts.push(sqlEq(field,expected[field]));
  if(expected.data_type==='decimal')for(const field of ['numeric_precision','numeric_scale'])parts.push(sqlEq(field,expected[field]));
  parts.push(['int','bigint','tinyint'].includes(expected.data_type)?'LOWER(column_type) REGEXP '+literal('^'+expected.data_type+'(\\([0-9]+\\))?$').replaceAll('\\','\\\\'):sqlEq('column_type',expected.column_type));
  parts.push(expected.column_default==='CURRENT_TIMESTAMP'?"UPPER(REPLACE(column_default,'()',''))='CURRENT_TIMESTAMP'":sqlEq('column_default',expected.column_default));
  parts.push(expected.extra==='on update CURRENT_TIMESTAMP'?"UPPER(REPLACE(extra,'()',''))='ON UPDATE CURRENT_TIMESTAMP'":sqlEq('extra',expected.extra));
  condition='(SELECT COUNT(*) FROM information_schema.columns WHERE '+parts.join(' AND ')+')=1';
 }else if(kind==='INDEX'){
  const expected=sig.keys.find(k=>k.name===id.split('.')[1]);
  const global=expected.name.startsWith('hh_')||cpKeys.includes(expected.name);
  if(rule.global!==global)throw Error('Reserved index ownership drift: '+id);
  const where=sqlScope(table)+' AND '+sqlEq('index_name',expected.name);
  const scope=global?schemaIsDatabase('table_schema')+' AND '+sqlEq('index_name',expected.name):where;
  const parts=['(SELECT COUNT(*) FROM information_schema.statistics WHERE '+scope+')='+expected.columns.length];
  expected.columns.forEach((name,i)=>parts.push('EXISTS(SELECT 1 FROM information_schema.statistics WHERE '+where+' AND seq_in_index='+(i+1)+' AND '+sqlEq('column_name',name)+' AND non_unique='+(expected.unique?0:1)+" AND sub_part IS NULL AND collation='A' AND index_type='BTREE')"));
  if(global){
   const reserved=schemaIsDatabase('constraint_schema')+' AND '+sqlEq('constraint_name',expected.name);
   parts.push('(SELECT COUNT(*) FROM information_schema.table_constraints WHERE '+reserved+')='+(expected.unique?1:0));
   parts.push('(SELECT COUNT(*) FROM information_schema.key_column_usage WHERE '+reserved+')='+(expected.unique?expected.columns.length:0));
   parts.push('(SELECT COUNT(*) FROM information_schema.referential_constraints WHERE '+reserved+')=0');
   if(expected.unique){
    parts.push('EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE '+reserved+' AND '+sqlEq('table_name',table)+" AND constraint_type='UNIQUE')");
    expected.columns.forEach((name,i)=>parts.push('EXISTS(SELECT 1 FROM information_schema.key_column_usage WHERE '+reserved+' AND '+sqlEq('table_name',table)+' AND '+sqlEq('column_name',name)+' AND ordinal_position='+(i+1)+' AND position_in_unique_constraint IS NULL AND referenced_table_schema IS NULL AND referenced_table_name IS NULL AND referenced_column_name IS NULL)'));
   }
  }
  condition=parts.join(' AND ');
 }else if(kind==='FK'){
  const name=id.split('.')[1];
  const expected=/^\d+$/.test(name)?sig.fks[Number(name)]:sig.fks.find(f=>f.name===name);
  // 匿名旧外键必须以同一个实际约束身份校验列、基数和动作，不能拼接两个外键的证据。
  const keyIdentity=constraintIdentity('k','r');
  const parts=[schemaIsDatabase('constraint_schema','r'),sqlEq('table_name',table,'r'),
   sqlEq('referenced_table_name',expected.target,'r'),schemaIsDatabase('unique_constraint_schema','r'),
   'r.update_rule='+literal(expected.update),'r.delete_rule='+literal(expected.delete)];
  if(expected.name)parts.push(sqlEq('constraint_name',expected.name,'r'));
  parts.push('(SELECT COUNT(*) FROM information_schema.key_column_usage k WHERE '+keyIdentity+')='+expected.columns.length);
  expected.columns.forEach((name,i)=>parts.push('EXISTS(SELECT 1 FROM information_schema.key_column_usage k WHERE '+keyIdentity+
   ' AND '+sqlEq('column_name',name,'k')+' AND k.ordinal_position='+(i+1)+' AND k.position_in_unique_constraint='+(i+1)+
   ' AND '+schemaIsDatabase('referenced_table_schema','k')+' AND '+sqlEq('referenced_table_name',expected.target,'k')+' AND '+sqlEq('referenced_column_name',expected.targetColumns[i],'k')+')'));
  parts.push('(SELECT COUNT(*) FROM information_schema.table_constraints c WHERE '+constraintIdentity('c','r')+" AND c.constraint_type='FOREIGN KEY')=1");
  condition='EXISTS(SELECT 1 FROM information_schema.referential_constraints r WHERE '+parts.join(' AND ')+')';
  if(expected.name)for(const relation of ['key_column_usage','referential_constraints','table_constraints']){
   condition+=' AND (SELECT COUNT(*) FROM information_schema.'+relation+' WHERE '+schemaIsDatabase('constraint_schema')+' AND '+sqlEq('constraint_name',expected.name)+')='+(relation==='key_column_usage'?expected.columns.length:1);
  }
 }else if(kind==='GUARD'){
  const guard=[...m.canonicalPrerequisiteGuards,...m.guards].find(g=>g.name===id);
  let where=schemaIsDatabase('trigger_schema')+' AND '+schemaIsDatabase('event_object_schema')+' AND '+sqlEq('trigger_name',guard.name)+' AND '+sqlEq('event_object_table',guard.table)+' AND action_timing='+literal(guard.timing)+' AND event_manipulation='+literal(guard.event)+" AND action_orientation='ROW' AND LOWER(SHA2(action_statement,256))="+literal(guard.sha256);
  if(guard.sqlMode)where+=' AND '+guard.sqlMode.split(',').map(mode=>'FIND_IN_SET('+literal(mode)+',sql_mode)>0').join(' AND ')+" AND LENGTH(sql_mode)-LENGTH(REPLACE(sql_mode,',',''))=5 AND character_set_client='utf8mb4' AND collation_connection='utf8mb4_bin' AND database_collation=(SELECT default_collation_name FROM information_schema.schemata WHERE "+schemaIsDatabase('schema_name')+")";
  condition='(SELECT COUNT(*) FROM information_schema.triggers WHERE '+where+')=1';
 }else if(kind==='SERVER'){
  condition="DATABASE() IS NOT NULL AND SUBSTRING_INDEX(VERSION(),'-',1)='5.7.18'";
 }else if(kind==='UNKNOWN'){
  const tables=[...Object.values(m.tables),...Object.values(m.native)];
  const reserved=tables.flatMap(t=>t.keys.filter(k=>k.name!=='PRIMARY').map(k=>k.name));
  const fks=tables.flatMap(t=>t.fks.map(f=>f.name));
  if(id==='guards')condition="NOT EXISTS(SELECT 1 FROM information_schema.triggers WHERE "+schemaIsDatabase('trigger_schema')+" AND (LEFT(trigger_name,3)='hh_' OR "+binaryColumn('event_object_table')+' IN ('+[...Object.keys(m.tables),...Object.keys(m.originalNativeColumns)].map(literal).join(',')+')) AND '+binaryColumn('trigger_name')+' NOT IN ('+m.guards.map(g=>literal(g.name)).join(',')+'))';
  else if(id==='routines')condition="NOT EXISTS(SELECT 1 FROM information_schema.routines WHERE "+schemaIsDatabase('routine_schema')+" AND LEFT(routine_name,3)='hh_')";
  else if(id==='index')condition="NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE "+schemaIsDatabase('table_schema')+" AND LEFT(index_name,3)='hh_' AND "+binaryColumn('index_name')+' NOT IN ('+reserved.map(literal).join(',')+'))';
  else if(id==='constraint')condition="NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE "+schemaIsDatabase('constraint_schema')+" AND LEFT(constraint_name,3)='hh_' AND "+binaryColumn('constraint_name')+' NOT IN ('+[...reserved,...fks].map(literal).join(',')+'))';
 }
 if(condition===undefined)throw Error('Unknown finite metadata rule '+rule.reason);
 return 'SELECT CASE WHEN ('+condition+') THEN 1 ELSE 0 END AS compatible';
}
export function snapshotQueries(m){
 const names=Object.keys(m.signatures).map(literal).join(',');
 const reserved=cpKeys.map(literal).join(',');
 const foreignNames=Object.values(m.signatures).flatMap(t=>t.fks.map(f=>f.name).filter(Boolean)).map(literal).join(',');
 const constraints=schemaIsDatabase('constraint_schema')+" AND (table_name IN ("+names+") OR LEFT(constraint_name,3)='hh_' OR constraint_name IN ("+foreignNames+','+reserved+'))';
 return {
  tables:'SELECT table_schema,table_name,table_type,engine,table_collation,row_format FROM information_schema.tables WHERE '+schemaIsDatabase('table_schema')+' AND table_name IN ('+names+')',
  columns:'SELECT table_schema,table_name,column_name,ordinal_position,data_type,column_type,is_nullable,column_default,extra,character_set_name,collation_name,character_maximum_length,numeric_precision,numeric_scale,datetime_precision FROM information_schema.columns WHERE '+schemaIsDatabase('table_schema')+' AND table_name IN ('+names+')',
  statistics:'SELECT table_schema,table_name,index_name,seq_in_index,column_name,non_unique,sub_part,collation,index_type FROM information_schema.statistics WHERE '+schemaIsDatabase('table_schema')+" AND (table_name IN ("+names+") OR LEFT(index_name,3)='hh_' OR index_name IN ("+reserved+'))',
  key_column_usage:'SELECT constraint_schema,table_name,constraint_name,column_name,ordinal_position,position_in_unique_constraint,referenced_table_schema,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage WHERE '+constraints,
  referential_constraints:'SELECT constraint_schema,table_name,constraint_name,referenced_table_name,unique_constraint_schema,update_rule,delete_rule FROM information_schema.referential_constraints WHERE '+constraints,
  table_constraints:'SELECT constraint_schema,table_name,constraint_name,constraint_type FROM information_schema.table_constraints WHERE '+constraints,
  triggers:'SELECT trigger_schema,event_object_schema,trigger_name,event_object_table,action_timing,event_manipulation,action_orientation,action_statement,sql_mode,character_set_client,collation_connection,database_collation FROM information_schema.triggers WHERE '+schemaIsDatabase('trigger_schema')+" AND (event_object_table IN ("+names+") OR LEFT(trigger_name,3)='hh_' OR LEFT(trigger_name,5)='cp57_')",
  routines:'SELECT routine_schema,routine_name FROM information_schema.routines WHERE '+schemaIsDatabase('routine_schema')+" AND LEFT(routine_name,3)='hh_'",
  schema:"SELECT DATABASE() AS schema_name,SUBSTRING_INDEX(VERSION(),'-',1) AS server_version,default_collation_name FROM information_schema.schemata WHERE "+schemaIsDatabase('schema_name')
 };
}
export function presenceSql(m){
 // Presence intentionally discovers partial/miscased object names; schema ownership is always exact.
 const clauses=['EXISTS(SELECT 1 FROM information_schema.tables WHERE '+schemaIsDatabase('table_schema')+' AND table_name IN ('+Object.keys(m.tables).map(literal).join(',')+'))'];
 const columns=Object.entries(m.native).filter(([,table])=>Object.keys(table.columns).length).map(([name,table])=>'(table_name='+literal(name)+' AND column_name IN ('+Object.keys(table.columns).map(literal).join(',')+'))');
 clauses.push('EXISTS(SELECT 1 FROM information_schema.columns WHERE '+schemaIsDatabase('table_schema')+' AND ('+columns.join(' OR ')+'))');
 for(const [relation,schema,name] of [['statistics','table_schema','index_name'],['table_constraints','constraint_schema','constraint_name'],['triggers','trigger_schema','trigger_name'],['routines','routine_schema','routine_name']]){
  clauses.push('EXISTS(SELECT 1 FROM information_schema.'+relation+' WHERE '+schemaIsDatabase(schema)+' AND LEFT('+column(name)+",3)='hh_')");
 }
 return 'SELECT CASE WHEN ('+clauses.join(' OR ')+') THEN 1 ELSE 0 END AS present';
}
export function selectedRowContract(m){
 const selectedTables={},excludedTables=[];
 for(const assertion of m.assertions.filter(a=>a.phase==='rows')){
  if(!m.signatures[assertion.table].columns.some(c=>c.column_name==='patient_id')){excludedTables.push(assertion.table);continue;}
  const prefix='SELECT CASE WHEN EXISTS(SELECT 1 FROM `'+assertion.table+'` n WHERE ';
  const suffix=' LIMIT 1) THEN 0 ELSE 1 END AS compatible';
  if(!assertion.sql.startsWith(prefix)||!assertion.sql.endsWith(suffix))throw Error('Unknown row predicate framing');
  const predicate=assertion.sql.slice(prefix.length,-suffix.length);
  // Every related lookup is a known primary-key point lookup, except the declared unique invitation pair.
  const subqueries=[...predicate.matchAll(/FROM (\w+) (\w+) WHERE ([^)]*)/g)];
  for(const [,table,alias,where] of subqueries){
   const point=where.startsWith(alias+'.id=');
   const invitation=table==='emergency_assistance_recipient'&&where.startsWith('er.emergency_event_id=n.id AND er.recipient_user_id=r.id');
   if(!point&&!invitation)throw Error('Unbounded selected-row related lookup: '+assertion.table);
  }
  if(subqueries.length>15)throw Error('Selected-row related lookup budget exceeded');
  selectedTables[assertion.table]={invalidPredicate:predicate};
 }
 return {selectedTables,excludedTables};
}
export function validateManifest(m){
 if(!same(snapshotQueries(m),m.snapshotQueries))throw Error("Snapshot query drift");
 if(presenceSql(m)!==m.presenceSql)throw Error("Presence query drift");
 const selected=selectedRowContract(m),admission=m.mutationAdmission;
 if(!admission||admission.structuralStatus!=='STRUCTURALLY_READY'||admission.auditStatus!=='READY'||admission.maxSelectedRows!==50||admission.queryBudgetSeconds!==5
   ||!same(selected.selectedTables,admission.selectedTables)||!same(selected.excludedTables,admission.excludedTables))throw Error('Mutation admission contract drift');
 const familyDue=admission.systemFamilyDueAdmission;if(!familyDue||familyDue.scope!=='FAMILY_ASSISTANCE_DUE_ONLY'||familyDue.humanAccountRequired!==false||familyDue.maxSelectedRows!==4||familyDue.allowsCommandOrReceipt!==false||!same(familyDue.match,['patientId','assistanceId','cycle','exactDeadline'])||!same(familyDue.states,['REQUESTED','ACCEPTED','AWAITING_CONFIRMATION'])||familyDue.event!=='SYSTEM_DUE_EXACT_KEY_ONLY'||familyDue.target!=='LOCKED_CURRENT_TYPED_SOURCE_AND_ASSISTANCE'||familyDue.optionalNotification!=='EXACT_SYSTEM_DUE_EVENT_AND_DISPATCH'||familyDue.deliveryHousekeeping!=='HISTORIC_DUE_IDENTITY_CURRENT_SEND_GATES')throw Error('Family due admission contract drift');
 const all=new Set([...Object.keys(m.tables),...m.prerequisites.map(p=>p.name)]);if(!same([...all].sort(),Object.keys(m.signatures).sort()))throw Error('Signature table drift');
 for(const name of all){
  const fresh=m.tables[name],native=m.native[name],source=fresh||nativeDeclaration(m.prerequisites.find(p=>p.name===name)),order=fresh?source.columnOrder:Object.keys(source.columns),collation=fresh?'utf8mb4_bin':'utf8mb4_general_ci';
  if(new Set(order).size!==order.length||!same([...order].sort(),Object.keys(source.columns).sort()))throw Error('Column order drift: '+name);
  const expected=order.map((c,i)=>columnSignature(c,source.columns[c],i+1,collation));if(native)for(const c of native.columnOrder)expected.push(columnSignature(c,native.columns[c],expected.length+1,collation));
  const sig=m.signatures[name];if(!same(expected,sig.columns)||sig.engine!=='InnoDB'||sig.collation!==collation||sig.rowFormat!==(fresh?'Dynamic':null))throw Error('Column/table signature drift: '+name);
  if(!same([...source.keys,...(native?.keys||[])],sig.keys)||!same([...source.fks,...(native?.fks||[])],sig.fks))throw Error('Key/FK signature drift: '+name);
 }
 const rules=new Map(m.metadataRules.map(r=>[r.reason,r])),assertions=m.assertions.filter(a=>a.phase!=='rows');if(rules.size!==m.metadataRules.length||rules.size!==assertions.length)throw Error('Metadata rule coverage drift');
 for(const a of assertions){const rule=rules.get(a.reason);if(!rule||rule.reason!==rule.kind+':'+rule.identity||ruleSql(m,rule,a.phase)!==a.sql)throw Error('Metadata SQL drift: '+a.reason);}
 for(const g of [...m.guards,...m.canonicalPrerequisiteGuards])if(hash(g.body)!==g.sha256)throw Error('Guard body drift: '+g.name);
 for(const a of m.assertions.filter(a=>a.phase==='rows')){
  const guard=m.guards.find(g=>g.table===a.table&&g.event==='INSERT');let expression=guard.body.slice('BEGIN\n  IF '.length).split(' THEN\n    SIGNAL')[0];
  if(a.table.endsWith('_command')){const target=Object.keys(m.tables[a.table].columns).find(c=>c.endsWith('_id')&&c!=='actor_id');expression=expression.replace('OR (NEW.result_json IS NOT NULL)','OR (NEW.result_json IS NULL)')+' OR NEW.'+target+' IS NULL';}
  if(['care_intake_action','medication_stock_movement'].includes(a.table))expression=expression.split('\n').filter(line=>!(line.includes('OR ((EXISTS ')&&line.includes('NEW.workflow_version<>1'))).join('\n');
  const sql='SELECT CASE WHEN EXISTS(SELECT 1 FROM `'+a.table+'` n WHERE '+expression.replaceAll('NEW.','n.')+' LIMIT 1) THEN 0 ELSE 1 END AS compatible';if(a.sql!==sql)throw Error('Row SQL drift: '+a.reason);
 }
}

export function render(manifest){
 validateManifest(manifest);
 const m=manifest,ddl=[];
 for(const t of Object.values(m.tables))ddl.push('CREATE TABLE `'+t.name+'` (\n'+[...t.columnOrder.map(c=>'  `'+c+'` '+t.columns[c]),...t.keys.map(k=>'  '+key(k))].join(',\n')+'\n) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;');
 for(const t of Object.values(m.native)){
  const changes=[...t.columnOrder.map(c=>'ADD COLUMN `'+c+'` '+t.columns[c]),...t.keys.map(k=>'ADD '+key(k))];
  if(changes.length)ddl.push('ALTER TABLE `'+t.name+'`\n  '+changes.join(',\n  ')+';');
 }
 for(const t of [...Object.values(m.tables),...Object.values(m.native)])if(t.fks.length)ddl.push('ALTER TABLE `'+t.name+'`\n  '+t.fks.map(f=>'ADD '+fk(f)).join(',\n  ')+';');
 ddl.push('SET @hh_saved_sql_mode=@@SESSION.sql_mode;','SET SESSION sql_mode='+literal(m.guardSqlMode)+';','SET NAMES utf8mb4 COLLATE utf8mb4_bin;','DELIMITER $$');
 for(const g of m.guards){if(hash(g.body)!==g.sha256)throw Error('Guard hash: '+g.name);ddl.push('CREATE TRIGGER '+g.name+' '+g.timing+' '+g.event+' ON '+g.table+'\nFOR EACH ROW '+g.body+'$$');}
 ddl.push('DELIMITER ;','SET SESSION sql_mode=@hh_saved_sql_mode;');
 const statements=ddl.join('\n\n')+'\n';
 const lines=[header,'SET @hh_row_errors=0;','SET @hh_metadata_errors=0;','SET @hh_prerequisite_errors=0;','SET @hh_present=('+m.presenceSql+');'];
 for(const a of m.assertions){
  if(a.phase==='rows'){
   const raw=a.sql,hex=Buffer.from('SELECT ('+raw+') INTO @hh_row_check').toString('hex');
   lines.push('/* '+a.reason+'\n'+raw+'\n*/',"SET @hh_query=IF(@hh_present=1 AND @hh_metadata_errors=0,CONVERT(0x"+hex+" USING utf8mb4),'SELECT NULL INTO @hh_row_check /* ROW_CHECK_NOT_SAFE */');",'PREPARE hh_assert FROM @hh_query; EXECUTE hh_assert; DEALLOCATE PREPARE hh_assert;','SELECT '+literal(a.reason)+' AS reason,@hh_row_check AS compatible;','SET @hh_row_errors=@hh_row_errors+IF(@hh_row_check=1,0,1);');
  }else{
   lines.push('-- '+a.reason,'SET @hh_check=('+a.sql+');','SELECT '+literal(a.reason)+' AS reason,@hh_check AS compatible;','SET @hh_metadata_errors=@hh_metadata_errors+IF(@hh_check=1,0,1);');
   if(a.phase==='prerequisite')lines.push('SET @hh_prerequisite_errors=@hh_prerequisite_errors+IF(@hh_check=1,0,1);');
  }
 }
 lines.push("SELECT @hh_present AS household_objects_present,@hh_prerequisite_errors AS prerequisite_errors,@hh_metadata_errors AS metadata_errors,@hh_row_errors AS row_errors,CASE WHEN @hh_present=0 THEN 'ABSENT' WHEN @hh_metadata_errors=0 AND @hh_row_errors=0 THEN 'READY' ELSE 'INCOMPATIBLE' END AS household_status;",'-- Rows are diagnostics only until every metadata assertion is compatible; unknown rows never mean READY.');
 const report=lines.join('\n')+'\n';
 const admission=`
-- Stop BEFORE DDL unless this is a fully absent B installation with exact old prerequisites.
SET @hh_query=IF(@hh_present=0 AND @hh_prerequisite_errors=0 AND SUBSTRING_INDEX(VERSION(),'-',1)='5.7.18' AND DATABASE() IS NOT NULL,'SELECT 1 AS admitted','SELECT * FROM information_schema.HH_STOP_REVIEW_PREFLIGHT');
PREPARE hh_assert FROM @hh_query; EXECUTE hh_assert; DEALLOCATE PREPARE hh_assert;
-- DDL implicitly commits. A partial failure needs operator review, never automatic repair.
`;
 return {preflight:report,postcheck:report,upgrade:report+admission+statements,ddl:statements};
}
function normalizedBody(source,table){
 const match=source.match(new RegExp('CREATE TABLE IF NOT EXISTS `?'+table+'`?\\s*\\(([\\s\\S]*?)\\) ENGINE='));
 if(!match)throw Error('Missing prerequisite '+table);
 return match[1].replace(/\s+COMMENT\s*=?\s*'(?:''|\\.|[^'])*'/gi,'').replace(/\/\*!80016[\s\S]*?\*\//g,'').replaceAll('`','').replace(/\s+/g,' ').replace(/,\s*$/,'').trim();
}
export function generatedFiles(){
 const raw=read('src/main/resources/household-workflow-schema.json');
 if(raw!==read('cn/src/main/resources/household-workflow-schema.json'))throw Error('Manifest mirror differs');
 const m=JSON.parse(raw),out=render(m),files=new Map();
 for(const prefix of ['', 'cn/']){
  const manualFile=prefix+'src/main/resources/sql/health_upgrade_01_precheck_20261015.sql';
  const manual=read(manualFile).replace(/(-- HH_CANONICAL_PRESENCE_BEGIN\n)SELECT CASE WHEN \([\s\S]*?\)=1\n( THEN 'B_PRESENT_READ_POSTCHECK_READY_ONLY_ELSE_STOP'[\s\S]*?-- HH_CANONICAL_PRESENCE_END)/,(_all,before,after)=>before+'SELECT CASE WHEN ('+m.presenceSql+')=1\n'+after);
  if(!manual.includes(m.presenceSql))throw Error('Missing canonical manual B presence');
  files.set(manualFile,manual);
  const canonical=read(prefix+'src/main/resources/sql/mysql57_schema_upgrade_20261012.sql');
  const file=prefix+'src/main/resources/sql/init.sql';
  const baseline=read(file).split('\n-- Exact canonical cp57 prerequisites, fresh empty database only.')[0];
  if((baseline.match(/CREATE TRIGGER `?aw57_/g)||[]).length!==22||/CREATE TRIGGER `?cp57_/.test(baseline))throw Error('Unknown fresh-init baseline');
  for(const g of m.canonicalPrerequisiteGuards){
   if(!canonical.includes(g.definition)||hash(g.body)!==g.sha256||!canonical.includes(g.sha256))throw Error('Canonical source mismatch: '+g.name);
   if(new RegExp('CREATE TRIGGER[^\\n]* ON `?'+g.table+'\\b').test(baseline))throw Error('Fresh-init trigger collision: '+g.table);
  }
  for(const table of ['care_plan_revision','care_plan_action','care_plan_notification','care_plan_command'])if(normalizedBody(baseline,table)!==normalizedBody(m.prerequisites.find(p=>p.name===table).declaration,table))throw Error('Prerequisite target mismatch: '+table);
  for(const stage of ['preflight','postcheck','upgrade'])files.set(prefix+'src/main/resources/sql/household_workflow_'+stage+'_20261014.sql',out[stage]);
  files.set(file,baseline+'\n-- Exact canonical cp57 prerequisites, fresh empty database only.\nDELIMITER $$\n'+m.canonicalPrerequisiteGuards.map(g=>g.definition).join('\n\n')+'\nDELIMITER ;\n\n-- Reviewed household storage, fresh empty database only. All feature flags remain off.\n'+out.ddl);
 }
 return files;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const mode=process.argv[2]||'--check';if(!['--check','--write'].includes(mode))throw Error('Use --check or --write');
 for(const [file,content] of generatedFiles()){
  if(mode==='--write')writeFileSync(path.join(root,file),content);
  else if(read(file)!==content)throw Error('Generated source differs: '+file);
 }
 console.log('Household schema generated source '+(mode==='--write'?'written':'matches'));
}
