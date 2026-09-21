package org.familyhealthcare.service;

import org.familyhealthcare.entity.NutritionDiary;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * Nutrition Diary Service
 */
public interface NutritionDiaryService extends IService<NutritionDiary> {

    /** by PatientqueryNutrition Diarylist */
    List<NutritionDiary> listByPatient(Long patientId);

    /** Addrecord (verify ownership)  */
    boolean saveOwned(NutritionDiary record);

    /** Saveor updaterecord (verify ownership)  */
    boolean saveOrUpdateRecord(NutritionDiary record);

    /** updaterecord (verify ownership)  */
    boolean updateOwned(NutritionDiary record);

    /** getrecordDetails (verify ownership)  */
    NutritionDiary getOwnedById(Long id);
    /** Deleterecord (verify ownership)  */
    boolean deleteOwned(Long id);
}
