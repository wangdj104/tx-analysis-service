import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'

async function helpers() {
  assert.ok(fs.existsSync(new URL('../src/utils/carePlanTime.js', import.meta.url)), 'care-plan time helpers exist')
  return import('../src/utils/carePlanTime.js')
}

test('explicit offset preserves local clock and UTC cross-day instant', async () => {
  const { toOffsetIso } = await helpers()
  const iso = toOffsetIso('2026-10-03T00:30', 540, { timeZone: 'Asia/Tokyo' })
  assert.equal(iso, '2026-10-03T00:30:00+09:00')
  assert.equal(new Date(iso).toISOString(), '2026-10-02T15:30:00.000Z')
})

test('nonexistent local clock is rejected without Date normalization', async () => {
  const { toOffsetIso, getLocalTimeOffsets } = await helpers()
  assert.deepEqual(getLocalTimeOffsets('2026-03-08T02:30', { timeZone: 'America/New_York' }), [])
  for (const offset of [-300, -240]) assert.throws(() => toOffsetIso('2026-03-08T02:30', offset, { timeZone: 'America/New_York' }), /nonexistent|不存在/)
})

test('ambiguous local clock exposes both offsets and requires explicit valid selection', async () => {
  const { toOffsetIso, getLocalTimeOffsets } = await helpers()
  assert.deepEqual(getLocalTimeOffsets('2026-11-01T01:30', { timeZone: 'America/New_York' }), [-240, -300])
  assert.throws(() => toOffsetIso('2026-11-01T01:30', undefined, { timeZone: 'America/New_York' }), /offset|偏移/)
  assert.equal(toOffsetIso('2026-11-01T01:30', -240, { timeZone: 'America/New_York' }), '2026-11-01T01:30:00-04:00')
  assert.equal(toOffsetIso('2026-11-01T01:30', -300, { timeZone: 'America/New_York' }), '2026-11-01T01:30:00-05:00')
  assert.throws(() => toOffsetIso('2026-11-01T01:30', 0, { timeZone: 'America/New_York' }), /offset|偏移/)
})

test('invalid clocks and offsets are rejected', async () => {
  const { toOffsetIso } = await helpers()
  for (const offset of [NaN, Infinity, 0.5, 1081, -1081, null, '0']) assert.throws(() => toOffsetIso('2026-10-03T12:00', offset, { timeZone: 'UTC' }))
  for (const clock of ['2026-02-30T12:00', '2026-10-03T25:00', '2026-10-03T12:60', '2026-10-03T12:00:60', '2026-10-03 12:00', '2026-10-03T12:00Z', 'not a date']) assert.throws(() => toOffsetIso(clock, 0, { timeZone: 'UTC' }))
})

test('microsecond precision is exact and sub-microseconds cannot silently round or truncate', async () => {
  const { toOffsetIso } = await helpers()
  assert.equal(toOffsetIso('2026-10-03T12:00:01.123456', 0, { timeZone: 'UTC' }), '2026-10-03T12:00:01.123456+00:00')
  assert.equal(toOffsetIso('2026-10-03T12:00:01.123456000', 0, { timeZone: 'UTC' }), '2026-10-03T12:00:01.123456+00:00')
  assert.throws(() => toOffsetIso('2026-10-03T12:00:01.123456001', 0, { timeZone: 'UTC' }), /microsecond|微秒/)
  assert.throws(() => toOffsetIso('2026-10-03T12:00:01.1234561', 0, { timeZone: 'UTC' }), /microsecond|微秒/)
})

test('UTC storage bounds include the exact endpoints and reject values outside them', async () => {
  const { toOffsetIso } = await helpers()
  assert.equal(toOffsetIso('1000-01-01T00:00:00', 0, { timeZone: 'UTC' }), '1000-01-01T00:00:00+00:00')
  assert.equal(toOffsetIso('9999-12-31T23:59:59.499999', 0, { timeZone: 'UTC' }), '9999-12-31T23:59:59.499999+00:00')
  for (const clock of ['0999-12-31T23:59:59', '9999-12-31T23:59:59.500000']) assert.throws(() => toOffsetIso(clock, 0, { timeZone: 'UTC' }), /range|范围/)
  assert.throws(() => toOffsetIso('1000-01-01T00:00:00', 60, { timeZone: 'Europe/Berlin' }), /range|范围/)
})

test('display always names timezone and numeric offset, including each DST fold instant', async () => {
  const { formatPlanTime } = await helpers()
  const early = formatPlanTime('2026-11-01T05:30:00Z', 'en-US', { timeZone: 'America/New_York' })
  const late = formatPlanTime('2026-11-01T06:30:00Z', 'en-US', { timeZone: 'America/New_York' })
  assert.match(early, /America\/New_York/)
  assert.match(early, /-04:00/)
  assert.match(late, /-05:00/)
  assert.throws(() => formatPlanTime('2026-11-01T01:30:00', 'en-US'), /offset|偏移/)
})

test('exclusive next local midnight uses actual DST rather than fixed 24 hours', async () => {
  const { nextLocalMidnightUtc, localDayContext } = await helpers()
  assert.equal(nextLocalMidnightUtc(new Date('2026-03-08T05:00:00Z'), { timeZone: 'America/New_York' }), '2026-03-09T04:00:00.000Z')
  assert.equal(nextLocalMidnightUtc(new Date('2026-11-01T04:00:00Z'), { timeZone: 'America/New_York' }), '2026-11-02T05:00:00.000Z')
  assert.deepEqual(localDayContext(new Date('2026-10-03T23:00:00Z'), { timeZone: 'Asia/Tokyo' }), { dayKey: '2026-10-04', timeZone: 'Asia/Tokyo', dueBefore: '2026-10-04T15:00:00.000Z' })
})

test('historical second-based timezone offsets display exact signed hours minutes and seconds', async () => {
  const { formatPlanTime } = await helpers()
  const tokyo = formatPlanTime('1000-01-01T00:00:00Z', 'en-US', { timeZone: 'Asia/Tokyo' })
  const newYork = formatPlanTime('1000-01-01T00:00:00Z', 'zh-CN', { timeZone: 'America/New_York' })
  assert.match(tokyo, /UTC\+09:18:59, Asia\/Tokyo/)
  assert.match(newYork, /UTC-04:56:02, America\/New_York/)
  assert.doesNotMatch(tokyo, /UTC[^,]*\./)
})
