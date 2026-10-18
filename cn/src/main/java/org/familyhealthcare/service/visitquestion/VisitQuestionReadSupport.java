package org.familyhealthcare.service.visitquestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;

/** 有限游标、只读来源检查和响应预算；不把原始正文装载后再裁剪。 */
final class VisitQuestionReadSupport {
    private VisitQuestionReadSupport(){}
    static final String ORDINARY_EVENT="JSON_EXTRACT(body_json,'$.ordinary')";
    static final String CURRENT_BYTES="OCTET_LENGTH(s.current_title)+COALESCE(OCTET_LENGTH(s.current_description),0)+COALESCE(OCTET_LENGTH(s.current_recorded_answer),0)+COALESCE(OCTET_LENGTH(s.current_follow_up),0)";
    static String jsonObject(String alias){return "CASE WHEN JSON_VALID("+alias+".data_json) THEN "+alias+".data_json ELSE '{}' END";}
    static String field(String alias,String field){return "CASE WHEN JSON_TYPE(JSON_EXTRACT("+jsonObject(alias)+",'$."+field+"'))='STRING' THEN JSON_UNQUOTE(JSON_EXTRACT("+jsonObject(alias)+",'$."+field+"')) ELSE NULL END";}
    static String marker(String alias){return "JSON_EXTRACT("+jsonObject(alias)+",'$.questionWorkflowVersion')";}
    /** 解码 ASCII 键名后在数据库内做保守排除，包含嵌套键及 Unicode 转义键。 */
    static String ordinarySource(String alias){
        String decoded="LOWER("+alias+".data_json)";
        for(char c='a';c<='z';c++)for(char encoded:new char[]{c,Character.toUpperCase(c)})
            decoded="REPLACE("+decoded+",CONCAT(CHAR(92),'u"+String.format(Locale.ROOT,"%04x",(int)encoded)+"'),'"+c+"')";
        decoded="REPLACE(REPLACE("+decoded+",CONCAT(CHAR(92),'u005f'),'_'),'_','')";
        return "("+alias+".data_json IS NULL OR (JSON_VALID("+alias+".data_json) AND JSON_TYPE("+jsonObject(alias)+")='OBJECT' AND NOT ("+decoded+" REGEXP '(psychological|minor|restricted|private)[^\"]*\"[[:space:]]*:')))";
    }
    static String bValid(String alias,boolean present){
        if(!present)return "1=1";
        return "(("+alias+".family_workflow_version=0 AND "+alias+".family_assistance_id IS NULL) OR ("+alias+".family_workflow_version=1 AND EXISTS(SELECT 1 FROM family_assistance b WHERE b.id="+alias+".family_assistance_id AND b.patient_id="+alias+".patient_id AND b.source_kind='CARE_ITEM' AND b.care_item_id="+alias+".id AND b.care_appointment_id IS NULL AND b.request_kind='QUESTION' AND SHA2(b.origin_evidence_json,256)=b.origin_evidence_hash)))";
    }
    static String noB(String alias,boolean present){return present?alias+".family_workflow_version=0 AND "+alias+".family_assistance_id IS NULL":"1=1";}
    static String owned(String alias,boolean present){return present?alias+".family_assistance_id IS NOT NULL":"1=0";}
    static String flags(boolean managed,boolean b){
        String ownership=managed?"n.id IS NOT NULL AND s.patient_id=n.patient_id AND ((JSON_TYPE("+marker("n")+")='INTEGER' AND "+marker("n")+"=1) OR ("+marker("n")+" IS NULL AND "+owned("n",b)+"))":marker("n")+" IS NULL";
        return "CASE WHEN "+ordinarySource("n")+" THEN 1 ELSE 0 END AS ordinary_allowed,CASE WHEN "+bValid("n",b)+" AND ("+ownership+") THEN 1 ELSE 0 END AS identity_valid";
    }
    static long cursor(String value,long actor,long patient,String lane){
        if(value==null)return 0;
        if(value.length()>256)throw invalid("问题翻页位置无效，请重新加载。");
        try{
            String decoded=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);
            String prefix="VQ1:"+actor+":"+patient+":"+hash(lane)+":";
            if(!decoded.startsWith(prefix))throw invalid("问题翻页位置无效，请重新加载。");
            String id=decoded.substring(prefix.length());if(!id.matches("[1-9][0-9]{0,15}"))throw invalid("问题翻页位置无效，请重新加载。");
            return VisitQuestionContracts.id(Long.valueOf(id),"cursor");
        }catch(IllegalArgumentException failure){throw invalid("问题翻页位置无效，请重新加载。");}
    }
    static String next(long actor,long patient,String lane,long after){return Base64.getUrlEncoder().withoutPadding().encodeToString(("VQ1:"+actor+":"+patient+":"+hash(lane)+":"+after).getBytes(StandardCharsets.UTF_8));}
    static boolean flag(Map<String,Object> row,String key){return Boolean.TRUE.equals(row.get(key))||row.get(key) instanceof Number&&((Number)row.get(key)).intValue()==1;}
    static void identity(Map<String,Object> row){if(row==null)throw VisitQuestionRepository.denied();if(!flag(row,"identity_valid"))throw VisitQuestionRepository.conflict("QUESTION_OWNERSHIP_INVALID");if(!flag(row,"ordinary_allowed"))throw VisitQuestionRepository.denied();}
    static WorkflowException invalid(String message){return WorkflowException.invalid(message);}
    static WorkflowException limit(){return new WorkflowException(413,"QUESTION_LIMIT","本次问题内容超过安全读取上限，请减少每页数量；历史未被截断或修改。");}
    static void bytes(Object value,long max){if(!(value instanceof Number)||((Number)value).longValue()<0||((Number)value).longValue()>max)throw limit();}
    static void encoded(Object value,int maximum){
        // 流式计数不分配完整 JSON 副本；HTTP 输出仍是结构化对象，不二次引用 JSON 字符串。
        try{new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValue(new OutputStream(){long count;public void write(int b){if(++count>maximum)throw limit();}public void write(byte[] b,int offset,int length){count+=length;if(count>maximum)throw limit();}},value);}
        catch(IOException failure){throw limit();}
    }
}
