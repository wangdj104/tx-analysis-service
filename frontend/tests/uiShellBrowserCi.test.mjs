import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const harness=readFileSync(new URL('./uiSeventeenShell.browser.mjs',import.meta.url),'utf8')
test('shell browser uses compiled preview with no development-client socket exception',()=>{
 assert.match(harness,/await build\(/)
 assert.match(harness,/await preview\(/)
 assert.doesNotMatch(harness,/createServer\(/)
 assert.match(harness,/state\.violations\.push\(`Blocked external WebSocket/)
})
test('theme verification waits for exact rendered color rather than reading before repaint',()=>{
 assert.match(harness,/await expect\.poll\(.*export-scope.*backgroundColor[^\n]*\)\.toBe\(expectedScopeBackground\)/)
})
test('privacy toggling targets visible Element Plus label and verifies actual native state',()=>{
 assert.match(harness,/await privacy\.locator\('\.el-checkbox__label'\)\.click\(\)/)
 assert.match(harness,/await expect\(privacy\.getByRole\('checkbox'\)\)\.not\.toBeChecked\(\)/)
 assert.doesNotMatch(harness,/getByRole\('checkbox'\)\.uncheck\(/)
})
for(const edition of ['../../frontend','../../cn/frontend'])test(`${edition}: portable privacy text wraps instead of clipping on narrow screens`,()=>{
 const source=readFileSync(new URL(`${edition}/src/views/DataExportManager.vue`,import.meta.url),'utf8')
 assert.match(source,/el-checkbox class="export-privacy"/)
 assert.match(source,/export-privacy \.el-checkbox__label\)[^}]*white-space:\s*normal[^}]*overflow-wrap:\s*anywhere/)
})
