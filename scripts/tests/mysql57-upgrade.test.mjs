import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
for (const edition of ['.', 'cn']) {
  test(`${edition} provides a guarded standalone MySQL 5.7 upgrade`, () => {
    const file = path.join(root, edition, 'src/main/resources/sql/mysql57_schema_upgrade_20261012.sql')
    assert.ok(existsSync(file), 'Missing standalone MySQL 5.7 upgrade')
    const sql = readFileSync(file, 'utf8')
    assert.ok(sql.indexOf('CALL cp57_upgrade_preflight()') < sql.indexOf('ALTER TABLE doctor_care_plan ADD COLUMN'))
    assert.equal((sql.match(/^CREATE TRIGGER /gm) ?? []).length, 8)
    assert.equal((sql.match(/CREATE TABLE IF NOT EXISTS `care_/g) ?? []).length, 7)
    assert.doesNotMatch(sql, /^\s*(?:DROP TABLE|DELETE FROM|TRUNCATE)\b/im)
    assert.doesNotMatch(sql, /CONVERT TO CHARACTER SET|SKIP LOCKED|utf8mb4_0900/)
  })
}
for (const edition of ['.', 'cn']) {
  test(`${edition} supplies read-only preflight and postcheck with exact execution order`, () => {
    for (const phase of ['preflight', 'postcheck']) {
      const file = path.join(root, edition, `src/main/resources/sql/mysql57_${phase}_20261012.sql`)
      assert.ok(existsSync(file), `Missing ${phase}`)
      const sql = readFileSync(file, 'utf8')
      assert.doesNotMatch(sql, /^\s*(?:CREATE|ALTER|DROP|DELETE|INSERT|UPDATE|TRUNCATE|REPLACE|CALL)\b/im)
      assert.match(sql, /SELECT DATABASE\(\)/)
    }
    const catalog = readFileSync(path.join(root, edition, 'src/main/resources/sql/health_upgrade_01_precheck_20261015.sql'), 'utf8')
    for (const [stage,file] of [['01','mysql57_preflight_20261012.sql'],['02','mysql57_schema_upgrade_20261012.sql'],['03','mysql57_postcheck_20261012.sql']]) assert.ok(catalog.includes(stage+' '+file),stage)
    assert.match(catalog,/A_ABSENT/);assert.match(catalog,/A_PRESENT/);assert.match(catalog,/部分.*停止|停止.*部分/)
    assert.doesNotMatch(catalog.replace(/^--.*$/gm,''),/^\s*(?:CREATE|ALTER|DROP|DELETE|INSERT|UPDATE|TRUNCATE|REPLACE|CALL)\b/im)

  })
}
