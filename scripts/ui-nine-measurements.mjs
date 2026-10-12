import assert from 'node:assert/strict'
import {assertReadableBox,assertDisjointBoxes,assertTouchTarget} from '../frontend/tests/uiNineGeometry.mjs'
const close=(a,b)=>Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<=1
const real=box=>assert.ok(box&&Number.isFinite(box.width)&&box.width>0&&Number.isFinite(box.height)&&box.height>0,'Actual rendered region required')
export function assertNineVisualMeasurement(group,id,m,viewport){
 assert.ok(m&&typeof m==='object'&&!Array.isArray(m),'Measured item object required')
 const readable=box=>assertReadableBox(box,viewport), mobile=viewport<=768
 if(group==='monitoring'&&id===1){
  assert.equal(m.presentation,mobile?'record-cards':'desktop-table');assert.equal(m.records.length,mobile?21:3)
  assert.equal(new Set(m.records.map(r=>r.id)).size,m.records.length,'Distinct record identities required')
  for(const r of m.records){assert.ok(Number.isSafeInteger(r.id));assert.ok(r.fields.length>=3);r.fields.forEach(readable);readable(r.edit);if(mobile)assertTouchTarget(r.edit)}
 }else if(group==='monitoring'&&id===2){
  for(const [key,values] of Object.entries({monitoring:[140],bp:[140,6.1,7.8]})){
   assert.deepEqual(m[key].map(x=>x.value),values,'Original reference values retained')
   for(const r of m[key]){readable(r);assert.ok(r.text.includes(String(r.value)));assert.match(r.text,r.value===140?/mmHg/:/mmol\/L/)}
  }
 }else if(group==='monitoring'&&id===7){
  const {primary,trend,lastRow,tasks,events,alerts}=m;[primary,trend,lastRow,tasks,events,alerts].forEach(real)
  assert.ok(tasks.top>=trend.bottom-1&&events.top>=tasks.bottom-1);assert.ok(close(tasks.left,trend.left)&&close(events.left,trend.left))
  assert.ok(close(m.taskGap,tasks.top-lastRow.bottom)&&m.taskGap>=-1&&m.taskGap<=48,'Next left content follows last data row within48px')
  assert.ok(close(m.eventGap,events.top-tasks.bottom))
  if(viewport===1365){assert.equal(m.layout,'independent-columns');assert.ok(alerts.left>=primary.right-1&&tasks.top<alerts.bottom)}
  else{assert.equal(m.layout,'stacked-flow');assert.ok(alerts.bottom<=primary.top+1||primary.bottom<=alerts.top+1)}
 }else if(group==='monitoring'&&id===8){
  real(m.summary);assert.equal(m.cards.length,4);m.cards.forEach(readable)
  if(mobile){
   assert.equal(m.layout,'mobile-2x2');const [a,b,c,d]=m.cards
   assert.ok(close(a.top,b.top)&&close(c.top,d.top)&&c.top>=a.bottom&&b.left>=a.right,'Two rows and two columns required')
   if(viewport===375)assert.ok(m.summary.height<=220)
   assert.equal(m.navigation.length,6)
   for(const id of ['monitoring-alerts','monitoring-trends','monitoring-tasks'])for(const action of ['click','keyboard']){
    const rows=m.navigation.filter(r=>r.action===action&&r.target?.id===id);assert.equal(rows.length,1)
    const {link,target}=rows[0];readable(link);assertTouchTarget(link);assert.equal(target.tag,'H2');assert.equal(target.focused,true);assert.equal(target.unobscured,true)
    assert.ok(target.top>=0&&target.bottom<=target.viewportHeight)
   }
  }
 }else if(group==='shell'&&id===3){
  for(const surface of [m.export,m.report]){
   assert.ok(surface.labels.length>=4&&surface.labels.some(l=>l.required));assert.equal(surface.layout,mobile?'labels-above-controls':'adaptive-label-columns')
   for(const label of surface.labels){readable(label);real(label.content);assert.ok(label.content.left>=-1&&label.content.right<=viewport+1)
    assert.ok(mobile?label.bottom<=label.content.top+1:label.right<=label.content.left+1)
    assert.equal(new Set(label.textBoxes.map(t=>Math.round(t.top))).size,1)
    if(label.required){assert.match(label.marker,/\*/);assert.ok(label.textBoxes[0].top<label.top+label.lineHeight);assert.ok(label.width>=label.textBoxes[0].right-label.textBoxes[0].left+label.markerWidth+label.markerMargin-1)}
   }
  }
 }else if(group==='shell'&&id===4){
  assert.ok(m.duplicateName?.trim());assert.deepEqual(m.options.map(o=>o.id),[99011,99012]);m.options.forEach(o=>{readable(o);assert.ok(o.text.endsWith(`#${o.id}`))})
  assert.ok(m.switches.length>=2&&[99011,99012].every(id=>m.switches.some(s=>s.patientId===id)))
  assert.ok(m.allPatientsTransition?.allPatientsHeader?.height>0&&m.allPatientsTransition.selected&&m.rapidSwitchFinal,'All-to-selected and rapid-switch rendered evidence required')
  for(const s of [...m.switches,m.allPatientsTransition.selected,m.rapidSwitchFinal]){assert.equal(s.viewport.width,viewport);readable(s.identity);assert.equal(s.identity.text,`#${s.patientId}`)
   const notices=s.messages.filter(n=>n.patientSwitch);assert.equal(notices.length,1)
   for(const n of notices){assertDisjointBoxes(n,s.patientContext);assertDisjointBoxes(n,s.topbar);assert.ok(n.top>=s.topbar.bottom+11,'Toast clears the post-render toolbar with12px spacing');assert.ok(n.text.includes(`#${s.patientId}`)&&n.text.includes(m.duplicateName));assert.equal(n.opacity,1);assert.ok(n.left>=0&&n.right<=viewport&&n.top>=0&&n.bottom<=s.viewport.height&&n.contentScrollWidth<=n.contentClientWidth+1)}
  }
 }else if(group==='shell'&&id===9){
  for(const b of [m.primary,m.secondary]){readable(b);assertTouchTarget(b)}
  assert.notEqual(m.primary.background,m.secondary.background);assert.deepEqual(m.activation,['keyboard-expand','keyboard-collapse'])
 }else throw Error(`Unsupported visual measurement ${group}/${id}`)
}


// Independently consume the raw option observations, even when a browser row
// claims it passed. They must match the same identities accepted for item 4.
export function assertPatientOptionBounds(entries, item, viewport, edition) {
 assert.ok(Array.isArray(entries), 'Both actual patient dropdown options are required')
 assert.deepEqual(entries.map(entry=>entry?.id), [99011,99012], 'Two distinct expected patient options are required')
 assert.equal(item.duplicateName, 'Synthetic Same Name Alexandra Catherine Montgomery-Sutherland', 'The long duplicate-name fixture cannot be shortened')
 assert.ok(['en','cn'].includes(edition))
 for(const {id,dropdown,name,identity} of entries) {
  real(dropdown)
  for(const field of ['left','right','top','bottom'])assert.ok(Number.isFinite(dropdown[field]), `Patient popup ${field} must be measured`)
  assert.ok(close(dropdown.width,dropdown.right-dropdown.left)&&close(dropdown.height,dropdown.bottom-dropdown.top), 'Patient popup dimensions must match its edges')
  assert.equal(dropdown.viewport,viewport, 'Patient popup must be measured in the requested viewport')
  assert.ok(dropdown.left>=-1&&dropdown.right<=viewport+1&&dropdown.top>=-1&&dropdown.bottom<=901, 'The whole patient popup must fit the actual viewport')
  assert.ok(Number.isFinite(dropdown.scrollWidth)&&dropdown.scrollWidth>0&&dropdown.scrollWidth<=viewport+1, 'Patient popup cannot cause horizontal page overflow')
  assert.match(dropdown.placement,/^(top|bottom|left|right)(-(start|end))?$/, 'Patient popup placement must be observed')
  assert.equal(dropdown.opacity,1);assert.equal(dropdown.animating,false)
  assertReadableBox(name,viewport,`Full patient name for #${id}`)
  assertReadableBox(identity,viewport,`Patient option #${id}`)
  assert.equal(name.text,item.duplicateName, 'Both patients retain the exact full duplicate name')
  assert.equal(identity.text,`· ${edition==='cn'?'档案':'Record'} #${id}`, 'The full localized record label is untruncated')
  assert.equal(identity.whiteSpace,'nowrap', 'The patient record ID remains unbroken')
  assert.equal(new Set(identity.textBoxes.map(box=>Math.round(box.top))).size,1, 'The patient record ID occupies one line')
  for(const box of [name,identity])assert.ok(box.left>=dropdown.left-1&&box.right<=dropdown.right+1&&box.top>=dropdown.top-1&&box.bottom<=dropdown.bottom+1, 'Patient name and record ID must be inside the measured popup')
  const coreOption=item.options.find(option=>option.id===id)
  assert.ok(coreOption, 'The popup identity must also be recorded in item 4')
  const {id:recordId,...coreIdentity}=coreOption
  assert.deepEqual(identity,coreIdentity, 'Popup and item 4 must contain the same observed identity geometry')
 }
}
