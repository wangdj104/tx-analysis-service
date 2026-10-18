package org.familyhealthcare.service.medicalrecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.io.OutputStream;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.workflow.WorkflowData;
import java.time.Instant;
import java.util.Map;

/** 入库前和读取时使用同一历史详情投影，不能接受以后永远读不出的事件。 */
final class MedicalRecordRevisionProjection {
    private static final ObjectWriter HTTP_JSON = new ObjectMapper().writer();
    private MedicalRecordRevisionProjection() { }

    static Map<String,Object> bounded(long sequence, String operation, long before, long after,
                                      long actor, Instant capturedAt, String validity, String reason,
                                      MedicalRecordCorrectionContracts.Snapshot snapshot) {
        return bounded(WorkflowData.map("historySequence", sequence, "operation", operation,
                "beforeVersion", before, "afterVersion", after, "capturedBy", actor,
                "capturedAt", capturedAt.toString(), "validity", validity, "reason", reason, "snapshot", snapshot));
    }

    static Map<String,Object> bounded(Map<String,Object> revision) {
        Map<String,Object> result = WorkflowData.map(
                "historySequence", revision.get("historySequence"), "operation", revision.get("operation"),
                "beforeVersion", revision.get("beforeVersion"), "afterVersion", revision.get("afterVersion"),
                "capturedBy", revision.get("capturedBy"), "capturedAt", revision.get("capturedAt"),
                "validity", revision.get("validity"), "reason", revision.get("reason"),
                "snapshot", publicSnapshot((MedicalRecordCorrectionContracts.Snapshot) revision.get("snapshot")));
        // 同时计入既有 Result 包装。预算副本用 long 最宽表示和两版较长的成功文案，
        // 不能依赖本次时间戳/语言恰好较短；实际响应、事实和理由均不改变或截断。
        Result<Map<String,Object>> envelope = Result.ok(result);
        envelope.setTimestamp(Long.MIN_VALUE);
        if (MedicalRecordCorrectionContracts.utf8(WorkflowData.json(envelope.getMsg()))
                < MedicalRecordCorrectionContracts.utf8(WorkflowData.json("success"))) envelope.setMsg("success");
        // HTTP 用 UTF-8 generator；补充 Unicode 可能输出两个 Unicode 转义，不能按 String 的 UTF-8 长度估算。
        // 只统计实际编码字节并在超限时中止，不分配完整响应，也不改变存储快照的规范编码/哈希。
        ResponseBytes output = new ResponseBytes();
        try { HTTP_JSON.writeValue(output, envelope); }
        catch (IOException failure) {
            if (output.exceeded) throw MedicalRecordCorrectionContracts.limit();
            throw new IllegalStateException("Cannot encode medical record revision response");
        }
        return result;
    }

    private static final class ResponseBytes extends OutputStream {
        private int count;
        private boolean exceeded;
        @Override public void write(int value) throws IOException { add(1); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { add(length); }
        private void add(int length) throws IOException {
            if (length > MedicalRecordCorrectionContracts.SNAPSHOT_BYTES - count) {
                exceeded = true;
                throw new IOException("Medical record revision response exceeds its byte budget");
            }
            count += length;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> publicSnapshot(MedicalRecordCorrectionContracts.Snapshot snapshot) {
        Map<String,Object> value = WorkflowData.object(snapshot.json());
        Map<String,Object> record = (Map<String,Object>) value.get("record");
        record.remove("aiRawReference");
        record.remove("importFactsReference");
        return value;
    }
}
