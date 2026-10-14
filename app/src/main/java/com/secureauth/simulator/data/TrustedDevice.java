package com.secureauth.simulator.data;

/** One row of the trusted_devices table. */
public class TrustedDevice {
    public long id;
    public long userId;
    public String deviceName;
    public long addedAt;
    public boolean revoked;
}
