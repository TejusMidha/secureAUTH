package com.secureauth.simulator.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.secureauth.simulator.data.AuditLog;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

public class FileExporterTest {

    @Test
    public void backupCodesFileHasAHeaderAndNumberedCodes() {
        String text = FileExporter.formatBackupCodes(
                Arrays.asList("K7QM-X2PD", "AB23-CD45"), "alice", "2026-10-14 19:30");

        String[] lines = text.split("\n");
        assertEquals("SecureAuth backup codes", lines[0]);
        assertEquals("Account: alice", lines[1]);
        assertEquals("Created: 2026-10-14 19:30", lines[2]);
        assertTrue(text.contains("Each code can be used once"));
        assertTrue(text.contains("1. K7QM-X2PD\n"));
        assertTrue(text.contains("2. AB23-CD45\n"));
    }

    @Test
    public void everyCodeAppearsExactlyOnceAndInOrder() {
        String[] codes = {"AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH", "JJJJ-KKKK",
                "LLLL-MMMM", "NNNN-PPPP", "QQQQ-RRRR", "SSSS-TTTT", "UUUU-VVVV"};
        String text = FileExporter.formatBackupCodes(Arrays.asList(codes), "bob", "now");

        int last = -1;
        for (int i = 0; i < codes.length; i++) {
            int at = text.indexOf((i + 1) + ". " + codes[i]);
            assertTrue("code " + i, at > last);
            assertEquals(at, text.lastIndexOf((i + 1) + ". " + codes[i]));
            last = at;
        }
    }

    private static AuditLog entry(long ts, String event, String result, Double lat, Double lng) {
        AuditLog a = new AuditLog();
        a.ts = ts;
        a.event = event;
        a.result = result;
        a.lat = lat;
        a.lng = lng;
        return a;
    }

    @Test
    public void auditCsvHasAHeaderAndOneRowPerEntryInOrder() {
        String csv = FileExporter.formatAuditCsv(Arrays.asList(
                entry(1700000000000L, "PASSWORD_OK", "SUCCESS", 19.076, 72.8777),
                entry(0L, "REGISTER", "SUCCESS", null, null)), TimeZone.getTimeZone("UTC"));

        String[] lines = csv.split("\r\n");
        assertEquals("ts,datetime,event,result,lat,lng", lines[0]);
        assertEquals("1700000000000,2023-11-14T22:13:20,PASSWORD_OK,SUCCESS,19.076,72.8777", lines[1]);
        assertEquals("0,1970-01-01T00:00:00,REGISTER,SUCCESS,,", lines[2]); // no location = empty cells
        assertEquals(3, lines.length);
        assertTrue(csv.endsWith("\r\n"));
    }

    @Test
    public void theReadableTimeFollowsTheGivenTimeZone() {
        String csv = FileExporter.formatAuditCsv(Arrays.asList(
                entry(0L, "LOGOUT", "SUCCESS", null, null)), TimeZone.getTimeZone("GMT+05:30"));
        assertTrue(csv, csv.contains("1970-01-01T05:30:00"));
    }

    @Test
    public void coordinatesNeverUseScientificNotation() {
        String csv = FileExporter.formatAuditCsv(Arrays.asList(
                entry(1L, "TOTP_OK", "SUCCESS", 0.00001, -0.5)), TimeZone.getTimeZone("UTC"));
        assertTrue(csv, csv.contains(",0.00001,-0.5\r\n"));
    }

    @Test
    public void textColumnsAreQuotedAndFormulaProofButNumbersAreUntouched() {
        String csv = FileExporter.formatAuditCsv(Arrays.asList(
                entry(5L, "=EVIL()", "a,b", -10.5, -20.25)), TimeZone.getTimeZone("UTC"));
        String row = csv.split("\r\n")[1];
        assertEquals("5,1970-01-01T00:00:00,'=EVIL(),\"a,b\",-10.5,-20.25", row);
    }

    @Test
    public void emptyListStillGivesTheHeader() {
        String text = FileExporter.formatBackupCodes(Collections.<String>emptyList(), "carol", "now");
        assertTrue(text.startsWith("SecureAuth backup codes"));
        assertTrue(text.endsWith("\n\n"));
    }
}
