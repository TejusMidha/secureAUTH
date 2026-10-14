package com.secureauth.simulator.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** Tests for the foreign key rules and the DAOs of the remaining four tables. */
public class RelatedTablesDaoTest {

    private DbHelper helper;
    private UserDao users;
    private long userId;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new DbHelper(context, null);
        users = new UserDao(helper);

        User u = new User();
        u.username = "alice";
        u.passwordHash = "h";
        u.salt = "s";
        u.createdAt = 1L;
        userId = users.insert(u);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void foreignKeyRejectsRowForMissingUser() {
        AuditLog a = new AuditLog();
        a.userId = 9999L; // no such user
        a.event = "X";
        a.ts = 1L;
        assertEquals(-1L, new AuditLogDao(helper).insert(a));
    }

    @Test
    public void deletingUserCascadesToChildRows() {
        AuditLog a = new AuditLog();
        a.userId = userId;
        a.event = "REGISTER";
        a.ts = 1L;
        AuditLogDao audit = new AuditLogDao(helper);
        audit.insert(a);
        assertEquals(1, audit.listForUser(userId).size());

        users.delete(userId);
        assertTrue(audit.listForUser(userId).isEmpty());
    }

    @Test
    public void totpAccountRoundTripAndDefaults() {
        TotpAccountDao dao = new TotpAccountDao(helper);
        TotpAccount a = new TotpAccount();
        a.userId = userId;
        a.secretEnc = "cipher";
        a.iv = "iv";
        a.issuer = "SecureAuth";
        a.createdAt = 5L;
        long id = dao.insert(a);

        TotpAccount loaded = dao.getById(id);
        assertEquals("cipher", loaded.secretEnc);
        assertEquals(6, loaded.digits);
        assertEquals(30, loaded.period);
        assertEquals("HmacSHA1", loaded.algo);
        assertEquals(1, dao.listForUser(userId).size());

        loaded.digits = 8;
        assertEquals(1, dao.update(loaded));
        assertEquals(8, dao.getById(id).digits);

        assertEquals(1, dao.delete(id));
        assertNull(dao.getById(id));
    }

    @Test
    public void replaceForUserLeavesExactlyTheNewAccount() {
        TotpAccountDao dao = new TotpAccountDao(helper);
        dao.insert(account("old-1"));
        dao.insert(account("old-2"));
        assertEquals(2, dao.listForUser(userId).size());

        long newId = dao.replaceForUser(account("fresh"));
        assertTrue(newId > 0);

        List<TotpAccount> left = dao.listForUser(userId);
        assertEquals(1, left.size());
        assertEquals("fresh", left.get(0).secretEnc);
    }

    @Test
    public void replaceForUserKeepsTheOldAccountIfTheInsertFails() {
        TotpAccountDao dao = new TotpAccountDao(helper);
        dao.insert(account("keep-me"));

        // secret_enc is NOT NULL, so the insert fails AFTER the old row was deleted in the same
        // transaction; the rollback must bring the old account back
        TotpAccount bad = account(null);
        assertEquals(-1L, dao.replaceForUser(bad));

        assertEquals(1, dao.listForUser(userId).size());
        assertEquals("keep-me", dao.listForUser(userId).get(0).secretEnc);
    }

    @Test
    public void replaceForUserDoesNotTouchOtherUsers() {
        TotpAccountDao dao = new TotpAccountDao(helper);
        User other = new User();
        other.username = "bob";
        other.passwordHash = "h";
        other.salt = "s";
        other.createdAt = 1L;
        long otherId = users.insert(other);
        TotpAccount theirs = account("bobs");
        theirs.userId = otherId;
        dao.insert(theirs);

        dao.replaceForUser(account("mine"));

        assertEquals(1, dao.listForUser(otherId).size());
        assertEquals("bobs", dao.listForUser(otherId).get(0).secretEnc);
    }

    private TotpAccount account(String secretEnc) {
        TotpAccount a = new TotpAccount();
        a.userId = userId;
        a.secretEnc = secretEnc;
        a.iv = "iv";
        a.issuer = "SecureAuth";
        a.createdAt = 1L;
        return a;
    }

    @Test
    public void backupCodesListUnusedExcludesUsedOnes() {
        BackupCodeDao dao = new BackupCodeDao(helper);
        long first = dao.insert(code("h1"));
        dao.insert(code("h2"));
        assertEquals(2, dao.listUnused(userId).size());

        assertEquals(1, dao.markUsed(first));
        List<BackupCode> unused = dao.listUnused(userId);
        assertEquals(1, unused.size());
        assertEquals("h2", unused.get(0).codeHash);

        BackupCode used = dao.getById(first);
        assertTrue(used.used);
        assertNotNull(used.usedAt);
        assertEquals(2, dao.listForUser(userId).size());
    }

    @Test
    public void aBackupCodeCanBeMarkedUsedOnlyOnce() {
        BackupCodeDao dao = new BackupCodeDao(helper);
        long id = dao.insert(code("h1"));

        assertEquals(1, dao.markUsed(id)); // the first use wins
        assertEquals(0, dao.markUsed(id)); // a second, racing use changes nothing
    }

    @Test
    public void replacingBackupCodesSwapsTheWholeSet() {
        BackupCodeDao dao = new BackupCodeDao(helper);
        long usedOne = dao.insert(code("old-1"));
        dao.insert(code("old-2"));
        dao.markUsed(usedOne);

        assertTrue(dao.replaceForUser(userId, java.util.Arrays.asList(code("new-1"), code("new-2"), code("new-3"))));

        List<BackupCode> now = dao.listForUser(userId);
        assertEquals(3, now.size()); // used and unused old codes are both gone
        assertEquals(3, dao.listUnused(userId).size());
        assertEquals("new-1", now.get(0).codeHash);
    }

    @Test
    public void aFailedReplaceKeepsTheOldCodes() {
        BackupCodeDao dao = new BackupCodeDao(helper);
        dao.insert(code("keep-1"));
        dao.insert(code("keep-2"));

        BackupCode broken = code(null); // code_hash is NOT NULL, so this insert fails midway
        assertFalse(dao.replaceForUser(userId, java.util.Arrays.asList(code("new-1"), broken)));

        List<BackupCode> after = dao.listForUser(userId);
        assertEquals(2, after.size());
        assertEquals("keep-1", after.get(0).codeHash);
    }

    @Test
    public void replacingBackupCodesLeavesOtherUsersAlone() {
        BackupCodeDao dao = new BackupCodeDao(helper);
        User other = new User();
        other.username = "bob";
        other.passwordHash = "h";
        other.salt = "s";
        other.createdAt = 1L;
        long otherId = users.insert(other);
        BackupCode theirs = code("theirs");
        theirs.userId = otherId;
        dao.insert(theirs);

        dao.replaceForUser(userId, java.util.Collections.singletonList(code("mine")));

        assertEquals(1, dao.listForUser(otherId).size());
        assertEquals("theirs", dao.listForUser(otherId).get(0).codeHash);
    }

    private BackupCode code(String hash) {
        BackupCode b = new BackupCode();
        b.userId = userId;
        b.codeHash = hash;
        b.salt = "salt";
        return b;
    }

    @Test
    public void trustedDeviceRenameAndRevoke() {
        TrustedDeviceDao dao = new TrustedDeviceDao(helper);
        TrustedDevice d = new TrustedDevice();
        d.userId = userId;
        d.deviceName = "Google Pixel 8";
        d.addedAt = 10L;
        long id = dao.insert(d);

        TrustedDevice loaded = dao.getById(id);
        assertFalse(loaded.revoked);

        loaded.deviceName = "My phone";
        loaded.revoked = true;
        assertEquals(1, dao.update(loaded));

        TrustedDevice after = dao.getById(id);
        assertEquals("My phone", after.deviceName);
        assertTrue(after.revoked);
        assertEquals(1, dao.delete(id));
        assertTrue(dao.listForUser(userId).isEmpty());
    }

    @Test
    public void sessionFindValidChecksHashAndExpiry() {
        SessionDao dao = new SessionDao(helper);
        Session s = new Session();
        s.userId = userId;
        s.tokenHash = "abc";
        s.createdAt = 100L;
        s.expiresAt = 500L;
        dao.insert(s);

        assertNotNull(dao.findValid(userId, "abc", 400L));   // still valid
        assertNull(dao.findValid(userId, "abc", 500L));      // expired exactly now
        assertNull(dao.findValid(userId, "wrong", 400L));    // wrong token
    }

    @Test
    public void deleteExpiredRemovesOnlyOldSessions() {
        SessionDao dao = new SessionDao(helper);
        dao.insert(session("old", 300L));
        dao.insert(session("fresh", 900L));

        assertEquals(1, dao.deleteExpired(500L));
        List<Session> left = dao.listForUser(userId);
        assertEquals(1, left.size());
        assertEquals("fresh", left.get(0).tokenHash);
    }

    private Session session(String hash, long expiresAt) {
        Session s = new Session();
        s.userId = userId;
        s.tokenHash = hash;
        s.createdAt = 1L;
        s.expiresAt = expiresAt;
        return s;
    }
}
