import { captureAuthSession, isAuthSessionCurrent } from '../src/utils/authSession.js'
import { useFocusedCareSource } from '../src/composables/useFocusedCareSource.js'
import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { parse } from '@vue/compiler-dom'
import { ref, reactive, computed, watch, nextTick, effectScope } from 'vue'
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const ok = data => ({ code: 200, data })
function setup(t, api = {}, initialRoute = {}) {
  const source = fs.readFileSync(new URL('../src/views/MedicalRecordManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const currentPatientId = ref(1), scope = effectScope(), unmounts = [], errors = [], infos = [], warnings = []
  const confirmations = []
  const route = reactive({ query: {}, path: '/medical-record', ...initialRoute }), mounted=[]
  const deps = { captureAuthSession, isAuthSessionCurrent, useFocusedCareSource, ref, reactive, computed, watch, inject: (_key, fallback) => fallback,
    onMounted:fn=>mounted.push(fn), onUnmounted: fn => unmounts.push(fn), use() {}, CanvasRenderer: {}, EchartsLineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {},
    useCurrentPatient: () => ({ currentPatientId }), useTableColumns: () => ({}), useMobile: () => ({ isMobile: ref(false) }), useRoute: () => route, useRouter: () => ({ push: async () => {} }),
    localDateKey: () => '2026-10-02', readPermissionCache: () => ({}), canAccessWorkspace: () => true, dedupeRecognizedItems: value => value,
    isImageFile: () => false, compressImageFile: async value => value, formatFileSize: String,
    ElMessageBox: { confirm: async (...args) => { confirmations.push(args); throw 'cancel' } },
    ElMessage: { error: value => errors.push(value), info: value => infos.push(value), warning: value => warnings.push(value) }, window: { addEventListener() {}, removeEventListener() {} }, URL: { revokeObjectURL() {} },
    getPatientNames: async () => ok([]),
    api: { listRecords: async () => ok([]), getAllItemNames: async () => ok([]), getItemTrend: async () => ok([]), ...api }
  }
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn { filterForm, trendForm, trendData, trendLoading, loadTrend, allItemNames, loadItemNames, currentRecord, detailDialogVisible, viewDetail, sourceFocus, focusedMode, records, loadRecords, trendChartOption, getAbnormalText, getRecordTypeName, dedupeRecognizedItemsLocal, deleteRecord, archiveForm, resetUploadState, recognizedData }')(...Object.values(deps)))
  const leave = () => { unmounts.splice(0).forEach(fn => fn()); scope.stop() }
  t.after(leave)
  return { ...view, currentPatientId, errors, infos, warnings, confirmations, source, leave, route, start(){mounted.forEach(fn=>fn())} }
}


const locale = 'zh'
const expectedItems = [
  '白细胞', '红细胞', '血红蛋白', '血小板', '肌酐', '尿素氮', '尿酸',
  '丙氨酸氨基转移酶', '天冬氨酸氨基转移酶', '总胆红素', '血钙', '血磷', '甲状旁腺激素',
  '铁蛋白', '转铁蛋白饱和度', '钾', '钠', '氯', '二氧化碳结合力', '白蛋白',
  '总胆固醇', '甘油三酯', '血糖', '糖化血红蛋白', 'PTH', 'β2微球蛋白', 'C反应蛋白', '铁'
]
const labels = {
  status: ['偏高', '偏低', '正常'], types: ['肝功能', '肾功能', '影像报告'], measured: '检测值',
  noDuplicates: '当前没有可合并的重复项目', noTrend: '未找到该指标的历史记录', delete: '确认删除这条医疗记录吗？',
  controls: ['合并重复项', '新增一行', '全部患者', '肝功能', '肾功能', '影像报告', '选择日期', '患者姓名', '请输入患者姓名', '附件图片']
}

// These assertions exercise the production list/label functions. Restoring the
// original untranslated defaults, garbled labels, or wrong collation fails them.
test('test-item suggestions use readable locale defaults and preserve stored clinical names', async t => {
  const stored = ['Synthetic original α result', '患者原始检验', 'Synthetic original α result']
  const view = setup(t, { getAllItemNames: async () => ok(stored) })
  await view.loadItemNames()
  assert.deepEqual(view.allItemNames.value, [...new Set([...stored, ...expectedItems])].sort((a, b) => a.localeCompare(b, locale)))
})
test('test-item suggestions sort using the page locale', async t => {
  const stored = ['阿项', '波项', '中项', 'English item']
  const view = setup(t, { getAllItemNames: async () => ok(stored) })
  await view.loadItemNames()
  assert.deepEqual(view.allItemNames.value, [...view.allItemNames.value].sort((a, b) => a.localeCompare(b, locale)))
})
test('medical result badges display localized high, low, and normal labels', t => {
  const view = setup(t)
  assert.deepEqual([1, -1, 0].map(view.getAbnormalText), labels.status)
})
test('record-type labels are readable in the page language', t => {
  const view = setup(t)
  assert.deepEqual(['LIVER', 'KIDNEY', 'IMAGE'].map(view.getRecordTypeName), labels.types)
  assert.equal(view.getRecordTypeName('CUSTOM_TYPE'), 'CUSTOM_TYPE')
})
test('trend chart fallback label is localized and selected clinical names are preserved', t => {
  const view = setup(t)
  assert.equal(view.trendChartOption.value.series[0].name, labels.measured)
  view.trendForm.itemName = 'Original 源报告 item'
  assert.equal(view.trendChartOption.value.series[0].name, 'Original 源报告 item')
})
test('duplicate merge feedback uses readable localized text', t => {
  const view = setup(t)
  view.dedupeRecognizedItemsLocal()
  assert.deepEqual(view.infos, [labels.noDuplicates])
})
test('empty trend feedback uses readable localized text', async t => {
  const view = setup(t)
  view.trendForm.itemName = 'Synthetic item'
  await view.loadTrend()
  assert.deepEqual(view.infos, [labels.noTrend])
})
test('delete confirmation uses readable localized text', async t => {
  const view = setup(t)
  await view.deleteRecord({ id: 1 })
  assert.equal(view.confirmations[0][0], labels.delete)
})
function visibleCopy(source) {
  const template = source.match(/<template>([\s\S]*?)<\/template>\s*<script setup>/)[1]
  const texts = []
  function walk(node) {
    if (node.type === 2 && node.content.trim()) texts.push(node.content.trim())
    for (const prop of node.props || []) {
      if (prop.type === 6 && ['label', 'placeholder', 'title', 'alt', 'aria-label'].includes(prop.name) && prop.value?.content) texts.push(prop.value.content)
    }
    for (const child of node.children || []) walk(child)
  }
  walk(parse(template))
  return texts
}
test('medical page controls and help text contain readable localized copy', t => {
  const view = setup(t)
  const copy = visibleCopy(view.source)
  for (const label of labels.controls) assert.ok(copy.includes(label), `Missing visible copy: ${label}`)
  const visible = copy.join('\n')
  assert.doesNotMatch(visible, /mergeduplicateitem|Addonerow|AllPatient|liverfeature|kidneyfeature|imagingReport|selectDate|PatientName|positivein|recognitionresult|Attachmentimage|UploadnewAttachment|libraryselect|photoUpload/)
})
