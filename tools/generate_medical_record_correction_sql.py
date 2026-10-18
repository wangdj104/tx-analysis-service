#!/usr/bin/env python3
"""确定性生成四表、有限元数据签名与独立 SQL；只写文件，绝不连接或执行数据库。"""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
MAX = 9007199254740991
STATE = 'medical_record_correction_state'
HISTORY = 'medical_record_correction_history'
COMMAND = 'medical_record_command'
REFERENCE = 'medical_record_reference_version'
TABLES = [STATE, HISTORY, COMMAND, REFERENCE]
TRIGGERS = []


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def names(values):
    return ','.join(quote(value) for value in values)


def exact(left, right):
    # BINARY 转为字节串，NULL 安全；不能用 utf8mb4_bin 的补空格字符串等号替代。
    return f'(BINARY {left} <=> BINARY {right})'


def unchanged(fields):
    return ' AND '.join(exact('NEW.' + field, 'OLD.' + field) for field in fields)


def reject(condition, code):
    return f"  IF {condition} THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '{code}';\n  END IF;"


def trigger(name, table, event, body, invariant, positive, negative, follows=None):
    name = 'mrc57_' + name
    body = 'BEGIN\n' + body + '\nEND'
    TRIGGERS.append(dict(name=name, table=table, event=event, body=body, invariant=invariant,
                         positive=positive, negative=negative, follows=follows, order=2 if follows else 1))


def column(name, typ, nullable=False, default=None, extra='', charset=None):
    return dict(name=name, type=typ, nullable=nullable, default=default, extra=extra, charset=charset)


def bigint(name, nullable=False, auto=False):
    return column(name, 'bigint(20)', nullable, extra='auto_increment' if auto else '')


def tech(name, length):
    return column(name, f'varchar({length})', charset='ascii')


def date(name):
    return column(name, 'datetime(6)')


SCHEMA = {
    STATE: dict(columns=[bigint('record_id'), bigint('patient_id', True), bigint('owner_user_id', True),
                         bigint('baseline_content_version'), bigint('current_content_version'), bigint('current_history_sequence'),
                         tech('validity', 16), tech('content_digest', 64), date('created_at'), date('updated_at')],
                indexes=[('PRIMARY', True, ['record_id'])]),
    HISTORY: dict(columns=[bigint('id', auto=True), bigint('record_id'), bigint('history_sequence'), tech('operation', 16),
                           bigint('before_version'), bigint('after_version'), bigint('actor_id'),
                           column('reason', 'varchar(1000)', True, charset='utf8mb4'), date('captured_at'), tech('validity', 16),
                           column('snapshot_json', 'mediumtext', charset='utf8mb4'), tech('snapshot_hash', 64), tech('content_digest', 64)],
                  indexes=[('PRIMARY', True, ['id']), ('mrc57_history_sequence', True, ['record_id', 'history_sequence'])]),
    COMMAND: dict(columns=[bigint('id', auto=True), bigint('actor_id'), tech('command_key', 36), bigint('record_id'),
                           bigint('expected_version'), tech('payload_hash', 64), column('result_json', 'varchar(512)', True, charset='utf8mb4'), date('created_at')],
                  indexes=[('PRIMARY', True, ['id']), ('mrc57_actor_key', True, ['actor_id', 'command_key']), ('mrc57_command_record', False, ['record_id'])]),
    REFERENCE: dict(columns=[bigint('id', auto=True), tech('reference_kind', 32), bigint('reference_id'), bigint('patient_id'),
                             bigint('record_id'), bigint('source_item_id', True), bigint('content_version'), date('bound_at')],
                    indexes=[('PRIMARY', True, ['id']), ('mrc57_reference_anchor', True, ['reference_kind', 'reference_id']),
                             ('mrc57_reference_source', False, ['record_id', 'reference_kind', 'reference_id'])])
}
for table in TABLES:
    SCHEMA[table]['fk'] = 'mrc57_' + {STATE: 'state', HISTORY: 'history', COMMAND: 'command', REFERENCE: 'reference'}[table] + '_record'

# 基线先于初始状态；第二行事件先于原生推进。没有可伪造的会话授权开关。
trigger('history_insert', HISTORY, 'INSERT', '\n'.join([
    reject(f'NEW.record_id NOT BETWEEN 1 AND {MAX} OR NEW.actor_id NOT BETWEEN 1 AND {MAX} OR NEW.before_version NOT BETWEEN 1 AND {MAX} OR NEW.after_version NOT BETWEEN 1 AND {MAX} OR NEW.history_sequence NOT BETWEEN 0 AND {MAX}', 'MRC57_HISTORY_RANGE'),
    reject("NOT (BINARY NEW.operation IN ('BASELINE','CORRECT','INVALIDATE')) OR NOT (BINARY NEW.validity IN ('ACTIVE','INVALIDATED')) OR OCTET_LENGTH(NEW.snapshot_hash)<>64 OR BINARY NEW.snapshot_hash NOT REGEXP '^[0-9a-f]{64}$' OR OCTET_LENGTH(NEW.content_digest)<>64 OR BINARY NEW.content_digest NOT REGEXP '^[0-9a-f]{64}$'", 'MRC57_HISTORY_SHAPE'),
    reject('OCTET_LENGTH(NEW.snapshot_json)>262144 OR JSON_VALID(NEW.snapshot_json)<>1', 'MRC57_SNAPSHOT_LIMIT'),
    reject("NOT (JSON_TYPE(NEW.snapshot_json) <=> 'OBJECT') OR JSON_LENGTH(NEW.snapshot_json)<>4 OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.snapshot_json,'$.schemaVersion')) <=> 'INTEGER') OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.snapshot_json,'$.schemaVersion')) <=> BINARY '1') OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.snapshot_json,'$.record')) <=> 'OBJECT') OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.snapshot_json,'$.items')) <=> 'ARRAY') OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.snapshot_json,'$.attachments')) <=> 'ARRAY') OR JSON_LENGTH(JSON_EXTRACT(NEW.snapshot_json,'$.items'))>100 OR JSON_LENGTH(JSON_EXTRACT(NEW.snapshot_json,'$.attachments'))>100 OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.snapshot_json,'$.record.id')) <=> BINARY CAST(NEW.record_id AS CHAR)) OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.snapshot_json,'$.record.contentVersion')) <=> BINARY CAST(NEW.after_version AS CHAR)) OR NOT (BINARY SHA2(NEW.snapshot_json,256) <=> BINARY NEW.snapshot_hash)", 'MRC57_SNAPSHOT_SHAPE'),
    "  IF BINARY NEW.operation='BASELINE' THEN",
    reject(f"NEW.history_sequence<>0 OR NEW.before_version<>NEW.after_version OR NEW.reason IS NOT NULL OR BINARY NEW.validity<>'ACTIVE' OR EXISTS(SELECT 1 FROM {STATE} WHERE record_id=NEW.record_id) OR EXISTS(SELECT 1 FROM {HISTORY} WHERE record_id=NEW.record_id) OR NOT EXISTS(SELECT 1 FROM medical_record WHERE id=NEW.record_id AND content_version=NEW.after_version)", 'MRC57_BASELINE_ORDER'),
    '  ELSE',
    reject("NEW.reason IS NULL OR CHAR_LENGTH(NEW.reason)>1000 OR NOT (NEW.reason REGEXP '[^[:space:]]') OR NEW.after_version<>NEW.before_version+1", 'MRC57_CHANGE_REASON'),
    reject(f"NOT EXISTS(SELECT 1 FROM {STATE} s JOIN medical_record m ON m.id=s.record_id WHERE s.record_id=NEW.record_id AND s.current_content_version=NEW.before_version AND m.content_version=s.current_content_version AND NEW.history_sequence=s.current_history_sequence+1 AND ((BINARY NEW.operation='CORRECT' AND BINARY NEW.validity=BINARY s.validity) OR (BINARY NEW.operation='INVALIDATE' AND BINARY s.validity='ACTIVE' AND BINARY NEW.validity='INVALIDATED')))", 'MRC57_CHANGE_ORDER'),
    '  END IF;']), '历史只能追加真实版本的基线或紧接当前状态的待完成更正事件', '真实版本12的基线0，或12到13的下一条事件', '伪版本1、重复基线、跳序、无理由或恢复有效')
trigger('history_update', HISTORY, 'UPDATE', reject('TRUE', 'MRC57_HISTORY_IMMUTABLE'), '历史快照和采集事实不可改写', 'INSERT追加新事件', 'UPDATE已有历史')
trigger('history_delete', HISTORY, 'DELETE', reject('TRUE', 'MRC57_HISTORY_IMMUTABLE'), '历史不可删除', '事务回滚不受此触发器妨碍', 'DELETE历史')
trigger('state_insert', STATE, 'INSERT', '\n'.join([
    reject(f'NEW.record_id NOT BETWEEN 1 AND {MAX} OR (NEW.patient_id IS NOT NULL AND NEW.patient_id NOT BETWEEN 1 AND {MAX}) OR (NEW.owner_user_id IS NOT NULL AND NEW.owner_user_id NOT BETWEEN 1 AND {MAX})', 'MRC57_STATE_BINDING'),
    reject(f"NEW.current_history_sequence<>0 OR NEW.baseline_content_version<>NEW.current_content_version OR BINARY NEW.validity<>'ACTIVE' OR NOT EXISTS(SELECT 1 FROM medical_record m JOIN {HISTORY} h ON h.record_id=m.id AND h.history_sequence=0 WHERE m.id=NEW.record_id AND (m.patient_id <=> NEW.patient_id) AND (m.user_id <=> NEW.owner_user_id) AND m.content_version=NEW.current_content_version AND h.before_version=m.content_version AND h.after_version=m.content_version AND BINARY h.operation='BASELINE' AND BINARY h.validity='ACTIVE' AND BINARY h.content_digest=BINARY NEW.content_digest)", 'MRC57_INITIAL_STATE')]), '初始状态绑定原生身份及真实基线', '先写基线，再写版本12/序号0状态', '未写基线或患者/所有者不符')
trigger('state_update', STATE, 'UPDATE', '\n'.join([
    reject('NOT (' + unchanged(['record_id', 'patient_id', 'owner_user_id', 'baseline_content_version', 'created_at']) + ')', 'MRC57_STATE_BINDING'),
    reject(f"NEW.current_content_version<>OLD.current_content_version+1 OR NEW.current_history_sequence<>OLD.current_history_sequence+1 OR NEW.current_content_version>{MAX} OR NEW.current_history_sequence>{MAX} OR NOT EXISTS(SELECT 1 FROM medical_record m JOIN {HISTORY} h ON h.record_id=m.id AND h.history_sequence=NEW.current_history_sequence WHERE m.id=NEW.record_id AND (m.patient_id <=> NEW.patient_id) AND (m.user_id <=> NEW.owner_user_id) AND m.content_version=NEW.current_content_version AND h.before_version=OLD.current_content_version AND h.after_version=NEW.current_content_version AND BINARY h.validity=BINARY NEW.validity AND BINARY h.content_digest=BINARY NEW.content_digest AND ((BINARY h.operation='CORRECT' AND BINARY NEW.validity=BINARY OLD.validity) OR (BINARY h.operation='INVALIDATE' AND BINARY OLD.validity='ACTIVE' AND BINARY NEW.validity='INVALIDATED')))", 'MRC57_FINAL_STATE')]), '只在原生推进后令状态指向对应事件，不允许反向有效性', '原生13后状态12到13/序号0到1', '跳版本、提前推进、作废恢复有效')
trigger('state_delete', STATE, 'DELETE', reject('TRUE', 'MRC57_STATE_IMMUTABLE'), '纳管身份不能通过删除状态退出', '原事务整体回滚', 'DELETE已纳管状态')

trigger('command_insert', COMMAND, 'INSERT', '\n'.join([
    reject(f"NEW.actor_id NOT BETWEEN 1 AND {MAX} OR NEW.record_id NOT BETWEEN 1 AND {MAX} OR NEW.expected_version NOT BETWEEN 1 AND {MAX-1} OR OCTET_LENGTH(NEW.command_key)<>36 OR BINARY NEW.command_key NOT REGEXP '^[0-9a-f]{{8}}-[0-9a-f]{{4}}-[0-9a-f]{{4}}-[0-9a-f]{{4}}-[0-9a-f]{{12}}$' OR OCTET_LENGTH(NEW.payload_hash)<>64 OR BINARY NEW.payload_hash NOT REGEXP '^[0-9a-f]{{64}}$' OR NEW.result_json IS NOT NULL", 'MRC57_COMMAND_RESERVATION')]), '命令先保留唯一actor/key且不预填回执', '授权及源锁之后保留待完成命令', '预填结果、非法ID或非规范UUID')
receipt_checks = " OR ".join("NOT (JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$."+field+"')) <=> 'INTEGER')" for field in ['recordId','contentVersion','historySequence'])
trigger('command_update', COMMAND, 'UPDATE', '\n'.join([
    reject('OLD.result_json IS NOT NULL OR NEW.result_json IS NULL OR NOT (' + unchanged(['id','actor_id','command_key','record_id','expected_version','payload_hash','created_at']) + ')', 'MRC57_COMMAND_IMMUTABLE'),
    reject('OCTET_LENGTH(NEW.result_json)>512 OR JSON_VALID(NEW.result_json)<>1', 'MRC57_RECEIPT_LIMIT'),
    reject("NOT (JSON_TYPE(NEW.result_json) <=> 'OBJECT') OR JSON_LENGTH(NEW.result_json)<>5 OR "+receipt_checks+" OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.recordId')) <=> BINARY CAST(NEW.record_id AS CHAR)) OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.contentVersion')) <=> BINARY CAST(NEW.expected_version+1 AS CHAR))", 'MRC57_RECEIPT_SHAPE'),
    reject(f"NOT EXISTS(SELECT 1 FROM {STATE} s JOIN medical_record m ON m.id=s.record_id JOIN {HISTORY} h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence WHERE s.record_id=NEW.record_id AND m.content_version=s.current_content_version AND s.current_content_version=NEW.expected_version+1 AND h.before_version=NEW.expected_version AND h.after_version=s.current_content_version AND h.actor_id=NEW.actor_id AND BINARY h.content_digest=BINARY s.content_digest AND BINARY h.validity=BINARY s.validity AND BINARY h.operation IN ('CORRECT','INVALIDATE') AND (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.historySequence')) <=> BINARY CAST(s.current_history_sequence AS CHAR)) AND (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.validity')) <=> BINARY s.validity) AND (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.operation')) <=> BINARY h.operation))", 'MRC57_RECEIPT_TRANSITION')]), '只允许从空结果一次完成最小回执，且对应已完成的原生/状态/事件', '完整转换后写入五字段回执', '泄露多余字段、提前回执或改写完成回执')
trigger('command_delete', COMMAND, 'DELETE', reject('TRUE', 'MRC57_COMMAND_IMMUTABLE'), '命令去重事实不可删除', '失败事务回滚保留与业务一起撤销', 'DELETE完成命令以重放业务')

trigger('reference_insert', REFERENCE, 'INSERT', '\n'.join([
    reject(f"NEW.reference_id NOT BETWEEN 1 AND {MAX} OR NEW.patient_id NOT BETWEEN 1 AND {MAX} OR NEW.record_id NOT BETWEEN 1 AND {MAX} OR NEW.content_version NOT BETWEEN 1 AND {MAX} OR NOT (BINARY NEW.reference_kind IN ('CARE_PLAN_EVIDENCE','ALERT_EVIDENCE','LEGACY_ALERT'))", 'MRC57_REFERENCE_SHAPE'),
    reject(f"(BINARY NEW.reference_kind='LEGACY_ALERT' AND (NEW.source_item_id IS NULL OR NEW.source_item_id NOT BETWEEN 1 AND {MAX} OR NOT EXISTS(SELECT 1 FROM medical_record_item WHERE id=NEW.source_item_id AND record_id=NEW.record_id))) OR (BINARY NEW.reference_kind IN ('CARE_PLAN_EVIDENCE','ALERT_EVIDENCE') AND NEW.source_item_id IS NOT NULL)", 'MRC57_REFERENCE_ITEM'),
    reject(f"NOT EXISTS(SELECT 1 FROM medical_record m JOIN {STATE} s ON s.record_id=m.id WHERE m.id=NEW.record_id AND m.patient_id=NEW.patient_id AND s.patient_id=NEW.patient_id AND (m.user_id <=> s.owner_user_id) AND m.content_version=NEW.content_version AND s.current_content_version=m.content_version AND BINARY s.validity='ACTIVE' AND BINARY m.verification_status='VERIFIED' AND NOT EXISTS(SELECT 1 FROM {HISTORY} h WHERE h.record_id=s.record_id AND h.history_sequence>s.current_history_sequence))", 'MRC57_REFERENCE_SOURCE')]), '三种引用只绑定当时可用的真实当前版本，LEGACY_ALERT必须带同记录原生item', '锁源后新anchor与当前有效已核对版本同事务绑定', '错患者、错item、非legacy带item、待核对/已作废版本')
trigger('reference_update', REFERENCE, 'UPDATE', reject('TRUE', 'MRC57_REFERENCE_IMMUTABLE'), '旧引用不允许重绑当前版本', '业务另行合法创建新引用', 'UPDATE旧引用以隐藏来源变化')
trigger('reference_delete', REFERENCE, 'DELETE', reject('TRUE', 'MRC57_REFERENCE_IMMUTABLE'), '引用版本事实不可删除', '未提交引用整体回滚', 'DELETE历史绑定')

immutable_parent = ['id','patient_id','patient_name','user_id','record_type','source_type','source_external_id','ai_raw_result','import_facts_json','confidence_score','created_at']
content_parent = ['record_date','hospital_name','dept_name','doctor_name','remark']
trigger('native_update', 'medical_record', 'UPDATE', '\n'.join([
    f'  IF EXISTS(SELECT 1 FROM {STATE} WHERE record_id=OLD.id) THEN',
    reject('NOT (' + unchanged(immutable_parent) + ')', 'MRC57_NATIVE_IDENTITY'),
    '  IF NEW.content_version=OLD.content_version THEN',
    reject('NOT (' + unchanged(content_parent) + ')', 'MRC57_NATIVE_UNAUDITED'),
    # 待完成事件的核对结果已进入快照；父/状态尚未闭合时不能插入独立核对，尤其不能先批准再完成作废。
    reject(f"EXISTS(SELECT 1 FROM {STATE} s WHERE s.record_id=OLD.id AND (s.current_content_version<>OLD.content_version OR EXISTS(SELECT 1 FROM {HISTORY} h WHERE h.record_id=s.record_id AND h.history_sequence>s.current_history_sequence)))", 'MRC57_PENDING_REVIEW'),
    reject(f"BINARY NEW.verification_status='VERIFIED' AND EXISTS(SELECT 1 FROM {STATE} WHERE record_id=OLD.id AND BINARY validity='INVALIDATED')", 'MRC57_INVALIDATED_REVIEW'),
    '  ELSE',
    reject(f"NEW.content_version<>OLD.content_version+1 OR NEW.content_version>{MAX} OR NOT (BINARY NEW.verification_status <=> BINARY 'REVIEW_REQUIRED') OR NEW.verified_by IS NOT NULL OR NEW.verified_at IS NOT NULL OR NEW.verification_note IS NOT NULL OR NOT EXISTS(SELECT 1 FROM {STATE} s JOIN {HISTORY} h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence+1 WHERE s.record_id=OLD.id AND s.current_content_version=OLD.content_version AND h.before_version=OLD.content_version AND h.after_version=NEW.content_version AND BINARY h.operation IN ('CORRECT','INVALIDATE'))", 'MRC57_NATIVE_TRANSITION'),
    reject(f"NOT ({unchanged(content_parent)}) AND EXISTS(SELECT 1 FROM {STATE} s JOIN {HISTORY} h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence+1 WHERE s.record_id=OLD.id AND BINARY h.operation='INVALIDATE')", 'MRC57_INVALIDATE_CONTENT'),
    '  END IF;',
    '  END IF;']), '纳管父身份/原始事实不变；内容只在待完成事件中推进一次，独立核对仅在无待完成事件且父/状态版本闭合时保持同版本', '待完成更正12到13并清核对，或稳定ACTIVE同版本独立核对', '同版本改正文、换来源、跳版本、待完成时插入核对或批准已作废记录', 'if57_medical_update_facts')
trigger('native_delete', 'medical_record', 'DELETE', reject(f'EXISTS(SELECT 1 FROM {STATE} WHERE record_id=OLD.id)', 'MRC57_NATIVE_RETAINED'), '纳管原生报告不能物理删除', '未纳管旧报告沿原合同删除', '删除纳管父并触发级联')

trigger('item_insert', 'medical_record_item', 'INSERT', reject(f'EXISTS(SELECT 1 FROM {STATE} WHERE record_id=NEW.record_id)', 'MRC57_ITEM_TOPOLOGY'), '纳管报告不能新增item', '普通未纳管新报告写入原item', '纳管后插入新item')
trigger('item_update', 'medical_record_item', 'UPDATE', '\n'.join([
    f'  IF EXISTS(SELECT 1 FROM {STATE} WHERE record_id=OLD.record_id OR record_id=NEW.record_id) THEN',
    reject('NOT ('+unchanged(['id','record_id','ai_confidence','created_at'])+')', 'MRC57_ITEM_IDENTITY'),
    '  IF NOT ('+unchanged(['item_name','item_code','result_value','unit','reference_range','is_abnormal'])+') THEN',
    reject(f"NOT EXISTS(SELECT 1 FROM {STATE} s JOIN medical_record m ON m.id=s.record_id JOIN {HISTORY} h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence+1 WHERE s.record_id=OLD.record_id AND m.content_version=s.current_content_version+1 AND h.before_version=s.current_content_version AND h.after_version=m.content_version AND BINARY h.operation='CORRECT')", 'MRC57_ITEM_TRANSITION'),
    '  END IF;', '  END IF;']), '原item只能在父已推进、状态未推进的更正窗口原位修改允许字段', 'pending CORRECT→父13→原item UPDATE→状态13', '先改item、状态完成后改item、移动/换ID/改AI置信度')
trigger('item_delete', 'medical_record_item', 'DELETE', reject(f'EXISTS(SELECT 1 FROM {STATE} WHERE record_id=OLD.record_id)', 'MRC57_ITEM_TOPOLOGY'), '纳管item不能删除重插', '未纳管旧编辑保留原兼容', 'DELETE纳管item')
for event in ['INSERT','UPDATE','DELETE']:
    condition = f'EXISTS(SELECT 1 FROM {STATE} WHERE record_id=' + ('NEW.record_id' if event == 'INSERT' else 'OLD.record_id')
    if event == 'UPDATE': condition += ' OR record_id=NEW.record_id'
    condition += ')'
    trigger('attachment_'+event.lower(), 'medical_record_attachment', event, reject(condition, 'MRC57_ATTACHMENT_RETAINED'),
            '纳管附件身份、元数据和字节全部只读', '未纳管附件沿既有合同处理', '写入、替换、移动或删除纳管附件')


def create_table(table):
    schema = SCHEMA[table]; rows = []
    for col in schema['columns']:
        typ = re.sub(r'\(20\)$', '', col['type']).upper()
        line = f"  `{col['name']}` {typ}"
        if col['charset']: line += f" CHARACTER SET {col['charset']} COLLATE {col['charset']}_bin"
        line += ' NULL' if col['nullable'] else ' NOT NULL'
        if col['extra']: line += ' AUTO_INCREMENT'
        elif col['nullable']: line += ' DEFAULT NULL'
        rows.append(line)
    for name, unique, columns in schema['indexes']:
        kind = 'PRIMARY KEY' if name == 'PRIMARY' else ('UNIQUE KEY ' if unique else 'KEY ') + f'`{name}`'
        rows.append('  '+kind+' ('+','.join('`'+c+'`' for c in columns)+')')
    rows.append(f"  CONSTRAINT `{schema['fk']}` FOREIGN KEY (`record_id`) REFERENCES `medical_record` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT")
    return f'CREATE TABLE `{table}` (\n'+',\n'.join(rows)+'\n) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;'


def trigger_sql():
    result = []
    for t in TRIGGERS:
        result.append('-- '+t['invariant']+'。允许：'+t['positive']+'；拒绝：'+t['negative']+'。')
        following = ' FOLLOWS `'+t['follows']+'`' if t['follows'] else ''
        result.append(f"CREATE TRIGGER `{t['name']}` BEFORE {t['event']} ON `{t['table']}` FOR EACH ROW{following}\n{t['body']}$$")
    return 'DELIMITER $$\n'+'\n\n'.join(result)+'\nDELIMITER ;\n'


# 只复用已接受的导入事实签名，不修改旧文件、不重新执行旧迁移。
def import_prerequisite(directory):
    text = (directory/'import_facts_03_postcheck_20261016.sql').read_text()
    match = re.search(r'SELECT CASE WHEN \((.*?)\) THEN \'FULLY_INSTALLED\'', text, re.S)
    if not match: raise ValueError('The accepted import-facts postcheck signature was not found')
    return match.group(1).strip()


def native_children(directory):
    init = (directory/'init_with_import_facts_20261016.sql').read_text()
    expressions = []
    for table in ['medical_record_item', 'medical_record_attachment']:
        block = re.search(r'CREATE TABLE IF NOT EXISTS `'+table+r'` \((.*?)\n\) ENGINE=', init, re.S).group(1)
        columns = []
        for line in block.splitlines():
            m = re.match(r'\s+`(\w+)` (BIGINT|TINYINT|VARCHAR\(\d+\)|DECIMAL\(\d+,\d+\)|DATETIME|MEDIUMTEXT)(.*)', line)
            if not m: continue
            name, typ, rest = m.groups(); typ = {'BIGINT':'bigint(20)', 'TINYINT':'tinyint(4)'}.get(typ,typ.lower())
            nullable = 'NOT NULL' not in rest and 'PRIMARY KEY' not in rest
            default = 'CURRENT_TIMESTAMP' if 'DEFAULT CURRENT_TIMESTAMP' in rest else '0' if 'DEFAULT 0' in rest else None
            columns.append(column(name,typ,nullable,default,'auto_increment' if 'AUTO_INCREMENT' in rest else '', 'utf8mb4' if typ.startswith(('varchar','mediumtext')) else None))
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)} AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND TABLE_COLLATION LIKE 'utf8mb4\\_%')=1")
        expressions.append(column_signature(table, columns, ordinal=False, exact_collation=False))
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)} AND INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id' AND SUB_PART IS NULL AND INDEX_TYPE='BTREE')=1")
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)} AND SEQ_IN_INDEX=1 AND COLUMN_NAME='record_id' AND SUB_PART IS NULL AND INDEX_TYPE='BTREE')>=1")
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON BINARY r.CONSTRAINT_SCHEMA=BINARY k.CONSTRAINT_SCHEMA AND BINARY r.CONSTRAINT_NAME=BINARY k.CONSTRAINT_NAME AND BINARY r.TABLE_NAME=BINARY k.TABLE_NAME WHERE BINARY k.TABLE_SCHEMA=BINARY DATABASE() AND BINARY k.TABLE_NAME={quote(table)} AND k.COLUMN_NAME='record_id' AND k.ORDINAL_POSITION=1 AND k.POSITION_IN_UNIQUE_CONSTRAINT=1 AND BINARY k.REFERENCED_TABLE_SCHEMA=BINARY DATABASE() AND k.REFERENCED_TABLE_NAME='medical_record' AND k.REFERENCED_COLUMN_NAME='id' AND r.DELETE_RULE='CASCADE' AND r.UPDATE_RULE IN ('RESTRICT','NO ACTION'))=1")
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)} AND REFERENCED_TABLE_NAME IS NOT NULL)=1")
    return ' AND\n  '.join(expressions)


def column_signature(table, columns, ordinal=True, exact_collation=True):
    cases=[]
    for index, col in enumerate(columns,1):
        row = [f"BINARY COLUMN_NAME={quote(col['name'])}", f"LOWER(COLUMN_TYPE)={quote(col['type'])}", f"IS_NULLABLE={quote('YES' if col['nullable'] else 'NO')}",
               f"(BINARY COLUMN_DEFAULT <=> {'NULL' if col['default'] is None else 'BINARY '+quote(col['default'])})", f"LOWER(EXTRA)={quote(col['extra'])}"]
        if ordinal: row.append(f'ORDINAL_POSITION={index}')
        if col['charset']:
            row.append(f"CHARACTER_SET_NAME={quote(col['charset'])}")
            if exact_collation: row.append(f"COLLATION_NAME={quote(col['charset']+'_bin')}")
        else: row.append('CHARACTER_SET_NAME IS NULL AND COLLATION_NAME IS NULL')
        cases.append('('+' AND '.join(row)+')')
    return f"(SELECT COUNT(*)={len(columns)} AND SUM(CASE WHEN "+' OR\n    '.join(cases)+f" THEN 1 ELSE 0 END)={len(columns)} FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)})"


RESERVED = "(BINARY TABLE_NAME IN ("+names(TABLES)+") OR BINARY LEFT(TABLE_NAME,26)=BINARY 'medical_record_correction_' OR BINARY LEFT(TABLE_NAME,22)=BINARY 'medical_record_command' OR BINARY LEFT(TABLE_NAME,32)=BINARY 'medical_record_reference_version')"
PRESENCE = ("SELECT CASE WHEN DATABASE() IS NULL THEN NULL ELSE "
            "(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND "+RESERVED+")+"
            "(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND (BINARY EVENT_OBJECT_TABLE IN ("+names(TABLES)+") OR BINARY LEFT(TRIGGER_NAME,6)=BINARY 'mrc57_'))+"
            "(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE BINARY ROUTINE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(ROUTINE_NAME,6)=BINARY 'mrc57_')+"
            "(SELECT COUNT(*) FROM information_schema.EVENTS WHERE BINARY EVENT_SCHEMA=BINARY DATABASE() AND BINARY LEFT(EVENT_NAME,6)=BINARY 'mrc57_')+"
            "(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(INDEX_NAME,6)=BINARY 'mrc57_')+"
            "(SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE BINARY CONSTRAINT_SCHEMA=BINARY DATABASE() AND BINARY LEFT(CONSTRAINT_NAME,6)=BINARY 'mrc57_') END AS present")


def metadata(prerequisite):
    expressions=[prerequisite, f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND {RESERVED})=4"]
    for table, schema in SCHEMA.items():
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)} AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND ROW_FORMAT='Dynamic' AND TABLE_COLLATION='utf8mb4_bin')=1")
        expressions.append(column_signature(table,schema['columns']))
        entries=[]
        for name, unique, columns in schema['indexes']:
            for index,col in enumerate(columns,1):
                entries.append(f"(BINARY INDEX_NAME={quote(name)} AND NON_UNIQUE={0 if unique else 1} AND SEQ_IN_INDEX={index} AND BINARY COLUMN_NAME={quote(col)} AND SUB_PART IS NULL AND INDEX_TYPE='BTREE' AND COLLATION='A')")
        expressions.append(f"(SELECT COUNT(*)={len(entries)} AND SUM(CASE WHEN "+' OR '.join(entries)+f" THEN 1 ELSE 0 END)={len(entries)} FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)})")
        fk=schema['fk']
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON BINARY r.CONSTRAINT_SCHEMA=BINARY k.CONSTRAINT_SCHEMA AND BINARY r.CONSTRAINT_NAME=BINARY k.CONSTRAINT_NAME AND BINARY r.TABLE_NAME=BINARY k.TABLE_NAME WHERE BINARY k.TABLE_SCHEMA=BINARY DATABASE() AND BINARY k.TABLE_NAME={quote(table)} AND BINARY k.CONSTRAINT_NAME={quote(fk)} AND k.COLUMN_NAME='record_id' AND k.ORDINAL_POSITION=1 AND k.POSITION_IN_UNIQUE_CONSTRAINT=1 AND BINARY k.REFERENCED_TABLE_SCHEMA=BINARY DATABASE() AND k.REFERENCED_TABLE_NAME='medical_record' AND k.REFERENCED_COLUMN_NAME='id' AND r.UNIQUE_CONSTRAINT_NAME='PRIMARY' AND r.DELETE_RULE='RESTRICT' AND r.UPDATE_RULE='RESTRICT')=1")
        expected_constraints = [(n, 'PRIMARY KEY' if n=='PRIMARY' else 'UNIQUE') for n,u,c in schema['indexes'] if u] + [(fk,'FOREIGN KEY')]
        expressions.append(f"(SELECT COUNT(*)={len(expected_constraints)} AND SUM(CASE WHEN "+' OR '.join(f"(BINARY CONSTRAINT_NAME={quote(n)} AND CONSTRAINT_TYPE={quote(t)})" for n,t in expected_constraints)+f" THEN 1 ELSE 0 END)={len(expected_constraints)} FROM information_schema.TABLE_CONSTRAINTS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)})")
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME={quote(table)} AND REFERENCED_TABLE_NAME IS NOT NULL)=1")
    # 新保留名称不允许被未知表/触发器/过程占用；旧事实触发器本体由 prerequisite 精确验证。
    touched=TABLES+['medical_record','medical_record_item','medical_record_attachment']
    expressions.append(f"(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND (BINARY EVENT_OBJECT_TABLE IN ({names(touched)}) OR BINARY LEFT(TRIGGER_NAME,6)=BINARY 'mrc57_'))={len(TRIGGERS)+2}")
    for t in TRIGGERS:
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND BINARY TRIGGER_NAME={quote(t['name'])} AND BINARY EVENT_OBJECT_TABLE={quote(t['table'])} AND EVENT_MANIPULATION={quote(t['event'])} AND ACTION_TIMING='BEFORE' AND ACTION_ORDER={t['order']} AND ACTION_ORIENTATION='ROW' AND ACTION_CONDITION IS NULL AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION LIKE 'utf8mb4\\_%' AND DATABASE_COLLATION LIKE 'utf8mb4\\_%' AND (FIND_IN_SET('STRICT_TRANS_TABLES',SQL_MODE)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',SQL_MODE)>0) AND BINARY ACTION_STATEMENT=BINARY {quote(t['body'])})=1")
    for table,col in [('ROUTINES','ROUTINE'),('EVENTS','EVENT')]:
        expressions.append(f"(SELECT COUNT(*) FROM information_schema.{table} WHERE BINARY {col}_SCHEMA=BINARY DATABASE() AND BINARY LEFT({col}_NAME,6)=BINARY 'mrc57_')=0")
    allindexes=[n for s in SCHEMA.values() for n,u,c in s['indexes'] if n!='PRIMARY']
    constraints=[s['fk'] for s in SCHEMA.values()]+[n for s in SCHEMA.values() for n,u,c in s['indexes'] if n!='PRIMARY' and u]
    expressions.append("(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(INDEX_NAME,6)=BINARY 'mrc57_' AND NOT (BINARY TABLE_NAME IN ("+names(TABLES)+") AND BINARY INDEX_NAME IN ("+names(allindexes)+")))=0")
    expressions.append("(SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE BINARY CONSTRAINT_SCHEMA=BINARY DATABASE() AND BINARY LEFT(CONSTRAINT_NAME,6)=BINARY 'mrc57_' AND NOT (BINARY TABLE_NAME IN ("+names(TABLES)+") AND BINARY CONSTRAINT_NAME IN ("+names(constraints)+")))=0")
    return 'SELECT CASE WHEN\n  '+' AND\n  '.join(expressions)+'\nTHEN 1 ELSE 0 END AS compatible'


def header(edition,purpose):
    return f'''-- 中文操作说明：病历有理由更正/作废历史的独立 SQL；适用 {edition} 版，MySQL 5.7.18（允许供应商后缀，不支持 MariaDB）。
-- 实际目标库：操作者用 --database=实际库名明确选库，核对 selected_schema；同一库只选 root 或 cn 一套。
-- 前置：已安装并核验导入原始事实基础结构，以及原生 medical_record、item、attachment；可选业务模块不是本迁移的前置。
-- 既有库步骤：停写并确认备份可恢复 → 单独执行只读 01 → EXACT_OLD 才经批准执行 02 → 单独只读 03。
-- FULLY_INSTALLED 必须跳过 02，直接核对 03；PARTIAL_OR_UNKNOWN 或任一错误立即停止，不可盲目重跑或补齐。
-- 新库另选完整空库 init 文件，不能把 init 当作升级，也不能在 02 后执行 init；旧已执行 SQL 不重跑。
-- 必须停写、核实备份/恢复方案、可用空间、表大小、同表触发器及 SELECT/CREATE/ALTER/TRIGGER/REFERENCES 权限。
-- DDL 隐式提交且可能等待元数据锁；不保证在线、无锁、原子回滚或固定执行时长，失败后保持停写并人工恢复或审定向前修复。
-- 每个文件独立遇错即停；禁止 --force 或忽略错误；不要删除历史/原始事实模拟回滚。不在脚本保存凭据。
-- 连接命令采用实际已批准参数和交互密码：mysql --batch --default-character-set=utf8mb4 --host=HOST --port=PORT --user=USER --password --database=DB < SCRIPT.sql
-- 本文件：{purpose}。这里只交付源文；原生 MySQL 执行与部署未验证，须另行授权。
'''


def build(edition):
    resources=ROOT/edition/'src/main/resources'; directory=resources/'sql'
    prerequisite=import_prerequisite(directory)+' AND\n  '+native_children(directory)+" AND\n  (FIND_IN_SET('STRICT_TRANS_TABLES',@@SESSION.sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@SESSION.sql_mode)>0) AND @@SESSION.foreign_key_checks=1 AND @@SESSION.unique_checks=1"
    old_triggers="(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND BINARY EVENT_OBJECT_TABLE IN ('medical_record','medical_record_item','medical_record_attachment'))=2"
    old='('+prerequisite+') AND ('+PRESENCE+')=0 AND '+old_triggers
    ready=metadata(prerequisite)
    state="SELECT DATABASE() AS selected_schema,VERSION() AS server_version,@@character_set_client AS client_charset,CASE WHEN ("+old+") THEN 'EXACT_OLD' WHEN ("+ready+")=1 THEN 'FULLY_INSTALLED' ELSE 'PARTIAL_OR_UNKNOWN' END AS medical_record_correction_state;\n"
    new_ddl='\n\n'.join(create_table(t) for t in TABLES)+'\n\n'+trigger_sql()
    label=edition or 'root'
    (directory/'medical_record_correction_01_precheck_20261016.sql').write_text(header(label,'只读预检，仅检查有限已命名元数据，不扫描临床数据')+state)
    (directory/'medical_record_correction_03_postcheck_20261016.sql').write_text(header(label,'只读后检，必须输出 FULLY_INSTALLED')+"SELECT DATABASE() AS selected_schema,VERSION() AS server_version,CASE WHEN ("+ready+")=1 THEN 'FULLY_INSTALLED' ELSE 'PARTIAL_OR_UNKNOWN' END AS medical_record_correction_state;\n")
    guard="SET @mrc57_exact_old = ("+old+");\nSET @mrc57_guard_sql = IF(@mrc57_exact_old,'SELECT 1 AS exact_old_precondition','SELECT * FROM information_schema.MRC57_STOP_NOT_EXACT_OLD');\nPREPARE mrc57_guard FROM @mrc57_guard_sql;\nEXECUTE mrc57_guard;\nDEALLOCATE PREPARE mrc57_guard;\n\n"
    (directory/'medical_record_correction_02_upgrade_20261016.sql').write_text(header(label,'仅从 EXACT_OLD 增量新增四表和守卫，无回填')+guard+new_ddl)
    # 完整继承已接受空库源码，但删除新文件中的盲 IF NOT EXISTS；旧36文件字节不改。
    old_init=(directory/'init_with_import_facts_20261016.sql').read_text()
    old_init=old_init[old_init.index('SET @if57_empty ='):]
    old_init=old_init.replace('CREATE TABLE IF NOT EXISTS','CREATE TABLE')
    empty="SET @mrc57_empty=(DATABASE() IS NOT NULL AND (VERSION()='5.7.18' OR VERSION() LIKE '5.7.18-%') AND LOWER(VERSION()) NOT LIKE '%mariadb%' AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE())=0 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE())=0 AND (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE())=0 AND (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE())=0 AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@SESSION.sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@SESSION.sql_mode)>0) AND @@SESSION.foreign_key_checks=1 AND @@SESSION.unique_checks=1);\nSET @mrc57_fresh_sql=IF(@mrc57_empty,'SELECT 1 AS empty_database_precondition','SELECT * FROM information_schema.MRC57_STOP_NOT_EMPTY');\nPREPARE mrc57_fresh FROM @mrc57_fresh_sql;\nEXECUTE mrc57_fresh;\nDEALLOCATE PREPARE mrc57_fresh;\n\n"
    (directory/'init_with_medical_record_corrections_20261016.sql').write_text(header(label,'仅确认空库后完整初始化；完成后另行执行本组03')+empty+old_init+'\n\n-- 原有完整初始化结束，追加本片四表与守卫；不重跑旧升级。\n'+new_ddl)
    manifest=dict(contractVersion=1,tables=TABLES,presenceSql=PRESENCE,metadataSql=ready,
                  transition=['authority/source/state locks; reserve or exact replay before current version/digest checks',
                              'NEW only: current version/digest; baseline sequence 0 at actual native version',
                              'initial state at baseline', 'pending immutable CORRECT/INVALIDATE event',
                              'native parent version+1 and cleared review', 'existing child UPDATEs for CORRECT',
                              'final state version/sequence/digest', 'exact source and current authority/expiry verification; minimal receipt',
                              'beforeCommit completeness; rollback everything on failure'],
                  guards=TRIGGERS,schema=SCHEMA,
                  inputs={name:hashlib.sha256((directory/name).read_bytes()).hexdigest() for name in ['import_facts_03_postcheck_20261016.sql','init_with_import_facts_20261016.sql']})
    (resources/'medical-record-correction-schema.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')


if __name__ == '__main__':
    for edition in ['', 'cn']: build(edition)
