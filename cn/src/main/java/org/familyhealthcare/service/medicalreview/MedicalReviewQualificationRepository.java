package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;

/** Closed scalar inventory. No command owner, native body, review text, or replay payload access. */
final class MedicalReviewQualificationRepository {
    private static final String E="e.id,e.record_id,e.source_generation,e.channel,e.operation,e.before_content_version,e.content_version,e.decision_version,e.cycle,e.state,e.actor_id,e.actor_kind,DATE_FORMAT(e.occurred_at,'%Y-%m-%dT%H:%i:%s.%f') AS occurred_at_text,e.occurred_at IS NULL AS occurred_at_null";
    private static final String F="(EXISTS (SELECT 1 FROM medical_review_state s FORCE INDEX (PRIMARY) WHERE s.record_id=r.id LIMIT 1) OR EXISTS (SELECT 1 FROM medical_review_event e FORCE INDEX (mrv57_event_deletion) WHERE e.record_id=r.id LIMIT 1) OR EXISTS (SELECT 1 FROM medical_review_text t FORCE INDEX (mrv57_text_source) WHERE t.record_id=r.id LIMIT 1) OR EXISTS (SELECT 1 FROM medical_review_command c FORCE INDEX (mrv57_command_source) WHERE c.record_id=r.id LIMIT 1) OR EXISTS (SELECT 1 FROM medical_review_reference v FORCE INDEX (mrv57_reference_source) WHERE v.record_id=r.id LIMIT 1)) AS footprint";
    private static final String P9=",s.record_id AS managed_id,s.patient_id AS state_patient,s.owner_user_id,s.baseline_content_version,s.current_content_version,s.current_history_sequence,s.validity,s.content_digest AS state_digest,h.id AS head_id,h.history_sequence AS head_sequence,h.after_version,h.validity AS head_validity,h.content_digest AS head_digest,b.id AS baseline_id,b.history_sequence AS baseline_sequence,b.before_version AS baseline_before,b.after_version AS baseline_after,b.operation AS baseline_operation,CASE WHEN EXISTS (SELECT 1 FROM medical_record_correction_history p WHERE p.record_id=r.id AND p.history_sequence>s.current_history_sequence) THEN 1 ELSE 0 END AS pending";
    private static final String JOINS=" LEFT JOIN medical_record_correction_state s ON s.record_id=r.id LEFT JOIN medical_record_correction_history h ON h.record_id=s.record_id AND h.history_sequence=s.current_history_sequence LEFT JOIN medical_record_correction_history b ON b.record_id=s.record_id AND b.history_sequence=0";
    private final MedicalReviewQualificationReader.Resource resource;
    private final MedicalRecordCorrectionSchema.Status parent9;
    private final MedicalReviewSchema.Status review;
    private final List<Object> witnesses=new ArrayList<>();
    private final Map<Long,List<Object>> sourceWitnesses=new LinkedHashMap<>();
    private final Map<Long,Native> natives=new LinkedHashMap<>();
    private final Map<Long,Pair> pairs=new LinkedHashMap<>();
    private final Map<Long,Event> watermarks=new LinkedHashMap<>();

    MedicalReviewQualificationRepository(MedicalReviewQualificationReader.Resource resource,MedicalRecordCorrectionSchema.Status parent9,MedicalReviewSchema.Status review){this.resource=resource;this.parent9=parent9;this.review=review;}
    private static final class Invalid extends RuntimeException { }
    private static Invalid invalid(){return new Invalid();}
    static final class Frame {
        final MedicalReviewSchema.Status review;final MedicalRecordCorrectionSchema.Status parent9;
        final Map<Long,MedicalReviewQualificationReader.Observation> observations;final String signature;final boolean invalid;
        Frame(MedicalReviewSchema.Status review,MedicalRecordCorrectionSchema.Status parent9,Map<Long,MedicalReviewQualificationReader.Observation> observations,String signature,boolean invalid){this.review=review;this.parent9=parent9;this.observations=observations;this.signature=signature;this.invalid=invalid;}
        MedicalReviewQualificationReader.Batch batch(MedicalReviewQualificationReader.Semantics semantics){return new MedicalReviewQualificationReader.Batch(review,parent9,semantics,observations,signature);}
    }
    Frame collect(LinkedHashSet<Long> ids)throws SQLException{
        witnesses.add(parent9.name());witnesses.add(review.name());
        for(long id:ids)sourceWitnesses.put(id,new ArrayList<>());
        readNative(ids); // Native and parent9 errors retain their original strict boundary.
        try{
            List<Long> positive=new ArrayList<>();for(Native n:natives.values())if(n.footprint)positive.add(n.id);
            if(!positive.isEmpty())proof(positive);
            Map<Long,MedicalReviewQualificationReader.Observation> observations=new LinkedHashMap<>();
            for(long id:ids){Native n=natives.get(id);Pair pair=pairs.get(id);Event w=watermarks.get(id);
                ReviewState source=pair==null?null:pair.source,doctor=pair==null?null:pair.doctor;
                String signature=WorkflowData.json(Arrays.asList(parent9.name(),review.name(),n==null?null:n.raw,sourceWitnesses.get(id)));
                String release=WorkflowData.json(Arrays.asList(parent9.name(),review.name(),n==null?null:n.raw,pair==null?null:pair.generation(),pair!=null&&pair.size()!=0,w==null?null:w.raw,
                        source==null?null:source.raw,source==null?null:source.head.raw,pair==null||pair.size()==0?sourceWitnesses.get(id):null));
                observations.put(id,new MedicalReviewQualificationReader.Observation(id,n==null?null:n.patient,n==null?null:n.owner,n==null?null:n.version,n!=null,n!=null&&n.managed,
                        n==null?null:n.validity,n==null?null:n.legacy,n!=null&&n.footprint,source==null?null:new MedicalReviewQualificationReader.ChannelFacts(source,source.head),doctor==null?null:new MedicalReviewQualificationReader.ChannelFacts(doctor,doctor.head),signature,release));
            }
            return new Frame(review,parent9,observations,WorkflowData.json(witnesses),false);
        }catch(Invalid malformed){resource.verify();return new Frame(review,parent9,Collections.emptyMap(),WorkflowData.json(Arrays.asList("INVALID",witnesses)),true);}
    }
    private static final class Native {
        final long id,version;final Long patient,owner;final boolean managed,footprint;final String validity,legacy;final Map<String,Object> raw;
        Native(Map<String,Object> row,boolean ready){raw=row;id=positive(row.get("id"));patient=nullable(row.get("patient_id"));owner=nullable(row.get("user_id"));version=positive(row.get("content_version"));legacy=string(row,"verification_status");
            long f=nonnegative(row.get("footprint"));if(f>1)throw invalid();footprint=f==1;
            managed=ready&&row.get("managed_id")!=null;validity=managed?string(row,"validity"):"UNTRACKED";
            if(managed){long baseline=positive(row.get("baseline_content_version")),sequence=nonnegative(row.get("current_history_sequence"));String digest=string(row,"state_digest");
                if(positive(row.get("managed_id"))!=id||!Objects.equals(patient,nullable(row.get("state_patient")))||!Objects.equals(owner,nullable(row.get("owner_user_id")))
                        ||positive(row.get("current_content_version"))!=version||baseline>version||!("ACTIVE".equals(validity)||"INVALIDATED".equals(validity))
                        ||digest==null||!digest.matches("[0-9a-f]{64}")||positive(row.get("head_id"))<1||nonnegative(row.get("head_sequence"))!=sequence
                        ||positive(row.get("after_version"))!=version||!validity.equals(string(row,"head_validity"))||!digest.equals(string(row,"head_digest"))
                        ||positive(row.get("baseline_id"))<1||nonnegative(row.get("baseline_sequence"))!=0||positive(row.get("baseline_before"))!=baseline
                        ||positive(row.get("baseline_after"))!=baseline||!"BASELINE".equals(string(row,"baseline_operation"))||nonnegative(row.get("pending"))!=0)throw invalid();
            }
        }
    }
    private void readNative(LinkedHashSet<Long> ids)throws SQLException{
        if(ids.isEmpty())return;
        boolean ready=parent9==MedicalRecordCorrectionSchema.Status.READY;
        String sql="SELECT r.id,r.patient_id,r.user_id,r.content_version,r.verification_status,"+(review==MedicalReviewSchema.Status.READY?F:"0 AS footprint")+(ready?P9:"")+" FROM medical_record r"+(ready?JOINS:"")+" WHERE r.id IN ("+marks(ids.size())+") ORDER BY r.id LIMIT "+(ids.size()+1);
        try{List<Map<String,Object>> rows=query(sql,new ArrayList<>(ids),ids.size()+1,ready?25:6);long previous=0;
            if(rows.size()>ids.size())throw invalid();for(Map<String,Object> row:rows){Native n=new Native(row,ready);if(!ids.contains(n.id)||n.id<=previous||natives.put(n.id,n)!=null)throw invalid();previous=n.id;sourceWitnesses.get(n.id).add(row);}
        }catch(Invalid malformed){throw MedicalRecordCorrectionContracts.integrity();}
    }
    static final class ReviewState {
        final long record,content,decision,cycle,last;final Long patient,owner,pointer;final String generation;final Channel channel;final State state;final Map<String,Object> raw;Event head;
        ReviewState(Map<String,Object> row){raw=row;record=positive(row.get("record_id"));content=positive(row.get("content_version"));decision=positive(row.get("decision_version"));cycle=positive(row.get("cycle"));last=positive(row.get("last_event_id"));
            patient=nullable(row.get("patient_id"));owner=nullable(row.get("owner_user_id"));pointer=nullable(row.get("current_decision_event_id"));generation=string(row,"source_generation");
            try{channel=Channel.valueOf(string(row,"channel"));state=State.valueOf(string(row,"state"));requireStateFacts(generation,channel,state,content,decision,cycle);}catch(IllegalArgumentException|NullPointerException|WorkflowException bad){throw invalid();}
            if(channel==Channel.DOCTOR&&patient==null)throw invalid();if(state==State.APPROVED||state==State.REJECTED){if(!Long.valueOf(last).equals(pointer))throw invalid();}else if(pointer!=null)throw invalid();
        }
    }
    private static final class Pair {
        ReviewState doctor,source;
        int size(){return(doctor==null?0:1)+(source==null?0:1);}
        String generation(){return doctor!=null?doctor.generation:source==null?null:source.generation;}
        ReviewState get(Channel channel){return channel==Channel.DOCTOR?doctor:source;}
    }
    static final class Event {
        final long id,record,content,decision,cycle,actor;final Long before;final String generation,kind,time;final Channel channel;final Operation operation;final State state;final Map<String,Object> raw;
        Event(Map<String,Object> row){raw=row;id=positive(row.get("id"));record=positive(row.get("record_id"));content=positive(row.get("content_version"));actor=positive(row.get("actor_id"));decision=nonnegative(row.get("decision_version"));cycle=nonnegative(row.get("cycle"));before=nullable(row.get("before_content_version"));generation=string(row,"source_generation");kind=string(row,"actor_kind");
            try{channel=Channel.valueOf(string(row,"channel"));operation=Operation.valueOf(string(row,"operation"));state=State.valueOf(string(row,"state"));requireEventFacts(generation,channel,operation,state,before,content,decision,cycle);}catch(IllegalArgumentException|NullPointerException|WorkflowException bad){throw invalid();}
            if(!Arrays.asList("ADMIN","ASSIGNED_DOCTOR","OWNER","DELEGATE").contains(kind)||channel==Channel.DOCTOR&&(operation==Operation.APPROVE||operation==Operation.REJECT)&&!Arrays.asList("ADMIN","ASSIGNED_DOCTOR").contains(kind))throw invalid();
            if(nonnegative(row.get("occurred_at_null"))!=0)throw invalid();time=string(row,"occurred_at_text");
            if(time==null||!time.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw invalid();
            try{LocalDateTime.parse(time,DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withResolverStyle(ResolverStyle.STRICT));}catch(java.time.DateTimeException bad){throw invalid();}
        }
        void key(Key key,Channel channel,Operation operation){if(record!=key.record||key.generation!=null&&!key.generation.equals(generation)||channel!=null&&this.channel!=channel||operation!=null&&this.operation!=operation)throw invalid();}
    }
    private void proof(List<Long> records)throws SQLException{
        String sql="SELECT record_id,channel,source_generation,patient_id,owner_user_id,content_version,decision_version,cycle,state,last_event_id,current_decision_event_id FROM medical_review_state FORCE INDEX (PRIMARY) WHERE record_id IN ("+marks(records.size())+") ORDER BY record_id,channel LIMIT "+(2*records.size()+1);
        for(long id:records)pairs.put(id,new Pair());
        List<Map<String,Object>> rows=query(sql,new ArrayList<>(records),2*records.size()+1,11);
        if(rows.size()>2*records.size())throw invalid();String prior=null;
        for(Map<String,Object> row:rows){ReviewState s=new ReviewState(row);Native n=natives.get(s.record);Pair pair=pairs.get(s.record);
            String order=String.format(Locale.ROOT,"%016d:%s",s.record,s.channel.name());if(prior!=null&&prior.compareTo(order)>=0)throw invalid();prior=order;
            if(pair==null||n==null||s.content!=n.version||!Objects.equals(s.patient,n.patient)||!Objects.equals(s.owner,n.owner))throw invalid();
            if(pair.get(s.channel)!=null)throw invalid();if(s.channel==Channel.DOCTOR)pair.doctor=s;else pair.source=s;
            sourceWitnesses.get(s.record).add(row);
        }
        List<Key> live=new ArrayList<>(),heads=new ArrayList<>(),zero=new ArrayList<>(),all=new ArrayList<>();
        for(long id:records){Pair p=pairs.get(id);if(p.size()==2&&!p.source.generation.equals(p.doctor.generation))throw invalid();all.add(new Key(id,null,null));
            if(p.size()==0)zero.add(new Key(id,null,null));else{live.add(new Key(id,p.generation(),null));for(Channel ch:new Channel[]{Channel.DOCTOR,Channel.SOURCE})heads.add(new Key(id,p.generation(),ch));}}
        Map<Key,Event> latest=events("L",heads,null,null);Map<Key,Event> water=events("W",all,Channel.RECORD,Operation.SOURCE_DELETED);
        Map<Key,Event> closed=events("C",live,Channel.RECORD,Operation.SOURCE_DELETED);
        for(Key key:all){Event w=water.get(key);watermarks.put(key.record,w);Pair p=pairs.get(key.record);long watermark=w==null?0:w.id;
            if(p.size()!=0){if(closed.get(new Key(key.record,p.generation(),null))!=null)throw invalid();
                for(Channel ch:new Channel[]{Channel.DOCTOR,Channel.SOURCE}){ReviewState s=p.get(ch);Event e=latest.get(new Key(key.record,p.generation(),ch));if(s==null){if(e!=null)throw invalid();continue;}
                    if(e==null||e.id!=s.last||e.content!=s.content||e.decision!=s.decision||e.cycle!=s.cycle||e.state!=s.state||e.id<=watermark)throw invalid();s.head=e;}
            }else if(w==null)throw invalid();
        }
        if(!zero.isEmpty()){
            List<Event> oldEvents=new ArrayList<>();LinkedHashSet<Key> closureKeys=new LinkedHashSet<>();
            for(Channel ch:new Channel[]{Channel.DOCTOR,Channel.SOURCE})for(Operation op:new Operation[]{Operation.REQUEST,Operation.APPROVE,Operation.REJECT,Operation.RESUBMIT,Operation.CONTENT_CHANGED}){
                for(Event e:events("O",zero,ch,op).values())if(e!=null){Event w=watermarks.get(e.record);if(w==null||e.id>w.id)throw invalid();oldEvents.add(e);closureKeys.add(new Key(e.record,e.generation,null));}}
            Map<Key,Event> historical=events("C",new ArrayList<>(closureKeys),Channel.RECORD,Operation.SOURCE_DELETED);
            for(Event e:oldEvents){Event closure=historical.get(new Key(e.record,e.generation,null));if(closure==null||closure.id<=e.id||closure.id>watermarks.get(e.record).id)throw invalid();}
            payloadAbsent(zero,false);payloadAbsent(zero,true);
        }
    }
    private static final class Key implements Comparable<Key>{
        final long record;final String generation;final Channel channel;
        Key(long record,String generation,Channel channel){this.record=record;this.generation=generation;this.channel=channel;}
        public int compareTo(Key k){int a=Long.compare(record,k.record);if(a!=0)return a;a=Objects.toString(generation,"").compareTo(Objects.toString(k.generation,""));return a!=0?a:Objects.toString(channel,"").compareTo(Objects.toString(k.channel,""));}
        @Override public boolean equals(Object o){return o instanceof Key&&compareTo((Key)o)==0;}
        @Override public int hashCode(){return Objects.hash(record,generation,channel);}
    }
    private Map<Key,Event> events(String kind,List<Key> input,Channel channel,Operation operation)throws SQLException{
        Map<Key,Event> result=new LinkedHashMap<>();List<Key> keys=new ArrayList<>(input);Collections.sort(keys);
        for(int offset=0;offset<keys.size();offset+=100){List<Key> part=keys.subList(offset,Math.min(offset+100,keys.size()));
            String[] columns="L".equals(kind)?new String[]{"record_id","generation","channel"}:"C".equals(kind)?new String[]{"record_id","generation"}:new String[]{"record_id"};
            List<Object> args=arguments(part,columns.length);String select="",order="";for(String col:columns){if(!select.isEmpty()){select+=",";order+=",";}select+="k."+col+" AS requested_"+col;order+="k."+col;}
            String join;
            if("C".equals(kind))join=" LEFT JOIN medical_review_event e FORCE INDEX (mrv57_event_version) ON e.record_id=k.record_id AND e.source_generation=k.generation AND e.channel='RECORD' AND e.decision_version=0";
            else{String index="L".equals(kind)?"mrv57_event_version":"mrv57_event_deletion",predicate="x.record_id=k.record_id";
                if("L".equals(kind))predicate+=" AND x.source_generation=k.generation AND x.channel=k.channel";
                else predicate+=" AND x.channel='"+channel.name()+"' AND x.operation='"+operation.name()+"'";
                join=" LEFT JOIN medical_review_event e ON e.id=(SELECT x.id FROM medical_review_event x FORCE INDEX ("+index+") WHERE "+predicate+" ORDER BY x."+("L".equals(kind)?"decision_version":"id")+" DESC LIMIT 1)";}
            String sql="SELECT "+select+","+E+" FROM "+table(part.size(),columns)+join+" ORDER BY "+order+" LIMIT "+(part.size()+1);
            List<Map<String,Object>> rows=query(sql,args,part.size()+1,14+columns.length);if(rows.size()!=part.size())throw invalid();
            for(int i=0;i<part.size();i++){Map<String,Object> row=rows.get(i);Key key=part.get(i);
                if(positive(row.get("requested_record_id"))!=key.record||columns.length>1&&!Objects.equals(row.get("requested_generation"),key.generation)||columns.length>2&&!Objects.equals(row.get("requested_channel"),key.channel.name()))throw invalid();
                sourceWitnesses.get(key.record).add(row);Event e=null;
                if(row.get("id")!=null){e=new Event(row);e.key(key,"L".equals(kind)?key.channel:channel,operation);}
                else {for(Map.Entry<String,Object> field:row.entrySet())if(!field.getKey().startsWith("requested_")&&!"occurred_at_null".equals(field.getKey())&&field.getValue()!=null)throw invalid();if(nonnegative(row.get("occurred_at_null"))!=1)throw invalid();}
                if(result.containsKey(key))throw invalid();result.put(key,e);
            }
        }
        return result;
    }
    private void payloadAbsent(List<Key> keys,boolean command)throws SQLException{
        String name=command?"command":"text",sub=" FROM medical_review_"+name+" x FORCE INDEX (mrv57_"+name+"_source) WHERE x.record_id=k.record_id LIMIT 1)";
        String sql="SELECT k.record_id AS requested_record_id,(SELECT x.record_id"+sub+" AS record_id,(SELECT x.event_id"+sub+" AS event_id FROM "+table(keys.size(),new String[]{"record_id"})+" ORDER BY k.record_id LIMIT "+(keys.size()+1);
        List<Key> sorted=new ArrayList<>(keys);Collections.sort(sorted);List<Map<String,Object>> rows=query(sql,arguments(sorted,1),keys.size()+1,3);if(rows.size()!=keys.size())throw invalid();
        for(int i=0;i<rows.size();i++){Map<String,Object> row=rows.get(i);long id=positive(row.get("requested_record_id"));if(id!=sorted.get(i).record||row.get("record_id")!=null||row.get("event_id")!=null)throw invalid();sourceWitnesses.get(id).add(row);}
    }
    private List<Map<String,Object>> query(String sql,List<?> args,int limit,int columns)throws SQLException{
        resource.verify();List<Map<String,Object>> rows=new ArrayList<>();
        try(PreparedStatement statement=resource.connection.prepareStatement(sql)){
            resource.timeout(statement);for(int i=0;i<args.size();i++)statement.setObject(i+1,args.get(i));
            try(ResultSet result=statement.executeQuery()){
                ResultSetMetaData meta=result.getMetaData();if(meta.getColumnCount()!=columns){witnesses.add(Arrays.asList("COLUMNS",meta.getColumnCount(),columns));throw invalid();}
                while(result.next()){if(rows.size()>=limit)throw invalid();Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=columns;i++){
                    String label=meta.getColumnLabel(i);if(row.containsKey(label))throw invalid();Object value=result.getObject(i);if(value instanceof Boolean&&Arrays.asList("footprint","pending","occurred_at_null").contains(label))value=Boolean.TRUE.equals(value)?1L:0L;row.put(label,value);
                }rows.add(Collections.unmodifiableMap(row));}
            }
        }
        witnesses.add(rows);resource.verify();return rows;
    }
    private static String table(int count,String[] columns){
        String tuple="SELECT ";
        for(int i=0;i<columns.length;i++){
            if(i>0)tuple+=",";
            // Event generation/channel keys are ascii_bin even when the pooled connection is utf8mb4.
            String value="generation".equals(columns[i])||"channel".equals(columns[i])
                    ?"CAST(? AS CHAR CHARACTER SET ascii) COLLATE ascii_bin":"?";
            tuple+=value+" AS "+columns[i];
        }
        return "("+String.join(" UNION ALL ",Collections.nCopies(count,tuple))+") k";
    }
    private static List<Object> arguments(List<Key> keys,int width){List<Object> args=new ArrayList<>();for(Key key:keys){args.add(key.record);if(width>1)args.add(key.generation);if(width>2)args.add(key.channel.name());}return args;}
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static String string(Map<String,Object> row,String key){Object value=row.get(key);if(value!=null&&!(value instanceof String))throw invalid();return(String)value;}
    private static Long nullable(Object value){if(value==null)return null;return positive(value);}
    private static long positive(Object value){long v=nonnegative(value);if(v==0)throw invalid();return v;}
    private static long nonnegative(Object value){long n;try{if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)n=((Number)value).longValue();else if(value instanceof BigInteger)n=((BigInteger)value).longValueExact();else if(value instanceof BigDecimal)n=((BigDecimal)value).longValueExact();else throw invalid();}catch(ArithmeticException malformed){throw invalid();}if(n<0||n>MAX_ID)throw invalid();return n;}
}
