import { reactive, unref, watch, nextTick, getCurrentScope, onScopeDispose } from 'vue'
import { previewExecutionReport, exportExecutionReport } from '@/api/careExecutionReport'
import { AUTH_STORAGE_KEYS, captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'
import { reportError, countReportCsvRows, reportExportMetadata } from '@/utils/careExecutionReport'

/** Each preview/download is freshly authorized. Abort does not promise server cancellation. */
export function useCareExecutionReport(contextRef) {
  const state = reactive({ phase: 'idle', report: null, error: null, lastExport: null })
  const objectUrls = new Set()
  let epoch = 0, active = null, disposed = false
  let auth = captureAuthSession(), actorId = localStorage.getItem('userId')
  const contextKey = () => JSON.stringify(['patientId', 'planId', 'fromDate', 'toDate', 'timeZone', 'language', 'format'].map(key => unref(contextRef)?.[key] ?? null))
  function revoke(url) { window.URL.revokeObjectURL(url); objectUrls.delete(url) }
  function clear() {
    epoch++; active?.controller.abort(); active = null
    for (const url of objectUrls) revoke(url)
    Object.assign(state, { phase: 'idle', report: null, error: null, lastExport: null })
  }
  function authChanged() {
    clear(); auth = captureAuthSession(); actorId = localStorage.getItem('userId')
  }
  function storageChanged(event) { if (event.key == null || AUTH_STORAGE_KEYS.includes(event.key)) authChanged() }
  function sessionCurrent() {
    if (disposed) return false
    if (!isAuthSessionCurrent(auth) || actorId !== localStorage.getItem('userId')) authChanged()
    return !!auth.token
  }
  function contextCurrent(attempt) {
    if (!attempt || disposed) return false
    if (!isAuthSessionCurrent(attempt.auth) || attempt.actorId !== localStorage.getItem('userId')) {
      if (!isAuthSessionCurrent(auth) || actorId !== localStorage.getItem('userId')) authChanged()
      return false
    }
    return attempt.epoch === epoch && attempt.key === contextKey()
  }
  function owns(attempt) { return contextCurrent(attempt) && active === attempt }
  function begin(exporting) {
    if (!sessionCurrent() || active) return null
    const attempt = { epoch, auth: { ...auth }, actorId, key: contextKey(), controller: new AbortController(), body: { ...unref(contextRef) } }
    active = attempt
    Object.assign(state, { phase: exporting ? 'downloading' : 'previewing', report: null, error: null, lastExport: null })
    return attempt
  }
  async function run(exporting, format) {
    const attempt = begin(exporting)
    if (!attempt) return { status: 'stale' }
    const options = { expectedAuth: { ...attempt.auth, actorId: attempt.actorId }, signal: attempt.controller.signal }
    let url, link
    try {
      if (!exporting) {
        const response = await previewExecutionReport(attempt.body, options)
        if (!owns(attempt)) return { status: 'stale' }
        state.report = response.data
        // Vue sync subscribers can replace the context inside either assignment.
        if (!owns(attempt)) return { status: 'stale' }
        state.phase = 'ready'
        return { status: owns(attempt) ? 'succeeded' : 'stale' }
      }
      const file = await exportExecutionReport({ ...attempt.body, format }, options)
      if (!owns(attempt)) return { status: 'stale' }
      const rowCount = ['actions_csv', 'events_csv'].includes(format) ? await countReportCsvRows(file.blob, () => owns(attempt)) : null
      if (!owns(attempt)) return { status: 'stale' }
      state.lastExport = reportExportMetadata(attempt.body, format, file.fileName, rowCount)
      // Let the page expose zero rows and this export's metadata before the browser receives it.
      await nextTick()
      if (!owns(attempt)) return { status: 'stale' }
      url = window.URL.createObjectURL(file.blob); objectUrls.add(url)
      if (!owns(attempt)) return { status: 'stale' }
      link = document.createElement('a'); link.href = url; link.download = file.fileName; link.style.display = 'none'
      document.body.appendChild(link)
      if (!owns(attempt)) return { status: 'stale' }
      link.click()
      if (!owns(attempt)) return { status: 'stale' }
      state.phase = 'downloaded'
      return { status: 'downloaded' }
    } catch (error) {
      if (!owns(attempt)) return { status: 'stale' }
      if (error?.code === 'ERR_CANCELED' || error?.name === 'AbortError') { state.phase = 'idle'; return { status: 'stale' } }
      const safeError = reportError(error)
      // clear publishes reactive state and can synchronously start a replacement.
      // Keep the exact expected generation rather than adopting whatever clear leaves.
      const failureContext = { ...attempt, epoch: epoch + 1 }
      const ownsFailure = () => contextCurrent(failureContext) && active === null
      clear()
      if (!ownsFailure()) return { status: 'stale' }
      state.error = safeError
      if (!ownsFailure()) return { status: 'stale' }
      state.phase = 'error'
      return { status: ownsFailure() ? 'failed' : 'stale' }
    } finally {
      link?.remove()
      if (url) revoke(url)
      // A stale completion must never release the replacement request's busy state.
      if (owns(attempt)) active = null
    }
  }
  const stopContext = watch(contextKey, clear, { flush: 'sync' })
  if (typeof window !== 'undefined') {
    window.addEventListener('auth-session-cleared', authChanged)
    window.addEventListener('storage', storageChanged)
  }
  function dispose() {
    if (disposed) return
    clear(); disposed = true; stopContext()
    if (typeof window !== 'undefined') {
      window.removeEventListener('auth-session-cleared', authChanged)
      window.removeEventListener('storage', storageChanged)
    }
  }
  if (getCurrentScope()) onScopeDispose(dispose)
  return { state, preview: () => run(false), download: format => run(true, format), clear, dispose }
}
