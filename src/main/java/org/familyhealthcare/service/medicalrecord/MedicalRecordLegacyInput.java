package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.entity.*;
import java.util.*;

/** 客户端创建没有历史/所有权/核对元数据的写权限；内部导入事实有独立入口。 */
public final class MedicalRecordLegacyInput {
    private MedicalRecordLegacyInput() { }
    private static final Set<String> RESERVED=new HashSet<>(Arrays.asList("userId","user_id","ownerUserId","owner_user_id","contentVersion","content_version","verificationStatus","verification_status","verifiedBy","verified_by","verifiedAt","verified_at","verificationNote","verification_note","createdAt","created_at","updatedAt","updated_at","history","snapshot","snapshotJson","snapshot_json","command","commandKey","expectedContentVersion","operation","reason","headerPatch","itemPatches","correctionState","correctionHistory","referenceVersion","validity","managed","historySequence","baselineContentVersion","currentContentVersion"));
    private static final Set<String> TECHNICAL=new HashSet<>(Arrays.asList("actorid","capturedby","capturedat","owner","ownerid","expectedversion","expectedcontentversion","currentcontentversion","baselinecontentversion","currenthistorysequence","historysequence","contentdigest","payloadhash","snapshot","snapshotjson","snapshothash","schemaversion","resultjson","receipt","command","commandkey","correctionstate","correctionhistory","referenceversion","referenceid","referencekind","sourceitemid","boundat","verificationstatus","verificationnote","verifiedat","verifiedby","contentversion","userid","owneruserid","createdat","updatedat"));
    public static void requireClientCreate(Object value) { scan(value, 0, new int[]{0}); }
    private static void scan(Object value, int depth, int[] count) {
        if (depth > 64 || ++count[0] > 10000) throw MedicalRecordCorrectionContracts.invalid();
        if(value instanceof Map){for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
            String key=String.valueOf(entry.getKey());
            if(RESERVED.contains(key)||TECHNICAL.contains(key.replace("_", "").toLowerCase(java.util.Locale.ROOT))||key.startsWith("medical_record_correction_")||key.equals("medical_record_command")||key.equals("medical_record_reference_version")
                    ||(key.equals("id")||key.equals("recordId")||key.equals("record_id"))&&entry.getValue()!=null)throw MedicalRecordCorrectionContracts.invalid();
            scan(entry.getValue(), depth + 1, count);
        }}else if(value instanceof Collection)for(Object item:(Collection<?>)value)scan(item, depth + 1, count);
    }
    public static void requireNewIdentity(MedicalRecord record,List<MedicalRecordItem> items){
        if(record==null||record.getId()!=null||record.getUserId()!=null||record.getContentVersion()!=null||record.getVerifiedBy()!=null||record.getVerifiedAt()!=null||record.getVerificationNote()!=null||record.getCreatedAt()!=null||record.getUpdatedAt()!=null)throw MedicalRecordCorrectionContracts.invalid();
        if(items!=null)for(MedicalRecordItem item:items)if(item==null||item.getId()!=null||item.getRecordId()!=null||item.getCreatedAt()!=null)throw MedicalRecordCorrectionContracts.invalid();
        if(record.getAttachments()!=null)for(MedicalRecordAttachment attachment:record.getAttachments())if(attachment==null||attachment.getId()!=null||attachment.getRecordId()!=null||attachment.getCreatedAt()!=null)throw MedicalRecordCorrectionContracts.invalid();
    }
}
