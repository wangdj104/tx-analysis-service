import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'

const dataModule = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
let id = 0
async function fixture(t) {
  const priorStorage = globalThis.localStorage, priorWindow = globalThis.window
  const storage = new Map([['token', 'synthetic-event-token'], ['userId', '7']])
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)), removeItem: key => storage.delete(key), key: index => [...storage.keys()][index], get length() { return storage.size } }
  globalThis.window = new EventTarget()
  const slot = `__careEventRequests${++id}`, calls = []
  globalThis[slot] = config => { calls.push(config); return Promise.resolve({ code: 200, data: { items: [], nextCursor: null } }) }
  const authUrl = new URL(`../src/utils/authSession.js?eventApi=${id}`, import.meta.url).href
  const source = fs.readFileSync(new URL('../src/api/carePlan.js', import.meta.url), 'utf8')
    .replace(/import request from ['"]@\/utils\/request['"];?/, `const request = globalThis.${slot}`)
    .replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`)
  const api = await import(dataModule(source))
  t.after(() => { delete globalThis[slot]; globalThis.localStorage = priorStorage; globalThis.window = priorWindow })
  return { api, calls }
}

test('real event wrapper preserves auth options and only explicit cursor/limit become query parameters', async t => {
  const { api, calls } = await fixture(t), expectedAuth = { actorId: '7', token: 'synthetic-event-token', sessionEpoch: 23 }, signal = new AbortController().signal
  await api.listPlanEvents(17, { expectedAuth, signal, params: { cursor: 'opaque-event-cursor', limit: 25, actorId: 900, expectedAuth: 'never-query' } })
  assert.deepEqual(calls[0], { url: '/care-plans/17/events', method: 'get', expectedAuth, signal, params: { cursor: 'opaque-event-cursor', limit: 25 } })
  await api.listPlanEvents(17, { expectedAuth, signal })
  assert.deepEqual(calls[1], { url: '/care-plans/17/events', method: 'get', expectedAuth, signal })
  await api.listPlanEvents(17, { expectedAuth, cursor: 'wrong-position', limit: 90 })
  assert.deepEqual(calls[2], { url: '/care-plans/17/events', method: 'get', expectedAuth })
})
