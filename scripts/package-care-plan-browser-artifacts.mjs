#!/usr/bin/env node
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { lstat, readdir, mkdir, copyFile, readFile, writeFile, rm } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const PRIMARY_SCENARIO_DIRECTORY='carePlanCollaboration-real-dcac3-p-return-review-and-closure'
export const PAYLOAD_BUDGET_BYTES=30*1024*1024 // Reserve 2MiB for ZIP/manifest overhead below the 32MiB download limit.
const roles=['personal','family','doctor','nurse','admin','outsider']
const groups=[...roles.map(role=>'main-'+role),'screenshots-1','screenshots-2','screenshots-3','screenshots-4','remainder']
const safePath=path=>typeof path==='string' && Buffer.byteLength(path)<=256 && !/[\\\x00-\x1f\x7f]/.test(path) && !path.startsWith('/') && path.split('/').every(part=>part!=='' && part!=='.' && part!=='..')
const allowed=path=>!path.split('/').some(part=>part.startsWith('.')) && (/\.(webm|mp4|png)$/.test(path) || path==='acceptance-evidence.json')

export function planArtifactGroups(input,{budgetBytes=PAYLOAD_BUDGET_BYTES}={}) {
  assert.ok(Number.isSafeInteger(budgetBytes) && budgetBytes>0,'Invalid artifact byte budget')
  for(const file of input)assert.ok(safePath(file.path) && Number.isSafeInteger(file.bytes) && file.bytes>=0,'Invalid artifact path or byte count')
  const files=input.filter(file=>allowed(file.path)).sort((a,b)=>a.path.localeCompare(b.path))
  assert.ok(files.length<=512 && new Set(files.map(file=>file.path)).size===files.length,'Artifact file count/identity limit exceeded')
  const result={groups:Object.fromEntries(groups.map(name=>[name,[]])),oversizedPrimaryRoles:[]},assigned=new Set()
  for(const role of roles) {
    const candidates=files.filter(file=>{const parts=file.path.split('/');return parts.length===3 && parts[0]===PRIMARY_SCENARIO_DIRECTORY && [role+'-desktop-video',role+'-390-video'].includes(parts[1]) && /\.(webm|mp4)$/.test(parts[2])})
      .sort((a,b)=>(a.path.endsWith('.mp4')?0:1)-(b.path.endsWith('.mp4')?0:1) || a.path.localeCompare(b.path))
    const selectedClips=new Set();let bytes=0
    for(const file of candidates) {
      const clip=file.path.replace(/\.(webm|mp4)$/,'')
      if(!selectedClips.has(clip) && bytes+file.bytes<=budgetBytes) {
        result.groups['main-'+role].push(file);assigned.add(file.path);selectedClips.add(clip);bytes+=file.bytes
      }
    }
    if(candidates.some(file=>!selectedClips.has(file.path.replace(/\.(webm|mp4)$/,''))))result.oversizedPrimaryRoles.push(role)
  }
  for(const file of files.filter(file=>file.path==='acceptance-evidence.json' || file.path.endsWith('.png'))) {
    const group=['screenshots-1','screenshots-2','screenshots-3','screenshots-4'].find(name=>result.groups[name].reduce((sum,entry)=>sum+entry.bytes,0)+file.bytes<=budgetBytes)
    if(group){result.groups[group].push(file);assigned.add(file.path)}
  }
  result.groups.remainder=files.filter(file=>!assigned.has(file.path))
  return result
}

async function sourceFiles(directory,prefix='') {
  const metadata=await lstat(directory)
  assert.ok(!metadata.isSymbolicLink() && metadata.isDirectory(),'Artifact source directory must not be a symlink')
  const files=[]
  for(const name of (await readdir(directory)).sort()) {
    if(name.startsWith('.'))continue // Never open hidden session/config data.
    const path=prefix?prefix+'/'+name:name,full=join(directory,name),entry=await lstat(full)
    assert.ok(!entry.isSymbolicLink(),'Artifact source must not contain a symlink')
    if(entry.isDirectory())files.push(...await sourceFiles(full,path))
    else if(entry.isFile())files.push({path,bytes:entry.size})
  }
  return files
}
function assertSafeEvidence(data) {
  const keys=['language','actualSpring','actualMySQL','builtVue','videoCount','screenshotCount','recordings','trace']
  assert.ok(data && Object.keys(data).length===keys.length && Object.keys(data).every(key=>keys.includes(key)) && ['en','cn'].includes(data.language) && data.actualSpring===true && data.actualMySQL===true && data.builtVue===true && data.trace===false,'Unsafe acceptance evidence')
  assert.ok([data.videoCount,data.screenshotCount].every(value=>Number.isSafeInteger(value) && value>=0 && value<=512) && Array.isArray(data.recordings) && data.recordings.length===data.videoCount,'Unsafe acceptance evidence counts')
  for(const recording of data.recordings)assert.ok(Object.keys(recording).length===4 && ['width','height','decodedFrames','durationSeconds'].every(key=>Number.isFinite(recording[key]) && recording[key]>0),'Unsafe recording evidence')
}
async function digest(path) {
  const hash=createHash('sha256')
  for await(const data of createReadStream(path))hash.update(data)
  return hash.digest('hex')
}
async function assertDirectoryAncestors(path) {
  assert.ok(typeof path==='string' && path!=='' && !path.split('/').includes('..'),'Invalid artifact directory path')
  const ancestors=[]
  for(let current=dirname(resolve(path));;current=dirname(current)) {
    ancestors.unshift(current)
    if(current===dirname(current))break
  }
  for(const ancestor of ancestors) {
    const metadata=await lstat(ancestor)
    assert.ok(metadata.isDirectory() && !metadata.isSymbolicLink(),'Artifact directory ancestor must be a real directory, never a symlink')
  }
}
export async function packageBrowserArtifacts(source,destination,options={}) {
  await assertDirectoryAncestors(source)
  await assertDirectoryAncestors(destination)
  try {await lstat(destination);throw new Error('Artifact destination already exists')}
  catch(error){if(error.code!=='ENOENT')throw error}
  let exists=true
  try {await lstat(source)}catch(error){if(error.code!=='ENOENT')throw error;exists=false}
  const files=exists?await sourceFiles(source):[]
  const result=planArtifactGroups(files,options),selected=Object.values(result.groups).flat()
  const evidence=selected.find(file=>file.path==='acceptance-evidence.json')
  if(evidence) {
    assert.ok(evidence.bytes<=65536,'Unsafe acceptance evidence size')
    assertSafeEvidence(JSON.parse(await readFile(join(source,evidence.path),'utf8')))
  }
  await mkdir(destination)
  try {
    const manifest={payloadBudgetBytes:options.budgetBytes??PAYLOAD_BUDGET_BYTES,selectedFileCount:selected.length,oversizedPrimaryRoles:result.oversizedPrimaryRoles,files:[]}
    for(const [group,entries] of Object.entries(result.groups)) {
      for(const file of entries) {
        const original=join(source,file.path),copy=join(destination,group,file.path)
        await mkdir(dirname(copy),{recursive:true});await copyFile(original,copy)
        const sha256=await digest(original)
        assert.ok((await lstat(copy)).size===file.bytes && await digest(copy)===sha256,'Staged artifact bytes changed')
        manifest.files.push({group,path:file.path,bytes:file.bytes,sha256})
      }
    }
    const text=JSON.stringify(manifest,null,2)
    assert.ok(Buffer.byteLength(text)<=256*1024,'Delivery manifest size limit exceeded')
    await mkdir(join(destination,'screenshots-1'),{recursive:true})
    await writeFile(join(destination,'screenshots-1','delivery-manifest.json'),text)
    return {...result,selectedFileCount:selected.length}
  } catch(error){await rm(destination,{recursive:true,force:true});throw error}
}
async function main() {
  const [project,output]=process.argv.slice(2),root=resolve(fileURLToPath(new URL('..',import.meta.url)))
  assert.ok(process.env.CI==='true' && process.env.GITHUB_ACTIONS==='true' && ['.','cn'].includes(project),'CI-only artifact packaging contract required')
  const language=project==='cn'?'cn':'en'
  assert.ok(process.env.RUNNER_TEMP && resolve(output||'')===join(resolve(process.env.RUNNER_TEMP),'care-plan-browser-artifacts-'+language),'Owned artifact destination required')
  const result=await packageBrowserArtifacts(join(root,project,'frontend/test-results'),resolve(output))
  console.log(`Sanitized browser artifact files staged exactly once: ${result.selectedFileCount}`)
  if(result.oversizedPrimaryRoles.length)console.warn(`Primary roles exceeding the delivery budget remain intact in remainder: ${result.oversizedPrimaryRoles.join(', ')}`)
  const remainderBytes=result.groups.remainder.reduce((sum,file)=>sum+file.bytes,0)
  if(remainderBytes>PAYLOAD_BUDGET_BYTES)console.warn('Raw/secondary remainder exceeds the bounded download size; primary role and screenshot artifacts remain separate')
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href)main().catch(()=>{console.error('Sanitized browser artifact packaging failed');process.exitCode=1})
