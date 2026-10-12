import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const workflow = readFileSync('.github/workflows/ci.yml', 'utf8')
const job = workflow.split('  ui-seventeen-browser:')[1]?.split('\n  care-plan-mysql:')[0] || ''
const steps = job.split(/^      - /m).slice(1)
const root = '${{ runner.temp }}/ui-seventeen/'
const sha = '${{ github.sha }}'
const suites = [
  ['shell', 'uiSeventeenShell', 'shell/**/*.png'],
  ['care', 'careDraftDialogBacklog', 'care/**/*.png'],
  ['monitoring', 'uiSeventeenMonitoring', 'monitoring/**/*.png'],
  ['clinical', 'uiSeventeenClinical', '*-clinical-*.png'],
]
const artifactName = (group, kind) => `ui-seventeen-${group}-${kind}-${sha}`
const findArtifact = name => steps.find(step => step.includes(`          name: ${name}\n`))
function uploadPaths(step) {
  assert.ok(step, 'The expected artifact upload must exist')
  const multiline = step.match(/^          path: \|\n((?:            [^\n]+\n)+)/m)
  return multiline ? multiline[1].trim().split('\n').map(line => line.trim())
    : [step.match(/^          path: ([^\n]+)/m)?.[1]]
}

// Observed run 38204792928 PNG bytes by suite: care 86,187,152;
// monitoring 364,455,602; clinical 50,482,258; shell 54,749,164.
// Even monitoring plus run 38210357456's 19,323,945-byte results JSON
// leaves >145 MiB below the 536,870,912-byte download limit before ZIP overhead.
// The complete run 38210357456 archive was 566,171,383 bytes and cannot
// be fetched through that connector. Keep every image, split the uploads.
test('each UI suite immediately preserves its outcomes then a separate screenshot archive', () => {
  for (const [group, harness] of suites) {
    const browser = steps.findIndex(step => step.includes(`run: node frontend/tests/${harness}.browser.mjs`))
    assert.ok(browser >= 0, `${group} actual-browser command remains mandatory`)
    const outcomes = findArtifact(artifactName(group, 'outcomes'))
    const screenshots = findArtifact(artifactName(group, 'screenshots'))
    assert.ok(screenshots, `${group} needs its own downloadable screenshot archive`)
    assert.equal(steps[browser + 1], outcomes, `${group} outcomes are preserved before PNG upload`)
    assert.equal(steps[browser + 2], screenshots, `${group} screenshots are preserved before another suite`)
    assert.deepEqual(uploadPaths(outcomes), [`${root}${group}-results.json`])
    for (const upload of [outcomes, screenshots]) {
      assert.match(upload, /if: always\(\)\n/)
      assert.match(upload, /uses: actions\/upload-artifact@v4\n/)
      assert.match(upload, /if-no-files-found: error\n/)
      assert.match(upload, /retention-days: 14\n/)
      assert.doesNotMatch(upload, /continue-on-error|overwrite: true|if-no-files-found: (?:warn|ignore)/)
    }
  }
})

test('screenshot uploads retain all four disjoint PNG paths and their evidence-root layout', () => {
  for (const [group, , pattern] of suites) {
    // The root-level report anchors upload-artifact's least common ancestor.
    // Without it, care/, monitoring/ and shell/ would be stripped on download,
    // breaking their original report paths and the strict acceptance collector.
    assert.deepEqual(uploadPaths(findArtifact(artifactName(group, 'screenshots'))), [
      `${root}${pattern}`, `${root}${group}-results.json`,
    ])
  }
  const pngUploads = steps.filter(step => step.includes('uses: actions/upload-artifact@v4') && uploadPaths(step).some(path => path.endsWith('.png')))
  assert.equal(pngUploads.length, 4, 'No missing group, duplicate PNG upload or giant combined screenshot archive')
})

test('the final artifact is JSON-only after the unchanged strict aggregate gate', () => {
  const aggregate = steps.findIndex(step => step.includes('run: node scripts/verify-ui-seventeen-browser.mjs "$UI_SEVENTEEN_EVIDENCE_DIR" "$(git rev-parse HEAD)"'))
  assert.ok(aggregate >= 0, 'The SHA-bound all-suite acceptance collector is retained')
  const reports = findArtifact(`ui-seventeen-${sha}`)
  assert.equal(steps[aggregate + 1], reports)
  assert.match(reports, /^name: Preserve synthetic reports and acceptance summary\n/)
  assert.deepEqual(uploadPaths(reports), [`${root}*-results.json`, `${root}acceptance-summary.json`])
  assert.match(reports, /if: always\(\)\n/)
  assert.match(reports, /if-no-files-found: error\n/)
  assert.match(reports, /retention-days: 14\n/)
  assert.equal(steps.filter(step => step.includes('uses: actions/upload-artifact@v4')).length, 9)
})

test('CI checks the packaging contract before any browser suite and prioritizes shell feedback', () => {
  const firstBrowser = steps.findIndex(step => step.includes('run: node frontend/tests/'))
  assert.ok(firstBrowser > 0)
  assert.match(steps[firstBrowser - 1], /node --test .*scripts\/tests\/ui-seventeen-browser-artifacts\.test\.mjs/)
  assert.deepEqual(steps.flatMap(step => {
    const match = step.match(/run: node frontend\/tests\/(\w+)\.browser\.mjs/)
    if (!match) return []
    assert.match(step, /if: \$\{\{ !cancelled\(\) \}\}/)
    return [match[1]]
  }), suites.map(([, harness]) => harness))
  assert.doesNotMatch(job, /continue-on-error|\|\|\s*true/)
})
