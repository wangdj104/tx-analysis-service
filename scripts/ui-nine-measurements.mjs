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
