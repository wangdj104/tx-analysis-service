// Display only. Stable patient IDs remain the values used for selection and writes.
export function patientName(patient) {
  return patient?.patientName || patient?.name || '患者姓名暂不可用';
}

export function patientIdLabel(id) {
  return '编号 ' + (id == null || id === '' ? '—' : String(id));
}

export function formatPatientIdentity(patient) {
  return `${patientName(patient)} · ${patientIdLabel(patient?.id)}`;
}
