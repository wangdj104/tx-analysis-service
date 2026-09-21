package org.familyhealthcare.service;

import org.familyhealthcare.entity.Patient;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

public interface PatientService extends IService<Patient> {

    List<Map<String, Object>> getPatientNames();

    Patient getOwnedById(Long id);

    boolean updateOwned(Patient patient);

    boolean deleteOwned(Long id);
}
