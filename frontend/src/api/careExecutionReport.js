import request from '@/utils/request'
import { captureAuthSession } from '@/utils/authSession'
import { safeReportFileName } from '@/utils/careExecutionReport'

function reportRequest(body, exporting, { expectedAuth, signal } = {}) {
  // Copy only report inputs. Optional null values from forms are not legal JSON fields.
  const data = Object.fromEntries(['patientId', 'planId', 'fromDate', 'toDate', 'timeZone', 'language', ...(exporting ? ['format'] : [])]
    .filter(key => body[key] != null).map(key => [key, body[key]]))
  const auth = expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') }
  return request({ url: `/care-plans/reports/${exporting ? 'export' : 'preview'}`, method: 'post', data,
    timeout: 45000, expectedAuth: { ...auth }, ...(signal ? { signal } : {}), executionReport: true,
    ...(exporting ? { responseType: 'blob', returnExportResponse: true } : {}) })
}
export function previewExecutionReport(body, options) { return reportRequest(body, false, options) }
export async function exportExecutionReport(body, options) {
  // Capture before any await, including caller-owned form changes.
  const { language, format } = body
  const fallback = safeReportFileName(null, language, format)
  const response = await reportRequest(body, true, options)
  if (!(response?.blob instanceof Blob)) throw new Error('The report did not return a file.')
  return { blob: response.blob, fileName: safeReportFileName(response.headers?.['content-disposition'], language, format) || fallback }
}
