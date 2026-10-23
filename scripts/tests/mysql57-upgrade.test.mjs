import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
for (const edition of ['.', 'cn']) {
  test(edition + ' exposes one complete MySQL 5.7 initialization entry point', () => {
    const directory = path.join(root, edition, 'src/main/resources/sql')
    assert.deepEqual(readdirSync(directory).filter(name => name.endsWith('.sql')), ['init.sql'])
    const sql = readFileSync(path.join(directory, 'init.sql'), 'utf8')
    for (const table of ['care_plan_revision', 'care_plan_action', 'care_plan_event', 'care_plan_evidence', 'care_nurse_assignment', 'care_plan_notification', 'care_plan_command']) {
      assert.match(sql, new RegExp('CREATE TABLE(?: IF NOT EXISTS)? `'+table+'`'))
    }
    assert.equal((sql.match(/^CREATE TRIGGER cp57_/gm) ?? []).length, 8)
    assert.doesNotMatch(sql, /^\s*(?:DROP TABLE|DELETE FROM|TRUNCATE|SOURCE)\b/im)
    assert.doesNotMatch(sql, /CONVERT TO CHARACTER SET|SKIP LOCKED|utf8mb4_0900/)
  })
  test(edition + ' preserves collaboration constraints and final scoped indexes', () => {
    const sql = readFileSync(path.join(root, edition, 'src/main/resources/sql/init.sql'), 'utf8')
    for (const rule of ['NEW.revision_no <= 0', 'NEW.ordinal NOT BETWEEN 1 AND 50', 'NEW.lock_version < 0', 'NEW.attempt_count NOT BETWEEN 0 AND 3', 'CHAR_LENGTH(NEW.dispatch_key) = 0', 'NEW.expected_version < 0']) assert.ok(sql.includes(rule), rule)
    for (const key of ['fk_doctor_plan_current_revision', 'fk_doctor_plan_draft_revision', 'fk_doctor_plan_legacy_source', 'uk_user_patient_name', 'uk_medication_stock', 'idx_alert_dedupe', 'idx_intake_patient_schedule', 'idx_schedule_patient_date', 'idx_event_patient_date']) assert.ok(sql.includes(key), key)
    assert.match(sql, /DELIMITER \$\$/)
    assert.match(sql, /'care-plan-nursing'/)
    assert.match(sql, /'care-plan-nurse-assignments'/)
  })
}
