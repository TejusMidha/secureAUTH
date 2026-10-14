package com.secureauth.simulator.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/** Importing into a real (in-memory) database, and reading files through a ContentResolver. */
@RunWith(AndroidJUnit4.class)
public class CsvImporterDeviceTest {

    private static final String HEADER = "ts,datetime,event,result,lat,lng\r\n";

    private Context context;
    private DbHelper helper;
    private AuditLogDao dao;
    private long alice;
    private long bob;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null);
        dao = new AuditLogDao(helper);
        UserDao users = new UserDao(helper);
        alice = users.insert(user("alice"));
        bob = users.insert(user("bob"));
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private static User user(String name) {
        User u = new User();
        u.username = name;
        u.passwordHash = "h";
        u.salt = "s";
        u.createdAt = 1L;
        return u;
    }

    private void insert(long userId, long ts, String event, String result, Double lat, Double lng) {
        AuditLog a = new AuditLog();
        a.userId = userId;
        a.ts = ts;
        a.event = event;
        a.result = result;
        a.lat = lat;
        a.lng = lng;
        dao.insert(a);
    }

    // ---- importing --------------------------------------------------------------------------

    @Test
    public void rowsGoToTheGivenUserOnly() {
        CsvImporter.Result r = CsvImporter.importInto(dao, alice, HEADER
                + "100,,PASSWORD_OK,SUCCESS,,\r\n"
                + "200,,TOTP_OK,SUCCESS,19.5,72.5\r\n"
                + "300,,LOGOUT,SUCCESS,,\r\n");

        assertEquals(3, r.imported);
        assertEquals(0, r.duplicates);
        assertEquals(0, r.invalid);
        assertEquals(3, dao.listForUser(alice).size());
        assertEquals(0, dao.listForUser(bob).size());
        AuditLog withPlace = dao.listForUser(alice).get(1); // newest first: 300, 200, 100
        assertEquals("TOTP_OK", withPlace.event);
        assertEquals(19.5, withPlace.lat, 0.0);
        assertEquals(72.5, withPlace.lng, 0.0);
    }

    @Test
    public void aRowWithTheSameTimeAndEventIsSkipped() {
        insert(alice, 100L, "LOGOUT", "SUCCESS", null, null);

        CsvImporter.Result r = CsvImporter.importInto(dao, alice, HEADER
                + "100,,LOGOUT,SUCCESS,,\r\n"      // already there: same ts and event
                + "100,,TOTP_OK,SUCCESS,,\r\n"     // same time, other event: new
                + "200,,LOGOUT,SUCCESS,,\r\n"      // same event, other time: new
                + "200,,LOGOUT,SUCCESS,,\r\n");    // repeated inside the file itself

        assertEquals(2, r.imported);
        assertEquals(2, r.duplicates);
        assertEquals(3, dao.listForUser(alice).size());
    }

    @Test
    public void importingTheSameFileTwiceAddsNothingTheSecondTime() {
        String file = HEADER + "1,,A_ONE,SUCCESS,,\r\n2,,A_TWO,FAIL,,\r\n";
        assertEquals(2, CsvImporter.importInto(dao, alice, file).imported);

        CsvImporter.Result again = CsvImporter.importInto(dao, alice, file);
        assertEquals(0, again.imported);
        assertEquals(2, again.duplicates);
        assertEquals(2, dao.listForUser(alice).size());
    }

    @Test
    public void anotherUsersRowWithTheSameTimeAndEventDoesNotCountAsADuplicate() {
        insert(bob, 100L, "LOGOUT", "SUCCESS", null, null);
        CsvImporter.Result r = CsvImporter.importInto(dao, alice, HEADER + "100,,LOGOUT,SUCCESS,,\r\n");
        assertEquals(1, r.imported);
    }

    @Test
    public void invalidRowsAreCountedAndGoodOnesStillImport() {
        CsvImporter.Result r = CsvImporter.importInto(dao, alice, HEADER
                + "1,,GOOD_ONE,SUCCESS,,\r\n"
                + "x,,BAD_TIME,SUCCESS,,\r\n"
                + "3,,bad event,SUCCESS,,\r\n");
        assertEquals(1, r.imported);
        assertEquals(2, r.invalid);
        assertEquals(1, dao.listForUser(alice).size());
    }

    @Test
    public void aFileWithoutTheKeyColumnsChangesNothing() {
        insert(alice, 5L, "KEEP_ME", "SUCCESS", null, null);
        try {
            CsvImporter.importInto(dao, alice, "name,value\nx,y\n");
            fail("expected a rejection");
        } catch (IllegalArgumentException expected) {
            // good
        }
        assertEquals(1, dao.listForUser(alice).size());
    }

    @Test
    public void exportedHistoryCanBeImportedByAnotherUser() {
        insert(alice, 1700000000000L, "PASSWORD_OK", "SUCCESS", 19.076, 72.8777);
        insert(alice, 1700000005000L, "TOTP_FAIL", "FAIL", null, null);
        insert(alice, 1700000009000L, "SUSPICIOUS, \"odd\"", "SUCCESS", -1.5, -2.5); // would not import (invalid event)

        List<AuditLog> original = dao.listForUser(alice);
        String csv = FileExporter.formatAuditCsv(original, TimeZone.getTimeZone("UTC"));
        CsvImporter.Result r = CsvImporter.importInto(dao, bob, csv);

        assertEquals(2, r.imported);
        assertEquals(1, r.invalid); // the odd event name is refused, not smuggled in
        List<AuditLog> copy = dao.listForUser(bob);
        assertEquals(2, copy.size());
        assertEquals("TOTP_FAIL", copy.get(0).event);
        assertNull(copy.get(0).lat);
        assertEquals("PASSWORD_OK", copy.get(1).event);
        assertEquals(19.076, copy.get(1).lat, 0.0);
        assertEquals(72.8777, copy.get(1).lng, 0.0);
        assertEquals(1700000000000L, copy.get(1).ts);
    }

    @Test
    public void insertAllStoresEverythingOrNothing() {
        AuditLog good = new AuditLog();
        good.userId = alice;
        good.event = "GOOD";
        good.ts = 1L;
        AuditLog broken = new AuditLog();
        broken.userId = alice;
        broken.event = null; // event is NOT NULL
        broken.ts = 2L;

        assertEquals(-1, dao.insertAll(Arrays.asList(good, broken)));
        assertEquals(0, dao.listForUser(alice).size()); // the good one was rolled back too

        assertEquals(1, dao.insertAll(Arrays.asList(good)));
        assertEquals(1, dao.listForUser(alice).size());
    }

    // ---- reading the chosen file ------------------------------------------------------------

    @Test
    public void readsAFileThroughTheContentResolver() throws IOException {
        File file = new File(context.getCacheDir(), "import_test.csv");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("ts,event\n1,LOGOUT\n".getBytes("UTF-8"));
        }
        try {
            assertEquals("ts,event\n1,LOGOUT\n",
                    CsvImporter.readText(context.getContentResolver(), Uri.fromFile(file)));
        } finally {
            file.delete();
        }
    }

    @Test
    public void refusesAFileOverTheSizeLimit() throws IOException {
        File file = new File(context.getCacheDir(), "too_big.csv");
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'a');
            for (int written = 0; written <= CsvImporter.MAX_FILE_BYTES; written += chunk.length) {
                out.write(chunk);
            }
        }
        try {
            CsvImporter.readText(context.getContentResolver(), Uri.fromFile(file));
            fail("expected a size refusal");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("large"));
        } finally {
            file.delete();
        }
    }

    @Test(expected = IOException.class)
    public void aMissingFileIsAnIoError() throws IOException {
        CsvImporter.readText(context.getContentResolver(),
                Uri.fromFile(new File(context.getCacheDir(), "does_not_exist.csv")));
    }
}
