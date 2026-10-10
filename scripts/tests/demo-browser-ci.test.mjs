import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8')
const demoJob = workflow.split('  static-demo:')[1]?.split('  care-plan-mysql:')[0] || ''

test('static demo CI runs real bilingual desktop and mobile browser checks', () => {
  assert.match(demoJob, /working-directory: frontend[\s\S]*npm ci[\s\S]*playwright install --with-deps chromium/)
  assert.match(demoJob, /node demo\/tests\/carePlanCollaboration\.browser\.mjs/)
  assert.match(demoJob, /node demo\/tests\/executionReport\.browser\.mjs/)
  assert.match(demoJob, /node frontend\/tests\/dialysisHtmlSafety\.browser\.mjs/)
  assert.match(demoJob, /apt-get install --yes --no-install-recommends fonts-wqy-microhei/)
  assert.doesNotMatch(demoJob, /continue-on-error|\|\|\s*true/)
})

test('static demo CI publishes only synthetic browser screenshots for review', () => {
  assert.match(demoJob, /DEMO_QA_OUTPUT: \$\{\{ runner\.temp \}\}\/demo-language-qa/)
  assert.match(demoJob, /uses: actions\/upload-artifact@v4/)
  assert.match(demoJob, /path: \$\{\{ runner\.temp \}\}\/demo-language-qa\/\*\.png/)
  assert.match(demoJob, /retention-days: 7/)
})
