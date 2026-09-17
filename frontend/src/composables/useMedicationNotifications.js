import { onMounted, onUnmounted } from 'vue'
import { getIntakes } from '@/api/familyHealth'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import { localDateKey } from '@/utils/familyHealth'

// Browser notifications work while this application remains open, for the selected family member.
export function useMedicationNotifications() {
  const { currentPatientId, currentPatientName } = useCurrentPatient()
  let timer, running = false, stopped = false
  async function check() {
    if (stopped || running || !localStorage.getItem('token') || !currentPatientId.value ||
        !('Notification' in window) || Notification.permission !== 'granted') return
    const id = currentPatientId.value
    const account = localStorage.getItem('username') || localStorage.getItem('token')?.slice(-20)
    running = true
    try {
      const result = await getIntakes(id)
      if (stopped || id !== currentPatientId.value || !localStorage.getItem('token')) return
      const storageKey = `medication-notifications:${account}:${id}`
      let history
      try { history = JSON.parse(localStorage.getItem(storageKey) || '{}') } catch { history = {} }
      const today = localDateKey()
      if (history.date !== today) history = { date: today, sent: [] }
      history.sent ||= []
      for (const task of result.data || []) {
        if (!['PENDING', 'MISSED', 'SNOOZED'].includes(task.status)) continue
        const due = task.snoozeUntil || task.scheduledAt
        const dueTime = new Date(due?.replace(' ', 'T')).getTime()
        const key = `${task.id}:${due}`
        if (!Number.isFinite(dueTime) || dueTime > Date.now() || history.sent.includes(key)) continue
        const notification = new Notification(`${currentPatientName.value || 'Family Member'} · Medication Reminders`, {
          body: `${task.drugName || 'medication intake'} · ${task.dosage || 'As prescribed'}`,
          tag: `medication-${task.id}`, requireInteraction: true
        })
        notification.onclick = () => { window.focus(); notification.close() }
        history.sent.push(key)
      }
      localStorage.setItem(storageKey, JSON.stringify(history))
    } catch { /* The next poll retries. */ }
    finally { running = false }
  }
  onMounted(() => { check(); timer = window.setInterval(check, 30000) })
  onUnmounted(() => { stopped = true; window.clearInterval(timer) })
}
