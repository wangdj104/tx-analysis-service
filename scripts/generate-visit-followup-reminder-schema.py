#!/usr/bin/env python3
"""Generate only the reviewed exact-log-ID reminder extension (Python 3.8+).

Default --check is read-only. --write emits exactly ten new source files.
业务说明：先核对全部冻结输入；本工具不连接数据库、不执行SQL、不自动开功能。
"""
from pathlib import Path
import argparse
import copy
import hashlib
import types
import json
import re

W = Path(__file__).resolve().parent.parent
# Accepted 15A and historical SQL are immutable inputs, never refreshed automatically.
INPUT_SHA256 = {'cn/demo/sql/demo-data.sql': '77206a8f1ff7c3d6fe36a5f7c866fbc8e108ee7406d7b756e2cc9710aad0755d', 'cn/src/main/resources/household-workflow-schema.json': '1027b6dca4e158fe9e41cc73f308bc0c2d2d037958f5206926d974ae5abf488e', 'cn/src/main/resources/sql/alert_workflow_postcheck_20261013.sql': '070a6e1422dd7ec0d0e44f57d0c8103a3d87976a089bc738a2e31b742bc3ae4d', 'cn/src/main/resources/sql/alert_workflow_preflight_20261013.sql': 'cb956b03ca306e2b65fcba020d3912c3319a6d9ef3cd34de350c14eee21a263c', 'cn/src/main/resources/sql/alert_workflow_upgrade_20261013.sql': 'c43ffcd24378770461236a8859feb104a9107fbb8a13147ebeaf64e8ad3f0678', 'cn/src/main/resources/sql/common_care_goal_01_precheck_20261016.sql': '029cd08259deb5a0a0355bbf4f8accb6189632c89432761c512f3dd4ce5a320b', 'cn/src/main/resources/sql/common_care_goal_02_upgrade_20261016.sql': '465de2c32e0acb2d0899fa7425a35c8f968522059287b55d9873980a9a6d045f', 'cn/src/main/resources/sql/common_care_goal_03_postcheck_20261016.sql': '0b0e25788d2d89bf10f02640e620e82f384d6695bf28a55743ec0b7295888c77', 'cn/src/main/resources/sql/health_upgrade_01_precheck_20261015.sql': '59502ffec3af7947a0bede3d256a04260aa1a8e45f643abbbc02ca66b2f5c2e3', 'cn/src/main/resources/sql/household_workflow_postcheck_20261014.sql': 'c3a380ab71d12072220d6fc38147700d31915cf49e299e12ac26aec554c160d2', 'cn/src/main/resources/sql/household_workflow_preflight_20261014.sql': 'c3a380ab71d12072220d6fc38147700d31915cf49e299e12ac26aec554c160d2', 'cn/src/main/resources/sql/household_workflow_upgrade_20261014.sql': '24c7af5c0f219b4d1e20e42b94cca2b8026864c40d040ad420fadaa078932b81', 'cn/src/main/resources/sql/import_facts_01_precheck_20261016.sql': 'db3e4abe6fac2cfc75c83bb7bcc26e4f7738fab508e264464e1dbd38cbc8e1cb', 'cn/src/main/resources/sql/import_facts_02_upgrade_20261016.sql': '4cbed43a2d26ba7da17abc27afd6c8e2b0b0a829a9ef53640c591c6217cea363', 'cn/src/main/resources/sql/import_facts_03_postcheck_20261016.sql': '82fc2e627c98ce3ea45f15d68cab11205f475b857be566c835116deeebc2ec48', 'cn/src/main/resources/sql/init.sql': '0b9f82586970d8379a369e8b19f0b2217ea35534806f9a6ca89e1e48885ff651', 'cn/src/main/resources/sql/init_with_common_care_goals_20261016.sql': '42442f44d30ffe9b78bdb8489f060b097939eea055ee4f55bd0d73c8c7e4d706', 'cn/src/main/resources/sql/init_with_import_facts_20261016.sql': '1a6b47ba2402ebcfee20d38ed57d3ac52c1635f89fefdb355944a527e10754f6', 'cn/src/main/resources/sql/init_with_medical_record_corrections_20261016.sql': '54bc0a115bfd5e5eded42394d246fd838d1cd01210984a80fee71faf5e5bca3c', 'cn/src/main/resources/sql/init_with_visit_followup_20261017.sql': '14442c62896fad9b72b33f95af28d257ec890a3510036cdfa11feb8cc21a9b96', 'cn/src/main/resources/sql/medical_record_correction_01_precheck_20261016.sql': 'cfebe469394be1d659ed2e65b094c0a3ada5c42b2a61ff51dd04eceffb639882', 'cn/src/main/resources/sql/medical_record_correction_02_upgrade_20261016.sql': 'cfb715bd2eff90fd2f3845f26b4a76ac8ddcaa01660677a45b7a94ae06740ff8', 'cn/src/main/resources/sql/medical_record_correction_03_postcheck_20261016.sql': '38a347eb2db7309f412250072061be90e3fe3db9e3eb5a78d48ea866ee51d11b', 'cn/src/main/resources/sql/mysql57_postcheck_20261012.sql': '7e501df7537d7f48e127bbb14deed7cde7f5cef15f837a4f2b8925ef3cbbf8b1', 'cn/src/main/resources/sql/mysql57_preflight_20261012.sql': 'a30752d9b049c1bebce794eceab93347039846dc8b9431a035ca0dcec7ea98ae', 'cn/src/main/resources/sql/mysql57_schema_upgrade_20261012.sql': '49b38aff63615c76b1214c31cb7e9b0131aa2505dbfa2c2723362f4b5b68e3aa', 'cn/src/main/resources/sql/visit_followup_01_precheck_20261017.sql': '94623e08b9e96bf61570ed5b2a757189b6b3d1f8654e684ebe6375997326d5c8', 'cn/src/main/resources/sql/visit_followup_02_upgrade_20261017.sql': 'd9638eba6e0f77988bdff392781002176fcd065dfe9aeafc5905251c6b3c90bf', 'cn/src/main/resources/sql/visit_followup_03_postcheck_20261017.sql': 'c7b193f7f8f7b80d892d304e80e6237cf51506b5d33804f464f344ff7f5d6c1a', 'cn/src/main/resources/sql/visit_question_01_precheck_20261015.sql': 'd69874af1928a5c1ed27af750b6ded91201590cf0a7593ad892d8b1420aaa348', 'cn/src/main/resources/sql/visit_question_02_upgrade_20261015.sql': 'f1a5e321d4cec8969deeeb379914eedf8fdd25c1a9c290c6436deaa7460838a6', 'cn/src/main/resources/sql/visit_question_03_postcheck_20261015.sql': 'dc547833054a898c7dc5a6d18d47cb3db3b491bfdb848f312dd01f8e187386d9', 'cn/src/main/resources/visit-followup-schema.json': '4c4101719816fc989a6640919d24de4e41350405ab715f135ba4861664d8966b', 'cn/src/test/resources/alertworkflow/legacy-alert-schema.sql': 'c30058fa60ebabbde8a130ea8fe5ac1bda0a6d6450d0af1154576a8792333eb6', 'demo/sql/demo-data.sql': 'aa781b1d532c66f1854d2e6c670fa64705686541eae06fe5a25e1c5d05baf7e5', 'scripts/generate-visit-followup-schema.py': '2a23c0da4218cb69f397f0b419448fa41939b745d080d0c517699cd402f06dba', 'src/main/resources/household-workflow-schema.json': '1027b6dca4e158fe9e41cc73f308bc0c2d2d037958f5206926d974ae5abf488e', 'src/main/resources/sql/alert_workflow_postcheck_20261013.sql': '070a6e1422dd7ec0d0e44f57d0c8103a3d87976a089bc738a2e31b742bc3ae4d', 'src/main/resources/sql/alert_workflow_preflight_20261013.sql': 'cb956b03ca306e2b65fcba020d3912c3319a6d9ef3cd34de350c14eee21a263c', 'src/main/resources/sql/alert_workflow_upgrade_20261013.sql': 'c43ffcd24378770461236a8859feb104a9107fbb8a13147ebeaf64e8ad3f0678', 'src/main/resources/sql/common_care_goal_01_precheck_20261016.sql': 'aa277982b86eaaa4766809c8dc26c95371cba5d8cc44b6852ea63d0445609a1d', 'src/main/resources/sql/common_care_goal_02_upgrade_20261016.sql': '1287022f763aac83a32f949c6b1633b5e481803a693e7ce233ef6ca5089258e9', 'src/main/resources/sql/common_care_goal_03_postcheck_20261016.sql': 'e91eb488e263eeac7830232ddc5bd6b93dfdf6dac122dec26c2115f0a79fbfc7', 'src/main/resources/sql/health_upgrade_01_precheck_20261015.sql': '59502ffec3af7947a0bede3d256a04260aa1a8e45f643abbbc02ca66b2f5c2e3', 'src/main/resources/sql/household_workflow_postcheck_20261014.sql': 'c3a380ab71d12072220d6fc38147700d31915cf49e299e12ac26aec554c160d2', 'src/main/resources/sql/household_workflow_preflight_20261014.sql': 'c3a380ab71d12072220d6fc38147700d31915cf49e299e12ac26aec554c160d2', 'src/main/resources/sql/household_workflow_upgrade_20261014.sql': '24c7af5c0f219b4d1e20e42b94cca2b8026864c40d040ad420fadaa078932b81', 'src/main/resources/sql/import_facts_01_precheck_20261016.sql': 'db3e4abe6fac2cfc75c83bb7bcc26e4f7738fab508e264464e1dbd38cbc8e1cb', 'src/main/resources/sql/import_facts_02_upgrade_20261016.sql': '4cbed43a2d26ba7da17abc27afd6c8e2b0b0a829a9ef53640c591c6217cea363', 'src/main/resources/sql/import_facts_03_postcheck_20261016.sql': '82fc2e627c98ce3ea45f15d68cab11205f475b857be566c835116deeebc2ec48', 'src/main/resources/sql/init.sql': 'e3e69907a0d3ad6b6cd48aac1bcbf0120779db8aa37797ed82931338c58b12e4', 'src/main/resources/sql/init_with_common_care_goals_20261016.sql': '278e607867a8c0f295300682229f2fb90f541a6a2e089bca4273c4028584913c', 'src/main/resources/sql/init_with_import_facts_20261016.sql': 'e1d1f31291e7fdca610402bb467909ab053e5f84eefc20af1b2f7bc91858dcea', 'src/main/resources/sql/init_with_medical_record_corrections_20261016.sql': 'd19e8fcc94dff1438365f457c24b67618a4e0e0b5e3c3f9e96230c3b42da34c1', 'src/main/resources/sql/init_with_visit_followup_20261017.sql': '8963b6596c849fb3530b93e35b5423a8df5666f798f13aa0391228be77ed6827', 'src/main/resources/sql/medical_record_correction_01_precheck_20261016.sql': '4460117785f5c160d3ad84652f235a9959bc41f2764f77c4b5eb242e94af819e', 'src/main/resources/sql/medical_record_correction_02_upgrade_20261016.sql': '003f4dc55564ecf502ba3c8abbe19aa0aa68ac328f4d222ce0362f39d2ab9177', 'src/main/resources/sql/medical_record_correction_03_postcheck_20261016.sql': '1136e8a07967ea9dec1886caf425cd5197764a286bb1b37759d82a8788443a30', 'src/main/resources/sql/mysql57_postcheck_20261012.sql': '7e501df7537d7f48e127bbb14deed7cde7f5cef15f837a4f2b8925ef3cbbf8b1', 'src/main/resources/sql/mysql57_preflight_20261012.sql': 'a30752d9b049c1bebce794eceab93347039846dc8b9431a035ca0dcec7ea98ae', 'src/main/resources/sql/mysql57_schema_upgrade_20261012.sql': '442236748cc8b28bb58a596a01c2009642ea87e711d4bbf048cda011521a0a97', 'src/main/resources/sql/visit_followup_01_precheck_20261017.sql': 'f20a75c6694b521721f84348aa74eabfacd0225f1abddb913d68d77425851fec', 'src/main/resources/sql/visit_followup_02_upgrade_20261017.sql': '0293aec55687d5aa5bc50f4b0ea15c71179989a50ba5c24ded57134c3551bc86', 'src/main/resources/sql/visit_followup_03_postcheck_20261017.sql': 'ef072a00022d5385a3cd4668ff6429ef3163874b44d4fe50e2c45eb306ad9100', 'src/main/resources/sql/visit_question_01_precheck_20261015.sql': 'd69874af1928a5c1ed27af750b6ded91201590cf0a7593ad892d8b1420aaa348', 'src/main/resources/sql/visit_question_02_upgrade_20261015.sql': 'f1a5e321d4cec8969deeeb379914eedf8fdd25c1a9c290c6436deaa7460838a6', 'src/main/resources/sql/visit_question_03_postcheck_20261015.sql': 'dc547833054a898c7dc5a6d18d47cb3db3b491bfdb848f312dd01f8e187386d9', 'src/main/resources/visit-followup-schema.json': '22d7207926157d0d61ac53095241f9b9db7d46601df6f47897ff1503adc0c794', 'src/test/resources/alertworkflow/legacy-alert-schema.sql': 'c30058fa60ebabbde8a130ea8fe5ac1bda0a6d6450d0af1154576a8792333eb6'}
SQL_FILES = ('visit_followup_reminder_01_precheck_20261017.sql',
             'visit_followup_reminder_02_upgrade_20261017.sql',
             'visit_followup_reminder_03_postcheck_20261017.sql',
             'init_with_visit_followup_reminder_20261017.sql')
INPUT_BYTES = {}
GENERATED = {}
POINTER = 'reminder_delivery_log_id'
EVENT = 'VISIT_FOLLOWUP_REMINDER'


def regular_path(path):
    relative = path.relative_to(W)
    current = W
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('Symlink is not a regular source path: ' + relative.as_posix())
    if path.exists() and not path.is_file():
        raise ValueError('Not a regular source file: ' + relative.as_posix())


def verify_inputs():
    for relative, signature in INPUT_SHA256.items():
        path = W / relative
        regular_path(path)
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != signature:
            raise ValueError('Immutable baseline input mismatch: ' + relative)
        INPUT_BYTES[relative] = data


def load_helpers():
    # Only import verified source definitions; never call the frozen generator main/write.
    relative = 'scripts/generate-visit-followup-schema.py'
    module = types.ModuleType('accepted_visit_schema')
    module.__file__ = str(W / relative)
    exec(compile(INPUT_BYTES[relative], module.__file__, 'exec'), module.__dict__)
    return module


def read_input(edition, relative):
    return INPUT_BYTES[(edition + '/' if edition else '') + 'src/main/resources/' + relative].decode('utf-8')


def emit(path, value):
    if path in GENERATED:
        raise ValueError('Duplicate generated path: ' + path.relative_to(W).as_posix())
    GENERATED[path] = value.encode('utf-8')


def exact_log_proof(m, state, pointer):
    # PK定位在前，JSON只能检查这一行；畸形JSON由安全IF保护，不能放宽类型比较。
    safe = "IF(JSON_VALID(n.detail),n.detail,'{}')"
    extract = lambda key: "JSON_EXTRACT(" + safe + ",'$." + key + "')"
    scalar = lambda key: 'JSON_UNQUOTE(' + extract(key) + ')'
    fields = {'attemptId': state + '.reminder_attempt_id', 'stateId': 'CAST(' + state + '.id AS CHAR)',
              'sourceKind': state + '.source_kind',
              'sourceId': 'CAST(COALESCE(' + state + '.care_item_id,' + state + '.care_appointment_id) AS CHAR)',
              'sourceRevision': 'CAST(' + state + '.reminder_attempt_source_revision AS CHAR)'}
    terms = ['n.id=' + pointer, 'n.patient_id=' + state + '.patient_id',
             "BINARY n.event_type='" + EVENT + "'", "BINARY n.delivery_status='DELIVERED'",
             'n.recipient_user_id BETWEEN 1 AND ' + str(m.MAX), 'JSON_VALID(n.detail)=1',
             "JSON_TYPE(" + safe + ")='OBJECT'", 'JSON_LENGTH(' + safe + ')=7']
    for key, value in fields.items():
        terms += ["JSON_TYPE(" + extract(key) + ")='" + ('STRING' if key in ('attemptId', 'sourceKind') else 'INTEGER') + "'",
                  m.eq(scalar(key), value)]
    terms += ["JSON_TYPE(" + extract('channelId') + ")='INTEGER'",
              'CAST(' + scalar('channelId') + ' AS UNSIGNED) BETWEEN 1 AND ' + str(m.MAX),
              "JSON_TYPE(" + extract('reasonCode') + ")='NULL'"]
    return 'EXISTS(SELECT 1 FROM notification_delivery_log n WHERE ' + ' AND '.join(terms) + ')'


def current_native(m):
    out = []
    for kind in ('CARE_ITEM_APPOINTMENT', 'CARE_APPOINTMENT'):
        field, table = m.KINDS[kind]
        active = 'OPEN' if kind == 'CARE_ITEM_APPOINTMENT' else 'BOOKED'
        out.append("(BINARY NEW.source_kind=" + m.q(kind) + " AND BINARY NEW.attendance='UNVERIFIED' AND BINARY NEW.native_status=" + m.q(active)
                   + ' AND EXISTS(SELECT 1 FROM ' + table + ' n WHERE n.id=NEW.' + field
                   + ' AND n.patient_id=NEW.patient_id AND n.notified_at IS NULL'
                   + (" AND BINARY n.kind='APPOINTMENT'" if kind == 'CARE_ITEM_APPOINTMENT' else '')
                   + ' AND ' + m.native_match('n', kind).replace('s.', 'NEW.') + '))')
    return '(' + ' OR '.join(out) + ')'


def update_guard(guard, body, invariant):
    guard.update(body=body, sha256=hashlib.sha256(body.encode('utf-8')).hexdigest(), invariant=invariant)


def replace_log_predicates(body, replacement):
    needle = 'EXISTS(SELECT 1 FROM notification_delivery_log n WHERE '
    cursor = 0
    found = 0
    while True:
        start = body.find(needle, cursor)
        if start < 0:
            break
        depth, quoted, i = 0, False, start + len('EXISTS')
        while i < len(body):
            c = body[i]
            if c == "'":
                if quoted and i + 1 < len(body) and body[i + 1] == "'":
                    i += 2
                    continue
                quoted = not quoted
            elif not quoted:
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        break
            i += 1
        if depth or quoted:
            raise ValueError('Unbalanced frozen native log predicate')
        body = body[:start] + replacement + body[i + 1:]
        cursor = start + len(replacement)
        found += 1
    if found == 0:
        raise ValueError('Frozen native log predicate missing')
    return body


def extension_manifest(m, accepted):
    b = copy.deepcopy(accepted)
    state = b['tables'][m.S]
    state['columns'].append(m.big(POINTER, True))
    state['keys'].append(m.ix('state_reminder_log', [POINTER], True))
    state['fks'].append(m.fk('state_reminder_log', [POINTER], 'notification_delivery_log'))
    signature = b['signatures'][m.S]
    column = copy.deepcopy(next(c for c in signature['columns'] if c['column_name'] == 'reminder_attempt_source_revision'))
    column.update(column_name=POINTER, ordinal_position=41)
    signature['columns'].append(column)
    signature['keys'] = copy.deepcopy(state['keys'])
    signature['fks'] = copy.deepcopy(state['fks'])
    proof = exact_log_proof(m, 'NEW', 'NEW.' + POINTER)
    pointer_shape = '(NEW.' + POINTER + ' IS NULL OR (NEW.' + POINTER + ' BETWEEN 1 AND ' + str(m.MAX)         + " AND BINARY NEW.source_kind IN ('CARE_ITEM_APPOINTMENT','CARE_APPOINTMENT') AND NEW.reminder_attempt_source_revision IS NOT NULL AND NEW.reminder_attempt_id IS NOT NULL AND NEW.reminder_attempted_at_utc IS NOT NULL AND " + proof + '))'
    for guard in b['guards']:
        if guard['name'] == 'vf57_state_insert':
            body = guard['body'][:-4] + '\n' + m.reject('NEW.' + POINTER + ' IS NOT NULL', 'VF57_REMINDER_SKELETON') + '\nEND'
            update_guard(guard, body, '保留原骨架约束；新事务骨架必须没有投递证明')
        elif guard['name'] == 'vf57_state_update':
            fields = [c['name'] for c in accepted['tables'][m.S]['columns']]
            common = "OLD.lock_version>0 AND NEW.reminder_attempt_source_revision=NEW.source_revision AND BINARY NEW.attendance='UNVERIFIED' AND " + current_native(m)
            claim = '(' + common + ' AND ' + m.unchanged([f for f in fields if f not in m.MARKERS])                 + ' AND (OLD.reminder_attempt_source_revision IS NULL OR OLD.reminder_attempt_source_revision<OLD.source_revision)'                 + ' AND NEW.reminder_attempt_id IS NOT NULL AND NEW.reminder_attempted_at_utc IS NOT NULL AND NEW.' + POINTER + ' IS NULL)'
            bind = '(' + common + ' AND ' + m.unchanged(fields) + ' AND OLD.' + POINTER + ' IS NULL AND NEW.' + POINTER + ' IS NOT NULL AND ' + proof + ')'
            body = guard['body']
            start = body.index('  IF NEW.lock_version=OLD.lock_version THEN')
            end = body.index('  ELSE', start)
            body = body[:start] + m.reject('NOT (' + pointer_shape + ')', 'VF57_REMINDER_LOG_SHAPE') + '\n'                 + '  IF NEW.lock_version=OLD.lock_version THEN\n' + m.reject('NOT (' + claim + ' OR ' + bind + ')', 'VF57_REMINDER_ONCE') + '\n' + body[end:]
            body = body.replace('  ELSE\n', '  ELSE\n' + m.reject('NOT (' + m.unchanged([POINTER]) + ')', 'VF57_REMINDER_HUMAN_IMMUTABLE') + '\n', 1)
            update_guard(guard, body, '人类事件保持旧证明；系统仅当期首次CLAIM或精确已提交DELIVERED首次BIND，无人类版本/时间改写')
    log_body = 'BEGIN\n' + m.reject("BINARY OLD.event_type='" + EVENT + "' OR BINARY NEW.event_type='" + EVENT + "'", 'VF57_REMINDER_LOG_IMMUTABLE') + '\nEND'
    b['guards'].append(dict(name='vf57_reminder_log_update', table='notification_delivery_log', event='UPDATE', timing='BEFORE',
                           body=log_body, sha256=hashlib.sha256(log_body.encode()).hexdigest(), sqlMode=m.SQLMODE,
                           invariant='已进入或离开就诊提醒类别均禁止更新；只读OLD/NEW，不反向读取state'))
    for guard in b['householdGuardOverrides']:
        update_guard(guard, replace_log_predicates(guard['body'], exact_log_proof(m, 's', 's.' + POINTER)),
                     '原B冻结与原生事件分支保持；新notified_at只接受当前attempt精确真实DELIVERED主键')
    b['assertions'] = with_log_trigger_scope(m.metadata_assertions(b['signatures'], b['guards']))
    b['snapshotQueries']['triggers'] = b['snapshotQueries']['triggers'].replace("event_object_table='visit_record'", "event_object_table IN ('visit_record','notification_delivery_log')")
    b['metadataRules'] += [dict(kind='COLUMN', identity=m.S + '.' + POINTER, table=m.S, reason='COLUMN:' + m.S + '.' + POINTER),
                           dict(kind='INDEX', identity=m.S + '.vf57_state_reminder_log', table=m.S, reason='INDEX:' + m.S + '.vf57_state_reminder_log'),
                           dict(kind='FK', identity=m.S + '.vf57_state_reminder_log_fk', table=m.S, reason='FK:' + m.S + '.vf57_state_reminder_log_fk'),
                           dict(kind='GUARD', identity='vf57_reminder_log_update', reason='GUARD:vf57_reminder_log_update')]
    b['contractVersion'] = 2
    b['profileContract'] = 'OLD/FULL_A/FULL_B are complete alternatives; FULL_A remains readable but all new p15 writes require FULL_B; no mixtures, historical backfill, recursive readiness or automatic migration'
    b['inputs'] = copy.deepcopy(INPUT_SHA256)
    return b


def with_log_trigger_scope(assertions):
    out = copy.deepcopy(assertions)
    for a in out:
        if a['reason'] == 'UNKNOWN:guards':
            a['sql'] = a['sql'].replace("BINARY EVENT_OBJECT_TABLE='visit_record'", "BINARY EVENT_OBJECT_TABLE IN ('visit_record','notification_delivery_log')")
    return out


def header(edition, purpose):
    return '-- 中文操作说明：就诊提醒精确真实日志主键扩展；适用{edition}版，MySQL 5.7.18（允许真实供应商后缀，拒绝MariaDB）。\n-- 01 操作者明确选择并核对selected_schema；root/CN只选对应一套，不得混跑。\n-- 02 维护停写并排空旧writer；先验证可恢复完整备份，保存vf57_state_insert、vf57_state_update、hh_nci_bu、hh_nca_bu完整定义、SQL_MODE及字符集。\n-- 03 既有库顺序：独立只读01 → 仅FULL_A经批准执行02 → 独立只读03；FULL_B跳过02。OLD先独立走已接受15A升级路径。\n-- PARTIAL_OR_UNKNOWN、元数据不可读或任何错误立即停止；不盲重跑补齐、不使用--force或忽略错误。\n-- 04 空库仅用完整独立init_with_visit_followup_reminder_20261017.sql；不用于升级，不重复执行旧脚本。\n-- 05 DDL隐式提交、非原子，可等待metadata锁，ALTER可能扫描或重建state及索引；整个替换窗口保持停写，不承诺在线/耗时/原子回滚。\n-- 06 恢复须依完整备份与已保存四个trigger定义或独审前进修复；已有新业务数据不能删表“回滚”，家庭归档不能替代完整备份。\n-- 07 不猜填旧log ID、不改旧notified_at/attempt/事件/时间，不启flag；VISIT_FOLLOWUP_ENABLED仍默认false。\n-- 08 操作者核实权限/空间/恢复方案，使用支持DELIMITER的mysql客户端，禁止RunSqlMigration分号splitter；不把凭据写入脚本。\n-- 原生MySQL、真实provider和生产部署均NOT_RUN；本文件仅供审查及另行授权执行。\n-- 本文件：{purpose}。\n'.format(edition=edition or 'root', purpose=purpose)


def build(m, edition):
    resources = W / edition / 'src/main/resources'
    sql = resources / 'sql'
    a = json.loads(read_input(edition, 'visit-followup-schema.json'))
    hh = json.loads(read_input(edition, 'household-workflow-schema.json'))
    b = extension_manifest(m, a)
    full_a_assertions = m.hh_assertions(hh, a['householdGuardOverrides']) + with_log_trigger_scope(a['assertions'])
    full_b_assertions = m.hh_assertions(hh, b['householdGuardOverrides']) + b['assertions']
    old_assertions = m.hh_assertions(hh, []) + [v for v in a['assertions'] if any(v['reason'].endswith(':' + t) for t in a['prerequisiteTables'])]
    zero_log = "(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND BINARY EVENT_OBJECT_TABLE='notification_delivery_log')=0"
    old = '(' + m.and_all(old_assertions) + ') AND (' + m.PRESENCE + ')=0 AND (' + m.VERSION + ') AND (' + m.SESSION + ') AND (' + zero_log + ')'
    full_a = m.and_all(full_a_assertions)
    full_b = m.and_all(full_b_assertions)
    b['compatibleProfiles'] = dict(old='EXACT_OLD', fullA='FULLY_INSTALLED', fullB='FULLY_INSTALLED_REMINDER')
    b['fullAAssertions'] = full_a_assertions
    b['oldNoLogTriggerSql'] = 'SELECT CASE WHEN (' + zero_log + ') THEN 1 ELSE 0 END AS compatible'
    emit(sql / SQL_FILES[0], header(edition, '只读结构预检，不扫描患者正文或投递历史')
         + 'SELECT DATABASE() AS selected_schema,VERSION() AS server_version,CASE WHEN (' + old + ") THEN 'OLD' WHEN (" + full_a + ") THEN 'FULL_A' WHEN (" + full_b + ") THEN 'FULL_B' ELSE 'PARTIAL_OR_UNKNOWN' END AS visit_followup_reminder_state;\n")
    column = 'ALTER TABLE `visit_followup_state`\n  ADD COLUMN `reminder_delivery_log_id` BIGINT NULL DEFAULT NULL,\n  ADD UNIQUE KEY `vf57_state_reminder_log` (`reminder_delivery_log_id`),\n  ADD ' + m.fk_sql(b['tables'][m.S]['fks'][-1]) + ';\n'
    replaced = [g for g in b['guards'] if g['name'] in ('vf57_state_insert', 'vf57_state_update')] + b['householdGuardOverrides']
    ddl = column + '\n-- 只替换四个已备份守卫，其余已接受守卫保持原定义。\n' + m.trigger_sql(replaced, True)         + '\n-- 新日志守卫只检查OLD/NEW，不读取state。\n' + m.trigger_sql([b['guards'][-1]])
    gate = "SET @vf57_reminder_full_a=(" + full_a + ");\nSET @vf57_reminder_gate_sql=IF(@vf57_reminder_full_a,'SELECT 1 AS full_a_precondition','SELECT * FROM information_schema.VF57_STOP_FULL_A_REQUIRED');\nPREPARE vf57_reminder_gate FROM @vf57_reminder_gate_sql;\nEXECUTE vf57_reminder_gate;\nDEALLOCATE PREPARE vf57_reminder_gate;\n"
    emit(sql / SQL_FILES[1], header(edition, '只从完整FULL_A升级；任何DDL前硬核完整旧合同') + gate + '\n' + ddl)
    differences = '\nUNION ALL\n'.join('SELECT ' + m.q(v['reason']) + ' AS object_difference WHERE NOT COALESCE((' + m.predicate(v) + '),FALSE)' for v in full_b_assertions)
    emit(sql / SQL_FILES[2], header(edition, '独立只读后检，只认FULL_B并列出精确结构差异')
         + 'SELECT DATABASE() AS selected_schema,VERSION() AS server_version,CASE WHEN (' + full_b + ") THEN 'FULL_B' ELSE 'PARTIAL_OR_UNKNOWN' END AS visit_followup_reminder_state;\n" + differences + ';\n')
    fresh = read_input(edition, 'sql/init_with_visit_followup_20261017.sql')
    fresh = fresh[fresh.index('SET @ccg57_empty='):]
    emit(sql / SQL_FILES[3], header(edition, '完整独立空库初始化；首步空库门，内嵌冻结15A后追加本扩展') + fresh + '\n\n-- 精确日志证明扩展：空库15A定义已完成，追加唯一新版本。\n' + ddl)
    emit(resources / 'visit-followup-reminder-schema.json', json.dumps(b, ensure_ascii=False, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true', help='verify bytes without writing (default)')
    mode.add_argument('--write', action='store_true', help='write only the ten new regular source paths')
    args = parser.parse_args()
    verify_inputs()
    helpers = load_helpers()
    for edition in ('', 'cn'):
        build(helpers, edition)
    allowed = {W / edition / 'src/main/resources' / 'visit-followup-reminder-schema.json' for edition in ('', 'cn')}
    allowed.update(W / edition / 'src/main/resources/sql' / name for edition in ('', 'cn') for name in SQL_FILES)
    if set(GENERATED) != allowed:
        raise ValueError('Generated output set is outside the ten reviewed source files')
    for path in allowed:
        regular_path(path)
    if args.write:
        for path, value in GENERATED.items():
            path.write_bytes(value)
        print('Wrote 10 reminder SQL/profile files; all 69 immutable input signatures verified.')
    else:
        differences = [path.relative_to(W).as_posix() for path, value in GENERATED.items() if not path.is_file() or path.read_bytes() != value]
        if differences:
            raise ValueError('Generated source differs: ' + ', '.join(differences))
        print('Verified 10 byte-identical reminder SQL/profile files and 69 immutable inputs.')


if __name__ == '__main__':
    main()
