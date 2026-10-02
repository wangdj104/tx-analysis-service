import assert from 'node:assert/strict'
import test from 'node:test'
import config from '../vite.config.js'

const chunks = config({ mode: 'test' }).build.rollupOptions.output.manualChunks

test('report image capture has its own deferred chunk', () => {
  assert.equal(chunks('/project/node_modules/html2canvas/dist/html2canvas.esm.js'), 'report-capture')
})
test('existing framework, chart and ordinary vendor chunks remain unchanged', () => {
  for (const [id, expected] of [
    ['echarts/lib/core/echarts.js', 'charts'], ['zrender/lib/core/PathProxy.js', 'charts'],
    ['element-plus/es/index.mjs', 'element-plus'], ['@element-plus/icons-vue/dist/index.js', 'element-plus'],
    ['vue/dist/vue.runtime.esm-bundler.js', 'vue-vendor'], ['vue-router/dist/vue-router.mjs', 'vue-vendor'],
    ['axios/index.js', 'vendor'], ['html2canvas-extension/index.js', 'vendor']
  ]) assert.equal(chunks('/project/node_modules/' + id), expected, id)
  assert.equal(chunks('/project/src/views/DialysisManager.vue'), undefined)
})
