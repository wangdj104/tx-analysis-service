package org.familyhealthcare.service.workflow;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MySQL 5.7 metadata lookup predicates for fixed, trusted schema manifests only.
 * All exact predicates remain. This changes neither the canonical manifest nor
 * its generated migration SQL, and never caches a storage/authorization result. */
public final class MetadataQueryPrefilter {
    private MetadataQueryPrefilter() {}
    private static final String ID="(?:[a-z][a-z0-9_]*\\.)?";
    private static Pattern pattern(String expression) { return Pattern.compile(expression,Pattern.CASE_INSENSITIVE); }
    private static final Pattern SCHEMA=pattern("\\bBINARY ("+ID+"(?:TABLE_SCHEMA|TRIGGER_SCHEMA|ROUTINE_SCHEMA|EVENT_SCHEMA|CONSTRAINT_SCHEMA|SCHEMA_NAME))=BINARY DATABASE\\(\\)");
    private static final Pattern NAME=pattern("\\bBINARY ("+ID+"(?:TABLE_NAME|TRIGGER_NAME|EVENT_OBJECT_TABLE))=(?:BINARY )?'([a-z0-9_]+)'");
    private static final Pattern TRIGGER=pattern("\\bFROM information_schema\\.TRIGGERS WHERE (?=\\(TRIGGER_SCHEMA=DATABASE\\(\\) AND BINARY TRIGGER_SCHEMA=BINARY DATABASE\\(\\)\\))");
    private static final Pattern CONSTRAINT=pattern("\\bFROM information_schema\\.(?:TABLE_CONSTRAINTS|KEY_COLUMN_USAGE) WHERE (?=\\(CONSTRAINT_SCHEMA=DATABASE\\(\\) AND BINARY CONSTRAINT_SCHEMA=BINARY DATABASE\\(\\)\\))");
    private static final Pattern FOREIGN_KEY=pattern("(\\bFROM information_schema\\.KEY_COLUMN_USAGE k JOIN information_schema\\.REFERENTIAL_CONSTRAINTS r ON BINARY r.CONSTRAINT_SCHEMA=BINARY k.CONSTRAINT_SCHEMA AND BINARY r.CONSTRAINT_NAME=BINARY k.CONSTRAINT_NAME AND BINARY r.TABLE_NAME=BINARY k.TABLE_NAME WHERE )(\\(k.TABLE_SCHEMA=DATABASE\\(\\) AND BINARY k.TABLE_SCHEMA=BINARY DATABASE\\(\\)\\) AND \\(k.TABLE_NAME='([a-z0-9_]+)' AND )");

    public static String indexed(String canonical) {
        if(canonical==null)throw new IllegalArgumentException("A metadata query is required");
        // Keep the replacement atomic even beneath NOT or an IS FALSE test.
        String sql=replace(canonical,SCHEMA,m->"("+m.group(1)+"=DATABASE() AND "+m.group()+")");
        sql=replace(sql,NAME,m->"("+m.group(1)+"='"+m.group(2)+"' AND "+m.group()+")");
        // These metadata schema identities are the schema of the owning table.
        // In particular, do NOT scope queries about REFERENCED_TABLE_SCHEMA:
        // those must continue to discover incoming references from other schemas.
        sql=replace(sql,TRIGGER,m->m.group()+"EVENT_OBJECT_SCHEMA=DATABASE() AND ");
        sql=replace(sql,CONSTRAINT,m->m.group()+"TABLE_SCHEMA=DATABASE() AND ");
        return replace(sql,FOREIGN_KEY,m->m.group(1)+"r.CONSTRAINT_SCHEMA=DATABASE() AND r.TABLE_NAME='"+m.group(3)+"' AND "+m.group(2));
    }

    private static String replace(String sql,Pattern pattern,Function<Matcher,String> replacement) {
        boolean[] code=codePositions(sql);
        Matcher matcher=pattern.matcher(sql);StringBuffer result=new StringBuffer();
        while(matcher.find())if(code[matcher.start()])matcher.appendReplacement(result,Matcher.quoteReplacement(replacement.apply(matcher)));
        matcher.appendTail(result);return result.toString();
    }

    /** Skip SQL strings, quoted identifiers and all ordinary comment forms. */
    private static boolean[] codePositions(String sql) {
        boolean[] code=new boolean[sql.length()];
        for(int i=0;i<sql.length();) {
            char ch=sql.charAt(i);
            if(ch=='\''||ch=='"'||ch=='`') {
                char quote=ch;i++;
                while(i<sql.length()) {
                    char value=sql.charAt(i++);
                    if(value=='\\'&&quote!='`'&&i<sql.length()){i++;continue;}
                    if(value==quote){if(i<sql.length()&&sql.charAt(i)==quote){i++;continue;}break;}
                }
            } else if(ch=='#'||(ch=='-'&&i+2<sql.length()&&sql.charAt(i+1)=='-'&&Character.isWhitespace(sql.charAt(i+2)))) {
                while(i<sql.length()&&sql.charAt(i)!='\n'&&sql.charAt(i)!='\r')i++;
            } else if(ch=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*') {
                int end=sql.indexOf("*/",i+2);i=end<0?sql.length():end+2;
            } else {code[i]=true;i++;}
        }
        return code;
    }
}
