package org.familyhealthcare.service;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface BpSelfMonitorRecordService extends IService<BpSelfMonitorRecord> {

    List<BpSelfMonitorRecord> listByPatient(Long patientId);

    List<BpSelfMonitorRecord> listByPatientAndType(Long patientId, String measureType);

    boolean saveOwned(BpSelfMonitorRecord record);

    boolean updateOwned(BpSelfMonitorRecord record);

    boolean deleteOwned(Long id);
}
