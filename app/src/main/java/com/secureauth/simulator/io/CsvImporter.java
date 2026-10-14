package com.secureauth.simulator.io;

import android.content.ContentResolver;
import android.net.Uri;

import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads an audit-log CSV file chosen by the user and adds its rows to the history.
 *
 * The file is untrusted input, so every field is checked before anything is stored:
 * the header must name the needed columns, events and results must look like our own
 * upper-case codes, and coordinates must be real, in-range numbers. Rows that fail are
 * counted and skipped; rows already in the history (same time and event) are skipped too.
 */
public final class CsvImporter {

    /** Refuse huge files instead of running out of memory. */
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 50_000;

    private static final long MAX_TIMESTAMP = 32_503_680_000_000L; // year 3000
    private static final Pattern EVENT = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern RESULT = Pattern.compile("[A-Z][A-Z0-9_]{0,19}");

    private CsvImporter() {
    }

    /** The valid rows found in a file, and how many rows had to be rejected. */
    public static final class Parsed {
        public final List<AuditLog> rows;
        public final int invalid;

        Parsed(List<AuditLog> rows, int invalid) {
            this.rows = rows;
            this.invalid = invalid;
        }
    }

    /** What an import did, for the "imported / skipped" message. */
    public static final class Result {
        public final int imported;
        public final int duplicates;
        public final int invalid;

        Result(int imported, int duplicates, int invalid) {
            this.imported = imported;
            this.duplicates = duplicates;
            this.invalid = invalid;
        }
    }

    /** Reads the chosen file as UTF-8 text, refusing anything over {@link #MAX_FILE_BYTES}. */
    public static String readText(ContentResolver resolver, Uri source) throws IOException {
        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) {
                throw new IOException("Cannot open the file");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_FILE_BYTES) {
                    throw new IOException("File is too large");
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (SecurityException e) {
            throw new IOException("No permission to read the file", e);
        }
    }

    /**
     * Turns CSV text into rows without touching the database.
     * The header must contain "ts" (epoch milliseconds) and "event"; "result", "lat" and "lng"
     * are optional; any other column (for example the readable "datetime") is ignored.
     *
     * @throws IllegalArgumentException if the file is empty, malformed or lacks the key columns
     */
    public static Parsed parse(String csvText) {
        List<List<String>> records = Csv.parse(csvText);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("The file is empty");
        }
        if (records.size() - 1 > MAX_ROWS) {
            throw new IllegalArgumentException("Too many rows");
        }

        Map<String, Integer> column = new HashMap<>();
        List<String> header = records.get(0);
        for (int i = 0; i < header.size(); i++) {
            column.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        if (!column.containsKey("ts") || !column.containsKey("event")) {
            throw new IllegalArgumentException("The header must contain ts and event");
        }

        List<AuditLog> rows = new ArrayList<>();
        int invalid = 0;
        for (int r = 1; r < records.size(); r++) {
            AuditLog row = toRow(records.get(r), column);
            if (row == null) {
                invalid++;
            } else {
                rows.add(row);
            }
        }
        return new Parsed(rows, invalid);
    }

    /**
     * Adds the file's rows to {@code userId}'s history. Rows always belong to the given user,
     * whatever the file says. Skips rows whose time + event are already present.
     *
     * @throws IllegalArgumentException if the file cannot be understood (see {@link #parse})
     */
    public static Result importInto(AuditLogDao dao, long userId, String csvText) {
        Parsed parsed = parse(csvText);

        // Everything already stored, as "time|event", so duplicates are found without extra queries
        Set<String> seen = new HashSet<>();
        for (AuditLog existing : dao.listForUser(userId)) {
            seen.add(existing.ts + "|" + existing.event);
        }

        List<AuditLog> toStore = new ArrayList<>();
        int duplicates = 0;
        for (AuditLog row : parsed.rows) {
            // add() is false when the key was already there: a repeat of an old row or of an earlier line of this file
            if (!seen.add(row.ts + "|" + row.event)) {
                duplicates++;
                continue;
            }
            row.userId = userId;
            toStore.add(row);
        }

        if (!toStore.isEmpty() && dao.insertAll(toStore) < 0) {
            throw new IllegalArgumentException("The rows could not be stored");
        }
        return new Result(toStore.size(), duplicates, parsed.invalid);
    }

    /** One data row, or null if any field is not acceptable. */
    private static AuditLog toRow(List<String> cells, Map<String, Integer> column) {
        String ts = cell(cells, column, "ts");
        String event = cell(cells, column, "event");
        String result = cell(cells, column, "result");
        String lat = cell(cells, column, "lat");
        String lng = cell(cells, column, "lng");

        long time;
        try {
            time = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return null;
        }
        if (time < 0 || time > MAX_TIMESTAMP || !EVENT.matcher(event).matches()) {
            return null;
        }
        if (!result.isEmpty() && !RESULT.matcher(result).matches()) {
            return null;
        }

        Double latitude = null;
        Double longitude = null;
        if (!lat.isEmpty() || !lng.isEmpty()) {
            // A place needs both numbers; one without the other is a damaged row
            latitude = parseCoordinate(lat, 90);
            longitude = parseCoordinate(lng, 180);
            if (latitude == null || longitude == null) {
                return null;
            }
        }

        AuditLog row = new AuditLog();
        row.ts = time;
        row.event = event;
        row.result = result.isEmpty() ? null : result;
        row.lat = latitude;
        row.lng = longitude;
        return row;
    }

    private static String cell(List<String> cells, Map<String, Integer> column, String name) {
        Integer index = column.get(name);
        if (index == null || index >= cells.size()) {
            return "";
        }
        return cells.get(index).trim();
    }

    /** A finite number within +-limit, or null. ("NaN" and "Infinity" parse as numbers, so check them.) */
    private static Double parseCoordinate(String text, double limit) {
        try {
            double value = Double.parseDouble(text);
            if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) > limit) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
