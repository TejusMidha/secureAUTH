package com.secureauth.simulator.io;

import android.content.ContentResolver;
import android.net.Uri;

import com.secureauth.simulator.data.AuditLog;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Writes text files chosen by the user through the Storage Access Framework (the system file
 * picker). The app never asks for storage permission: the user picks the place and name, and
 * Android hands us a Uri that is allowed to be written for that one file.
 */
public final class FileExporter {

    public static final String BACKUP_CODES_FILE_NAME = "backup_codes.txt";
    public static final String MIME_TEXT = "text/plain";
    public static final String AUDIT_LOG_FILE_NAME = "audit_log.csv";
    public static final String MIME_CSV = "text/csv";

    private FileExporter() {
    }

    /**
     * Writes {@code content} to the file the user picked, replacing anything already in it.
     * Returns true on success. Call from a background thread.
     */
    public static boolean writeText(ContentResolver resolver, Uri destination, String content) {
        // "wt" = write + truncate, so an existing longer file is not left with old text at the end
        try (OutputStream out = resolver.openOutputStream(destination, "wt")) {
            if (out == null) {
                return false;
            }
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The audit log as CSV: a header row, then one row per entry in the order given.
     * Columns: ts (epoch milliseconds, what import reads), datetime (readable, for people),
     * event, result, lat, lng. Empty lat/lng means "no location".
     */
    public static String formatAuditCsv(List<AuditLog> rows, TimeZone timeZone) {
        SimpleDateFormat readable = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        readable.setTimeZone(timeZone);

        StringBuilder csv = new StringBuilder();
        csv.append(Csv.formatRow(Arrays.asList("ts", "datetime", "event", "result", "lat", "lng")));
        for (AuditLog row : rows) {
            csv.append(Csv.formatRow(Arrays.asList(
                    String.valueOf(row.ts),
                    readable.format(new Date(row.ts)),
                    Csv.neutralizeFormula(row.event),   // text columns: no spreadsheet formulas
                    Csv.neutralizeFormula(row.result),
                    coordinate(row.lat),                // numbers stay exactly as they are
                    coordinate(row.lng))));
        }
        return csv.toString();
    }

    /** A plain decimal ("72.8777", never "7.28777E1"), or empty when there is no value. */
    private static String coordinate(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** The text saved to backup_codes.txt and sent by "Share". */
    public static String formatBackupCodes(List<String> codes, String username, String createdText) {
        StringBuilder sb = new StringBuilder();
        sb.append("SecureAuth backup codes\n");
        sb.append("Account: ").append(username).append('\n');
        sb.append("Created: ").append(createdText).append('\n');
        sb.append("Each code can be used once. Keep this file somewhere safe and private.\n");
        sb.append("Creating a new set makes all older codes stop working.\n\n");
        for (int i = 0; i < codes.size(); i++) {
            sb.append(i + 1).append(". ").append(codes.get(i)).append('\n');
        }
        return sb.toString();
    }
}
