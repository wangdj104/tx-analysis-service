const exact = {
  success: '成功',
  'Saved successfully': '保存成功',
  'Failed to save': '保存失败',
  'Updated successfully': '更新成功',
  'Update failed': '更新失败',
  'Deleted successfully': '删除成功',
  'Failed to delete': '删除失败',
  'Created successfully': '创建成功',
  'Operation completed': '操作成功',
  'Operation failed': '操作失败',
  'Not signed in': '尚未登录',
  'User not found': '用户不存在',
  'Role not found': '角色不存在',
  'Permission denied': '没有执行此操作的权限',
  'Select a patient': '请选择患者',
  'Invalid request parameter.': '请求参数无效。',
  'The record does not exist.': '记录不存在。',
  'The patient does not exist.': '患者不存在。',
  'The alert does not exist.': '提醒不存在。',
  'Medication not found': '药品不存在',
  'Medication list cannot be empty': '药品列表不能为空',
  'No data': '暂无数据',
  'No plan': '暂无计划',
  'Normal': '正常',
  'Critical': '严重异常',
  'Needs attention': '需要关注',
  'Data overdue': '数据延迟',
  'In progress': '进行中',
  'All completed': '全部完成',
  'Overdue tasks': '存在逾期任务',
  'Awaiting health data': '等待健康数据',
  'Health status is stable': '当前状态稳定',
  'Items need attention': '存在待处理事项',
  'Immediate attention required': '需要立即关注',
  'Blood pressure': '血压监测',
  'Blood glucose': '血糖监测',
  'Dialysis monitoring': '透析监测',
  "Today's medications": '今日用药',
  'Awaiting the first measurement': '等待首次测量',
  'Awaiting the first record': '等待首次记录',
  'No medication tasks are scheduled for today': '今日未安排用药任务',
  'No updates': '暂无更新',
  'Review and document the clinical response': '复核并记录临床处置情况',
  'Confirm taken, snooze, or record the reason': '确认已服、稍后提醒或记录原因',
  'Complete, reassign, or document why it is deferred': '完成任务、重新指派或记录延期原因',
  'Imported record needs review': '导入记录需要审核',
  'Low-confidence recognition': '识别置信度较低',
  'Record context is incomplete': '记录上下文不完整',
  'Possible duplicate imported records': '可能存在重复导入记录',
  'Dialysis quality fields are incomplete': '透析质量字段不完整',
  'AI analysis draft needs clinical review': 'AI 分析草稿需要临床审核',
  'Possible duplicate therapy': '可能存在重复用药',
  'Confirm whether both active entries are intended.': '请确认这两条启用记录是否均为预期医嘱。',
  'Medication matches the recorded allergy list': '药品与已记录的过敏清单匹配',
  'Do not change treatment in the app; contact the prescribing clinician or pharmacist promptly.': '请勿在平台内自行变更治疗方案，应尽快联系开方医生或药师。',
  'Renal-dose/renal-safety review suggested': '建议复核肾功能剂量与用药安全',
  'NSAIDs may be unsuitable in advanced kidney disease; verify the prescriber plan.': 'NSAIDs 可能不适合晚期肾病患者，请核实开方方案。',
  'Potential medication interaction': '潜在药物相互作用',
  'Screening is decision support only. A clinician or pharmacist must confirm every medication change.': '筛查结果仅用于辅助决策，任何用药变更都必须由医生或药师确认。',
  'Thresholds are screening cues, not treatment targets. The dialysis team must interpret them in clinical context.': '阈值仅用于风险筛查，并非治疗目标；应由透析团队结合临床情况解读。',
  'For emergency communication only. Verify identity, medication list, and allergies with the patient or treating team.': '仅用于急救沟通。请与患者或诊疗团队核验身份、用药清单和过敏史。',
  'A critical reading was detected. Repeat the measurement now and contact a clinician promptly if symptoms are present.': '存在严重异常，请立即复测；如伴随明显不适，请按医嘱联系医生或及时就医。',
  'No recent blood pressure record is available. Consider taking one resting measurement.': '暂无近期血压记录，建议在静息状态下测量一次。',
  'The latest blood-pressure reading is outside the usual range. Rest, repeat the measurement, and continue recording results.': '最近血压超出常用范围，建议休息后复测并持续记录。',
  'No recent blood glucose record is available. Add one if it is part of the care plan.': '暂无近期血糖记录；如照护计划包含血糖监测，请补充一次。',
  'Monitoring coverage is limited. Regular entries will make trend assessments more reliable.': '当前监测数据覆盖不足，持续记录后趋势判断会更可靠。',
  'No urgent items are present. Continue recording measurements, medications, and follow-up visits as planned.': '当前没有紧急事项，请继续按计划记录、用药和复查。'
}

const patterns = [
  [/^Failed to save: /, '保存失败：'],
  [/^Update failed: /, '更新失败：'],
  [/^Failed to load: /, '加载失败：'],
  [/^Failed to read file: /, '读取文件失败：'],
  [/^Failed to parse file: /, '解析文件失败：'],
  [/^Unsupported data type: /, '不支持的数据类型：'],
  [/^Unsupported file format: /, '不支持的文件格式：'],
  [/^Notification delivery failed: /, '通知发送失败：'],
  [/^Remaining /, '剩余 '],
  [/^Low medication stock · /, '药品库存不足 · ']
]

export function localizeServerText(value) {
  if (typeof value !== 'string') return value
  if (exact[value]) return exact[value]
  for (const [pattern, replacement] of patterns) {
    if (pattern.test(value)) return value.replace(pattern, replacement)
  }
  return value
}

export function localizePayload(value, seen = new WeakSet()) {
  if (typeof value === 'string') return localizeServerText(value)
  if (!value || typeof value !== 'object' || value instanceof Blob || value instanceof ArrayBuffer) return value
  if (seen.has(value)) return value
  seen.add(value)
  if (Array.isArray(value)) return value.map(item => localizePayload(item, seen))
  for (const key of Object.keys(value)) value[key] = localizePayload(value[key], seen)
  return value
}
