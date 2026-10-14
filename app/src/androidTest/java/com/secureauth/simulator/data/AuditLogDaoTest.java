package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** CRUD and date-range tests for AuditLogDao on an in-memory database. */
public class AuditLogDaoTest {

    private DbHelper helper;
    private AuditLogDao dao;
    private long userId;
    private long otherUserId;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null);
        dao = new AuditLogDao(helper);

        // audit_log rows need a real user because of the foreign key
        UserDao users = new UserDao(helper);
        userId = users.insert(user("alice"));
        otherUserId = users.insert(user("bob"));
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private User user(String name) {
        User u = new User();
        u.username = name;
        u.passwordHash = "h";
        u.salt = "s";
        u.createdAt = 1L;
        return u;
    }

    private AuditLog log(long forUser, String event, long ts) {
        AuditLog a = new AuditLog();
        a.userId = forUser;
        a.event = event;
        a.result = "SUCCESS";
        a.ts = ts;
        return a;
    }

    @Test
    public void insertThenGetByIdKeepsLocation() {
        AuditLog a = log(userId, "PASSWORD_OK", 5000L);
        a.lat = 19.076;
        a.lng = 72.8777;
        long id = dao.insert(a);
        assertTrue(id > 0);

        AuditLog loaded = dao.getById(id);
        assertNotNull(loaded);
        assertEquals("PASSWORD_OK", loaded.event);
        assertEquals("SUCCESS", loaded.result);
        assertEquals(19.076, loaded.lat, 0.00001);
        assertEquals(72.8777, loaded.lng, 0.00001);
        assertEquals(5000L, loaded.ts);
    }

    @Test
    public void missingLocationStaysNullNotZero() {
        long id = dao.insert(log(userId, "REGISTER", 1L));
        AuditLog loaded = dao.getById(id);
        assertNull(loaded.lat);
        assertNull(loaded.lng);
    }

    @Test
    public void listForUserIsNewestFirstAndOnlyThatUser() {
        dao.insert(log(userId, "A", 100L));
        dao.insert(log(userId, "C", 300L));
        dao.insert(log(userId, "B", 200L));
        dao.insert(log(otherUserId, "OTHER", 250L));

        List<AuditLog> rows = dao.listForUser(userId);
        assertEquals(3, rows.size());
        assertEquals("C", rows.get(0).event);
        assertEquals("B", rows.get(1).event);
        assertEquals("A", rows.get(2).event);
    }

    @Test
    public void listForUserBetweenIsInclusiveAtBothEnds() {
        dao.insert(log(userId, "BEFORE", 99L));
        dao.insert(log(userId, "START", 100L));
        dao.insert(log(userId, "MIDDLE", 150L));
        dao.insert(log(userId, "END", 200L));
        dao.insert(log(userId, "AFTER", 201L));
        dao.insert(log(otherUserId, "OTHER_USER", 150L));

        List<AuditLog> rows = dao.listForUserBetween(userId, 100L, 200L);
        assertEquals(3, rows.size());
        assertEquals("END", rows.get(0).event);
        assertEquals("MIDDLE", rows.get(1).event);
        assertEquals("START", rows.get(2).event);
    }

    @Test
    public void logWithAPlaceStoresTheCoordinates() {
        long id = dao.log(userId, "PASSWORD_OK", "SUCCESS", 19.076, 72.8777);
        AuditLog row = dao.getById(id);
        assertEquals(19.076, row.lat, 0.00001);
        assertEquals(72.8777, row.lng, 0.00001);

        long plain = dao.log(userId, "LOGOUT", "SUCCESS"); // the short form has no place
        assertNull(dao.getById(plain).lat);
    }

    @Test
    public void findLastWithLocationReturnsTheNewestLocatedEntryOfThatEvent() {
        AuditLog older = log(userId, "SESSION_START", 1000L);
        older.lat = 10.0;
        older.lng = 20.0;
        dao.insert(older);
        AuditLog newer = log(userId, "SESSION_START", 3000L);
        newer.lat = 11.0;
        newer.lng = 21.0;
        dao.insert(newer);
        dao.insert(log(userId, "SESSION_START", 5000L));          // newest, but no place: skipped
        AuditLog otherEvent = log(userId, "PASSWORD_OK", 6000L);  // newest with a place, wrong event
        otherEvent.lat = 99.0;
        otherEvent.lng = 99.0;
        dao.insert(otherEvent);
        AuditLog otherUser = log(otherUserId, "SESSION_START", 7000L);
        otherUser.lat = 55.0;
        otherUser.lng = 55.0;
        dao.insert(otherUser);

        AuditLog found = dao.findLastWithLocation(userId, "SESSION_START");
        assertNotNull(found);
        assertEquals(3000L, found.ts);
        assertEquals(11.0, found.lat, 0.0);
    }

    @Test
    public void findLastWithLocationIsNullWhenThereIsNone() {
        dao.insert(log(userId, "SESSION_START", 1000L)); // no place
        assertNull(dao.findLastWithLocation(userId, "SESSION_START"));
        assertNull(dao.findLastWithLocation(userId, "NEVER_HAPPENED"));
    }

    @Test
    public void updateChangesTheRow() {
        long id = dao.insert(log(userId, "TOTP_FAIL", 10L));
        AuditLog a = dao.getById(id);
        a.result = "FAIL";
        a.lat = 1.5;
        a.lng = 2.5;

        assertEquals(1, dao.update(a));
        AuditLog reloaded = dao.getById(id);
        assertEquals("FAIL", reloaded.result);
        assertEquals(1.5, reloaded.lat, 0.00001);
    }

    @Test
    public void deleteRemovesTheRow() {
        long id = dao.insert(log(userId, "LOGOUT", 10L));
        assertEquals(1, dao.delete(id));
        assertNull(dao.getById(id));
        assertTrue(dao.listForUser(userId).isEmpty());
    }
}
