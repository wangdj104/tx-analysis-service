/** One in-memory immutable command envelope. Missing receipts never prove a timeout did not commit. */
export function createHouseholdCommandSession({ patient, captureAuth, isAuthCurrent, events, newKey }) {
  let epoch = 0, navigation = 0, closed = false, disposed = false, envelope = null, owner = null, phase = 'idle', result = null;
  let inFlight = false;
  const identity = Object.freeze({}), listeners = [], lanes = new Map();
  const patientId = () => Number(typeof patient === 'function' ? patient() : patient?.value ?? patient);
  // captureAuth implementations may check only token/revision or their complete own snapshot.
  const authCurrent = value => !!value && isAuthCurrent(value.auth) && value.identity === identity && value.patientId === patientId() && value.epoch === epoch;
  const erase = () => { epoch++; lanes.clear(); envelope = null; owner = null; result = null; phase = 'idle'; inFlight = false; closed = false; };
  const invalidate = () => erase();
  for (const type of ['auth-session-cleared', 'auth-session-changed', 'storage', 'patient-changed']) {
    events?.addEventListener?.(type, invalidate); listeners.push(type);
  }
  function capture(lane) { if (disposed) throw new Error('Session disposed'); closed = false; const auth = freeze(captureAuth()); const sequence = (lanes.get(lane) || 0) + 1; lanes.set(lane, sequence); return Object.freeze({ identity, auth, patientId: patientId(), epoch, navigation, lane, sequence }); }
  function isOwned(value) { return !disposed && !closed && value?.navigation === navigation && value.sequence === lanes.get(value.lane) && authCurrent(value); }
  function begin(operation, target, meta, body) {
    if (disposed || envelope || inFlight || !meta || Number(meta.patientId) !== patientId()) throw new Error('Refresh or reconcile the existing command first');
    const auth = freeze(captureAuth()); if (!isAuthCurrent(auth)) throw new Error('Current sign-in required');
    closed = false; owner = { identity, auth, patientId: patientId(), epoch };
    envelope = freeze({ operation, target, commandKey: newKey(), expectedVersion: meta.expectedVersion, patientId: meta.patientId, body });
    phase = 'ready'; result = null; return envelope;
  }
  function requireEnvelope(allowed) {
    if (disposed || !envelope || !authCurrent(owner) || inFlight || !allowed.includes(phase)) throw new Error('No owned command is available');
  }
  async function sendWith(send, allowed) {
    requireEnvelope(allowed); const sent = envelope, captured = owner, view = navigation; inFlight = true; phase = 'submitting';
    try {
      const value = await send(sent);
      if (!authCurrent(captured) || disposed || envelope !== sent) return { phase: 'discarded', result: null };
      phase = 'succeeded'; result = value; if (closed || view !== navigation) { result = null; return { phase: 'discarded', settledPhase: phase, result: null }; } return { phase, result };
    } catch (error) {
      if (!authCurrent(captured) || disposed || envelope !== sent) return { phase: 'discarded', result: null };
      const status = error?.response?.status ?? error?.status ?? error?.code;
      if (status === 401 || status === 403) { erase(); phase = 'forbidden'; return { phase, result: null }; }
      phase = status === 400 ? 'invalid' : status === 409 ? 'conflict' : 'unknown';
      result = null; const errorCode = error?.response?.data?.data?.errorCode ?? error?.data?.errorCode ?? error?.errorCode ?? null;
      return closed || view !== navigation ? { phase: 'discarded', settledPhase: phase, result: null, errorCode } : { phase, result, errorCode };
    } finally { if (envelope === sent) inFlight = false; }
  }
  async function reconcile(readReceipt) {
    requireEnvelope(['unknown']); closed = false; const sent = envelope, captured = owner, view = navigation; inFlight = true;
    try {
      const value = await readReceipt(sent);
      if (!authCurrent(captured) || disposed || envelope !== sent) return { phase: 'discarded', result: null };
      if (value) { phase = 'succeeded'; result = value; }
      if (closed || view !== navigation) { result = null; return { phase: 'discarded', settledPhase: phase, result: null }; }
      return { phase, result };
    } catch (error) {
      if (!authCurrent(captured) || disposed || envelope !== sent) return { phase: 'discarded', result: null };
      if ([401, 403].includes(error?.response?.status ?? error?.status ?? error?.code)) { erase(); phase = 'forbidden'; }
      return { phase, result: null };
    } finally { if (envelope === sent) inFlight = false; }
  }
  function close() { navigation++; closed = true; result = null; if (!inFlight && phase !== 'unknown') { envelope = null; owner = null; phase = 'idle'; } }
  function reset() { if (inFlight || phase === 'unknown') throw new Error('The original command must be reconciled'); erase(); }
  function dispose() { erase(); disposed = true; for (const type of listeners) events?.removeEventListener?.(type, invalidate); }
  return Object.freeze({ capture, owns: isOwned, begin, submit: send => sendWith(send, ['ready']), reconcile, retryIdentical: send => { requireEnvelope(['unknown']); closed = false; return sendWith(send, ['unknown']); }, close, reset, dispose });
}
function freeze(value) {
  if (Array.isArray(value)) return Object.freeze(value.map(freeze));
  if (value && typeof value === 'object') return Object.freeze(Object.fromEntries(Object.entries(value).map(([key, item]) => [key, freeze(item)])));
  return value;
}
