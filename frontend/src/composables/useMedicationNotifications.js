import { onMounted, onUnmounted, watch } from 'vue'
import { getIntakes } from '@/api/familyHealth'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import { localDateKey } from '@/utils/familyHealth'
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'

// Browser notifications work while this application remains open, for the selected family member.
export function useMedicationNotifications() {
  const { currentPatientId, currentPatientName } = useCurrentPatient()
  let timer, running = false, stopped = false, patientEpoch = 0, historyDate
  const delivered = new Map()
  watch([currentPatientId, currentPatientName], () => { patientEpoch++ }, { flush: 'sync' })
  const accountKey = session => localStorage.getItem('username') || session.token?.slice(-20)
  async function check() {
    if (stopped || running) return
    running = true
    try {
      const session = captureAuthSession()
      if (!session.token || !currentPatientId.value ||
          !('Notification' in window) || Notification.permission !== 'granted') return
      const id = currentPatientId.value, name = currentPatientName.value, epoch = patientEpoch
      const account = accountKey(session)
      const isCurrent = () => !stopped && epoch === patientEpoch && id === currentPatientId.value &&
        name === currentPatientName.value && isAuthSessionCurrent(session) && account === accountKey(session) &&
        ('Notification' in window) && Notification.permission === 'granted'
      const result = await getIntakes(id)
      if (!isCurrent()) return
      const storageKey = `medication-notifications:${account}:${id}`
      let history
      try { history = JSON.parse(localStorage.getItem(storageKey) || '{}') } catch { /* Use this instance's delivery history. */ }
      const today = localDateKey()
      // Keep only the current day's per-account/patient keys if storage is unavailable.
      if (historyDate !== today) { delivered.clear(); historyDate = today }
      const sent = delivered.get(storageKey) || new Set()
      if (history?.date === today && Array.isArray(history.sent)) {
        for (const key of history.sent) if (typeof key === 'string') sent.add(key)
      }
      delivered.set(storageKey, sent)
      for (const task of result.data || []) {
        if (!['PENDING', 'MISSED', 'SNOOZED'].includes(task.status)) continue
        const due = task.snoozeUntil || task.scheduledAt
        const dueTime = new Date(due?.replace(' ', 'T')).getTime()
        const key = `${task.id}:${due}`
        if (!Number.isFinite(dueTime) || dueTime > Date.now() || sent.has(key)) continue
        if (!isCurrent()) return
        const notification = new Notification(`${name || 'Family Member'} · Medication Reminders`, {
          body: `${task.drugName || 'medication intake'} · ${task.dosage || 'As prescribed'}`,
          tag: `medication-${task.id}`, requireInteraction: true
        })
        // Record only successful construction, before persistence can fail.
        sent.add(key)
        notification.onclick = () => { window.focus(); notification.close() }
      }
      localStorage.setItem(storageKey, JSON.stringify({ date: today, sent: [...sent] }))
    } catch { /* The next poll retries. */ }
    finally { running = false }
  }
  onMounted(() => { check(); timer = window.setInterval(check, 30000) })
  onUnmounted(() => { stopped = true; window.clearInterval(timer) })
}
