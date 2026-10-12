// Fabricated guard-unit-test inputs only. Never written to browser evidence.
export function visualMeasurement(group,id,viewport=375,edition='en'){
 const box=(text='Synthetic text',left=10,top=10,width=100,height=44)=>({text,left,right:left+width,top,bottom:top+height,width,height,visible:true,textBoxes:[{left:left+2,right:left+width-2,top:top+2,bottom:top+20}],clips:[]})
 const mobile=viewport<=768
 if(group==='monitoring'&&id===1)return {presentation:mobile?'record-cards':'desktop-table',records:Array.from({length:mobile?21:3},(_,i)=>({id:101+i,fields:[box('2026-10-11 07:30'),box('BP'),box('120/80 mmHg')],edit:box('Edit')}))}
 if(group==='monitoring'&&id===2)return {monitoring:[{value:140,...box('Reference 140 mmHg')}],bp:[140,6.1,7.8].map(value=>({value,...box(`Reference ${value} ${value===140?'mmHg':'mmol/L'}`)}))}
 if(group==='monitoring'&&id===7){const top=mobile?120:10;return {layout:mobile?'stacked-flow':'independent-columns',primary:box('',10,top,150,350),trend:box('',10,top,150,100),lastRow:box('',10,top+50,150,40),tasks:box('',10,top+110,150,100),events:box('',10,top+220,150,100),alerts:mobile?box('',10,10,150,100):box('',180,10,150,600),taskGap:20,eventGap:10}}
 if(group==='monitoring'&&id===8)return {layout:mobile?'mobile-2x2':'wide-summary',summary:box('',10,10,220,170),cards:[box('A',10,10,100,80),box('B',120,10,100,80),box('C',10,100,100,80),box('D',120,100,100,80)],navigation:mobile?['monitoring-alerts','monitoring-trends','monitoring-tasks'].flatMap(id=>['click','keyboard'].map(action=>({action,link:box('Navigate'),target:{id,tag:'H2',text:'Synthetic section',...box('Synthetic section'),focused:true,unobscured:true,viewportHeight:900,scrollY:100}}))):[]}
 if(group==='shell'&&id===3){const labels=Array.from({length:4},()=>({...box('Label'),content:mobile?box('',10,60,150,44):box('',120,10,150,44),required:true,marker:'"*"',markerWidth:2,markerMargin:0,lineHeight:24}));return {export:{surface:'data-export',layout:mobile?'labels-above-controls':'adaptive-label-columns',labels},report:{surface:'report',layout:mobile?'labels-above-controls':'adaptive-label-columns',labels:structuredClone(labels)}}}
 if(group==='shell'&&id===4){const measurement={duplicateName:'Synthetic Same Name Alexandra Catherine Montgomery-Sutherland',options:[99011,99012].map((id,index)=>({id,...box(`· ${edition==='cn'?'档案':'Record'} #${id}`,viewport-132,100+index*44,108,44),whiteSpace:'nowrap'})),switches:[99012,99011].map(patientId=>({patientId,viewport:{width:viewport,height:900},patientContext:box('',10,0,150,64),topbar:box('',0,0,viewport,64),messages:[{patientSwitch:true,text:`Synthetic Same Name Alexandra Catherine Montgomery-Sutherland #${patientId}`,left:10,right:viewport-10,top:80,bottom:150,opacity:1,contentLeft:20,contentRight:viewport-20,contentScrollWidth:100,contentClientWidth:100}],identity:box(`#${patientId}`)}))};measurement.allPatientsTransition={allPatientsHeader:{bottom:64,height:64},selected:structuredClone(measurement.switches[0])};measurement.rapidSwitchFinal=structuredClone(measurement.switches[1]);return measurement}
 if(group==='shell'&&id===9)return {secondary:{...box('Mode'),background:'rgb(255, 255, 255)'},primary:{...box('Generate'),background:'rgb(0, 100, 50)'},activation:['keyboard-expand','keyboard-collapse']}
 throw Error(`No fixture for ${group}/${id}`)
}

// Fabricated collector guard fixtures only; never used as browser evidence.
export function patientOptionBounds(viewport, measurement) {
 return measurement.options.map(option=>({id:option.id,identity:structuredClone(Object.fromEntries(Object.entries(option).filter(([key])=>key!=='id'))),
  name:{text:measurement.duplicateName,visible:true,left:24,right:viewport-136,top:option.top,bottom:option.bottom,width:viewport-160,height:44,
   textBoxes:[{left:24,right:viewport-136,top:option.top+2,bottom:option.top+20}],clips:[]},
  dropdown:{left:12,right:viewport-12,top:90,bottom:210,width:viewport-24,height:120,placement:'bottom-start',opacity:1,animating:false,scrollWidth:viewport,viewport}}))
}

// Fabricated chart guard inputs only; actual runs use ECharts display-list bounds.
export function chartTextGeometry(edition) {
 const box=(text,left,top,width,height=12)=>({text,left,top,right:left+width,bottom:top+height,width,height,spans:text.split('\n').map((text,index)=>({text,left,top:top+index*12,right:left+width,bottom:top+(index+1)*12,width,height:12}))})
 const series=edition==='cn'?[['收缩压','舒张压'],['血糖']]:[['Systolic Pressure','Diastolic Pressure'],['Blood Glucose']]
 return series.map(seriesNames=>({width:214,height:320,seriesNames,endpoints:['09-13 08:00','10-11 07:30'],
  ticks:[box('09-13\n08:00',42,240,32,24),box('10-11\n07:30',166,240,32,24)],
  legend:seriesNames.map((name,index)=>box(name,65,280+index*22,100))}))
}
