package org.familyhealthcare.service.impl;

import org.familyhealthcare.config.OcrVisionProperties;
import org.familyhealthcare.service.AiOcrService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * ExaminationReportrecognizechart: call OpenAI compatible multiplemodal Chat Completions (image_url + text) .
 */
@Service
public class AiOcrServiceImpl implements AiOcrService {

    @Value("${bailian.api-key:}")
    private String bailianApiKey;

    @Autowired
    private OcrVisionProperties visionProps;

    @Autowired
    private RestTemplate restTemplate;

    private static final String OCR_PROMPT_TEMPLATE =
            "youYesoneprofessional medicallaboratory testReportrecognitionassistant. userUploadmultipleimagesExaminationReportimage (can cancomeselfsameonecopy PDF  not samepage) . Please recognitionhas laboratory testitemitem, andby Examination Typesplitafter Backstrictgrid JSON. \n" +
            "\n" +
            "Please by todown formatBack (mustuse records countgroup) : \n" +
            "{\n" +
            "  \"records\": [\n" +
            "    {\n" +
            "      \"recordType\": \"BLOOD|URINE|KIDNEY|LIVER|BONE|IRON|IMAGE|OTHER ofone\",\n" +
            "      \"hospitalName\": \"HospitalName (not findto fill null) \",\n" +
            "      \"checkDate\": \"Examination Date yyyy-MM-dd (not findto fill null) \",\n" +
            "      \"doctorName\": \"ClinicianName (not findto fill null) \",\n" +
            "      \"items\": [\n" +
            "        {\n" +
            "          \"name\": \"ExaminationitemName\",\n" +
            "          \"value\": \"measured value\",\n" +
            "          \"unit\": \"Unit (nohas then null) \",\n" +
            "          \"referenceRange\": \"Reference Range (nohas then null) \",\n" +
            "          \"isAbnormal\": \"high/low/Normal\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "\n" +
            "heavyneedneedrequest: \n" +
            "1. mustBackcombinemethod JSON, not need markdown replacecodeblock or additionalinstructions\n" +
            "2. ifReportcontainmultipletypeExamination (for example bloodoftenrule, urineoftenrule, biochemistry, tumorlabellogitem) , mustsplitcompletemultipleitems records, each itemscorrespondingonetype recordType, items onlyplacethis type itemitem\n" +
            "3. BLOOD=bloodoftenrule/bloodbiochemistryetc.blooditem; URINE=urineoftenrule; KIDNEY=kidneyfeature; LIVER=liverfeature; BONE=bone metabolism; IRON=iron metabolism; IMAGE=imaging; OTHER=Nonemethodcategorize\n" +
            "4. ifwholecopyReportonlyhas onetypetype, records alsoonlyneedoneitems\n" +
            "5. each Examinationitemmustinclude name, value, unit, referenceRange, isAbnormal; isAbnormal can onlyYeshigh/low/Normal\n" +
            "6. tablegridReportPlease eachrowread, not needomit; Nonedatatime items for  []\n" +
            "7. sameoneindicatorin sameone record withindo notduplicate (measured value+Unit+Reference Rangerelativesameviewfor duplicate) ";

    private static final String MEDICATION_PROMPT_TEMPLATE =
            "youYesoneprofessional Medicationinformationrecognitionassistant. userUploadmultipleimagesMedicationpackage or instructionsdocumentimage, each imagescan canincludeonetype or multipletypeMedication. Please carefullyViewhas image, recognitionoutputhas not same Medication, andBackstrictgrid JSONformatdata. \n" +
            "\n" +
            "Please by todown formatBack (mustuse drugs countgroup) : \n" +
            "{\n" +
            "  \"drugs\": [\n" +
            "    {\n" +
            "      \"drugName\": \"Medication Name (productname, for example : visitnewsame, if unavailable, enternull) \",\n" +
            "      \"genericName\": \"Generic Name/chemistryname (for example : nifedipineflatcontrolexplaintablet, if unavailable, enternull) \",\n" +
            "      \"specification\": \"Specification (for example : 30mg*7tablet, if unavailable, enternull) \",\n" +
            "      \"unit\": \"Unit (tablet/dose/bottle/box/itemetc., if unavailable, enternull) \",\n" +
            "      \"dosageForm\": \"dosage form (Please Backtodown ofone: tablet/capsule/injection/oral solution/powder, Nonemethodrecognitionthenfillnull) \",\n" +
            "      \"manufacturer\": \"manufacturer (if unavailable, enternull) \",\n" +
            "      \"approvalNumber\": \"approval number (for example : SinopharmaccuratecharacterH20240001, if unavailable, enternull) \",\n" +
            "      \"category\": \"Medicationcategory (Please Backtodown ofone: antihypertensive/phosphate binder/iron supplement/vitamin/erythropoietin/calcium supplement/active vitamin DD/diuretic/antibiotic/Other, Nonemethodrecognitionthenfillnull) \",\n" +
            "      \"defaultDosage\": \"DefaultDose instructions (for example : each times1tablet, each days1times, if unavailable, enternull) \",\n" +
            "      \"remark\": \"Otherheavyneedinformation (if unavailable, enternull) \"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "\n" +
            "heavyneedneedrequest: \n" +
            "1. mustBackcombinemethodJSON, not needhas any markdown replacecodeblockmark or Othertextcharacterinstructions\n" +
            "2. ifimageinhas multipletypenot sameMedication, mustin  drugs countgroupinpointothercolumnoutputeach typeMedication\n" +
            "3. ifmultipleimagesimageYessameonetypeMedication not samecornerlevel/page, onlyBackoneitemsthis Medication information (mergeinformation) \n" +
            "4. has fieldallmustinclude, nohas information fieldwrite null, not needomitthis field\n" +
            "5. dosageForm can onlyBack: tablet/capsule/injection/oral solution/powder itsinofone,  or  null\n" +
            "6. category can onlyBack: antihypertensive/phosphate binder/iron supplement/vitamin/erythropoietin/calcium supplement/active vitamin DD/diuretic/antibiotic/Other itsinofone,  or  null\n" +
            "7. ifNonemethodrecognitionimagecontent, drugs Backemptycountgroup []";

    @Override
    public Map<String, Object> recognizeMedicalReport(String base64Image, String recordType) {
        return recognizeMedicalReport(Collections.singletonList(base64Image), recordType);
    }

    @Override
    public Map<String, Object> recognizeMedicalReport(List<String> base64Images, String recordType) {
        Map<String, Object> result = new HashMap<>();

        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            result.put("error", "not configuration OCR secret, Please settings OCR_API_KEY  or  BAILIAN_API_KEY environmentvariable. ");
            return result;
        }

        String prompt = OCR_PROMPT_TEMPLATE;
        if (recordType != null && !recordType.isEmpty()) {
            prompt = prompt.replace("recordType", "recordType (excellentfirstrecognitionfor " + recordType + ") ");
        }

        List<String> imageDataUris = new ArrayList<>();
        for (String base64Image : base64Images) {
            String mime = guessMimeType(base64Image);
            imageDataUris.add("data:" + mime + ";base64," + stripDataUriPrefix(base64Image));
        }

        String model = visionProps.getModel();
        String baseUrl = visionProps.getBaseUrl();
        String rawResult = callVisionApi(apiKey, baseUrl, model, prompt, imageDataUris);
        result.put("rawResult", rawResult);

        JSONObject errJson = extractJson(rawResult);
        if (errJson != null && errJson.containsKey("error")) {
            String err = errJson.getString("error");
            result.put("error", err);
            if (err != null && (err.contains("image_url") || err.contains("unknown variant"))) {
                result.put("error", err + ". currentmodel「" + model + "」not supportimageinput, Please changeusevisualmodel (for example  gpt-4o-mini, qwen-vl-plus) , seeconfiguration ocr.vision.model. ");
            }
            return result;
        }

        try {
            JSONObject json = extractJson(rawResult);
            if (json == null) {
                result.put("error", "AI BackcontentNonemethodparsefor  JSON, Please changemoreclearimage or morechangerecognizechartmodelafter retry. ");
                return result;
            }

            result.put("data", json);

            List<Map<String, Object>> records = parseMedicalRecords(json);
            result.put("records", records);

            if (!records.isEmpty()) {
                Map<String, Object> first = records.get(0);
                result.put("recordType", first.get("recordType"));
                result.put("hospitalName", first.get("hospitalName"));
                result.put("checkDate", first.get("checkDate"));
                result.put("doctorName", first.get("doctorName"));
                result.put("items", first.get("items"));
            }

            int totalItems = 0;
            for (Map<String, Object> rec : records) {
                Object itemsObj = rec.get("items");
                if (itemsObj instanceof List) {
                    totalItems += ((List<?>) itemsObj).size();
                }
            }
            if (records.size() > 1) {
                String splitHint = "already by Examination Typesplitfor  " + records.size() + " records, Savetimewill pointotherenterdatabase. ";
                appendWarning(result, splitHint);
            }
            if (totalItems == 0) {
                appendWarning(result, "not recognitionto Examinationitem, Please Confirmimageclear, for laboratory testReport,  or trymorechangerecognizechartmodel (current: " + model + ") . ");
            }
        } catch (Exception e) {
            result.put("error", "parseAIBackresultfailed: " + e.getMessage());
        }

        return result;
    }

    @Override
    public Map<String, Object> recognizeMedication(String base64Image) {
        return recognizeMedication(Collections.singletonList(base64Image));
    }

    @Override
    public Map<String, Object> recognizeMedication(List<String> base64Images) {
        Map<String, Object> result = new HashMap<>();

        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            result.put("error", "not configuration OCR secret, Please settings OCR_API_KEY  or  BAILIAN_API_KEY environmentvariable. ");
            return result;
        }

        String prompt = MEDICATION_PROMPT_TEMPLATE;
        List<String> imageDataUris = new ArrayList<>();
        for (String base64Image : base64Images) {
            String mime = guessMimeType(base64Image);
            imageDataUris.add("data:" + mime + ";base64," + stripDataUriPrefix(base64Image));
        }

        String model = visionProps.getModel();
        String baseUrl = visionProps.getBaseUrl();
        String rawResult = callVisionApi(apiKey, baseUrl, model, prompt, imageDataUris);
        result.put("rawResult", rawResult);

        JSONObject errJson = extractJson(rawResult);
        if (errJson != null && errJson.containsKey("error")) {
            String err = errJson.getString("error");
            result.put("error", err);
            if (err != null && (err.contains("image_url") || err.contains("unknown variant"))) {
                result.put("error", err + ". currentmodel「" + model + "」not supportimageinput, Please changeusevisualmodel (for example  gpt-4o-mini, qwen-vl-plus) , seeconfiguration ocr.vision.model. ");
            }
            return result;
        }

        try {
            JSONObject json = extractJson(rawResult);
            if (json == null) {
                result.put("error", "AI BackcontentNonemethodparsefor  JSON, Please changemoreclearimage or morechangerecognizechartmodelafter retry. ");
                return result;
            }

            List<Map<String, Object>> drugs = parseMedicationDrugs(json);
            result.put("drugs", drugs);

            // legacy compatibilityformat: sametimekeeptop-levelfield (getNo. oneitems)
            if (!drugs.isEmpty()) {
                Map<String, Object> first = drugs.get(0);
                result.put("drugName", first.get("drugName"));
                result.put("genericName", first.get("genericName"));
                result.put("specification", first.get("specification"));
                result.put("unit", first.get("unit"));
                result.put("dosageForm", first.get("dosageForm"));
                result.put("manufacturer", first.get("manufacturer"));
                result.put("approvalNumber", first.get("approvalNumber"));
                result.put("category", first.get("category"));
                result.put("defaultDosage", first.get("defaultDosage"));
                result.put("remark", first.get("remark"));
            }

            if (drugs.isEmpty()) {
                result.put("warning", "not recognitionto Medication, Please Confirmimageclear, for Medicationpackage or instructionsdocument,  or trymorechangerecognizechartmodel (current: " + model + ") . ");
            } else if (drugs.size() > 1) {
                result.put("warning", "already recognitionto  " + drugs.size() + " typeMedication, Savetimewill pointotherenterdatabase. ");
            }
        } catch (Exception e) {
            result.put("error", "parseAIBackresultfailed: " + e.getMessage());
        }

        return result;
    }

    private String mapDosageFormCode(String form) {
        if (form == null) return "";
        switch (form.trim()) {
            case "tablet": return "TABLET";
            case "capsule": return "CAPSULE";
            case "injection": return "INJECTION";
            case "oral solution": return "SOLUTION";
            case "powder": return "POWDER";
            default: return "";
        }
    }

    private String mapCategoryCode(String cat) {
        if (cat == null) return "";
        switch (cat.trim()) {
            case "antihypertensive": return "ANTIHYPERTENSIVE";
            case "phosphate binder": return "PHOSPHATE_BINDER";
            case "iron supplement": return "IRON_SUPPLEMENT";
            case "vitamin": return "VITAMIN";
            case "erythropoietin": return "ESA";
            case "calcium supplement": return "CALCIUM";
            case "active vitamin DD": return "VD";
            case "diuretic": return "DIURETIC";
            case "antibiotic": return "ANTIBIOTIC";
            case "Other": return "OTHER";
            default: return "";
        }
    }

    private List<Map<String, Object>> parseMedicationDrugs(JSONObject json) {
        List<Map<String, Object>> drugs = new ArrayList<>();

        // newformat: drugs countgroup
        if (json.containsKey("drugs") && json.get("drugs") instanceof JSONArray) {
            JSONArray array = json.getJSONArray("drugs");
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) continue;
                Map<String, Object> drug = extractDrugFields(item);
                if (drug.get("drugName") != null && !((String) drug.get("drugName")).isEmpty()) {
                    drugs.add(drug);
                }
            }
            return drugs;
        }

        // legacy compatibilityformat: singleitemsMedicationfielddirectlyin top-level
        Map<String, Object> drug = extractDrugFields(json);
        if (drug.get("drugName") != null && !((String) drug.get("drugName")).isEmpty()) {
            drugs.add(drug);
        }
        return drugs;
    }

    private Map<String, Object> extractDrugFields(JSONObject json) {
        Map<String, Object> drug = new HashMap<>();
        drug.put("drugName", json.getString("drugName"));
        drug.put("genericName", json.getString("genericName"));
        drug.put("specification", json.getString("specification"));
        drug.put("unit", json.getString("unit"));
        drug.put("dosageForm", mapDosageFormCode(json.getString("dosageForm")));
        drug.put("manufacturer", json.getString("manufacturer"));
        drug.put("approvalNumber", json.getString("approvalNumber"));
        drug.put("category", mapCategoryCode(json.getString("category")));
        drug.put("defaultDosage", json.getString("defaultDosage"));
        drug.put("remark", json.getString("remark"));
        return drug;
    }

    private String resolveApiKey() {
        if (StringUtils.hasText(visionProps.getApiKey())) {
            return visionProps.getApiKey().trim();
        }
        return bailianApiKey != null ? bailianApiKey.trim() : "";
    }

    private String stripDataUriPrefix(String base64) {
        if (base64 == null) return "";
        int comma = base64.indexOf(',');
        if (base64.startsWith("data:") && comma > 0) {
            return base64.substring(comma + 1);
        }
        return base64;
    }

    private String guessMimeType(String base64) {
        String raw = stripDataUriPrefix(base64);
        if (raw.startsWith("iVBORw0KGgo")) return "image/png";
        if (raw.startsWith("/9j/")) return "image/jpeg";
        if (raw.startsWith("R0lGOD")) return "image/gif";
        if (raw.startsWith("UklGR")) return "image/webp";
        return "image/jpeg";
    }

    private String callVisionApi(String apiKey, String baseUrl, String model, String prompt, List<String> imageDataUris) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            List<Object> content = new ArrayList<>();
            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            content.add(textPart);

            for (String imageDataUri : imageDataUris) {
                Map<String, Object> imagePart = new HashMap<>();
                imagePart.put("type", "image_url");
                Map<String, Object> imageUrl = new HashMap<>();
                imageUrl.put("url", imageDataUri);
                imagePart.put("image_url", imageUrl);
                content.add(imagePart);
            }

            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "youYesprofessional medicallaboratory testReportrecognitionassistant. Please carefullyrecognitionimagein ExaminationReportcontent, extracthas laboratory testitemitem data. ");

            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", content);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(systemMsg);
            messages.add(userMsg);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", visionProps.getTemperature());
            body.put("max_tokens", visionProps.getMaxTokens());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String url = normalizeChatCompletionsUrl(baseUrl);

            String response = restTemplate.postForObject(url, entity, String.class);
            JSONObject json = JSON.parseObject(response);
            if (json.containsKey("choices") && !json.getJSONArray("choices").isEmpty()) {
                return json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
            }
            return "{\"error\": \"AIRecognition failed, not Backhas validresult\"}";
        } catch (HttpStatusCodeException e) {
            String body = e.getResponseBodyAsString();
            String msg = body != null && !body.isEmpty() ? body : e.getMessage();
            return "{\"error\": \"AIrecognitioncallfailed: " + escapeJson(msg) + "\"}";
        } catch (Exception e) {
            return "{\"error\": \"AIrecognitioncallfailed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private static String normalizeChatCompletionsUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            url = "https://api.openai.com/v1";
        }
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        if (url.endsWith("/responses")) {
            return url.substring(0, url.length() - "/responses".length()) + "/chat/completions";
        }
        return url + "/chat/completions";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private JSONObject extractJson(String text) {
        if (text == null) return null;

        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = text.indexOf("```json", jsonStart) + 7;
            int jsonEnd = text.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                String jsonStr = text.substring(contentStart, jsonEnd).trim();
                return JSON.parseObject(jsonStr);
            }
        }

        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            String jsonStr = text.substring(braceStart, braceEnd + 1);
            return JSON.parseObject(jsonStr);
        }

        return null;
    }

    private List<Map<String, Object>> parseMedicalRecords(JSONObject json) {
        JSONArray recordsArray = json.getJSONArray("records");
        if (recordsArray != null && !recordsArray.isEmpty()) {
            List<Map<String, Object>> records = new ArrayList<>();
            for (int r = 0; r < recordsArray.size(); r++) {
                Map<String, Object> rec = parseSingleMedicalRecord(recordsArray.getJSONObject(r));
                if (rec != null) {
                    records.add(rec);
                }
            }
            return records;
        }
        Map<String, Object> legacy = parseSingleMedicalRecord(json);
        if (legacy == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(legacy);
    }

    private Map<String, Object> parseSingleMedicalRecord(JSONObject json) {
        if (json == null) {
            return null;
        }
        Map<String, Object> record = new HashMap<>();
        record.put("recordType", normalizeRecordType(json.getString("recordType")));
        record.put("hospitalName", json.getString("hospitalName"));
        record.put("checkDate", json.getString("checkDate"));
        record.put("doctorName", json.getString("doctorName"));

        List<Map<String, Object>> items = new ArrayList<>();
        JSONArray itemsArray = json.getJSONArray("items");
        if (itemsArray != null) {
            for (int i = 0; i < itemsArray.size(); i++) {
                JSONObject itemJson = itemsArray.getJSONObject(i);
                Map<String, Object> item = new HashMap<>();
                item.put("itemName", itemJson.getString("name"));
                item.put("resultValue", itemJson.getString("value"));
                item.put("unit", itemJson.getString("unit"));
                item.put("referenceRange", itemJson.getString("referenceRange"));
                item.put("isAbnormal", parseAbnormalStatus(itemJson.getString("isAbnormal")));
                items.add(item);
            }
        }
        int rawCount = items.size();
        items = dedupeItems(items);
        record.put("items", items);
        if (rawCount > items.size()) {
            record.put("_dedupeHint", rawCount - items.size());
        }
        return record;
    }

    private String normalizeRecordType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "OTHER";
        }
        String t = type.trim().toUpperCase(Locale.ROOT);
        if (t.contains("BLOOD") || t.contains("blood")) return "BLOOD";
        if (t.contains("URINE") || t.contains("urine")) return "URINE";
        if (t.contains("KIDNEY") || t.contains("kidney")) return "KIDNEY";
        if (t.contains("LIVER") || t.contains("liver")) return "LIVER";
        if (t.contains("BONE") || t.contains("bone")) return "BONE";
        if (t.contains("IRON") || t.contains("iron")) return "IRON";
        if (t.contains("IMAGE") || t.contains("imaging")) return "IMAGE";
        if (t.matches("[A-Z_]+")) return t;
        return "OTHER";
    }

    private void appendWarning(Map<String, Object> result, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Object existing = result.get("warning");
        if (existing != null && !String.valueOf(existing).isEmpty()) {
            result.put("warning", existing + " " + message);
        } else {
            result.put("warning", message);
        }
    }

    private Integer parseAbnormalStatus(String status) {
        if (status == null) return 0;
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("high") || normalized.contains("高") || normalized.contains("↑") || "h".equals(normalized)) return 1;
        if (normalized.contains("low") || normalized.contains("低") || normalized.contains("↓") || "l".equals(normalized)) return -1;
        return 0;
    }

    /**
     * Merge complete duplicate results only; equal values do not identify the same test.
     */
    private List<Map<String, Object>> dedupeItems(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String key = buildDedupeKey(item);
            if (key == null) key = "unidentified:" + merged.size();
            if (!merged.containsKey(key)) {
                merged.put(key, new HashMap<>(item));
                continue;
            }
            Map<String, Object> existing = merged.get(key);
            String name1 = str(existing.get("itemName"));
            String name2 = str(item.get("itemName"));
            if (name2.length() > name1.length()) {
                existing.put("itemName", name2);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private String buildDedupeKey(Map<String, Object> item) {
        String name = normalizeItemName(str(item.get("itemName")));
        String value = norm(str(item.get("resultValue")));
        String unit = norm(str(item.get("unit")));
        String range = norm(str(item.get("referenceRange")));
        if (name.isEmpty() || value.isEmpty()) return null;
        return com.alibaba.fastjson2.JSON.toJSONString(Arrays.asList(name, value, unit, range, item.getOrDefault("isAbnormal", 0)));
    }

    private static String normalizeItemName(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", "");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
