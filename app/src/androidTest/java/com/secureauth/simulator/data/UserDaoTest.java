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

/** CRUD tests for UserDao on an in-memory database (nothing touches the real secureauth.db). */
public class UserDaoTest {

    private DbHelper helper;
    private UserDao dao;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null); // null name = in-memory database
        dao = new UserDao(helper);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private User newUser(String name) {
        User u = new User();
        u.username = name;
        u.passwordHash = "hash-" + name;
        u.salt = "salt-" + name;
        u.createdAt = 1000L;
        return u;
    }

    @Test
    public void insertThenGetByIdReturnsSameData() {
        long id = dao.insert(newUser("alice"));
        assertTrue(id > 0);

        User loaded = dao.getById(id);
        assertNotNull(loaded);
        assertEquals("alice", loaded.username);
        assertEquals("hash-alice", loaded.passwordHash);
        assertEquals("salt-alice", loaded.salt);
        assertNull(loaded.avatarUri);
        assertEquals(0, loaded.failedAttempts);
        assertEquals(0L, loaded.lockedUntil);
        assertEquals(1000L, loaded.createdAt);
    }

    @Test
    public void findByUsernameFindsExistingAndNullForMissing() {
        long id = dao.insert(newUser("bob"));
        assertEquals(id, dao.findByUsername("bob").id);
        assertNull(dao.findByUsername("nobody"));
    }

    @Test
    public void duplicateUsernameIsRejected() {
        assertTrue(dao.insert(newUser("carol")) > 0);
        assertEquals(-1L, dao.insert(newUser("carol")));
        assertEquals(1, dao.list().size());
    }

    @Test
    public void sqlInjectionTextIsStoredAsPlainValue() {
        // A parameterised query treats this as ordinary text, not as SQL
        String tricky = "x' OR '1'='1";
        dao.insert(newUser("dave"));
        assertNull(dao.findByUsername(tricky));
    }

    @Test
    public void updateChangesTheRow() {
        long id = dao.insert(newUser("erin"));
        User u = dao.getById(id);
        u.avatarUri = "file:///avatar.jpg";
        u.passwordHash = "new-hash";

        assertEquals(1, dao.update(u));
        User reloaded = dao.getById(id);
        assertEquals("file:///avatar.jpg", reloaded.avatarUri);
        assertEquals("new-hash", reloaded.passwordHash);
    }

    @Test
    public void failedAttemptsCountUpAndReset() {
        long id = dao.insert(newUser("frank"));
        dao.incrementFailedAttempts(id);
        dao.incrementFailedAttempts(id);
        assertEquals(2, dao.getById(id).failedAttempts);

        dao.resetFailedAttempts(id);
        assertEquals(0, dao.getById(id).failedAttempts);
    }

    @Test
    public void setLockedUntilStoresAndClearsTheLock() {
        long id = dao.insert(newUser("gina"));
        dao.setLockedUntil(id, 123456789L);
        assertEquals(123456789L, dao.getById(id).lockedUntil);

        dao.setLockedUntil(id, 0);
        assertEquals(0L, dao.getById(id).lockedUntil);
    }

    @Test
    public void deleteRemovesTheRow() {
        long id = dao.insert(newUser("hank"));
        assertEquals(1, dao.delete(id));
        assertNull(dao.getById(id));
        assertEquals(0, dao.delete(id)); // already gone
    }
}
