package org.familyhealthcare.service;

import org.familyhealthcare.entity.PatientClinical;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * Patientclinical detailsinformationService
 */
public interface PatientClinicalService extends IService<PatientClinical> {

    /**
     * based onPatientIDgetclinicalinformation
     */
    PatientClinical getByPatientId(Long patientId);

    /**
     * Saveor updateclinicalinformation (each Patientonlyoneitemsrecord)
     */
    boolean saveOrUpdateClinical(PatientClinical record);

    /**
     * Deleteclinicalinformation (containownershipvalidate)
     */
    boolean deleteOwned(Long id);
}
