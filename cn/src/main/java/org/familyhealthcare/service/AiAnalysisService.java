package org.familyhealthcare.service;

import org.familyhealthcare.entity.AiAnalysisRecord;
import org.familyhealthcare.vo.AiAnalysisResultVO;

import java.util.List;
import java.util.Collection;

/**
 * AI analysisservice
 */
public interface AiAnalysisService {

    /**
     * call DeepSeek analysisDialysisdata
     * @param timeType Time dimension
     * @param timeValue Timevalue
     * @return AI analysisresult (containstructuredfield)
     */
    AiAnalysisResultVO analyzeDialysisData(String timeType, String timeValue, Long patientId);

    /** by selecthealthmoduleanalysismost recent onesectionTime data, provideAutomatictaskuse.  */
    String analyzeHealthSnapshot(Long patientId, int rangeDays, Collection<String> analysisItems);

    /**
     * SaveAIanalysisrecord
     */
    boolean saveAnalysis(AiAnalysisRecord record);

    /**
     * queryanalysishistorylist
     */
    List<AiAnalysisRecord> listHistory(String timeType, String timeValue, Long patientId);

    /**
     * Deleteanalysisrecord
     */
    boolean deleteAnalysis(Long id);
}
