import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {parse} from '@babel/parser';
import {parse as parseHtml} from '@vue/compiler-dom';

const expected=JSON.parse(readFileSync(new URL('./fixtures/clinicalInterfaceCopy.json',import.meta.url),'utf8'));
for(const [view,phrases] of Object.entries(expected)) {
  test(`English ${view}: readable labels, help, errors and chart copy`,()=>{
    const source=readFileSync(new URL(`../src/views/${view}.vue`,import.meta.url),'utf8');
    for(const phrase of phrases)assert.ok(source.includes(phrase),`${view}: missing ${phrase}`);
  });
}
const read=(edition,view)=>readFileSync(new URL(`${edition==='en'?'../':'../../cn/frontend/'}src/views/${view}.vue`,import.meta.url),'utf8');
function loadFunction(source,name) {
  const script=source.match(/<script setup>([\s\S]*?)<\/script>/)[1];
  const definition=parse(script,{sourceType:'module'}).program.body.find(node=>node.type==='FunctionDeclaration'&&node.id.name===name);
  assert.ok(definition,`${name} is a presentation helper`);
  // Identity sanitizer lets these tests inspect the formatter's own escaping boundary.
  // The companion browser test exercises the real DOMPurify implementation.
  return new Function('DOMPurify',`${script.slice(definition.start,definition.end)};return ${name}`)({sanitize:html=>html});
}
for(const edition of ['en','zh']) {
  test(`${edition}: dialysis narrative recognizes both locale headings and the legacy marker without changing source text`,()=>{
    const source=read(edition,'DialysisManager'), format=loadFunction(source,'formatAiResult');
    for(const marker of ['【Dry weight adjustment conclusion】','【干体重调整结论】','【Dry Weightadjustment conclusion】']) {
      const input=`Original clinical narrative 原始记录\n${marker}\nDose: metformin 500 mg\nPatient wording: 原话 unchanged`;
      const html=format(input);
      assert.ok(html.includes('class="ai-dw-conclusion"'),marker);
      assert.ok(html.includes('metformin 500 mg'));
      assert.ok(html.includes('原话 unchanged'));
      assert.ok(html.includes(marker),'Keep the original narrative heading');
    }
  });
  test(`${edition}: dialysis adjustment tags translate known booleans and preserve unfamiliar clinical values`,()=>{
    const source=read(edition,'DialysisManager');
    const label=loadFunction(source,'dwAdjustmentLabel'),tone=loadFunction(source,'dwAdjustmentTagType');
    for(const value of ['Yes','是']) {assert.equal(label(value),edition==='en'?'Adjustment recommended':'建议调整');assert.equal(tone(value),'warning');}
    for(const value of ['No','否']) {assert.equal(label(value),edition==='en'?'No adjustment needed':'无需调整');assert.equal(tone(value),'success');}
    assert.equal(label('Clinician review pending 待医生判断'),'Clinician review pending 待医生判断');
    assert.equal(tone('Clinician review pending 待医生判断'),'info');
    assert.ok(source.includes('{{ dwAdjustmentLabel(item.dwAdjustNeeded) }}'));
    assert.ok(source.includes(':type="dwAdjustmentTagType(item.dwAdjustNeeded)"'));
  });
  test(`${edition}: AI source text is escaped before final allowlisted sanitization`,()=>{
    const source=read(edition,'DialysisManager'),format=loadFunction(source,'formatAiResult');
    const text='<img src=x onerror="alert(1)"><script>alert(2)</script>\n【干体重调整结论】\nDose: <svg onload="alert(3)">metformin 500 mg</svg>';
    const html=format(text),tree=parseHtml(html);
    const visit=node=>{
      if(node.type===1){assert.ok(['br','strong','div','span','p'].includes(node.tag),node.tag);for(const prop of node.props)assert.equal(prop.name,'class');}
      for(const child of node.children||[])visit(child);
    };
    visit(tree);
    assert.ok(html.includes('&lt;img'));
    assert.ok(html.includes('metformin 500 mg'));
    assert.match(source,/import DOMPurify from 'dompurify'/);
    assert.match(source,/return DOMPurify\.sanitize\(html,\s*\{\s*ALLOWED_TAGS:/);
  });
  test(`${edition}: narrative field labels support Chinese punctuation and keep multiword source labels`,()=>{
    const format=loadFunction(read(edition,'DialysisManager'),'formatAiResult');
    const html=format('【干体重调整结论】\nSource wording: 原始文字\n调整幅度：+0.5 kg');
    assert.ok(html.includes('class="ai-dw-key">Source wording</span>'));
    assert.ok(html.includes('class="ai-dw-key">调整幅度</span>'));
    assert.ok(html.includes('class="ai-dw-val highlight">+0.5 kg</span>'));
  });
}
test('Chinese daily weight gain units follow the page language in charts, previews and report images',()=>{
  for(const view of ['DialysisManager','HealthReportManager']) {
    const source=read('zh',view);
    assert.ok(source.includes('kg/天'));
    assert.ok(!source.includes('kg/days'));
  }
});
test('Chinese dialysis all-history scope uses a Chinese display label',()=>{
  const source=read('zh','DialysisManager');
  assert.ok(source.includes("listFilterOverride.value === 'Allhistory' ? '全部历史'"));
  assert.ok(source.includes("listFilterOverride.value = 'Allhistory'"),'preserve the existing filter state key');
});
test('export and report display corrections preserve permission aliases and request values',()=>{
  for(const view of ['DataExportManager','HealthReportManager']) {
    const source=read('en',view);
    assert.ok(source.includes("hasMenuName('Blood GlucoseBlood Pressurerecord')"));
    assert.match(source,/value: '(?:bp_self_monitor|bp_monitor)'/);
  }
});
