package org.familyhealthcare.service;

import java.util.List;
import java.util.Map;

/**
 * AIchartimagerecognitionservice interface
 */
public interface AiOcrService {

    /**
     * recognitionExaminationReportimagein textcharacter and structureddata
     * @param base64Image Base64Code imagedata
     * @param recordType Examination Type
     * @return recognitionresult (includeExamination item details)
     */
    Map<String, Object> recognizeMedicalReport(String base64Image, String recordType);

    /**
     * recognitionmultipleimagesExaminationReportimage, mergeresult
     * @param base64Images Base64Code imagedatalist
     * @param recordType Examination Type
     * @return mergeafter  recognitionresult
     */
    Map<String, Object> recognizeMedicalReport(List<String> base64Images, String recordType);

    /**
     * recognitionExaminationReportimageURL
     * @param imageUrl imageURL
     * @param recordType Examination Type
     * @return recognitionresult
     */
    /**
     * recognitionMedicationpackage/instructionsdocumentimage
     * @param base64Image Base64Code imagedata
     * @return recognitionresult (includeMedicationinformationfield)
     */
    Map<String, Object> recognizeMedication(String base64Image);

    /**
     * recognitionmultipleimagesMedicationimage, mergeresult
     * @param base64Images Base64Code imagedatalist
     * @return mergeafter  recognitionresult
     */
    Map<String, Object> recognizeMedication(List<String> base64Images);
}
