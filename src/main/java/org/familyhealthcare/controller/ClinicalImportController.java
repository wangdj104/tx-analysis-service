package org.familyhealthcare.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.ClinicalImportService;
import org.familyhealthcare.service.ClinicalImportFacts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.familyhealthcare.util.ExportLocalization.text;

@RestController
@RequestMapping("/clinical-import")
public class ClinicalImportController {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NUMBER_LENGTH = 256;
    private static final int MAX_ROWS = 1_000;
    // 导入独立使用精确数值读取器，不改变其他接口的 Jackson 配置。
    private static final ObjectMapper IMPORT_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final ObjectReader IMPORT_READER = IMPORT_JSON.readerFor(new TypeReference<Map<String, Object>>() { });

    @Autowired private ClinicalImportService service;

    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(HttpServletRequest request, HttpServletResponse response) {
        return importRequest(request, response, false);
    }

    @PostMapping("/commit")
    public Result<Map<String, Object>> commit(HttpServletRequest request, HttpServletResponse response) {
        return importRequest(request, response, true);
    }

    private Result<Map<String, Object>> importRequest(HttpServletRequest request, HttpServletResponse response, boolean commit) {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("X-Content-Type-Options", "nosniff");
        final Map<String, Object> body;
        final Long patientId;
        try {
            body = readImportBody(request);
            patientId = requirePatientId(body.get("patientId"));
            if (commit && !Boolean.parseBoolean(String.valueOf(body.get("confirmed")))) {
                response.setStatus(400);
                return Result.error(400, text("Preview the data and explicitly confirm the import first.", "请先预览数据并明确确认导入。"));
            }
        } catch (InvalidImportBody invalid) {
            response.setStatus(invalid.status);
            return Result.error(invalid.status, importErrorMessage(invalid.status));
        }
        // 保留既有患者权限校验；解析失败不能进入预览、批次或逐行写入。
        try { return Result.ok(commit ? service.commit(patientId, body) : service.preview(patientId, body)); }
        catch (ClinicalImportFacts.Failure failure) {
            response.setStatus(failure.getStatus());
            Result<Map<String,Object>> result=Result.error(failure.getStatus(),failure.getMessage());
            result.setData(java.util.Collections.singletonMap("errorCode",failure.getCode()));
            return result;
        }
    }

    private Map<String, Object> readImportBody(HttpServletRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream input = request.getInputStream();
            byte[] buffer = new byte[8192];
            int count;
            // 即使长度未知，也只读取上限加一个字节，避免先读完整个请求。
            while (bytes.size() <= MAX_BODY_BYTES && (count = input.read(buffer, 0,
                    Math.min(buffer.length, MAX_BODY_BYTES + 1 - bytes.size()))) != -1) {
                bytes.write(buffer, 0, count);
            }
            if (bytes.size() > MAX_BODY_BYTES) throw invalidBody(413);
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            validateTokens(json);
            Map<String, Object> body = IMPORT_READER.readValue(json);
            validateRows(body);
            return body;
        } catch (IOException | IllegalArgumentException invalid) {
            // 不附带解析器异常、请求片段或原始数值，防止进入全局异常日志。
            throw invalidBody(400);
        }
    }

    private void validateTokens(String json) throws IOException {
        try (JsonParser parser = IMPORT_JSON.getFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalidBody(400);
            int depth = 1;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (depth == 0) throw invalidBody(400);
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > MAX_DEPTH) throw invalidBody(400);
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                } else if (token.isNumeric() && parser.getTextLength() > MAX_NUMBER_LENGTH) {
                    throw invalidBody(400);
                }
            }
            if (depth != 0) throw invalidBody(400);
        }
    }

    private void validateRows(Map<String, Object> body) {
        int rows = 0;
        if (!body.containsKey("bundle") && !body.containsKey("readings")) throw invalidBody(400);
        if (body.containsKey("bundle")) {
            if (!(body.get("bundle") instanceof Map)) throw invalidBody(400);
            Map<?, ?> bundle = (Map<?, ?>) body.get("bundle");
            if (bundle.containsKey("entry")) {
                if (!(bundle.get("entry") instanceof List)) throw invalidBody(400);
                rows += ((List<?>) bundle.get("entry")).size();
            }
        }
        if (body.containsKey("readings")) {
            Object readings = body.get("readings");
            // 仅展开一层设备包装，内层患者或确认字段不能覆盖外层请求。
            if (readings instanceof Map) readings = ((Map<?, ?>) readings).get("readings");
            if (!(readings instanceof List)) throw invalidBody(400);
            rows += ((List<?>) readings).size();
        }
        if (rows > MAX_ROWS) throw invalidBody(400);
    }

    private Long requirePatientId(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) throw invalidBody(400);
        String number = value.toString().trim();
        if (number.isEmpty() || number.length() > MAX_NUMBER_LENGTH) throw invalidBody(400);
        try {
            long id = new BigDecimal(number).longValueExact();
            if (id <= 0) throw invalidBody(400);
            return id;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw invalidBody(400);
        }
    }

    private InvalidImportBody invalidBody(int status) {
        return new InvalidImportBody(status);
    }

    private String importErrorMessage(int status) {
        return status == 413
                ? text("The import request is too large. Split it into smaller imports.", "导入请求过大，请拆分后重试。")
                : text("The import request is invalid or exceeds the supported limits.", "导入请求无效或超出支持的限制。");
    }

    private static final class InvalidImportBody extends RuntimeException {
        final int status;
        InvalidImportBody(int status) { this.status = status; }
    }
}
