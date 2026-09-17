package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.entity.DryWeightMonthly;
import org.familyhealthcare.mapper.DialysisRecordMapper;
import org.familyhealthcare.service.DialysisRecordService;
import org.familyhealthcare.service.DryWeightMonthlyService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.DialysisStatsVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dialysis RecordsServiceimplement
 */
@Service
public class DialysisRecordServiceImpl extends ServiceImpl<DialysisRecordMapper, DialysisRecord> implements DialysisRecordService {

    @Autowired
    private DryWeightMonthlyService dryWeightMonthlyService;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired private org.familyhealthcare.service.DialysisScheduleService scheduleService;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean saveRecord(DialysisRecord record) {
        Long userId = dataScopeHelper.requireUserId();
        record.setUserId(userId);
        requirePatientAndUniqueDate(record.getPatientId(), record.getRecordDate(), null);
        calculateDerivedFields(record);
        boolean saved;
        try {
            saved = save(record);
        } catch (DuplicateKeyException e) {
            throw duplicateDate(record.getRecordDate());
        }
        if (saved && record.getPatientId() != null) scheduleService.list(record.getPatientId());
        return saved;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean updateRecord(DialysisRecord record) {
        DialysisRecord existing = getById(record.getId());
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        if (record.getPatientId() != null) {
            dataScopeHelper.requirePatient(record.getPatientId());
        }
        Long patientId = record.getPatientId() != null ? record.getPatientId() : existing.getPatientId();
        LocalDate recordDate = record.getRecordDate() != null ? record.getRecordDate() : existing.getRecordDate();
        requirePatientAndUniqueDate(patientId, recordDate, record.getId());
        calculateDerivedFields(record);
        boolean saved;
        try {
            saved = updateById(record);
        } catch (DuplicateKeyException e) {
            throw duplicateDate(recordDate);
        }
        if (saved) {
            if (existing.getPatientId() != null) scheduleService.list(existing.getPatientId());
            if (record.getPatientId() != null && !java.util.Objects.equals(existing.getPatientId(), record.getPatientId())) scheduleService.list(record.getPatientId());
        }
        return saved;
    }

    private void requirePatientAndUniqueDate(Long patientId, LocalDate recordDate, Long excludedId) {
        if (patientId == null) throw new IllegalArgumentException("Select a patient");
        if (recordDate == null) throw new IllegalArgumentException("SelectDialysis Date");
        dataScopeHelper.requirePatient(patientId);
        QueryWrapper<DialysisRecord> query = new QueryWrapper<DialysisRecord>()
                .eq("patient_id", patientId)
                .eq("record_date", recordDate);
        if (excludedId != null) query.ne("id", excludedId);
        if (baseMapper.selectCount(query) > 0) throw duplicateDate(recordDate);
    }

    private IllegalArgumentException duplicateDate(LocalDate recordDate) {
        return new IllegalArgumentException(recordDate + "  already has a dialysis record. Edit the existing record instead of creating a duplicate.");
    }

    /**
     * Automaticcalculatederived field (intervaldayscountAutomaticinference, Dry Weightfrom Monthly tablequery)
     */
    private void calculateDerivedFields(DialysisRecord record) {
        // 1. Automaticinferenceintervaldayscount (based onmost recent oneitemsrecord Datedifference) —— datamissingrecordalsoreference and , keepTimeordercolumncontinuous
        if (record.getIntervalDays() == null && !record.isTextImport()) {
            Integer inferredDays = inferIntervalDays(record);
            record.setIntervalDays(inferredDays != null ? inferredDays : 2);
        }

        // dataincompleterecord: onlykeepintervaldayscount, clearemptyhas Weightrelatedderived field, avoidimageresponsestatistics
        if ("INCOMPLETE".equals(record.getRecordType())) {
            record.setWeightGain(null);
            if (!record.isTextImport()) record.setUfAmount(null);
            record.setDailyWeightGain(null);
            record.setDehydrationStatus(null);
            record.setWeight3pct(null);
            record.setWeight5pct(null);
            return;
        }

        BigDecimal lastOff = record.getLastOffWeight();
        BigDecimal onWeight = record.getOnWeight();
        BigDecimal offWeight = record.getOffWeight();

        // 2. from Monthly Dry WeighttablequeryDry Weight (onlyused forcalculatethreshold, not storeenterDialysis Records)
        BigDecimal dryWeight = inferDryWeight(record);
        Integer intervalDays = record.getIntervalDays();

        // 3. interdialytic weight gain = this timespre-dialysis - up timespost-dialysis
        if (lastOff != null && onWeight != null) {
            record.setWeightGain(onWeight.subtract(lastOff).setScale(2, RoundingMode.HALF_UP));
        }

        // 4. ultrafiltration volume = this timespre-dialysis - this timespost-dialysis
        if (onWeight != null && offWeight != null && (!record.isTextImport() || record.getUfAmount() == null)) {
            record.setUfAmount(onWeight.subtract(offWeight).setScale(2, RoundingMode.HALF_UP));
        }

        // 5. Average daily weight gain = interdialytic weight gain / intervaldayscount
        if (record.getWeightGain() != null && intervalDays != null && intervalDays > 0) {
            record.setDailyWeightGain(record.getWeightGain()
                    .divide(new BigDecimal(intervalDays), 2, RoundingMode.HALF_UP));
        }

        // 6. Dry Weight 3% and 5%
        if (dryWeight != null) {
            record.setWeight3pct(dryWeight.multiply(new BigDecimal("0.03"))
                    .setScale(2, RoundingMode.HALF_UP));
            record.setWeight5pct(dryWeight.multiply(new BigDecimal("0.05"))
                    .setScale(2, RoundingMode.HALF_UP));
        }

        // 7. fluid removalStatusdetermine
        if (record.getUfAmount() != null && record.getWeightGain() != null) {
            BigDecimal diff = record.getUfAmount().subtract(record.getWeightGain());
            BigDecimal threshold = new BigDecimal("0.3"); // 0.3kgerrordifferencethreshold
            if (diff.compareTo(threshold) > 0) {
                record.setDehydrationStatus("TOO_MUCH"); // Excessive ultrafiltration (dialysiscompared withincrease multipletoomultiple)
            } else if (diff.compareTo(threshold.negate()) < 0) {
                record.setDehydrationStatus("INSUFFICIENT"); // Insufficient ultrafiltration (dialysisnot enough)
            } else {
                record.setDehydrationStatus("MATCH"); // match
            }
        }
    }

    /**
     * inferenceintervaldayscount: checkfindDatemost recent  before oneitemsrecord, calculateDateinterval
     */
    private Integer inferIntervalDays(DialysisRecord record) {
        if (record.getRecordDate() == null) return null;
        Long userId = CurrentUserUtil.getCurrentUserId();
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.lt("record_date", record.getRecordDate())
                .orderByDesc("record_date")
                .last("limit 1");
        if (record.getPatientId() != null) {
            qw.eq("patient_id", record.getPatientId());
        }
        if (record.getId() != null) {
            qw.ne("id", record.getId());
        }
        DialysisRecord last = baseMapper.selectOne(qw);
        if (last != null && last.getRecordDate() != null) {
            long days = ChronoUnit.DAYS.between(last.getRecordDate(), record.getRecordDate());
            return (int) days;
        }
        return null;
    }

    /**
     * inferenceDry Weight: onlyfrom Dry WeightMonthly tablequery
     */
    private BigDecimal inferDryWeight(DialysisRecord record) {
        if (record.getRecordDate() != null) {
            String yearMonth = record.getRecordDate().toString().substring(0, 7); // yyyy-MM
            return dryWeightMonthlyService.getDryWeightByMonth(yearMonth, record.getPatientId());
        }
        return null;
    }

    @Override
    public DialysisStatsVO getStatistics(String timeType, String timeValue, Long patientId) {
        Long userId = CurrentUserUtil.getCurrentUserId();
        DialysisStatsVO stats = new DialysisStatsVO();
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.orderByAsc("record_date");
        applyTimeFilter(qw, timeType, timeValue);
        List<DialysisRecord> list = list(qw);

        if (list.isEmpty()) {
            stats.setTotalCount(0L);
            return stats;
        }

        stats.setTotalCount((long) list.size());

        // pointawayNormalrecord and missingrecord
        List<DialysisRecord> normalList = list.stream()
                .filter(r -> !"INCOMPLETE".equals(r.getRecordType()))
                .collect(Collectors.toList());

        // missingrecordcount (reflectrealDialysisfrequencyrate, butdataincomplete)
        stats.setIncompleteCount(list.stream().filter(r -> "INCOMPLETE".equals(r.getRecordType())).count());

        // intervaldayscountaveragevalue: useAllrecord (keepTimeordercolumncontinuousproperty)
        stats.setAvgIntervalDays(calcAvgInt(list.stream().map(DialysisRecord::getIntervalDays).collect(Collectors.toList())));

        // Weightrelatedaveragevalue: onlyuseNormalrecord (excludeestimatecalculate/missingdata)
        stats.setAvgOnWeight(calcAvg(normalList.stream().map(DialysisRecord::getOnWeight).collect(Collectors.toList())));
        stats.setAvgOffWeight(calcAvg(normalList.stream().map(DialysisRecord::getOffWeight).collect(Collectors.toList())));
        stats.setAvgWeightGain(calcAvg(normalList.stream().map(DialysisRecord::getWeightGain).collect(Collectors.toList())));
        stats.setAvgUfAmount(calcAvg(normalList.stream().map(DialysisRecord::getUfAmount).collect(Collectors.toList())));
        stats.setAvgDailyWeightGain(calcAvg(normalList.stream().map(DialysisRecord::getDailyWeightGain).collect(Collectors.toList())));

        // Blood Pressureaveragevalue: Blood PressureFaircanrecordstay, useAllrecord; ifalsoneedexcludemissingrecordcan changefor  normalList
        stats.setAvgSystolicBp(calcAvgInt(list.stream().map(DialysisRecord::getSystolicBp).collect(Collectors.toList())));
        stats.setAvgDiastolicBp(calcAvgInt(list.stream().map(DialysisRecord::getDiastolicBp).collect(Collectors.toList())));

        List<BigDecimal> gains = list.stream().map(DialysisRecord::getWeightGain).filter(Objects::nonNull).collect(Collectors.toList());
        if (!gains.isEmpty()) {
            stats.setMaxWeightGain(gains.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO));
            stats.setMinWeightGain(gains.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO));
        }

        long over5 = 0, ideal = 0, under3 = 0, over3Compat = 0;
        for (DialysisRecord r : list) {
            String cat = classifyWeightGain(r);
            if ("OVER5".equals(cat)) {
                over5++;
            } else if ("IDEAL".equals(cat)) {
                ideal++;
            } else if ("UNDER3".equals(cat)) {
                under3++;
            }
            if (r.getWeightGain() != null && resolveWeight3pct(r) != null
                    && r.getWeightGain().compareTo(resolveWeight3pct(r)) > 0) {
                over3Compat++;
            }
        }
        stats.setOver5pctCount(over5);
        stats.setIdealGainCount(ideal);
        stats.setUnder3pctCount(under3);
        stats.setOver3pctCount(over3Compat);

        // fluid removalStatusstatistics
        // fluid removalStatusstatistics: onlyuseNormalrecord
        stats.setTooMuchCount(normalList.stream().filter(r -> "TOO_MUCH".equals(r.getDehydrationStatus())).count());
        stats.setInsufficientCount(normalList.stream().filter(r -> "INSUFFICIENT".equals(r.getDehydrationStatus())).count());
        stats.setMatchCount(normalList.stream().filter(r -> "MATCH".equals(r.getDehydrationStatus())).count());

        long normalTotal = normalList.size();
        if (normalTotal > 0) {
            stats.setDehydrationMatchRate(
                    new BigDecimal(stats.getMatchCount() * 100.0 / normalTotal).setScale(1, RoundingMode.HALF_UP));
        }

        // Blood PressureAbnormalstatistics: useAllhas Blood Pressuredata record
        stats.setBpAbnormalCount(list.stream().filter(this::isBpAbnormal).count());
        stats.setBpSysAbnormalCount(list.stream().filter(r -> isBpSysAbnormal(r.getSystolicBp())).count());
        stats.setBpDiaAbnormalCount(list.stream().filter(r -> isBpDiaAbnormal(r.getDiastolicBp())).count());

        stats.setMonthlyStats(buildMonthlyStats(list));

        return stats;
    }

    @Override
    public DialysisStatsVO getChartData(String timeType, String timeValue, Long patientId) {
        DialysisStatsVO vo = new DialysisStatsVO();
        Long userId = CurrentUserUtil.getCurrentUserId();
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<DialysisRecord>().orderByAsc("record_date");
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        applyTimeFilter(qw, timeType, timeValue);
        List<DialysisRecord> list = list(qw);

        List<String> dateList = new ArrayList<>();
        List<BigDecimal> onWeightList = new ArrayList<>();
        List<BigDecimal> offWeightList = new ArrayList<>();
        List<BigDecimal> dryWeightList = new ArrayList<>();
        List<BigDecimal> weightGainList = new ArrayList<>();
        List<BigDecimal> ufAmountList = new ArrayList<>();
        List<Integer> systolicBpList = new ArrayList<>();
        List<Integer> diastolicBpList = new ArrayList<>();
        List<BigDecimal> weight3pctList = new ArrayList<>();
        List<BigDecimal> weight5pctList = new ArrayList<>();
        List<BigDecimal> dailyWeightGainList = new ArrayList<>();

        for (DialysisRecord r : list) {
            dateList.add(r.getRecordDate().toString());
            // dataincompleterecord: Weightrelatedfieldsetfor  null, charttabledisplayfor breakpoint, not imageresponsevisualtrend
            if ("INCOMPLETE".equals(r.getRecordType())) {
                onWeightList.add(null);
                offWeightList.add(null);
                weightGainList.add(null);
                ufAmountList.add(null);
                dailyWeightGainList.add(null);
                weight3pctList.add(null);
                weight5pctList.add(null);
            } else {
                onWeightList.add(r.getOnWeight());
                offWeightList.add(r.getOffWeight());
                weightGainList.add(r.getWeightGain());
                ufAmountList.add(r.getUfAmount());
                dailyWeightGainList.add(r.getDailyWeightGain());
                weight3pctList.add(resolveWeight3pct(r));
                weight5pctList.add(resolveWeight5pct(r));
            }
            // Blood Pressure and Dry Weightalwayskeep (Blood PressureFaircanrecordstay, Dry WeightcomeselfMonthly table)
            dryWeightList.add(inferDryWeight(r));
            systolicBpList.add(r.getSystolicBp());
            diastolicBpList.add(r.getDiastolicBp());
        }

        vo.setDateList(dateList);
        vo.setOnWeightList(onWeightList);
        vo.setOffWeightList(offWeightList);
        vo.setDryWeightList(dryWeightList);
        vo.setWeightGainList(weightGainList);
        vo.setUfAmountList(ufAmountList);
        vo.setSystolicBpList(systolicBpList);
        vo.setDiastolicBpList(diastolicBpList);
        vo.setWeight3pctList(weight3pctList);
        vo.setWeight5pctList(weight5pctList);
        vo.setDailyWeightGainList(dailyWeightGainList);

        return vo;
    }

    /** excellentfirstusedatabasewithinthreshold, missingtimeby when MonthDry Weightestimate ( and Savetimelogiconecause)  */
    private BigDecimal resolveWeight3pct(DialysisRecord r) {
        if (r.getWeight3pct() != null) {
            return r.getWeight3pct();
        }
        BigDecimal dry = inferDryWeight(r);
        if (dry == null) {
            return null;
        }
        return dry.multiply(new BigDecimal("0.03")).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveWeight5pct(DialysisRecord r) {
        if (r.getWeight5pct() != null) {
            return r.getWeight5pct();
        }
        BigDecimal dry = inferDryWeight(r);
        if (dry == null) {
            return null;
        }
        return dry.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
    }

    /** OVER5 / IDEAL / UNDER3 / UNKNOWN */
    private String classifyWeightGain(DialysisRecord r) {
        BigDecimal gain = r.getWeightGain();
        BigDecimal t3 = resolveWeight3pct(r);
        BigDecimal t5 = resolveWeight5pct(r);
        if (gain == null || t3 == null || t5 == null) {
            return "UNKNOWN";
        }
        if (gain.compareTo(t5) > 0) {
            return "OVER5";
        }
        if (gain.compareTo(t3) < 0) {
            return "UNDER3";
        }
        return "IDEAL";
    }

    private boolean isBpSysAbnormal(Integer sys) {
        return sys != null && (sys > 140 || sys < 120);
    }

    private boolean isBpDiaAbnormal(Integer dia) {
        return dia != null && (dia > 90 || dia < 70);
    }

    private boolean isBpAbnormal(DialysisRecord r) {
        return isBpSysAbnormal(r.getSystolicBp()) || isBpDiaAbnormal(r.getDiastolicBp());
    }

    private BigDecimal calcAvg(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(v -> v != null).collect(Collectors.toList());
        if (nonNull.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(nonNull.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calcAvgInt(List<Integer> values) {
        List<Integer> nonNull = values.stream().filter(v -> v != null).collect(Collectors.toList());
        if (nonNull.isEmpty()) return BigDecimal.ZERO;
        int sum = nonNull.stream().mapToInt(Integer::intValue).sum();
        return new BigDecimal(sum).divide(new BigDecimal(nonNull.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * from Monthly Dry WeighttablequeryspecifiedDate Dry Weight
     */
    private BigDecimal getDryWeightForDate(LocalDate date) {
        if (date == null) return null;
        String yearMonth = date.toString().substring(0, 7);
        if (yearMonth.isEmpty()) return null;
        // charttableDry Weightordercolumnby inferDryWeight(record) responsible for, hereonlykeepcompatiblepropertyBack
        return null;
    }

    /**
     * shoulduseTime dimensionFilteritemsitem
     */
    private List<Map<String, Object>> buildMonthlyStats(List<DialysisRecord> records) {
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, List<DialysisRecord>> grouped = records.stream()
                .filter(r -> r.getRecordDate() != null)
                .collect(Collectors.groupingBy(r -> r.getRecordDate().toString().substring(0, 7)));
        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    List<DialysisRecord> monthRecords = e.getValue();
                    row.put("month", e.getKey());
                    row.put("avg_weight_gain", calcAvg(monthRecords.stream()
                            .map(DialysisRecord::getWeightGain).collect(Collectors.toList())));
                    row.put("avg_uf_amount", calcAvg(monthRecords.stream()
                            .map(DialysisRecord::getUfAmount).collect(Collectors.toList())));
                    return row;
                })
                .collect(Collectors.toList());
    }

    private void applyTimeFilter(QueryWrapper<DialysisRecord> qw, String timeType, String timeValue) {
        if (timeValue == null || timeValue.isEmpty()) return;
        org.familyhealthcare.util.HealthDateRange range = org.familyhealthcare.util.HealthDateRange.of(timeType, timeValue);
        qw.ge("record_date", range.from).le("record_date", range.to);
    }

    @Override
    public List<DialysisRecord> list() {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.orderByDesc("record_date");
        return super.list(qw);
    }

    @Override
    public List<DialysisRecord> listByFilter(String timeType, String timeValue, Long patientId) {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.orderByDesc("record_date");
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        applyTimeFilter(qw, timeType, timeValue);
        return super.list(qw);
    }

    @Override
    public DialysisRecord getOwnedById(Long id) {
        DialysisRecord record = getById(id);
        if (record == null) {
            return null;
        }
        dataScopeHelper.requirePatientOrOwner(record.getPatientId(), record.getUserId());
        return record;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean deleteOwned(Long id) {
        DialysisRecord existing = getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        boolean deleted = removeById(id);
        if (deleted && existing.getPatientId() != null) scheduleService.list(existing.getPatientId());
        return deleted;
    }
}
