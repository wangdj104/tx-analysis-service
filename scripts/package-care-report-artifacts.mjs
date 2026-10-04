#!/usr/bin/env node
// Only verified, explicitly synthetic report specimens. No HTTP/session data.
import assert from 'node:assert/strict'
import { lstat, mkdir, readdir, readFile, writeFile, rename, rm } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { PAYLOAD_BUDGET_BYTES, assertDirectoryAncestors, copyVerified, digest, planBoundedGroups } from './package-care-plan-browser-artifacts.mjs'

export const REPORT_GROUPS=Array.from({length:16},(_,index)=>'report-'+(index+1))
export const REPORT_SCENARIOS=['owner-desktop','family-desktop','doctor-desktop','nurse-desktop','owner-mobile','family-mobile','doctor-mobile','nurse-mobile','empty-actions','empty-events','limited-actions','limited-events','task5-coverage','task5-long']
const MANIFEST='report-evidence.json',MAX_MANIFEST_BYTES=128*1024,MAX_FILES=224
function exactKeys(value,keys) {
  return value && typeof value==='object' && !Array.isArray(value) && Object.keys(value).length===keys.length && keys.every(key=>Object.hasOwn(value,key))
}
export function reportEvidenceFilename(entry) {
  const additional=entry?.format==='pdf'?['pageCount']:['actions','events'].includes(entry?.format)?['csvRowCount']:[]
  assert.ok(exactKeys(entry,['edition','language','scenario','format','bytes','sha256','result',...additional]),'Unsafe report metadata keys')
  assert.ok(['en','cn'].includes(entry.edition) && ['en','zh-CN'].includes(entry.language) && REPORT_SCENARIOS.includes(entry.scenario) && ['html','pdf','actions','events'].includes(entry.format),'Unsafe report metadata enum')
  assert.ok(Number.isSafeInteger(entry.bytes) && entry.bytes>0 && entry.bytes<=PAYLOAD_BUDGET_BYTES && /^[a-f0-9]{64}$/.test(entry.sha256) && entry.result==='passed','Unsafe report metadata value')
  if(entry.format==='pdf')assert.ok(Number.isSafeInteger(entry.pageCount) && entry.pageCount>0 && entry.pageCount<=512,'Unsafe report page count')
  if(additional[0]==='csvRowCount')assert.ok(Number.isSafeInteger(entry.csvRowCount) && entry.csvRowCount>=0 && entry.csvRowCount<=(entry.format==='actions'?1000:5000),'Unsafe report CSV count')
  const extension=['actions','events'].includes(entry.format)?'csv':entry.format
  return `${entry.edition}-${entry.language}-${entry.scenario}-${entry.format}.${extension}`
}
export function validateReportManifest(value) {
  assert.ok(exactKeys(value,['syntheticOnly','files']) && value.syntheticOnly===true && Array.isArray(value.files) && value.files.length>0 && value.files.length<=MAX_FILES,'Unsafe report manifest')
  const filenames=value.files.map(reportEvidenceFilename)
  assert.ok(new Set(filenames).size===filenames.length && new Set(value.files.map(file=>file.edition)).size===1,'Unsafe report duplicate identity or mixed edition')
  assert.ok(Buffer.byteLength(JSON.stringify(value))<=MAX_MANIFEST_BYTES,'Unsafe report manifest size')
  return value
}
async function ownedDirectory(path) {
  await assertDirectoryAncestors(path)
  const metadata=await lstat(path)
  assert.ok(metadata.isDirectory() && !metadata.isSymbolicLink() && metadata.uid===process.getuid(),'Owned report directory must be non-symlink')
}
async function loadManifest(directory) {
  const path=join(directory,MANIFEST),metadata=await lstat(path)
  assert.ok(metadata.isFile() && !metadata.isSymbolicLink() && metadata.uid===process.getuid() && metadata.size<=MAX_MANIFEST_BYTES,'Unsafe report manifest file')
  return validateReportManifest(JSON.parse(await readFile(path,'utf8')))
}
async function verifyDirectory(directory,manifest) {
  const names=new Set([MANIFEST,...manifest.files.map(reportEvidenceFilename)])
  const actual=await readdir(directory)
  assert.ok(actual.length===names.size && actual.every(name=>names.has(name)),'Undeclared report artifact file')
  for(const name of actual) {
    const metadata=await lstat(join(directory,name))
    assert.ok(metadata.isFile() && !metadata.isSymbolicLink() && metadata.uid===process.getuid(),'Report artifact must be owned regular file, never a symlink')
  }
}
export async function stageReportEvidence(sourceFile,entry,env=process.env) {
  const filename=reportEvidenceFilename(entry)
  assert.ok(env.CI==='true' && env.GITHUB_ACTIONS==='true','Report staging requires CI contract')
  assert.ok(typeof env.RUNNER_TEMP==='string' && env.RUNNER_TEMP===resolve(env.RUNNER_TEMP) && !env.RUNNER_TEMP.split('/').includes('..'),'Owned report runner temp required')
  await ownedDirectory(env.RUNNER_TEMP)
  assert.ok(typeof sourceFile==='string' && sourceFile===resolve(sourceFile) && !sourceFile.split('/').includes('test-results'),'Report raw source must be outside test-results')
  await assertDirectoryAncestors(sourceFile)
  const metadata=await lstat(sourceFile)
  assert.ok(metadata.isFile() && !metadata.isSymbolicLink() && metadata.uid===process.getuid(),'Report source must be owned regular file, never a symlink')
  assert.ok(metadata.size===entry.bytes && await digest(sourceFile)===entry.sha256,'Report source size/hash mismatch')
  const directory=join(env.RUNNER_TEMP,'care-report-evidence-'+entry.edition)
  let prior={syntheticOnly:true,files:[]}
  try {await mkdir(directory,{mode:0o700})}catch(error){if(error.code!=='EEXIST')throw error;await ownedDirectory(directory);prior=await loadManifest(directory);await verifyDirectory(directory,prior)}
  assert.ok(!prior.files.some(file=>reportEvidenceFilename(file)===filename),'Report duplicate artifact identity')
  const manifest=validateReportManifest({syntheticOnly:true,files:[...prior.files,entry]})
  const copy=join(directory,filename),temporary=join(directory,'.report-evidence.pending')
  try {
    await copyVerified(sourceFile,copy,entry.bytes,entry.sha256)
    await writeFile(temporary,JSON.stringify(manifest,null,2),{flag:'wx',mode:0o600})
    await rename(temporary,join(directory,MANIFEST))
  } catch(error){await rm(copy,{force:true});await rm(temporary,{force:true});throw error}
  return copy
}
export async function packageReportArtifacts(source,destination,{budgetBytes=PAYLOAD_BUDGET_BYTES}={}) {
  await ownedDirectory(source);await assertDirectoryAncestors(destination)
  const src=resolve(source),dest=resolve(destination)
  assert.ok(src!==dest && !src.startsWith(dest+'/') && !dest.startsWith(src+'/'),'Report source/destination ancestry must be separate')
  try {await lstat(destination);throw new Error('Report artifact destination already exists')}catch(error){if(error.code!=='ENOENT')throw error}
  const manifest=await loadManifest(source);await verifyDirectory(source,manifest)
  const files=manifest.files.map(entry=>({path:reportEvidenceFilename(entry),bytes:entry.bytes,sha256:entry.sha256}))
  const groups=planBoundedGroups(files,REPORT_GROUPS,budgetBytes),text=JSON.stringify(manifest,null,2)
  assert.ok(Buffer.byteLength(text)<=Math.min(MAX_MANIFEST_BYTES,budgetBytes),'Report manifest exceeds payload budget')
  // Verify the complete selection before creating any upload destination.
  for(const file of files)assert.ok((await lstat(join(source,file.path))).size===file.bytes && await digest(join(source,file.path))===file.sha256,'Report source size/hash mismatch')
  await mkdir(destination,{mode:0o700})
  try {
    for(const [group,entries] of Object.entries(groups))for(const file of entries)await copyVerified(join(source,file.path),join(destination,group,file.path),file.bytes,file.sha256)
    await mkdir(join(destination,'report-manifest'));await writeFile(join(destination,'report-manifest',MANIFEST),text,{mode:0o600})
    return {groups,selectedFileCount:files.length}
  }catch(error){await rm(destination,{recursive:true,force:true});throw error}
}
async function main() {
  const [edition]=process.argv.slice(2)
  assert.ok(process.argv.length===3 && process.env.CI==='true' && process.env.GITHUB_ACTIONS==='true' && ['en','cn'].includes(edition),'CI-only report packaging contract required')
  assert.ok(process.env.RUNNER_TEMP && process.env.RUNNER_TEMP===resolve(process.env.RUNNER_TEMP),'Owned report destination required')
  const source=join(process.env.RUNNER_TEMP,'care-report-evidence-'+edition),output=join(process.env.RUNNER_TEMP,'care-report-artifacts-'+edition)
  const result=await packageReportArtifacts(source,output)
  console.log(`Verified synthetic report artifacts staged exactly once: ${result.selectedFileCount}`)
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href)main().catch(()=>{console.error('Strict report artifact packaging failed');process.exitCode=1})
