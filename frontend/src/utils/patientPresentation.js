// Display only. Stable patient IDs remain the values used for selection and writes.
export function patientName(patient) {
  return patient?.patientName || patient?.name || 'Patient name unavailable';
}

export function patientIdLabel(id) {
  return 'ID ' + (id == null || id === '' ? '—' : String(id));
}

export function formatPatientIdentity(patient) {
  return `${patientName(patient)} · ${patientIdLabel(patient?.id)}`;
}
