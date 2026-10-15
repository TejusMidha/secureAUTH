package com.secureauth.simulator.data;

/** Names written to audit_log.event and audit_log.result. Later phases add more events. */
public final class AuditEvent {

    public static final String REGISTER = "REGISTER";
    public static final String PASSWORD_OK = "PASSWORD_OK";
    public static final String PASSWORD_FAIL = "PASSWORD_FAIL";
    public static final String LOCKED_OUT = "LOCKED_OUT";

    public static final String TOTP_ENROLLED = "TOTP_ENROLLED";
    public static final String ENROLL_VERIFY_FAIL = "ENROLL_VERIFY_FAIL";

    public static final String TOTP_OK = "TOTP_OK";
    public static final String TOTP_FAIL = "TOTP_FAIL";

    public static final String SUSPICIOUS_LOCATION = "SUSPICIOUS_LOCATION";

    public static final String BACKUP_CODES_GENERATED = "BACKUP_CODES_GENERATED";
    public static final String BACKUP_CODE_USED = "BACKUP_CODE_USED";
    public static final String BACKUP_CODE_FAIL = "BACKUP_CODE_FAIL";

    public static final String BIOMETRIC_OK = "BIOMETRIC_OK";
    public static final String BIOMETRIC_FAIL = "BIOMETRIC_FAIL";
    public static final String SESSION_START = "SESSION_START";
    public static final String LOGOUT = "LOGOUT";
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";

    public static final String DEVICE_TRUSTED = "DEVICE_TRUSTED";
    public static final String DEVICE_RENAMED = "DEVICE_RENAMED";
    public static final String DEVICE_REVOKED = "DEVICE_REVOKED";

    public static final String AUTO_LOCK = "AUTO_LOCK";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_WARNING = "WARNING";
    public static final String RESULT_CANCELLED = "CANCELLED";
    public static final String RESULT_ERROR = "ERROR";

    private AuditEvent() {
    }
}
