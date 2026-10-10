const titles = {
  CONSULTATION: {'Remote consultation': '远程问诊'},
  VISIT: {'Visit summary': '就诊小结'},
  MEASUREMENT: {'Blood Pressure & Glucoserecord': '血压与血糖记录', 'Blood pressure and glucose record': '血压与血糖记录'},
  DIALYSIS: {'Dialysis Records': '透析记录'}
}

export function localizeHealthTimelineEntry(entry) {
  if (!entry || typeof entry !== 'object') return entry
  const sourceType = String(entry.sourceType || '').toUpperCase()
  if (!Object.hasOwn(titles, sourceType) && !['INTAKE','MEDICATION_LOG'].includes(sourceType)) return entry
  const titleLabels = titles[sourceType]
  let title = titleLabels && Object.hasOwn(titleLabels, entry.title) ? titleLabels[entry.title] : entry.title
  let summary = entry.summary
  if (sourceType === 'INTAKE' && typeof title === 'string') title = title.replace(/^Taken:\s*/, '已服用：')
  if (sourceType === 'MEDICATION_LOG' && typeof title === 'string') title = title.replace(/^(?:medicationrecord|Medication record):\s*/, '用药记录：')
  // These two summaries are assembled from numeric readings. Other summaries
  // and every remark are authored clinical content and must remain unchanged.
  if (sourceType === 'MEASUREMENT' && typeof summary === 'string' && /^(?:Blood Pressure [\d.]*\/[\d.]* mmHg(?:; )?)?(?:Blood Glucose [\d.]+ (?:mmol\/L|mg\/dL))?$/.test(summary)) {
    summary = summary.replace(/^Blood Pressure /, '血压 ').replace(/Blood Glucose /, '血糖 ').replace('; ', '；')
  }
  if (sourceType === 'DIALYSIS' && typeof summary === 'string' && /^Pre-dialysis Weight [\d.]* kg; Post-dialysis Weight [\d.]* kg$/.test(summary)) {
    summary = summary.replace('Pre-dialysis Weight ', '透析前体重 ').replace('; Post-dialysis Weight ', '；透析后体重 ')
  }
  return {...entry, title, summary}
}
