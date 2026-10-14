package com.secureauth.simulator.data;

/** One row of the users table. Times are epoch milliseconds. */
public class User {
    public long id;
    public String username;
    public String passwordHash;
    public String salt;
    public String avatarUri;      // null until the user picks a photo
    public int failedAttempts;
    public long lockedUntil;      // 0 means not locked
    public long createdAt;
}
