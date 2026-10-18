import { measurementText } from './measurementReadModelCopy.js';
const locale = 'en';
const copy = {
 correctionTitle:['Measurement corrections','测量记录更正'], correctionOpen:['Browse records to correct','浏览待更正记录'], correctionClose:['Close correction records','收起更正记录'],
 correctionScope:['Self-monitoring and health measurement records only. Laboratory reports and other record types are not included.','仅包含自测与健康测量记录，暂不包含化验报告等其他记录。'],
 correctionBaseline:['Revision history starts when a current baseline is first captured. Earlier changes may not be available.','修订历史从首次记录当前基线开始，更早的变更可能没有留存。'],
 correctionReview:['Review / correct / history','查看／更正／历史'], correctionNotes:['Notes are not loaded on this page; inspect the original record for any recorded note.','本页未加载备注，请查看原记录中已留存的备注。'],
 originalTitle:['Original measurement records','原始测量记录'], open:['Browse original records','浏览原始记录'], close:['Close original records','收起原始记录'],
 pageScope:['Only the current loaded page is shown. This is not a complete history or a clinical assessment.','仅显示当前加载页，并非完整历史或临床判定。'], allDates:['All dates, current page; records with missing dates can be included.','全部日期的当前页，可包含缺少日期的记录。'], rangeDates:['Selected date range; records without dates are excluded.','所选日期范围，不包含缺少日期的记录。'],
 empty:['No original records on this page.','本页没有原始记录。'], unassessed:['Original facts only; current verification, invalidation and assessment require the authorized trusted view.','仅为原始事实，当前核验、作废与判定请在获授权的可信视图中查看。'],
 pageLatestBp:['Most recent BP entry on this page','本页排序最近的血压记录'], pageLatestGlucose:['Most recent glucose entry on this page','本页排序最近的血糖记录'], pageCount:['Records on this page','本页记录数'], noReading:['No original reading for this metric','没有此指标的原始读数'],
 notes:['View original notes','查看原始备注'], noNotes:['No note was recorded','未记录备注'], detailLoading:['Loading this exact original record…','正在读取这条原始记录…'], detailError:['This exact record is unavailable. Retry before editing.','这条原始记录暂不可读，请重试后编辑。'],
 detailUnloaded:['Notes are available only after this exact record is loaded.','备注须读取这条记录的详情后才能查看或编辑。'], historyStatus:['Historical record marker','历史记录标记'],
 rawVerification:['Original verification marker','原核验标记'], missingContext:['Measurement information needs checking; no current clinical conclusion is provided here.','测量资料需核对，此处不提供当前临床结论。'],
 legacyRule:['Legacy-rule marker','旧规则标记'], legacyOverview:['Legacy-rule overview','旧规则概览'], legacySuggestions:['Legacy-rule suggestions','旧规则提示'], legacyNormal:['Legacy rule marked normal; current assessment is not established','旧规则标记为正常，不能作为本次已核判定'], legacyStable:['Legacy rule marked stable; current safety is not established','旧规则标记为稳定，不能据此确认当前安全'],
 generated:['Overview generated at','概览生成时间'], categories:['Recorded-category proportion in the legacy overview','旧概览已记录类别比例'], windowLatest:['Most recent recorded entry for each metric in the selected window; not a snapshot.','所选窗口内每项指标排序最近的原记录，并非快照。'],
 localTime:['Recorded local time; time zone not recorded','原记录当地时间，时区未记录'], timeUnconfirmed:['Time missing or invalid; needs confirmation','时刻缺失或无效，需核对'], dateUnconfirmed:['Date missing or invalid; needs confirmation','日期缺失或无效，需核对'],
 periodUnknown:['Meal period unknown / needs confirmation','餐时未知／需核对'], incompleteBp:['Blood-pressure pair is incomplete','血压成对数值不完整'], verificationNeeded:['Original verification needs checking','原核验状态待核对'],
 reportScope:['Up to 30 original records returned by the visit summary; not a complete measurement history.','就诊摘要返回的最多30条原记录，并非完整测量历史。'], rawChartHelp:['Assessable points are available in the trusted measurement view. Original records below remain table/card facts.','可评估散点请查看可信测量视图；下方原记录保留为表格／卡片事实。']
};
export const originalText = key => copy[key]?.[locale === 'zh' ? 1 : 0] ?? measurementText(key);
