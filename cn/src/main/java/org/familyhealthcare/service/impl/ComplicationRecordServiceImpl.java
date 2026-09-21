package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.ComplicationRecord;
import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.mapper.ComplicationRecordMapper;
import org.familyhealthcare.mapper.DialysisRecordMapper;
import org.familyhealthcare.service.ComplicationRecordService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.ComplicationStatsVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Complication TrackingrecordServiceimplement
 */
@Service
public class ComplicationRecordServiceImpl extends ServiceImpl<ComplicationRecordMapper, ComplicationRecord> implements ComplicationRecordService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private DialysisRecordMapper dialysisRecordMapper;

    @Override
    public List<ComplicationRecord> listByPatient(Long patientId) {
        if (patientId == null) {
            return Collections.emptyList();
        }
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<ComplicationRecord> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.eq("patient_id", patientId).orderByDesc("occurrence_date");
        List<ComplicationRecord> records = baseMapper.selectList(qw);
        enrichWithDialysis(records);
        return records;
    }

    /**
     * batchpopulaterelatedDialysis Records temporarytimefield
     */
    private void enrichWithDialysis(List<ComplicationRecord> records) {
        Set<Long> dialysisIds = records.stream()
                .filter(r -> r.getRelatedDialysisId() != null)
                .map(ComplicationRecord::getRelatedDialysisId)
                .collect(Collectors.toSet());
        if (dialysisIds.isEmpty()) return;

        List<DialysisRecord> dialysisList = dialysisRecordMapper.selectBatchIds(dialysisIds);
        Map<Long, DialysisRecord> lookup = dialysisList.stream()
                .collect(Collectors.toMap(DialysisRecord::getId, Function.identity()));

        for (ComplicationRecord r : records) {
            if (r.getRelatedDialysisId() != null) {
                DialysisRecord d = lookup.get(r.getRelatedDialysisId());
                if (d != null) {
                    r.setDialysisRecordDate(d.getRecordDate());
                    r.setDialysisOnWeight(d.getOnWeight());
                    r.setDialysisOffWeight(d.getOffWeight());
                    r.setDialysisWeightGain(d.getWeightGain());
                    r.setDialysisUfAmount(d.getUfAmount());
                    r.setDialysisSystolicBp(d.getSystolicBp());
                    r.setDialysisDiastolicBp(d.getDiastolicBp());
                    r.setDialysisDehydrationStatus(d.getDehydrationStatus());
                }
            }
        }
    }

    @Override
    public boolean saveRecord(ComplicationRecord record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
        dataScopeHelper.requirePatient(record.getPatientId());
        record.setUserId(userId);
        record.setPatientName(dataScopeHelper.resolvePatientName(record.getPatientId()));
        return save(record);
    }

    @Override
    public boolean updateRecord(ComplicationRecord record) {
        ComplicationRecord existing = getById(record.getId());
        if (existing == null) {
            throw new IllegalStateException("The record does not exist.");
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        // samesteppatientName
        if (record.getPatientId() != null) {
            record.setPatientName(dataScopeHelper.resolvePatientName(record.getPatientId()));
        }
        return updateById(record);
    }

    @Override
    public boolean deleteOwned(Long id) {
        ComplicationRecord existing = getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }

    @Override
    public ComplicationStatsVO getStats(Long patientId) {
        if (patientId == null) {
            return new ComplicationStatsVO();
        }
        dataScopeHelper.requirePatient(patientId);

        ComplicationStatsVO stats = new ComplicationStatsVO();
        // total
        QueryWrapper<ComplicationRecord> countQw = new QueryWrapper<>();
        countQw.eq("patient_id", patientId);
        stats.setTotalCount(baseMapper.selectCount(countQw));

        // eachtypecount
        stats.setTypeCounts(baseMapper.countByType(patientId));

        // eachSeverelevelcount
        stats.setSeverityCounts(baseMapper.countBySeverity(patientId));

        // recent 5itemsrecord
        QueryWrapper<ComplicationRecord> recentQw = new QueryWrapper<>();
        recentQw.eq("patient_id", patientId).orderByDesc("occurrence_date").last("limit 5");
        List<ComplicationRecord> recent = baseMapper.selectList(recentQw);
        enrichWithDialysis(recent);
        stats.setRecentList(recent.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("type", r.getComplicationType());
            m.put("severity", r.getSeverity());
            m.put("date", r.getOccurrenceDate() != null ? r.getOccurrenceDate().toString() : "");
            m.put("description", r.getDescription() != null ? r.getDescription() : "");
            if (r.getRelatedDialysisId() != null) {
                m.put("relatedDialysisId", r.getRelatedDialysisId());
                StringBuilder summary = new StringBuilder();
                if (r.getDialysisWeightGain() != null) summary.append("weight gain").append(r.getDialysisWeightGain()).append("kg");
                if (r.getDialysisSystolicBp() != null || r.getDialysisDiastolicBp() != null) {
                    if (summary.length() > 0) summary.append(" | ");
                    summary.append("Blood Pressure").append(r.getDialysisSystolicBp() != null ? r.getDialysisSystolicBp() : "-").append("/").append(r.getDialysisDiastolicBp() != null ? r.getDialysisDiastolicBp() : "-");
                }
                m.put("dialysisSummary", summary.toString());
            }
            return m;
        }).collect(Collectors.toList()));

        return stats;
    }
}
