package org.familyhealthcare.service;

import org.familyhealthcare.vo.MonitoringSnapshotVO;

public interface MonitoringService {
    MonitoringSnapshotVO getSnapshot(Long patientId, Integer days);
}
