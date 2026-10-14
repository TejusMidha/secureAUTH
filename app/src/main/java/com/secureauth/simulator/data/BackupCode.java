package com.secureauth.simulator.data;

/** One row of the backup_codes table. Only a salted hash of the code is stored. */
public class BackupCode {
    public long id;
    public long userId;
    public String codeHash;
    public String salt;
    public boolean used;
    public Long usedAt;        // null until the code is used
}
