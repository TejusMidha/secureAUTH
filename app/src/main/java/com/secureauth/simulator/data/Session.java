package com.secureauth.simulator.data;

/** One row of the sessions table. Only the SHA-256 hash of the session token is stored. */
public class Session {
    public long id;
    public long userId;
    public String tokenHash;
    public long createdAt;
    public long expiresAt;
}
