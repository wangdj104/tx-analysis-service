import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const script = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../verify-care-plan-mysql.sh')
const valid = {
  PATH: process.env.PATH, HOME: '/tmp', CARE_PLAN_TEST_ONLY: 'true',
  CARE_PLAN_MYSQL_HOST: '127.0.0.1', CARE_PLAN_MYSQL_PORT: '13306',
  CARE_PLAN_MYSQL_DATABASE: 'care_plan_test_en_123_fresh',
  CARE_PLAN_MYSQL_UPGRADE_DATABASE: 'care_plan_test_en_123_upgrade',
  CARE_PLAN_MYSQL_RESTORE_DATABASE: 'care_plan_test_en_123_restore',
  CARE_PLAN_MYSQL_USER: 'care_plan_test_en_123', CARE_PLAN_MYSQL_PASSWORD: 'SyntheticOnly0123456789abc',
  CARE_PLAN_MYSQL_CONTAINER_ID: '1234567890abcdef1234567890abcdef', CARE_PLAN_MYSQL_PROJECT: '.',
  CI: 'true', GITHUB_ACTIONS: 'true'
}
function run(overrides = {}, args = ['--check-guards']) {
  const env = { ...valid, ...overrides }
  Object.keys(env).filter(key => env[key] === undefined).forEach(key => delete env[key])
  return spawnSync('bash', [script, ...args], { env, encoding: 'utf8' })
}
// These tests catch guard removals before any Docker/JDBC/DDL dependency is reached.
for (const [label, overrides, expected] of [
  ['missing test flag', { CARE_PLAN_TEST_ONLY: undefined }, 'CARE_PLAN_TEST_ONLY'],
  ['false test flag', { CARE_PLAN_TEST_ONLY: 'false' }, 'CARE_PLAN_TEST_ONLY'],
  ['production host', { CARE_PLAN_MYSQL_HOST: 'db.example.org' }, 'host'],
  ['IPv4 host suffix', { CARE_PLAN_MYSQL_HOST: '127.0.0.1.attacker.test' }, 'host'],
  ['service host outside CI', { CARE_PLAN_MYSQL_HOST: 'mysql', CI: 'false' }, 'host'],
  ['port missing', { CARE_PLAN_MYSQL_PORT: undefined }, 'port'],
  ['port injection', { CARE_PLAN_MYSQL_PORT: '3306?db=production' }, 'port'],
  ['port outside range', { CARE_PLAN_MYSQL_PORT: '65536' }, 'port'],
  ['non-test database', { CARE_PLAN_MYSQL_DATABASE: 'family_health' }, 'database'],
  ['SQL database injection', { CARE_PLAN_MYSQL_UPGRADE_DATABASE: 'care_plan_test_x`;DROP DATABASE mysql;--' }, 'database'],
  ['overlength database', { CARE_PLAN_MYSQL_RESTORE_DATABASE: 'care_plan_test_' + 'x'.repeat(60) }, 'database'],
  ['restore equals source', { CARE_PLAN_MYSQL_RESTORE_DATABASE: valid.CARE_PLAN_MYSQL_UPGRADE_DATABASE }, 'distinct'],
  ['fresh equals upgrade', { CARE_PLAN_MYSQL_UPGRADE_DATABASE: valid.CARE_PLAN_MYSQL_DATABASE }, 'distinct'],
  ['root TCP user', { CARE_PLAN_MYSQL_USER: 'root' }, 'user'],
  ['blank password', { CARE_PLAN_MYSQL_PASSWORD: '' }, 'password'],
  ['password SQL injection', { CARE_PLAN_MYSQL_PASSWORD: "x' OR 1=1;" }, 'password'],
  ['container name instead of ID', { CARE_PLAN_MYSQL_CONTAINER_ID: 'production-mysql' }, 'container'],
  ['wrong project', { CARE_PLAN_MYSQL_PROJECT: '../../production' }, 'project']
]) {
  test(`rejects ${label} before external operations`, () => {
    const result = run(overrides)
    assert.equal(result.status, 64, result.stderr)
    assert.match(result.stderr, new RegExp(expected, 'i'))
    assert.doesNotMatch(result.stdout + result.stderr, /SyntheticOnly0123456789abc/)
  })
}
test('valid guard-only run requires no installed MySQL or Docker', () => {
  const result = run()
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /guards passed/)
  assert.doesNotMatch(result.stdout + result.stderr, /SyntheticOnly0123456789abc/)
})
test('execution is refused outside a disposable GitHub service job', () => {
  const result = run({ GITHUB_ACTIONS: 'false', CI: 'false' }, [])
  assert.equal(result.status, 64, result.stderr)
  assert.match(result.stderr, /GitHub/)
})
test('arguments cannot switch to an arbitrary database or bypass guards', () => {
  const result = run({}, ['--database=family_health'])
  assert.equal(result.status, 64, result.stderr)
})

import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import os from 'node:os'
// Controlled external command boundary for cleanup tests. The actual shell
// wrapper executes unchanged; this is NOT native MySQL acceptance evidence.
function syntheticService(initialDatabases, action, options = {}) {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'care-plan-mysql-guards-'))
  const stateFile = path.join(directory, 'state.json')
  writeFileSync(stateFile, JSON.stringify({ databases: initialDatabases, users: [], grants: [] }))
  writeFileSync(path.join(directory, 'docker'), `#!/usr/bin/env node
const fs = require('node:fs')
const args = process.argv.slice(2)
if (args[0] === 'inspect') {
  const format = args[args.indexOf('--format') + 1]
  console.log(format === '{{.Config.Image}}' ? 'mysql:8.0' : format === '{{.Image}}' ? 'sha256:synthetic-only' : process.env.CARE_PLAN_MYSQL_TEST_SERVICE_ENV)
  process.exit(0)
}
if (!args.includes('mysql') || !args.includes('--protocol=socket') || !args.includes('--user=root')) process.exit(90)
const sql = fs.readFileSync(0, 'utf8')
if (sql.startsWith('SELECT CURRENT_USER()')) { console.log('root@localhost\\t8.0.99-synthetic'); process.exit(0) }
if (sql.includes("FROM mysql.user WHERE User='root'")) { console.log(process.env.CARE_PLAN_MYSQL_TEST_NONLOCAL_ROOTS); process.exit(0) }
if (sql.includes('@@GLOBAL.partial_revokes')) { console.log(process.env.CARE_PLAN_MYSQL_TEST_PARTIAL_REVOKES); process.exit(0) }
const file = process.env.CARE_PLAN_MYSQL_TEST_STATE
const state = JSON.parse(fs.readFileSync(file, 'utf8'))
let match
if ((match = /^CREATE DATABASE \x60([^\x60]+)\x60/.exec(sql))) {
  if (state.databases.includes(match[1])) process.exit(1)
  state.databases.push(match[1])
} else if ((match = /^DROP DATABASE \x60([^\x60]+)\x60/.exec(sql))) {
  if (!state.databases.includes(match[1])) process.exit(91)
  state.databases = state.databases.filter(x => x !== match[1])
} else if ((match = /^CREATE USER '([^']+)'/.exec(sql))) {
  if (state.users.includes(match[1])) process.exit(1)
  state.users.push(match[1])
} else if ((match = /^DROP USER '([^']+)'/.exec(sql))) {
  if (!state.users.includes(match[1])) process.exit(92)
  state.users = state.users.filter(x => x !== match[1])
  state.grants = []
} else if (sql.startsWith('GRANT ALL PRIVILEGES')) {
  const pattern = sql.split('\x60')[1]
  state.grants.push(pattern)
} else process.exit(93)
fs.writeFileSync(file, JSON.stringify(state))
`, { mode: 0o700 })
  writeFileSync(path.join(directory, 'mvn'), `#!/usr/bin/env node
const fs = require('node:fs')
const state = JSON.parse(fs.readFileSync(process.env.CARE_PLAN_MYSQL_TEST_STATE, 'utf8'))
const denied = process.env.CARE_PLAN_MYSQL_TEST_DENIED_DB
if (denied) {
  function matches(pattern, value) {
    let regex = '^'
    for (let i=0; i<pattern.length; i++) {
      let character = pattern[i]
      if (character === String.fromCharCode(92)) { character = pattern[++i]; regex += character }
      else regex += character === '_' ? '.' : character === '%' ? '.*' : character
    }
    return new RegExp(regex + '$').test(value)
  }
  if (state.grants.some(pattern => matches(pattern, denied))) process.exit(94)
}
process.exit(42)
`, { mode: 0o700 })
  try {
    const result = run({ PATH: `${directory}:${valid.PATH}`, CARE_PLAN_MYSQL_TEST_STATE: stateFile,
      CARE_PLAN_MYSQL_TEST_SERVICE_ENV: options.serviceEnv ?? 'MYSQL_ALLOW_EMPTY_PASSWORD=yes\nMYSQL_ROOT_HOST=localhost',
      CARE_PLAN_MYSQL_TEST_NONLOCAL_ROOTS: String(options.nonlocalRoots ?? 0),
      CARE_PLAN_MYSQL_TEST_PARTIAL_REVOKES: String(options.partialRevokes ?? 0),
      CARE_PLAN_MYSQL_TEST_DENIED_DB: options.deniedDb }, [])
    action(result, JSON.parse(readFileSync(stateFile, 'utf8')))
  } finally { rmSync(directory, { recursive: true, force: true }) }
}
test('a failed native test run cleans exactly its created schemas and ephemeral user', () => {
  syntheticService(['care_plan_test_foreign'], (result, state) => {
    assert.equal(result.status, 42, result.stderr)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
    assert.doesNotMatch(result.stdout + result.stderr, /SyntheticOnly0123456789abc/)
  })
})
test('a schema-name collision refuses adoption and never drops that existing schema', () => {
  syntheticService(['care_plan_test_foreign', valid.CARE_PLAN_MYSQL_UPGRADE_DATABASE], (result, state) => {
    assert.equal(result.status, 1, result.stderr)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign', valid.CARE_PLAN_MYSQL_UPGRADE_DATABASE], users: [], grants: [] })
  })
})

for (const [label, serviceEnv] of [
  ['absent root host', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes'],
  ['wildcard root host', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes\nMYSQL_ROOT_HOST=%'],
  ['unexpected root host', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes\nMYSQL_ROOT_HOST=127.0.0.1'],
  ['duplicate root host', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes\nMYSQL_ROOT_HOST=localhost\nMYSQL_ROOT_HOST=%']
]) {
  test(`refuses ${label} before any schema/user is created`, () => {
    syntheticService(['care_plan_test_foreign'], (result, state) => {
      assert.equal(result.status, 64, result.stderr)
      assert.match(result.stderr, /MYSQL_ROOT_HOST=localhost/)
      assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
    }, { serviceEnv })
  })
}
test('refuses an actual non-local root account even when the service environment is safe', () => {
  syntheticService(['care_plan_test_foreign'], (result, state) => {
    assert.equal(result.status, 64, result.stderr)
    assert.match(result.stderr, /non-local root account/)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
  }, { nonlocalRoots: 1 })
})
test('refuses incompatible partial-revoke semantics before issuing database grants', () => {
  syntheticService(['care_plan_test_foreign'], (result, state) => {
    assert.equal(result.status, 64, result.stderr)
    assert.match(result.stderr, /partial_revokes/)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
  }, { partialRevokes: 1 })
})
test('escaped grants deny a sibling matched by the original underscore wildcard', () => {
  syntheticService(['care_plan_test_en_123xfresh'], (result, state) => {
    assert.equal(result.status, 42, result.stderr)
    assert.deepEqual(state, { databases: ['care_plan_test_en_123xfresh'], users: [], grants: [] })
  }, { deniedDb: 'care_plan_test_en_123xfresh' })
})
