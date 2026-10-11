import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Source/catalog checks are deliberately separate from guarded native MySQL execution.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const editions = ['.', 'cn']
const expectedCategories = {
  'init.sql': '新库基础',
  'doctor_workspace_20260921.sql': '旧库升级',
  'care_platform_upgrade_20260921.sql': '旧库升级',
  'care_plan_collaboration_20261003.sql': '旧库升级',
  'clinical_integrity_upgrade_20261011.sql': '旧库升级',
  'functional_review_repair_20260922.sql': '旧库升级',
  'notification_language_preference_20260922.sql': '旧库升级',
  'patient_specialty_roles_20260923.sql': '专病角色',
  'role_permission_cleanup_20260922.sql': '权限整理',
  'demo-data.sql': '演示数据',
  'care-plan-e2e-fixture.sql': '测试夹具',
  'care-plan-mysql-legacy.sql': '测试夹具',
  'care-plan-mysql-relationships.sql': '测试夹具'
}
const compatibilityDefinitions = [
  "bp_self_monitor_record.source_type VARCHAR(20) DEFAULT 'MANUAL' AFTER measure_period",
  'bp_self_monitor_record.source_external_id VARCHAR(120) DEFAULT NULL AFTER source_type',
  "bp_self_monitor_record.verification_status VARCHAR(30) DEFAULT 'VERIFIED' AFTER source_external_id",
  "medical_record.source_type VARCHAR(20) DEFAULT 'MANUAL' AFTER ai_raw_result",
  'medical_record.source_external_id VARCHAR(120) DEFAULT NULL AFTER source_type',
  "medical_record.verification_status VARCHAR(30) DEFAULT 'VERIFIED' AFTER source_external_id",
  'medical_record.confidence_score DECIMAL(5,4) DEFAULT NULL AFTER verification_status',
  'medical_record.verified_by BIGINT DEFAULT NULL AFTER confidence_score',
  'medical_record.verified_at DATETIME DEFAULT NULL AFTER verified_by',
  'dialysis_record.session_minutes INT DEFAULT NULL AFTER uf_amount',
  'dialysis_record.ktv DECIMAL(5,2) DEFAULT NULL AFTER session_minutes',
  'dialysis_record.urr DECIMAL(5,2) DEFAULT NULL AFTER ktv',
  'dialysis_record.access_issue VARCHAR(255) DEFAULT NULL AFTER urr',
  'patient_clinical.dialysis_weekdays VARCHAR(32) DEFAULT NULL AFTER fluid_limit_ml',
  'patient_clinical.dialysis_time VARCHAR(5) DEFAULT NULL AFTER dialysis_weekdays',
  'alert_record.source_type VARCHAR(30) DEFAULT NULL AFTER triggered_at',
  'alert_record.source_id BIGINT DEFAULT NULL AFTER source_type',
  'alert_record.dedupe_key VARCHAR(160) DEFAULT NULL AFTER source_id',
  'alert_record.occurrence_count INT NOT NULL DEFAULT 1 AFTER dedupe_key',
  'alert_record.last_triggered_at DATETIME DEFAULT NULL AFTER occurrence_count',
  'alert_record.acknowledged_by BIGINT DEFAULT NULL AFTER status',
  'alert_record.acknowledged_at DATETIME DEFAULT NULL AFTER acknowledged_by',
  'alert_record.resolved_by BIGINT DEFAULT NULL AFTER acknowledged_at',
  'alert_record.resolved_at DATETIME DEFAULT NULL AFTER resolved_by'
]
const compatibilityNames = new Set(compatibilityDefinitions.map(value => value.split(' ')[0]))
function read(relative) { return readFileSync(path.join(root, relative), 'utf8') }
function sqlPath(edition, file) { return path.join(edition, 'src/main/resources/sql', file) }
function canonicalStatement(value) {
  // Ignore formatting outside SQL string literals; defaults inside strings remain exact.
  return (value.match(/'(?:''|[^'])*'|[^']+/g) ?? []).map(token => token.startsWith("'")
    ? token : token.replace(/\s+/g, '').toLowerCase()).join('')
}
function conditionalAlters(sql) {
  return (sql.match(/^SET\s+@ddl\s*=[^\n]*;/gm) ?? [])
    .filter(statement => /ALTER TABLE/i.test(statement))
    .map(canonicalStatement)
}
function restoredDefinitions(sql) {
  const result = []
  for (const statement of sql.match(/^SET\s+@ddl\s*=[^\n]*;/gm) ?? []) {
    for (const token of statement.match(/'(?:''|[^'])*'/g) ?? []) {
      const decoded = token.slice(1, -1).replace(/''/g, "'")
      const alter = /^ALTER TABLE (\w+) ADD COLUMN (\w+) (.+)$/i.exec(decoded)
      if (alter && compatibilityNames.has(`${alter[1]}.${alter[2]}`)) result.push(`${alter[1]}.${alter[2]} ${alter[3]}`)
    }
  }
  return result
}
function entries(catalog) {
  return [...catalog.matchAll(/^\| \[([^\]]+\.sql)\]\(([^)]+)\) \| ([^|]+) \|/gm)]
    .map(([, name, target, category]) => ({ name, target, category: category.trim() }))
}

for (const edition of editions) {
  test(`${edition} catalog covers every production, demo and synthetic test SQL exactly once`, () => {
    const directory = path.join(root, edition, 'src/main/resources/sql')
    const catalogPath = path.join(directory, 'README.md')
    assert.ok(existsSync(catalogPath), 'SQL execution catalog is missing')
    const actual = entries(readFileSync(catalogPath, 'utf8'))
    const production = readdirSync(directory).filter(name => name.endsWith('.sql'))
    const fixtures = readdirSync(path.join(root, edition, 'src/test/resources/sql')).filter(name => name.endsWith('.sql'))
    assert.deepEqual(actual.map(item => item.name).sort(), [...production, ...fixtures].sort())
    assert.equal(new Set(actual.map(item => item.name)).size, actual.length)
    for (const item of actual) {
      assert.equal(item.category, expectedCategories[item.name], item.name)
      const expectedPath = production.includes(item.name) ? path.join(directory, item.name)
        : path.join(root, edition, 'src/test/resources/sql', item.name)
      assert.equal(path.resolve(directory, item.target), expectedPath)
      assert.ok(existsSync(expectedPath), item.target)
    }
  })
  test(`${edition} catalog explains actual startup, dependencies and destructive boundaries`, () => {
    const catalog = read(sqlPath(edition, 'README.md'))
    const order = ['init.sql', 'doctor_workspace_20260921.sql', 'care_platform_upgrade_20260921.sql',
      'care_plan_collaboration_20261003.sql', 'clinical_integrity_upgrade_20261011.sql', 'patient_specialty_roles_20260923.sql']
    for (const [index, file] of order.entries()) assert.match(catalog, new RegExp(`${index + 1}\\. \\x60${file.replaceAll('.', '\\.')}\\x60`))
    for (const expression of [/每次.*启动/, /没有.*关闭.*开关/, /DDL.*隐式提交/, /完整数据库.*附件/, /恢复演练/,
      /CREATE TABLE IF NOT EXISTS.*不能.*已有.*字段/, /删除.*角色/, /禁止.*生产/, /固定.*演示.*ID/, /隔离.*测试/]) {
      assert.match(catalog, expression)
    }
    assert.match(catalog, /只检查.*存在.*不.*修复.*类型/)
  })
  test(`${edition} SQL headers use readable Chinese purpose, prerequisites and rerun risks`, () => {
    for (const filename of readdirSync(path.join(root, edition, 'src/main/resources/sql')).filter(name => name.endsWith('.sql'))) {
      const source = read(sqlPath(edition, filename))
      const header = source.slice(0, source.search(/^(?!\s*(?:--|$))\S/m))
      for (const field of ['用途', '前提', '重复执行', '风险']) assert.match(header, new RegExp(`-- ${field}：`), filename)
      assert.doesNotMatch(header, /-p<pass>/)
    }
  })
  test(`${edition} current Docker and runtime SQL reference paths stay intact`, () => {
    const compose = read(path.join(edition, 'docker-compose.yml'))
    const files = [...compose.matchAll(/\.\/src\/main\/resources\/sql\/([^:\s]+\.sql):\/docker-entrypoint-initdb.d\/([^:\s]+):ro/g)]
    assert.deepEqual(files.map(([, file]) => file), ['init.sql', 'doctor_workspace_20260921.sql',
      'care_platform_upgrade_20260921.sql', 'care_plan_collaboration_20261003.sql', 'demo-data.sql'])
    assert.deepEqual(files.map(([, , target]) => target), ['01-schema.sql', '02-doctor-workspace.sql',
      '03-care-platform.sql', '04-care-plan-collaboration.sql', '05-demo-data.sql'])
    const startup = read(path.join(edition, 'src/main/java/org/familyhealthcare/config/PatientSpecialtyMigration.java'))
    assert.match(startup, /ClassPathResource\("sql\/patient_specialty_roles_20260923\.sql"\)/)
    for (const [, file] of files) assert.ok(existsSync(path.join(root, sqlPath(edition, file))))
  })
  test(`${edition} native legacy fixture is positioned after doctor workspace and before actual platform upgrade`, () => {
    const source = read(path.join(edition, 'src/test/java/org/familyhealthcare/service/CarePlanMysqlIntegrationTest.java'))
    assert.match(source, /if \(db\.equals\(upgrade\) && "sql\/care_platform_upgrade_20260921\.sql"\.equals\(script\)\)\s*\{\s*assertLegacyCompatibilityUpgrade\(connection\);\s*continue;/)
    assert.match(source, /private void assertLegacyCompatibilityUpgrade\(Connection c\)/)
    for (const name of ['uk_bp_external', 'idx_record_review', 'uk_record_external', 'idx_alert_dedupe']) assert.match(source, new RegExp(name))
    assert.match(source, /assertEquals\(24, missing/)
    assert.match(source, /comparisonSnapshot\(snapshot\(c\), false\)/)
    assert.match(source, /COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT/)
  })
}
test('editions have the same ordered complete conditional ALTER templates', () => {
  const en = conditionalAlters(read(sqlPath('.', 'care_platform_upgrade_20260921.sql')))
  const cn = conditionalAlters(read(sqlPath('cn', 'care_platform_upgrade_20260921.sql')))
  assert.equal(en.length, 28, 'Expected 24 legacy repairs and the four existing conditional ALTER statements')
  assert.deepEqual(cn, en, 'Guard, definition, default, nullability and dependency order must match')
})
for (const edition of editions) {
  test(`${edition} restores all 24 exact legacy definitions in dependency order`, () => {
    assert.deepEqual(restoredDefinitions(read(sqlPath(edition, 'care_platform_upgrade_20260921.sql'))), compatibilityDefinitions)
  })
}
test('definition comparator detects types, defaults, nullability, AFTER and missing guards', () => {
  const source = read(sqlPath('.', 'care_platform_upgrade_20260921.sql'))
  const expected = conditionalAlters(source)
  for (const [before, after] of [['VARCHAR(120)', 'VARCHAR(121)'], ["DEFAULT ''MANUAL''", "DEFAULT ''DEVICE''"],
    ['occurrence_count INT NOT NULL', 'occurrence_count INT NULL'], ['AFTER measure_period', 'AFTER record_date'],
    ["AND column_name='source_external_id'", "AND column_name='wrong_column'"]]) {
    assert.ok(source.includes(before), before)
    const start = source.indexOf('-- Compatibility upgrades')
    const changed = source.slice(0, start) + source.slice(start).replace(before, after)
    assert.notDeepEqual(conditionalAlters(changed), expected, `${before} mutation escaped`)
  }
  const first = source.indexOf('SET @ddl', source.indexOf('-- Compatibility upgrades'))
  const second = source.indexOf('SET @ddl', first + 1)
  const third = source.indexOf('SET @ddl', second + 1)
  assert.ok(first >= 0 && second > first && third > second)
  const reversed = source.slice(0, first) + source.slice(second, third) + source.slice(first, second) + source.slice(third)
  assert.notDeepEqual(conditionalAlters(reversed), expected, 'Dependency order mutation escaped')
  const guarded = source.slice(first, second).match(/^SET\s+@ddl\s*=[^\n]*;/m)[0]
  const unguarded = "SET @ddl='ALTER TABLE bp_self_monitor_record ADD COLUMN source_type VARCHAR(20) DEFAULT ''MANUAL'' AFTER measure_period';"
  assert.notDeepEqual(conditionalAlters(source.replace(guarded, unguarded)), expected, 'Literal IF/existence guard removal escaped')
})
test('main CI static guard runs the catalog regression', () => {
  const workflow = read('.github/workflows/ci.yml')
  const staticJob = workflow.slice(workflow.indexOf('  static-demo:'), workflow.indexOf('  care-plan-mysql:'))
  assert.match(staticJob, /node --test[^\n]*scripts\/tests\/sql-catalog\.test\.mjs/)
})
test('deployment guides no longer deny startup migration or require only init', () => {
  for (const edition of editions) {
    for (const filename of ['CONTRIBUTING.md', 'docs/family-care-guide.md', 'docs/USER_GUIDE.md']) {
      const source = read(path.join(edition, filename))
      assert.doesNotMatch(source, /No upgrade scripts are required|does not create (?:or alter )?tables automatically|应用不会自动创建表/)
      assert.match(source, /patient_specialty_roles_20260923\.sql/, `${edition}/${filename}`)
      assert.match(source, /src\/main\/resources\/sql\/README\.md/, `${edition}/${filename}`)
    }
  }
})
for (const edition of editions) {
  test(`${edition} init section and table headings are readable Chinese`, () => {
    const source = read(sqlPath(edition, 'init.sql'))
    for (const heading of ['一、系统基础表', '1. 系统用户表', '二、患者管理', '三、透析管理', '四、病历与附件',
      '五、用药管理', '六、AI 分析', '七、临床资料、指标字典与用户偏好', '八、用户角色与菜单关联',
      '九、健康监测与家庭照护', '十、基础字典与菜单']) assert.ok(source.includes(`-- ${heading}`), heading)
    assert.doesNotMatch(source, /^--.*(?:systemRoletable|systemMenu\/Permissiontable|Recordsmodule|userslightlygood|related table|usertable)/m)
  })
}
for (const edition of editions) {
  test(`${edition} repair rerun discloses six missing medication-rule inserts without overwriting existing pairs`, () => {
    const catalog = read(sqlPath(edition, 'README.md'))
    const row = catalog.split('\n').find(line => line.startsWith('| [functional_review_repair_20260922.sql]'))
    const header = read(sqlPath(edition, 'functional_review_repair_20260922.sql')).split('CREATE TABLE')[0]
    for (const text of [row, header]) {
      assert.match(text, /INSERT IGNORE.*(?:仍会|重复执行).*(?:补回|恢复).*6.*(?:初始|起始).*用药安全规则/)
      assert.match(text, /已有.*药物对.*不.*覆盖/)
      assert.match(text, /(?:默认启用|enabled=1)/)
    }
  })
  test(`${edition} collaboration rerun discloses role menus and bindings without account or patient grants`, () => {
    const catalog = read(sqlPath(edition, 'README.md'))
    const row = catalog.split('\n').find(line => line.startsWith('| [care_plan_collaboration_20261003.sql]'))
    const header = read(sqlPath(edition, 'care_plan_collaboration_20261003.sql')).split('SET @ddl')[0]
    for (const text of [row, header]) {
      assert.match(text, /重复执行.*(?:补回|恢复).*nurse.*角色.*2.*菜单.*nurse\/admin.*(?:角色菜单|菜单).*绑定/)
      assert.match(text, /删除.*绑定.*(?:恢复|补回)/)
      assert.match(text, /不.*(?:账号.*分配.*(?:nurse|护理)|分配护理账号)/)
      assert.match(text, /不.*(?:患者授权|患者权限|授予.*CARE_PLAN)/)
      assert.match(text, /不.*(?:自动发布|自动.*发布)/)
    }
  })
}

for (const edition of editions) {
  test(`${edition} clinical integrity upgrade fails closed before clinical DDL and is manual only`, () => {
    assert.ok(existsSync(path.join(root, sqlPath(edition, 'clinical_integrity_upgrade_20261011.sql'))), 'Manual clinical integrity migration is missing')
    const source = read(sqlPath(edition, 'clinical_integrity_upgrade_20261011.sql'))
    const prerequisite = source.indexOf('FROM health_analysis_automation WHERE 1=0')
    const firstDdl = source.search(/^(?:CREATE|ALTER|DROP) /m)
    assert.ok(prerequisite >= 0 && prerequisite < firstDdl, 'Automation schema must be checked before any DDL')
    assert.match(source, /SIGNAL SQLSTATE '45000'/)
    const duplicateGate = source.indexOf('EXECUTE clinical_integrity_preflight')
    assert.ok(duplicateGate > prerequisite)
    for (const table of ['bp_self_monitor_record', 'medical_record']) {
      assert.match(source, new RegExp(`FROM ${table}[\\s\\S]*?GROUP BY patient_id,source_type,source_external_id[\\s\\S]*?HAVING COUNT\\(\\*\\)>1`))
    }
    const tableMutation = source.search(/ALTER TABLE|CREATE TABLE/)
    assert.ok(duplicateGate < tableMutation, 'Duplicates must SIGNAL before clinical table or index changes')
    assert.doesNotMatch(source, /DELETE FROM|TRUNCATE|UPDATE medical_record|UPDATE bp_self_monitor_record/i)
    assert.match(source, /UNIQUE KEY uk_bp_external\(patient_id,source_type,source_external_id\)/)
    assert.match(source, /UNIQUE KEY uk_record_external\(patient_id,source_type,source_external_id\)/)
    assert.match(source, /ADD COLUMN content_version BIGINT NOT NULL DEFAULT 1/)
    assert.match(source, /ADD COLUMN verification_note TEXT/)
    for (const key of ['config_version', 'active_run_id', 'notification_outcomes', 'settings_json', 'claim_token']) assert.ok(source.includes(key), key)
    for (const file of ['docker-compose.yml', 'docker-compose.production.yml']) {
      const composePath = file === 'docker-compose.production.yml' ? file : path.join(edition, file)
      assert.doesNotMatch(read(composePath), /clinical_integrity_upgrade_20261011\.sql:[^\n]*docker-entrypoint/)
    }
    for (const filename of readdirSync(path.join(root, edition, 'src/main/java/org/familyhealthcare/config'))) {
      assert.doesNotMatch(read(path.join(edition, 'src/main/java/org/familyhealthcare/config', filename)), /ClassPathResource\("sql\/clinical_integrity_upgrade_20261011\.sql"\)/)
    }
    const native = read(path.join(edition, 'src/test/java/org/familyhealthcare/service/CarePlanMysqlIntegrationTest.java'))
    assert.match(native, /assertClinicalIntegrityUpgrade\(connection, db\.equals\(upgrade\)\)/)
    assert.match(native, /ClinicalImportDialysisMysqlAssertions\.verify\(/)
    assert.match(native, /MedicalRecordMysqlAssertions\.verify\(/)
    assert.match(native, /HealthAutomationMysqlAssertions\.verify\(/)
  })
}
test('clinical integrity upgrade has identical English and Chinese SQL contracts', () => {
  assert.ok(existsSync(path.join(root, sqlPath('.', 'clinical_integrity_upgrade_20261011.sql'))), 'Clinical integrity migration is missing')
  assert.equal(read(sqlPath('.', 'clinical_integrity_upgrade_20261011.sql')), read(sqlPath('cn', 'clinical_integrity_upgrade_20261011.sql')))
})

for (const edition of editions) {
  test(`${edition} clinical journal fresh-install and upgrade definitions are identical`, () => {
    const definition = source => source.match(/CREATE TABLE IF NOT EXISTS health_analysis_run \([\s\S]*?;/)?.[0]
    const init = definition(read(sqlPath(edition, 'init.sql')))
    const upgrade = definition(read(sqlPath(edition, 'clinical_integrity_upgrade_20261011.sql')))
    assert.ok(init && upgrade, 'Fresh and old schemas must both define the persistent run journal')
    assert.equal(canonicalStatement(init), canonicalStatement(upgrade))
  })
}
for (const edition of editions) {
  test(`${edition} new medical fields preserve the existing 24-column legacy AFTER contract`, () => {
    const source = read(sqlPath(edition, 'init.sql'))
    const medical = source.match(/CREATE TABLE IF NOT EXISTS `medical_record` \([\s\S]*?;/)?.[0]
    assert.ok(medical)
    const columns = [...medical.matchAll(/^\s*`(\w+)`\s+\w+/gm)].map(match => match[1])
    for (const definition of compatibilityDefinitions.filter(value => value.startsWith('medical_record.'))) {
      const column = definition.split(' ')[0].split('.')[1]
      const predecessor = definition.match(/AFTER (\w+)$/)[1]
      assert.equal(columns.indexOf(column), columns.indexOf(predecessor) + 1, `${column} must follow ${predecessor}`)
    }
    assert.equal(columns.indexOf('content_version'), columns.indexOf('verified_at') + 1)
    assert.equal(columns.indexOf('verification_note'), columns.indexOf('content_version') + 1)
  })
}
