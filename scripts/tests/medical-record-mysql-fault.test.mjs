import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
for (const edition of ['.', 'cn']) {
  const source = readFileSync(path.join(root, edition, 'src/test/java/org/familyhealthcare/service/MedicalRecordMysqlAssertions.java'), 'utf8')
  test(`${edition} injects a real attachment failure on MySQL 5.7 and 8`, () => {
    assert.doesNotMatch(source, /ADD CONSTRAINT[^"\n]*CHECK\s*\(|DROP CHECK/i)
    assert.match(source, /CREATE TRIGGER medical_risk_synthetic_attachment_failure BEFORE INSERT ON medical_record_attachment FOR EACH ROW/)
    assert.match(source, /IF NEW\.file_name='medical-risk-too-long\.txt' AND CHAR_LENGTH\(NEW\.file_content\)>8 THEN SIGNAL SQLSTATE '45000'/)
    assert.match(source, /MYSQL_ERRNO=1644/)
    assert.match(source, /finally\s*\{\s*if\(faultTrigger\) jdbc\.execute\("DROP TRIGGER medical_risk_synthetic_attachment_failure"\)/)
  })
  test(`${edition} verifies the injected native error and complete rollback`, () => {
    assert.equal((source.match(/assertNativeAttachmentFailure\(\(\)->CurrentUserUtil\.runAsUser/g) ?? []).length, 2)
    assert.match(source, /assertEquals\("45000",[^;]+getSQLState\(\)/)
    assert.match(source, /assertEquals\(1644,[^;]+getErrorCode\(\)/)
    assert.match(source, /assertEquals\(0,jdbc\.queryForObject\("SELECT COUNT\(\*\) FROM medical_record WHERE id=\?"/)
    assert.match(source, /assertEquals\(old\.getRemark\(\),service\.getById\(id\)\.getRemark\(\)\)/)
    assert.match(source, /assertEquals\(oldVersion,service\.getById\(id\)\.getContentVersion\(\)\)/)
    assert.match(source, /assertEquals\(oldBytes,jdbc\.queryForObject\("SELECT file_content FROM medical_record_attachment WHERE record_id=\?"/)
  })
}
