package com.secureauth.simulator.io;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CSV reading and writing (RFC 4180). A field that contains a comma, a double quote or a
 * line break is wrapped in double quotes, and any double quote inside it is written twice:
 * <pre>He said "hi", then left   becomes   "He said ""hi"", then left"</pre>
 */
public final class Csv {

    private Csv() {
    }

    /** One field, quoted only when it has to be. null becomes an empty field. */
    public static String escape(String field) {
        if (field == null) {
            return "";
        }
        boolean needsQuotes = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    /** A whole record: escaped fields joined by commas, ending with CRLF as the standard says. */
    public static String formatRow(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    /**
     * Stops a spreadsheet from treating text as a formula. Excel and Sheets run cells that
     * start with = + - or @, so a harmless-looking field could execute when the file is opened.
     * A leading apostrophe makes them plain text. Use it for free-text columns only, never
     * for numbers (a negative longitude legitimately starts with "-").
     */
    public static String neutralizeFormula(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + text;
        }
        return text;
    }

    /**
     * Splits CSV text into records of fields. Understands quoted fields (commas and line breaks
     * inside quotes, "" for a literal quote), CRLF / LF / CR line endings and a leading BOM.
     * Completely blank lines are skipped.
     *
     * @throws IllegalArgumentException if a quoted field is never closed
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> records = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return records;
        }
        if (text.charAt(0) == '\uFEFF') {
            text = text.substring(1); // Excel likes to put a byte-order mark at the start
        }

        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"'); // "" inside quotes is one quote character
                        i++;
                    } else {
                        inQuotes = false;  // the closing quote
                    }
                } else {
                    field.append(c);       // anything else, even a line break, belongs to the field
                }
            } else if (c == '"' && field.length() == 0) {
                inQuotes = true;           // a quote at the start of a field opens a quoted field
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;                   // CRLF counts as one line break
                }
                finishRecord(records, record, field);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("A quoted field is not closed");
        }
        finishRecord(records, record, field); // the last record may have no line break after it
        return records;
    }

    /** Closes the current field and keeps the record unless it was just an empty line. */
    private static void finishRecord(List<List<String>> records, List<String> record, StringBuilder field) {
        record.add(field.toString());
        field.setLength(0);
        boolean blankLine = record.size() == 1 && record.get(0).isEmpty();
        if (!blankLine) {
            records.add(record);
        }
    }
}
