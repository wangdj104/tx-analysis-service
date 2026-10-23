import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..')
// Source/DDL guard only; this does not exercise a real MySQL transaction.
function method(source,name){const at=source.search(new RegExp('(?:public|private|protected) [^\\n({;]*\\b'+name+'\\('));assert.ok(at>=0,name);const start=source.lastIndexOf('@Transactional',at),brace=source.indexOf('{',at);let depth=1;for(let n=brace+1;n<source.length;n++){if(source[n]==='{')depth++;if(source[n]==='}'&&--depth===0)return {annotation:source.slice(start,at),body:source.slice(brace+1,n)}}throw Error(name+' is unclosed')}
for(const edition of ['.','cn']){
 const source=readFileSync(path.join(root,edition,'src/main/java/org/familyhealthcare/service/impl/MedicalRecordServiceImpl.java'),'utf8')
 test(`${edition} current record/attachment writes retain one rollback boundary and propagate insertion errors`,()=>{
  for(const name of ['saveRecordWithItems','updateRecordWithItems']){
   const m=method(source,name)
   assert.match(m.annotation,/@Transactional\(\s*rollbackFor\s*=\s*Exception\.class\s*,\s*isolation\s*=\s*Isolation\.READ_COMMITTED\s*\)/)
   if(name.startsWith('save')){
    assert.doesNotMatch(m.body,/catch\s*\(|\bfinally\s*\{/)
    assert.match(m.body,/return\s+saveNewRecord\(record,\s*items\);/)
    assert.match(source,/private\s+boolean\s+saveNewRecord\s*\(/)
    const write=method(source,'saveNewRecord').body
    assert.doesNotMatch(write,/catch\s*\(|\bfinally\s*\{/)
    assert.match(write,/boolean\s+saved\s*=\s*super\.save\(record\);/)
    assert.match(write,/saveAttachments\(record.getId\(\), record.getAttachments\(\)\)/)
   }else{
    // Only the parent-row write is caught; translate and rethrow its binding failure.
    const bindingWrite=/try\s*\{\s*updated\s*=\s*super\.updateById\(record\);\s*\}\s*catch\s*\(\s*RuntimeException\s+(\w+)\s*\)\s*\{\s*throw\s+ClinicalImportFacts\.translateBinding\(\1\);\s*\}/
    assert.match(m.body,bindingWrite)
    // The lifecycle catch is allowed only when it marks rollback and rethrows the failure.
    const lifecycleCatch=/catch\s*\(\s*Throwable\s+(\w+)\s*\)\s*\{\s*throw\s+scope\.failure\(\1\);\s*\}/
    assert.match(m.body,lifecycleCatch)
    assert.doesNotMatch(m.body.replace(bindingWrite,'').replace(lifecycleCatch,''),/catch\s*\(|\bfinally\s*\{/)
    assert.match(m.body,/if\(scope\.participating\(\)\)return updateParticipating\(record,items,existing,admission,scope\)/)
    assert.match(m.body,/updateAttachments\(record.getId\(\), record.getAttachments\(\)\)/)
    const participating=method(source,'updateParticipating').body
    const participatingHeader=/try\s*\{scope\.requireHeaderCount\(baseMapper\.updateById\(record\)\);\}\s*catch\(RuntimeException\s+(\w+)\)\{throw ClinicalImportFacts\.translateBinding\(\1\);\}/
    assert.match(participating,participatingHeader)
    assert.match(participating,/scope\.prepareEdit\(record,items,attachments\);scope\.arm\(\)/)
    assert.match(participating,/attachmentMapper\.insert\(attachment\);scope\.insertedAttachment/)
    assert.doesNotMatch(participating.replace(participatingHeader,''),/catch\s*\(|\bfinally\s*\{/)
    const lifecycle=readFileSync(path.join(root,edition,'src/main/java/org/familyhealthcare/service/medicalreview/MedicalReviewNativeLifecycle.java'),'utf8')
    const failure=method(lifecycle,'failure').body
    assert.match(failure,/if\(mutationAttempted\)holder\.setRollbackOnly\(\)/)
    assert.match(failure,/if\(failure instanceof Error\)throw \(Error\)failure/)
    assert.match(failure,/if\(failure instanceof RuntimeException\)return \(RuntimeException\)failure/)
   }
  }
  for(const name of ['saveAttachments','updateAttachments']){const body=method(source,name).body;assert.match(body,/att.setRecordId\(recordId\)/);assert.match(body,/attachmentMapper.insert\(att\)/);assert.doesNotMatch(body,/catch\s*\(|\bfinally\s*\{/)}
  assert.match(method(source,'updateRecordWithItems').body,/selectForUpdate\(record.getId\(\)\)/)
 })
 test(`${edition} current attachment DDL preserves a foreign key instead of relying on an ignored MySQL5.7 CHECK`,()=>{
  const init=readFileSync(path.join(root,edition,'src/main/resources/sql/init.sql'),'utf8'),table=init.match(/CREATE TABLE(?: IF NOT EXISTS)? `medical_record_attachment`\s*\(([\s\S]*?)\) ENGINE=/)?.[1];assert.ok(table);assert.match(table,/FOREIGN KEY \(`record_id`\) REFERENCES `medical_record`\(`id`\) ON DELETE CASCADE/);assert.match(table,/`file_content` MEDIUMTEXT/);assert.doesNotMatch(table,/\bCHECK\s*\(/)
 })
}
