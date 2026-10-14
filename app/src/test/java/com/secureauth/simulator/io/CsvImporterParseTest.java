package com.secureauth.simulator.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.secureauth.simulator.data.AuditLog;

import org.junit.Test;

import java.util.Arrays;
import java.util.TimeZone;

/** The checking of an imported file, which needs no database. */
public class CsvImporterParseTest {

    private static final String HEADER = "ts,datetime,event,result,lat,lng\r\n";

    private static CsvImporter.Parsed parse(String body) {
        return CsvImporter.parse(HEADER + body);
    }

    @Test
    public void readsAFullRowWithLocation() {
        CsvImporter.Parsed p = parse("1700000000000,2023-11-14T22:13:20,PASSWORD_OK,SUCCESS,19.076,72.8777\r\n");
        assertEquals(1, p.rows.size());
        assertEquals(0, p.invalid);
        AuditLog row = p.rows.get(0);
        assertEquals(1700000000000L, row.ts);
        assertEquals("PASSWORD_OK", row.event);
        assertEquals("SUCCESS", row.result);
        assertEquals(19.076, row.lat, 0.0);
        assertEquals(72.8777, row.lng, 0.0);
    }

    @Test
    public void emptyLocationAndResultBecomeNull() {
        AuditLog row = parse("5,,REGISTER,,,\r\n").rows.get(0);
        assertNull(row.lat);
        assertNull(row.lng);
        assertNull(row.result);
    }

    @Test
    public void onlyTsAndEventAreRequiredAndOrderDoesNotMatter() {
        CsvImporter.Parsed p = CsvImporter.parse("EVENT,Ts\nLOGOUT,42\n");
        assertEquals(1, p.rows.size());
        assertEquals("LOGOUT", p.rows.get(0).event);
        assertEquals(42L, p.rows.get(0).ts);
    }

    @Test
    public void unknownExtraColumnsAreIgnored() {
        CsvImporter.Parsed p = CsvImporter.parse("ts,event,note\n7,TOTP_OK,hello world\n");
        assertEquals(1, p.rows.size());
    }

    @Test
    public void negativeCoordinatesAreFine() {
        AuditLog row = parse("1,,TOTP_OK,SUCCESS,-33.8688,-151.2093\r\n").rows.get(0);
        assertEquals(-33.8688, row.lat, 0.0);
        assertEquals(-151.2093, row.lng, 0.0);
    }

    @Test
    public void aMissingKeyColumnRejectsTheWholeFile() {
        for (String file : new String[]{"event,result\nA,B\n", "ts,result\n1,SUCCESS\n", "hello\nworld\n"}) {
            try {
                CsvImporter.parse(file);
                fail("expected a rejection of: " + file);
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
    }

    @Test
    public void anEmptyOrBrokenFileIsRejected() {
        for (String file : new String[]{"", "\n\n", "ts,event\n1,\"open quote\n"}) {
            try {
                CsvImporter.parse(file);
                fail("expected a rejection of: " + file);
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
    }

    @Test
    public void badRowsAreCountedAndSkippedWhileGoodOnesSurvive() {
        CsvImporter.Parsed p = parse(""
                + "10,,LOGIN_OK,SUCCESS,,\r\n"            // good
                + "abc,,LOGIN_OK,SUCCESS,,\r\n"           // time is not a number
                + "-5,,LOGIN_OK,SUCCESS,,\r\n"            // negative time
                + "99999999999999999,,LOGIN_OK,SUCCESS,,\r\n" // absurdly far in the future
                + "11,,login_ok,SUCCESS,,\r\n"            // event not in our upper-case style
                + "12,,,SUCCESS,,\r\n"                    // no event at all
                + "13,,LOGIN_OK,bad result!,,\r\n"        // result has illegal characters
                + "14,,LOGIN_OK,SUCCESS,19.0,\r\n"        // latitude without longitude
                + "15,,LOGIN_OK,SUCCESS,91,10\r\n"        // latitude out of range
                + "16,,LOGIN_OK,SUCCESS,10,181\r\n"       // longitude out of range
                + "17,,LOGIN_OK,SUCCESS,NaN,10\r\n"       // not a real number
                + "18,,LOGIN_OK,SUCCESS,Infinity,10\r\n"
                + "19,,LOGOUT,SUCCESS,,\r\n");            // good
        assertEquals(2, p.rows.size());
        assertEquals(11, p.invalid);
        assertEquals(10L, p.rows.get(0).ts);
        assertEquals(19L, p.rows.get(1).ts);
    }

    @Test
    public void spreadsheetFormulasAreNotAcceptedAsEventNames() {
        CsvImporter.Parsed p = parse(""
                + "1,,=HYPERLINK(\"http://evil\"),SUCCESS,,\r\n"
                + "2,,+cmd|' /C calc'!A0,SUCCESS,,\r\n"
                + "3,,@SUM(1+1),SUCCESS,,\r\n"
                + "4,,PASSWORD_OK,=1+1,,\r\n");
        assertEquals(0, p.rows.size());
        assertEquals(4, p.invalid);
    }

    @Test
    public void tooManyRowsAreRefused() {
        StringBuilder big = new StringBuilder("ts,event\n");
        for (int i = 0; i <= CsvImporter.MAX_ROWS; i++) {
            big.append(i).append(",LOGOUT\n");
        }
        try {
            CsvImporter.parse(big.toString());
            fail("expected a rejection");
        } catch (IllegalArgumentException expected) {
            // good
        }
    }

    @Test
    public void whatTheExporterWritesTheImporterReadsBack() {
        AuditLog a = new AuditLog();
        a.ts = 1700000000000L;
        a.event = "PASSWORD_OK";
        a.result = "SUCCESS";
        a.lat = 19.076;
        a.lng = -72.8777;
        AuditLog b = new AuditLog();
        b.ts = 1700000005000L;
        b.event = "TOTP_FAIL";
        b.result = "FAIL";

        String csv = FileExporter.formatAuditCsv(Arrays.asList(a, b), TimeZone.getTimeZone("UTC"));
        CsvImporter.Parsed p = CsvImporter.parse(csv);

        assertEquals(2, p.rows.size());
        assertEquals(0, p.invalid);
        assertEquals(a.ts, p.rows.get(0).ts);
        assertEquals("PASSWORD_OK", p.rows.get(0).event);
        assertEquals("SUCCESS", p.rows.get(0).result);
        assertEquals(19.076, p.rows.get(0).lat, 0.0);
        assertEquals(-72.8777, p.rows.get(0).lng, 0.0);
        assertEquals("TOTP_FAIL", p.rows.get(1).event);
        assertNull(p.rows.get(1).lat);
    }
}
