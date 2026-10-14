package com.secureauth.simulator.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Creates and opens the app database (secureauth.db). One shared instance is used
 * by every DAO so the app never has two helpers writing to the same file.
 */
public class DbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "secureauth.db";
    public static final int DB_VERSION = 1;

    public static final String TABLE_USERS = "users";
    public static final String TABLE_TOTP_ACCOUNTS = "totp_accounts";
    public static final String TABLE_BACKUP_CODES = "backup_codes";
    public static final String TABLE_AUDIT_LOG = "audit_log";
    public static final String TABLE_TRUSTED_DEVICES = "trusted_devices";
    public static final String TABLE_SESSIONS = "sessions";

    private static DbHelper instance;

    /** The app-wide helper. Uses the application context so no Activity is leaked. */
    public static synchronized DbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DbHelper(context.getApplicationContext(), DB_NAME);
        }
        return instance;
    }

    /**
     * Creates a helper for the given file name. Pass null as the name to get an
     * in-memory database that disappears when closed (used by the tests).
     */
    public DbHelper(Context context, String name) {
        super(context, name, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // SQLite ignores FOREIGN KEY rules unless this is switched on for every connection
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Child tables use ON DELETE CASCADE so deleting a user removes all of their rows
        db.execSQL("CREATE TABLE " + TABLE_USERS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "username TEXT NOT NULL UNIQUE, "
                + "password_hash TEXT NOT NULL, "
                + "salt TEXT NOT NULL, "
                + "avatar_uri TEXT, "
                + "failed_attempts INTEGER NOT NULL DEFAULT 0, "
                + "locked_until INTEGER NOT NULL DEFAULT 0, "
                + "created_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_TOTP_ACCOUNTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, "
                + "secret_enc TEXT NOT NULL, "
                + "iv TEXT NOT NULL, "
                + "issuer TEXT, "
                + "digits INTEGER NOT NULL DEFAULT 6, "
                + "period INTEGER NOT NULL DEFAULT 30, "
                + "algo TEXT NOT NULL DEFAULT 'HmacSHA1', "
                + "created_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_BACKUP_CODES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, "
                + "code_hash TEXT NOT NULL, "
                + "salt TEXT NOT NULL, "
                + "used INTEGER NOT NULL DEFAULT 0, "
                + "used_at INTEGER)");

        db.execSQL("CREATE TABLE " + TABLE_AUDIT_LOG + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, "
                + "event TEXT NOT NULL, "
                + "result TEXT, "
                + "lat REAL, "
                + "lng REAL, "
                + "ts INTEGER NOT NULL)");
        // History screens always filter by user and sort by time, so index exactly that
        db.execSQL("CREATE INDEX idx_audit_log_user_ts ON " + TABLE_AUDIT_LOG + " (user_id, ts)");

        db.execSQL("CREATE TABLE " + TABLE_TRUSTED_DEVICES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, "
                + "device_name TEXT NOT NULL, "
                + "added_at INTEGER NOT NULL, "
                + "revoked INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_SESSIONS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, "
                + "token_hash TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "expires_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first release, so there is nothing to migrate yet.
        // Never drop tables here: they hold encrypted TOTP secrets the user cannot recreate.
    }
}
