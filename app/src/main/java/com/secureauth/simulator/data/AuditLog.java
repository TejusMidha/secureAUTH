package com.secureauth.simulator.data;

/** One row of the audit_log table: something that happened during authentication. */
public class AuditLog {
    public long id;
    public long userId;
    public String event;       // e.g. PASSWORD_OK
    public String result;      // e.g. SUCCESS / FAIL
    public Double lat;         // null when no location was recorded
    public Double lng;
    public long ts;
}
