import request from '@/utils/request'
export const getHealthTarget = patientId => request({ url: '/family-health/target', params: { patientId } })
export const saveHealthTarget = data => request({ url: '/family-health/target', method: 'post', data })
export const getIntakes = (patientId, date) => request({ url: '/family-health/intakes', params: { patientId, date } })
export const actionIntake = (id, status, reason) => request({ url: `/family-health/intakes/${id}/action`, method: 'put', params: { status, reason } })
export const getTimeline = (patientId, from, to) => request({ url: '/family-health/timeline', params: { patientId, from, to } })
export const saveEvent = data => request({ url: '/family-health/events', method: 'post', data })
export const getDialysisSchedules = patientId => request({ url: '/family-health/dialysis-schedules', params: { patientId } })
export const saveDialysisSchedule = data => request({ url: '/family-health/dialysis-schedules', method: 'post', data })
export const getInsights = patientId => request({ url: '/family-health/insights', params: { patientId } })
export const getAlerts = patientId => request({ url: '/family-health/alerts', params: { patientId } })
export const updateAlertStatus = (id, status, handlingNote) => request({ url: `/family-health/alerts/${id}/status`, method: 'put', data: { status, handlingNote } })
export const getVisitSummary = (patientId, options = {}) => request({ url: '/family-health/visit-summary', params: { patientId }, expectedAuth: options.expectedAuth, signal: options.signal, ...(options.expectedAuth ? { executionReport: true } : {}) })

export const deleteEvent = id => request({ url: `/family-health/events/${id}`, method: 'delete' })

// Fresh scope for the optional dialysis read; clinical data remains separately authorized.
export const getSpecialtyMenuScope = patientId => request({ url: '/patient/specialty-menu-scope', params: { patientId } })
