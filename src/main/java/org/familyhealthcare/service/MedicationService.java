package org.familyhealthcare.service;

import org.familyhealthcare.entity.Medication;
import org.familyhealthcare.entity.MedicationLog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * Medicationservice interface
 */
public interface MedicationService extends IService<Medication> {

    /**
     * Savemedicationrecord
     */
    boolean saveLog(MedicationLog log);

    /**
     * updatemedicationrecord
     */
    boolean updateLog(MedicationLog log);

    /**
     * queryPatient medicationrecord
     */
    List<MedicationLog> listLogs(Long patientId, String patientName);

    /**
     * querycurrentuserAllmedicationrecord
     */
    List<MedicationLog> listAllLogs();

    /**
     * Deletemedicationrecord
     */
    boolean deleteLog(Long id);

    /**
     * gethas Enabled Medicationlist
     */
    List<Medication> listActiveMedications(Long patientId);

    /**
     * queryMedicationlist (supportby Patientfilter)
     */
    List<Medication> list(Long patientId);

    /**
     * batchSaveMedication
     */
    boolean saveMedicationsBatch(List<Medication> medications);
}