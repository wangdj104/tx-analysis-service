const MIN_MICROS = BigInt(Date.UTC(1000, 0, 1)) * 1000n
const MAX_MICROS = BigInt(Date.UTC(9999, 11, 31, 23, 59, 59)) * 1000n + 499999n
const browserZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone
const pad = value => String(value).padStart(2, '0')

function parseClock(value) {
  const match = typeof value === 'string' && value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/)
  if (!match) throw new RangeError('Use a valid local date and time without an offset.')
  const [, year, month, day, hour, minute, second = '00', fraction = ''] = match
  const parts = [year, month, day, hour, minute, second].map(Number)
  const [y, m, d, h, min, sec] = parts
  const date = new Date(0)
  date.setUTCFullYear(y, m - 1, d)
  date.setUTCHours(h, min, sec, 0)
  if (m < 1 || m > 12 || d < 1 || h > 23 || min > 59 || sec > 59 ||
      date.getUTCFullYear() !== y || date.getUTCMonth() + 1 !== m || date.getUTCDate() !== d) {
    throw new RangeError('Invalid local date or time.')
  }
  if (fraction.length > 6 && /[1-9]/.test(fraction.slice(6))) throw new RangeError('Time must have exact microsecond precision.')
  const micros = Number(fraction.slice(0, 6).padEnd(6, '0'))
  return { parts, base: date.getTime(), micros, text: `${year}-${month}-${day}T${hour}:${minute}:${second}${fraction ? `.${fraction.slice(0, 6)}` : ''}` }
}
function formatter(timeZone) {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
}
function zonedParts(date, format) {
  const parts = Object.fromEntries(format.formatToParts(date).filter(p => p.type !== 'literal').map(p => [p.type, Number(p.value)]))
  return [parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second]
}
function wallMillis(parts) {
  const date = new Date(0)
  date.setUTCFullYear(parts[0], parts[1] - 1, parts[2])
  date.setUTCHours(parts[3], parts[4], parts[5], 0)
  return date.getTime()
}
function offsetsFor(clock, timeZone) {
  const format = formatter(timeZone), candidates = new Set()
  // Probe both sides of timezone transitions, then round-trip each candidate.
  for (let hours = -36; hours <= 36; hours += 6) {
    const timestamp = clock.base + hours * 3600000
    const offset = (wallMillis(zonedParts(new Date(timestamp), format)) - timestamp) / 60000
    if (Number.isInteger(offset) && Math.abs(offset) <= 1080) candidates.add(offset)
  }
  return [...candidates].filter(offset => zonedParts(new Date(clock.base - offset * 60000), format).every((part, i) => part === clock.parts[i])).sort((a, b) => b - a)
}

/** Returns valid minute offsets in instant order. [] means a nonexistent clock; two entries expose a DST fold. */
export function getLocalTimeOffsets(localDateTime, { timeZone = browserZone() } = {}) {
  return offsetsFor(parseClock(localDateTime), timeZone)
}

/** offsetMinutes is east of UTC (New York summer = -240), unlike Date#getTimezoneOffset. */
export function toOffsetIso(localDateTime, offsetMinutes, { timeZone = browserZone() } = {}) {
  const clock = parseClock(localDateTime)
  if (!Number.isInteger(offsetMinutes) || Math.abs(offsetMinutes) > 1080) throw new RangeError('Select an explicit integer offset between -1080 and 1080 minutes.')
  const instantMicros = BigInt(clock.base - offsetMinutes * 60000) * 1000n + BigInt(clock.micros)
  if (instantMicros < MIN_MICROS || instantMicros > MAX_MICROS) throw new RangeError('Time is outside the supported UTC storage range.')
  const offsets = offsetsFor(clock, timeZone)
  if (!offsets.length) throw new RangeError('This local time is nonexistent in the selected timezone. Correct the time.')
  if (!offsets.includes(offsetMinutes)) throw new RangeError('The selected offset does not match this local time and timezone.')
  const sign = offsetMinutes < 0 ? '-' : '+', absolute = Math.abs(offsetMinutes)
  return `${clock.text}${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
}

export function formatPlanTime(iso, locale, { timeZone = browserZone() } = {}) {
  const match = typeof iso === 'string' && iso.match(/^(.*)(Z|[+-]\d{2}:\d{2})$/)
  if (!match) throw new RangeError('An explicit ISO time offset is required.')
  const clock = parseClock(match[1])
  const offset = match[2] === 'Z' ? 0 : (match[2][0] === '-' ? -1 : 1) * (Number(match[2].slice(1, 3)) * 60 + Number(match[2].slice(4)))
  if (Math.abs(offset) > 1080 || (match[2] !== 'Z' && Number(match[2].slice(4)) > 59)) throw new RangeError('Invalid ISO time offset.')
  const timestamp = clock.base - offset * 60000
  const instantMicros = BigInt(timestamp) * 1000n + BigInt(clock.micros)
  if (instantMicros < MIN_MICROS || instantMicros > MAX_MICROS) throw new RangeError('Time is outside the supported UTC storage range.')
  const date = new Date(timestamp + Math.floor(clock.micros / 1000))
  const display = new Intl.DateTimeFormat(locale, { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'longOffset' }).format(date)
  const offsetSeconds = (wallMillis(zonedParts(new Date(timestamp), formatter(timeZone))) - timestamp) / 1000
  const sign = offsetSeconds < 0 ? '-' : '+', absolute = Math.abs(offsetSeconds)
  const hours = pad(Math.floor(absolute / 3600)), minutes = pad(Math.floor(absolute / 60) % 60), seconds = absolute % 60
  return `${display} (UTC${sign}${hours}:${minutes}${seconds ? `:${pad(seconds)}` : ''}, ${timeZone})`
}

/** Actual next local date boundary, including 23/25-hour days and midnight timezone transitions. */
export function nextLocalMidnightUtc(now = new Date(), { timeZone = browserZone() } = {}) {
  const timestamp = now.getTime()
  if (!Number.isFinite(timestamp)) throw new RangeError('Invalid current time.')
  const format = formatter(timeZone)
  const dateKey = milliseconds => zonedParts(new Date(milliseconds), format).slice(0, 3).join('-')
  const today = dateKey(timestamp)
  let low = timestamp, high = timestamp + 48 * 3600000
  while (high - low > 1) {
    const middle = Math.floor((low + high) / 2)
    if (dateKey(middle) === today) low = middle
    else high = middle
  }
  return new Date(high).toISOString()
}
export function localDayContext(now = new Date(), { timeZone = browserZone() } = {}) {
  const [year, month, day] = zonedParts(now, formatter(timeZone))
  return { dayKey: `${String(year).padStart(4, '0')}-${pad(month)}-${pad(day)}`, timeZone, dueBefore: nextLocalMidnightUtc(now, { timeZone }) }
}
