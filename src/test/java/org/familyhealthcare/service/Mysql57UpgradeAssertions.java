package org.familyhealthcare.service;

import java.util.ArrayList;
import java.util.List;

/** Shared SQL delimiter parser for the complete initialization contract tests. */
final class Mysql57UpgradeAssertions {
    private Mysql57UpgradeAssertions() { }

    /** MySQL DELIMITER is a client directive, never sent to JDBC as SQL. */
    static List<String> statements(String source) {
        source = source.replace("\r\n", "\n");
        List<String> result = new ArrayList<>();
        String delimiter = ";";
        StringBuilder statement = new StringBuilder();
        char quote = 0;
        boolean lineComment = false, blockComment = false, hasSql = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (quote == 0 && !lineComment && !blockComment && (i == 0 || source.charAt(i - 1) == '\n')) {
                int end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
                String line = source.substring(i, end).trim();
                if (line.matches("(?i)DELIMITER\\s+\\S+")) {
                    if (hasSql) throw new IllegalArgumentException("DELIMITER encountered before SQL terminator");
                    delimiter = line.split("\\s+", 2)[1];
                    statement.setLength(0); i = end; continue;
                }
            }
            if (lineComment) {
                statement.append(ch);
                if (ch == '\n') lineComment = false;
            } else if (blockComment) {
                statement.append(ch);
                if (ch == '*' && next == '/') { statement.append(next); i++; blockComment = false; }
            } else if (quote != 0) {
                statement.append(ch);
                if (ch == '\\' && next != 0) { statement.append(next); i++; }
                else if (ch == quote) {
                    if (next == quote) { statement.append(next); i++; }
                    else quote = 0;
                }
            } else if (source.startsWith(delimiter, i)) {
                if (hasSql) result.add(statement.toString().trim());
                statement.setLength(0); hasSql = false; i += delimiter.length() - 1;
            } else if (ch == '#' || (ch == '-' && next == '-' &&
                    (i + 2 == source.length() || Character.isWhitespace(source.charAt(i + 2))))) {
                lineComment = true; statement.append(ch);
            } else if (ch == '/' && next == '*') {
                blockComment = true; statement.append(ch).append(next); i++;
            } else {
                statement.append(ch);
                if (!Character.isWhitespace(ch)) hasSql = true;
                if (ch == '\'' || ch == '"' || ch == '`') quote = ch;
            }
        }
        if (quote != 0 || blockComment) throw new IllegalArgumentException("Unterminated SQL literal/comment");
        if (hasSql) result.add(statement.toString().trim());
        return result;
    }

}
