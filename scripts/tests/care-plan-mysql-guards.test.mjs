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
  ['empty version selector', { CARE_PLAN_MYSQL_VERSION: '' }, 'version'],
  ['unbounded 5.7 selector', { CARE_PLAN_MYSQL_VERSION: '5.7' }, 'version'],
  ['different 5.7 patch', { CARE_PLAN_MYSQL_VERSION: '5.7.44' }, 'version'],
  ['unexpected server family', { CARE_PLAN_MYSQL_VERSION: 'mariadb:10.11' }, 'version'],
  ['invalid browser release flag', { CARE_PLAN_BROWSER_REQUIRED: 'false' }, 'CARE_PLAN_BROWSER_REQUIRED'],
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
  const queryFile = path.join(directory, 'queries.txt')
  writeFileSync(queryFile, '')
  writeFileSync(stateFile, JSON.stringify({ databases: initialDatabases, users: [], grants: [] }))
  writeFileSync(path.join(directory, 'docker'), `#!/usr/bin/env node
const fs = require('node:fs')
const args = process.argv.slice(2)
if (args[0] === 'inspect') {
  const format = args[args.indexOf('--format') + 1]
  console.log(format === '{{.Config.Image}}' ? process.env.CARE_PLAN_MYSQL_TEST_SERVICE_IMAGE : format === '{{.Image}}' ? 'sha256:synthetic-only' : process.env.CARE_PLAN_MYSQL_TEST_SERVICE_ENV)
  process.exit(0)
}
if (!args.includes('mysql') || !args.includes('--protocol=socket') || !args.includes('--user=root')) process.exit(90)
const sql = fs.readFileSync(0, 'utf8')
if (sql.startsWith('SELECT ') || sql.startsWith('SET GLOBAL ') || sql.startsWith('GRANT ')) fs.appendFileSync(process.env.CARE_PLAN_MYSQL_TEST_QUERY_FILE, sql)
if (sql.startsWith('SELECT CURRENT_USER()')) { console.log(process.env.CARE_PLAN_MYSQL_TEST_IDENTITY); process.exit(0) }
if (sql.includes("FROM mysql.user WHERE User='root'")) { console.log(process.env.CARE_PLAN_MYSQL_TEST_NONLOCAL_ROOTS); process.exit(0) }
if (sql.includes('@@GLOBAL.partial_revokes')) { if (process.env.CARE_PLAN_MYSQL_TEST_IDENTITY.endsWith('5.7.18')) process.exit(95); console.log(process.env.CARE_PLAN_MYSQL_TEST_PARTIAL_REVOKES); process.exit(0) }
if (sql.startsWith('SET GLOBAL log_bin_trust_function_creators=1;')) process.exit(Number(process.env.CARE_PLAN_MYSQL_TEST_SET_TRUST_STATUS))
if (sql.includes('@@GLOBAL.log_bin_trust_function_creators')) { console.log(process.env.CARE_PLAN_MYSQL_TEST_TRUST_CREATORS); process.exit(0) }
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
      CARE_PLAN_MYSQL_VERSION: options.version,
      CARE_PLAN_MYSQL_TEST_QUERY_FILE: queryFile,
      CARE_PLAN_MYSQL_TEST_SERVICE_IMAGE: options.image ?? 'mysql:8.0',
      CARE_PLAN_MYSQL_TEST_IDENTITY: options.identity ?? 'root@localhost\t8.0.99-synthetic',
      CARE_PLAN_MYSQL_TEST_SERVICE_ENV: options.serviceEnv ?? 'MYSQL_ALLOW_EMPTY_PASSWORD=yes\nMYSQL_ROOT_HOST=localhost',
      CARE_PLAN_MYSQL_TEST_NONLOCAL_ROOTS: String(options.nonlocalRoots ?? 0),
      CARE_PLAN_MYSQL_TEST_PARTIAL_REVOKES: String(options.partialRevokes ?? 0),
      CARE_PLAN_MYSQL_TEST_SET_TRUST_STATUS: String(options.setTrustStatus ?? 0),
      CARE_PLAN_MYSQL_TEST_TRUST_CREATORS: String(options.trustCreators ?? 1),
      CARE_PLAN_MYSQL_TEST_DENIED_DB: options.deniedDb }, [])
    action(result, JSON.parse(readFileSync(stateFile, 'utf8')), readFileSync(queryFile, 'utf8'))
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
  syntheticService(['care_plan_test_foreign'], (result, state, queries) => {
    assert.equal(result.status, 64, result.stderr)
    assert.match(result.stderr, /non-local root account/)
    assert.doesNotMatch(queries, /SET GLOBAL/)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
  }, { nonlocalRoots: 1 })
})
test('refuses incompatible partial-revoke semantics before issuing database grants', () => {
  syntheticService(['care_plan_test_foreign'], (result, state, queries) => {
    assert.equal(result.status, 64, result.stderr)
    assert.match(result.stderr, /partial_revokes/)
    assert.doesNotMatch(queries, /SET GLOBAL/)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
  }, { partialRevokes: 1 })
})
test('escaped grants deny a sibling matched by the original underscore wildcard', () => {
  syntheticService(['care_plan_test_en_123xfresh'], (result, state) => {
    assert.equal(result.status, 42, result.stderr)
    assert.deepEqual(state, { databases: ['care_plan_test_en_123xfresh'], users: [], grants: [] })
  }, { deniedDb: 'care_plan_test_en_123xfresh' })
})

// These exercise the real wrapper through synthetic process boundaries only.
// The separate CI jobs must execute the actual, version-verified MySQL servers.
const mysql57Image = 'mysql:5.7.18@sha256:d178dffba8d81afedc251498e227607934636e06228ac63d58b72f9e9ec271a6'
for (const version of ['8.0', '5.7.18']) {
  test(`explicit ${version} selector passes only the non-destructive input guards`, () => {
    const result = run({ CARE_PLAN_MYSQL_VERSION: version })
    assert.equal(result.status, 0, result.stderr)
    assert.match(result.stdout, /no database\/client operation performed/)
  })
}
for (const image of ['mysql:5.7.18', mysql57Image]) {
  test(`explicit 5.7.18 service ${image} skips the unavailable partial_revokes variable and cleans its own objects`, () => {
    syntheticService(['care_plan_test_foreign'], (result, state, queries) => {
      assert.equal(result.status, 42, result.stderr)
      assert.match(result.stdout, /Native MySQL service: 5\.7\.18;/)
      assert.doesNotMatch(queries, /partial_revokes/)
      assert.match(queries, /FROM mysql\.user WHERE User='root'/)
      assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
      assert.doesNotMatch(result.stdout + result.stderr, /SyntheticOnly0123456789abc/)
    }, { version: '5.7.18', image, identity: 'root@localhost\t5.7.18' })
  })
}
for (const [label, options] of [
  ['implicit downgrade to 5.7.18', { image: mysql57Image, identity: 'root@localhost\t5.7.18' }],
  ['5.7.18 selection backed by an 8.0 image', { version: '5.7.18' }],
  ['different official 5.7 patch image', { version: '5.7.18', image: 'mysql:5.7.44', identity: 'root@localhost\t5.7.44' }],
  ['lookalike image', { version: '5.7.18', image: 'attacker/mysql:5.7.18', identity: 'root@localhost\t5.7.18' }],
  ['invalid image digest', { version: '5.7.18', image: 'mysql:5.7.18@sha256:invalid', identity: 'root@localhost\t5.7.18' }],
  ['5.7.18 image with a different patch server', { version: '5.7.18', image: mysql57Image, identity: 'root@localhost\t5.7.44' }],
  ['5.7.18 prefix lookalike version', { version: '5.7.18', image: mysql57Image, identity: 'root@localhost\t5.7.180' }],
  ['5.7.18 vendor suffix', { version: '5.7.18', image: mysql57Image, identity: 'root@localhost\t5.7.18-MariaDB' }],
  ['unexpected socket identity', { version: '5.7.18', image: mysql57Image, identity: 'root@%\t5.7.18' }]
]) {
  test(`refuses ${label} before creating schemas or users`, () => {
    syntheticService(['care_plan_test_foreign'], (result, state, queries) => {
      assert.equal(result.status, 64, result.stderr)
      assert.match(result.stderr, /image|identity\/version/)
      assert.doesNotMatch(queries, /SET GLOBAL/)
      assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
    }, options)
  })
}
test('explicit MySQL 8.0 still checks partial_revokes and preserves failure cleanup', () => {
  syntheticService(['care_plan_test_foreign'], (result, state, queries) => {
    assert.equal(result.status, 42, result.stderr)
    assert.match(queries, /SELECT @@GLOBAL\.partial_revokes/)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
  }, { version: '8.0' })
})
test('MySQL 5.7.18 retains exact escaped schema grants', () => {
  syntheticService(['care_plan_test_en_123xfresh'], (result, state) => {
    assert.equal(result.status, 42, result.stderr)
    assert.deepEqual(state, { databases: ['care_plan_test_en_123xfresh'], users: [], grants: [] })
  }, { version: '5.7.18', image: mysql57Image, identity: 'root@localhost\t5.7.18', deniedDb: 'care_plan_test_en_123xfresh' })
})
for (const options of [{ version: '8.0' }, { version: '5.7.18', image: mysql57Image, identity: 'root@localhost\t5.7.18' }]) {
  test(`MySQL ${options.version} configures verified disposable trigger support without global user grants`, () => {
    syntheticService(['care_plan_test_foreign'], (result, state, queries) => {
      assert.equal(result.status, 42, result.stderr)
      assert.match(queries, /FROM mysql\.user WHERE User='root'[\s\S]*SET GLOBAL log_bin_trust_function_creators=1;\nSELECT @@GLOBAL\.log_bin_trust_function_creators;/)
      assert.equal(queries.match(/SET GLOBAL /g)?.length, 1)
      const grants = queries.split('\n').filter(line => line.startsWith('GRANT '))
      assert.equal(grants.length, 3)
      for (const grant of grants) {
        assert.match(grant, /^GRANT ALL PRIVILEGES ON `care\\_plan\\_test\\_[^`]+`\.\* TO 'care_plan_test_en_123'@'%';$/)
        assert.doesNotMatch(grant, /SUPER|ON \*\.\*|WITH GRANT OPTION/)
      }
      assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
    }, options)
  })
  test(`MySQL ${options.version} refuses unverified trigger support before schema or user creation`, () => {
    syntheticService(['care_plan_test_foreign'], (result, state) => {
      assert.equal(result.status, 64, result.stderr)
      assert.match(result.stderr, /log_bin_trust_function_creators/)
      assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
    }, { ...options, trustCreators: 0 })
  })
}
test('failure to configure disposable trigger support stops before schema or user creation', () => {
  syntheticService(['care_plan_test_foreign'], (result, state, queries) => {
    assert.equal(result.status, 96, result.stderr)
    assert.doesNotMatch(queries, /SELECT @@GLOBAL\.log_bin_trust_function_creators|GRANT /)
    assert.deepEqual(state, { databases: ['care_plan_test_foreign'], users: [], grants: [] })
  }, { setTrustStatus: 96 })
})
const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8')
function workflowJob(name) {
  return workflow.split(`\n  ${name}:`)[1]?.split(/\n  [a-zA-Z0-9_-]+:/)[0] ?? ''
}
test('CI requires exact official native MySQL 5.7.18 in both editions with bounded diagnostics', () => {
  const job = workflowJob('care-plan-mysql57')
  assert.ok(job, 'A separate required native 5.7.18 acceptance job is required')
  assert.ok(job.includes(`image: ${mysql57Image}`), 'Pin the verified official manifest, not an image config digest')
  assert.match(job, /language: en/)
  assert.match(job, /language: cn/)
  assert.match(job, /CARE_PLAN_MYSQL_VERSION: '5\.7\.18'/)
  assert.match(job, /MYSQL_ROOT_HOST: 'localhost'/)
  assert.match(job, /127\.0\.0\.1:13306:3306/)
  assert.match(job, /for pass in 1 2; do/)
  assert.match(job, /bash scripts\/verify-care-plan-mysql\.sh/)
  assert.match(job, /node --test scripts\/tests\/care-plan-mysql-guards\.test\.mjs/)
  assert.match(job, /node --test scripts\/tests\/mysql57-upgrade\.test\.mjs/)
  assert.match(job, /apt-get install --yes --no-install-recommends fonts-wqy-microhei python3-fonttools/)
  assert.match(job, /\/usr\/bin\/python3 scripts\/tests\/care-report-font\.test\.py/)
  assert.match(job, /\/usr\/bin\/python3 scripts\/prepare-care-report-font\.py/)
  assert.ok(job.indexOf('scripts/prepare-care-report-font.py') < job.indexOf('bash scripts/verify-care-plan-mysql.sh'))
  assert.match(job, /python3 scripts\/collect-native-diagnostics\.py/)
  assert.match(job, /if: always\(\)[\s\S]*actions\/upload-artifact@v4/)
  assert.match(job, /native-diagnostics\.json/)
  assert.doesNotMatch(job, /continue-on-error|\|\|\s*true|H2|5\.7\.44|CARE_PLAN_BROWSER_REQUIRED/)
})
test('existing MySQL 8.0 native and browser release gate remains required', () => {
  const job = workflowJob('care-plan-mysql')
  assert.match(job, /image: mysql:8\.0\s/)
  assert.match(job, /for pass in 1 2; do/)
  assert.match(job, /export CARE_PLAN_BROWSER_REQUIRED=true/)
  assert.match(job, /playwright install --with-deps chromium/)
  assert.match(job, /name: Preserve bounded final browser outcomes/)
  assert.doesNotMatch(job, /continue-on-error|\|\|\s*true/)
})

import { readdirSync } from 'node:fs'
// Source-only guard for the InnoDB AUTO_INCREMENT first-index rule. This scans
// original checked-in DDL, never rewrites it, and is not native execution proof.
function unindexedAutoColumns(sql) {
  const missing = []
  let checked = 0
  const tables = [...sql.matchAll(/CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?(\w+)`?\s*\(([\s\S]*?)\)\s*ENGINE\s*=\s*InnoDB\b[^;]*;/gi)]
  for (const [, table, body] of tables) {
    // Split only top-level definitions; decimal widths/index lists and quoted
    // comments containing commas must not create spurious column definitions.
    const definitions = []
    let start = 0, depth = 0, quote = ''
    for (let i = 0; i < body.length; i++) {
      const ch = body[i]
      if (quote) {
        if (ch === '\\') i++
        else if (ch === quote) {
          if (body[i + 1] === quote) i++
          else quote = ''
        }
      } else if ("'\"`".includes(ch)) quote = ch
      else if (ch === '(') depth++
      else if (ch === ')') depth--
      else if (ch === ',' && depth === 0) {
        definitions.push(body.slice(start, i).trim()); start = i + 1
      }
    }
    definitions.push(body.slice(start).trim())
    const syntax = definitions.map(definition => definition.replace(/'(?:[^'\\]|\\[\s\S]|'')*'|"(?:[^"\\]|\\[\s\S]|"")*"/g, ' '))
    const auto = syntax.map(definition => {
      const column = /^`?(\w+)`?\s+(?:BIGINT|INT|INTEGER|SMALLINT|MEDIUMINT|TINYINT)\b/i.exec(definition)
      return column && /\bAUTO_INCREMENT\b/i.test(definition) ? { name: column[1], definition } : null
    }).filter(Boolean)
    checked += auto.length
    const firstKeyColumns = syntax.map(definition => {
      const key = /^(?:PRIMARY\s+KEY|(?:UNIQUE\s+)?(?:KEY|INDEX)(?:\s+`?\w+`?)?)\s*\(\s*`?(\w+)`?(?=\s*[,\)])/i.exec(definition)
      return key?.[1]
    }).filter(Boolean)
    for (const column of auto) {
      if (!/\b(?:PRIMARY\s+KEY|UNIQUE)\b/i.test(column.definition) && !firstKeyColumns.includes(column.name)) {
        missing.push(`${table}.${column.name}`)
      }
    }
  }
  return { missing, checked }
}
for (const directory of ['.', 'cn']) {
  test(`original ${directory} InnoDB AUTO_INCREMENT columns begin an index`, () => {
    const sqlDirectory = path.resolve(path.dirname(script), '..', directory, 'src/main/resources/sql')
    const missing = []
    let checked = 0
    for (const filename of readdirSync(sqlDirectory).filter(name => name.endsWith('.sql')).sort()) {
      const result = unindexedAutoColumns(readFileSync(path.join(sqlDirectory, filename), 'utf8'))
      checked += result.checked
      missing.push(...result.missing.map(column => `${filename}:${column}`))
    }
    assert.ok(checked >= 80, `Expected all original native auto-column definitions, inspected ${checked}`)
    assert.deepEqual(missing, [], 'Every original InnoDB AUTO_INCREMENT column must begin an index')
  })
}
test('AUTO_INCREMENT structural scan rejects missing and non-leading keys', () => {
  const result = unindexedAutoColumns(`
    CREATE TABLE missing_key (id BIGINT AUTO_INCREMENT, note VARCHAR(50) COMMENT 'comma, ignored', KEY by_note(note)) ENGINE=InnoDB;
    CREATE TABLE wrong_order (id BIGINT AUTO_INCREMENT, owner BIGINT, KEY by_owner(owner,id)) ENGINE=InnoDB;
    CREATE TABLE comment_key (id BIGINT AUTO_INCREMENT COMMENT 'PRIMARY KEY') ENGINE=InnoDB;
    CREATE TABLE comment_auto (id BIGINT COMMENT 'AUTO_INCREMENT') ENGINE=InnoDB;
    CREATE TABLE inline_key (id BIGINT AUTO_INCREMENT PRIMARY KEY) ENGINE=InnoDB;
    CREATE TABLE table_key (id BIGINT AUTO_INCREMENT, PRIMARY KEY(id)) ENGINE=InnoDB;
    CREATE TABLE named_key (id BIGINT AUTO_INCREMENT, amount DECIMAL(5,2), KEY by_id(id,amount)) ENGINE=InnoDB;
  `)
  assert.equal(result.checked, 6)
  assert.deepEqual(result.missing, ['missing_key.id', 'wrong_order.id', 'comment_key.id'])
})
