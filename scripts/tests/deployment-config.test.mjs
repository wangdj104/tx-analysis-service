import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const root = new URL('../../', import.meta.url)
const read = path => readFileSync(new URL(path, root), 'utf8')

// Inspect the API environment specifically: a top-level .env value or an entry
// on another service does not make it available to Spring inside the container.
function apiEnvironment(path) {
  const lines = read(path).split(/\r?\n/)
  const apiStart = lines.findIndex(line => line === '  api:')
  assert.ok(apiStart >= 0, `${path}: API service must exist`)
  const apiEnd = lines.findIndex((line, index) => index > apiStart && /^  \S/.test(line))
  const api = lines.slice(apiStart + 1, apiEnd < 0 ? undefined : apiEnd)
  const start = api.findIndex(line => line === '    environment:')
  assert.ok(start >= 0, `${path}: API environment must exist`)
  const end = api.findIndex((line, index) => index > start && /^    \S/.test(line))
  const entries = api.slice(start + 1, end < 0 ? undefined : end)
    .filter(line => /^      [A-Z_]+: /.test(line))
    .map(line => line.trim().split(/: (.*)/s).slice(0, 2))
  assert.equal(new Set(entries.map(([key]) => key)).size, entries.length, 'No duplicate API variables')
  return Object.fromEntries(entries)
}

for (const path of ['docker-compose.yml', 'cn/docker-compose.yml', 'docker-compose.production.yml']) {
  test(`${path}: explicit care-plan opt-in reaches the API and defaults off`, () => {
    assert.equal(apiEnvironment(path).CARE_PLAN_ENABLED, '${CARE_PLAN_ENABLED:-false}')
  })
  test(`${path}: custom PDF font path reaches the API without forcing an override`, () => {
    assert.equal(apiEnvironment(path).REPORT_PDF_FONT_PATH, '${REPORT_PDF_FONT_PATH:-}')
  })
}

for (const path of ['.env.example', 'cn/.env.example', '.env.production.example']) {
  test(`${path}: care-plan feature remains explicitly opt-in`, () => {
    assert.match(read(path), /^CARE_PLAN_ENABLED=false$/m)
  })
  test(`${path}: optional PDF font override is discoverable and empty`, () => {
    assert.match(read(path), /^REPORT_PDF_FONT_PATH=$/m)
  })
}

for (const prefix of ['', 'cn/']) {
  test(`${prefix || 'root/'}: backend still defaults collaboration off`, () => {
    assert.match(read(`${prefix}src/main/resources/application.yml`), /care-plan:\s*\n\s+enabled: \$\{CARE_PLAN_ENABLED:false\}/)
  })
}

test('production template verifies the database TLS identity', () => {
  const url = read('.env.production.example').match(/^DB_URL=(.+)$/m)?.[1]
  assert.ok(url, 'Production database URL must be documented')
  const parameters = new URLSearchParams(url.split('?')[1])
  assert.equal(parameters.get('sslMode'), 'VERIFY_IDENTITY')
  assert.notEqual(parameters.get('useSSL'), 'false')
  assert.equal(parameters.get('allowPublicKeyRetrieval'), 'false')
})

test('production template uses a replaceable HTTPS origin', () => {
  const origin = read('.env.production.example').match(/^CORS_ALLOWED_ORIGINS=(.+)$/m)?.[1]
  assert.equal(origin, 'https://health.example.com')
})

test('production template keeps its HTTP upstream on loopback', () => {
  assert.match(read('.env.production.example'), /^WEB_PORT=127\.0\.0\.1:8080$/m)
  assert.match(read('docker-compose.production.yml'), /"\$\{WEB_PORT:-8080\}:80"/)
})
