package org.familyhealthcare.service;

import org.familyhealthcare.entity.ComplicationRecord;
import org.familyhealthcare.vo.ComplicationStatsVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * Complication TrackingrecordService
 */
public interface ComplicationRecordService extends IService<ComplicationRecord> {

    List<ComplicationRecord> listByPatient(Long patientId);

    boolean saveRecord(ComplicationRecord record);

    boolean updateRecord(ComplicationRecord record);

    boolean deleteOwned(Long id);

    ComplicationStatsVO getStats(Long patientId);
}
