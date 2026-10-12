import test from 'node:test'
import assert from 'node:assert/strict'
import {assertNineVisualMeasurement as verify} from '../ui-nine-measurements.mjs'
import {visualMeasurement as fixture} from './fixtures/nine-visual-measurements.mjs'
for(const [group,ids] of Object.entries({monitoring:[1,2,7,8],shell:[3,4,9]}))for(const id of ids){
 test(`${group}/${id} accepts complete measured shape at all five widths`,()=>{for(const width of [320,375,390,768,1365])verify(group,id,fixture(group,id,width),width)})
 test(`${group}/${id} rejects generic unrelated objects`,()=>assert.throws(()=>verify(group,id,{viewport:375},375)))
}
const mutations={
 'monitoring/1':[m=>m.records.pop(),m=>m.records[0].fields[0].right=1000,m=>m.records[0].edit.height=43,m=>m.records[1].id=m.records[0].id],
 'monitoring/2':[m=>m.bp.pop(),m=>m.bp[1].value=7,m=>m.bp[0].textBoxes[0].right=1000],
 'monitoring/7':[m=>m.taskGap=49,m=>m.tasks.top=m.alerts.bottom+500,m=>m.events.left=60],
 'monitoring/8':[m=>m.summary.height=221,m=>m.cards[1].top=120,m=>m.navigation[0].target.focused=false,m=>m.navigation.pop()],
 'shell/3':[m=>m.export.labels=[],m=>m.report.labels[0].marker='none',m=>m.export.labels[0].content.top=0],
 'shell/4':[m=>delete m.allPatientsTransition,m=>delete m.rapidSwitchFinal,m=>m.options.pop(),m=>m.switches[0].identity.text='#wrong',m=>m.switches[0].messages[0].top=5,m=>m.switches[0].messages.push(m.switches[0].messages[0])],
 'shell/9':[m=>m.primary.height=43,m=>m.primary.background=m.secondary.background,m=>m.activation=[]],
}
for(const [key,mutateAll] of Object.entries(mutations))test(`${key} rejects corrupted actual geometry and behavior`,()=>{const[group,raw]=key.split('/'),id=Number(raw);for(const mutate of mutateAll){const m=fixture(group,id);mutate(m);assert.throws(()=>verify(group,id,m,375))}})
