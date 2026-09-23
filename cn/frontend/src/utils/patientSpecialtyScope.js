import { getPatientSpecialtyMenuScope } from '@/api/patient'
import { getCurrentPatientIdFromStorage } from '@/composables/useCurrentPatient'

let cachedKey = ''
let cachedPromise = null

export function clearPatientSpecialtyScope() {
  cachedKey = ''
  cachedPromise = null
}

export function loadPatientSpecialtyScope(patientId = getCurrentPatientIdFromStorage()) {
  const key = `${localStorage.getItem('token') || ''}:${patientId || ''}`
  if (cachedKey !== key || !cachedPromise) {
    cachedKey = key
    cachedPromise = getPatientSpecialtyMenuScope(patientId)
      .then(res => res.code === 200 ? res.data : null)
      .catch(() => null)
  }
  return cachedPromise
}
