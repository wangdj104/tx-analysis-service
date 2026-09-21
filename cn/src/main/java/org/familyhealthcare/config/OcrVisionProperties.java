package org.familyhealthcare.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ExaminationReportrecognizechart (Vision) API configuration, Defaulttoconnect to AlibabaincloudBailian DashScope compatiblemode.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ocr.vision")
public class OcrVisionProperties {

    /** Bailian API Key; be emptytimeuse bailian.api-key */
    private String apiKey = "";

    /** Bailian OpenAI compatibleAddress */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** laboratory testReport OCR recommend qwen-vl-ocr-latest; throughuserecognizechartcan use qwen-vl-plus */
    private String model = "qwen-vl-ocr-latest";

    private double temperature = 0.1;

    private int maxTokens = 8192;
}
