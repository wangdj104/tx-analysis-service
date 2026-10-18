export const stockPolicyId = value => /^[1-9][0-9]*$/.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : null
export const stockPolicyVersion = value => Number.isSafeInteger(value) && value >= 0
export function policyEnvelope(command) {
  const body = { patientId: command.patientId, stockId: command.target, operation: command.operation, expectedControlVersion: command.expectedVersion, commandKey: command.commandKey }
  if (command.operation === 'ENABLE') Object.assign(body, { currentFactsHash: command.body.currentFactsHash, reviewAcknowledged: true })
  return Object.freeze(body)
}
export function validStockPolicyReceipt(receipt, command) {
  return !!(receipt && Object.keys(receipt).sort().join(',') === 'controlVersion,enabled,operation,recordedAt,stockId' &&
    receipt.stockId === command.target && stockPolicyId(receipt.stockId) && receipt.operation === command.operation &&
    stockPolicyVersion(receipt.controlVersion) && receipt.controlVersion === command.expectedVersion + 1 &&
    receipt.enabled === (command.operation === 'ENABLE') && typeof receipt.recordedAt === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(receipt.recordedAt) && Number.isFinite(Date.parse(receipt.recordedAt)))
}
export function validStockPolicyCurrent(value, patient, stock) {
  return !!(value && value.patientId === patient && value.stockId === stock && value.schemaStatus === 'READY' &&
    typeof value.marked === 'boolean' && typeof value.canWrite === 'boolean' && value.currentFacts &&
    value.currentFacts.patientId === patient && value.currentFacts.stockId === stock && typeof value.currentFacts.reviewReady === 'boolean' &&
    (!value.marked || typeof value.enabled === 'boolean' && stockPolicyVersion(value.controlVersion)) &&
    (!value.currentFacts.reviewReady || typeof value.currentFactsHash === 'string' && /^[0-9a-f]{64}$/.test(value.currentFactsHash)))
}
export function policySnapshot(current) {
  if (!current || current.schemaStatus !== 'READY') return { schemaStatus: 'UNAVAILABLE', canWrite: false }
  const result = { stockId: current.stockId, schemaStatus: 'READY', marked: current.marked, canWrite: current.canWrite }
  if (current.marked) Object.assign(result, { enabled: current.enabled, controlVersion: current.controlVersion })
  return result
}
