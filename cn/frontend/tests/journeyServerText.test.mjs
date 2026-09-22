import assert from 'node:assert/strict'
import test from 'node:test'
import { localizeServerText } from '../src/utils/serverText.js'
test('consultation and assessment errors are fully localized with dynamic numeric limits',()=>{
  assert.equal(localizeServerText('symptom is required.'),'症状或咨询问题不能为空。')
  assert.equal(localizeServerText('content exceeds 10000 characters.'),'消息内容不能超过 10000 个字符。')
  assert.equal(localizeServerText('PHQ9 requires 9 item scores.'),'PHQ9 需要填写 9 题得分。')
  assert.equal(localizeServerText('GAD7 item scores must be between 0 and 3.'),'GAD7 各题得分必须在 0 到 3 之间。')
  assert.equal(localizeServerText('The entered text is too long (maximum 500 characters).'),'输入内容过长，最多允许 500 个字符。')
  assert.equal(localizeServerText('Consultation is closed.'),'本次问诊已结束，无法继续发送消息或加入通话。')
  assert.equal(localizeServerText('Only a booked appointment can be completed.'),'只能将已预约的就诊标记为完成。')
  assert.equal(localizeServerText('Only the selected clinician can complete this appointment.'),'仅本次预约医生可完成就诊。')
  assert.equal(localizeServerText('Blood glucose unit must be mmol/L or mg/dL.'),'血糖单位必须为 mmol/L 或 mg/dL。')
  assert.equal(localizeServerText('Measurement unit must be mmHg for BP.'),'BP 指标的单位必须为 mmHg。')
})
