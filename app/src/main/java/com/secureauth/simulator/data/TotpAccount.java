package com.secureauth.simulator.data;

/** One row of the totp_accounts table. The secret is stored only in encrypted form. */
public class TotpAccount {
    public long id;
    public long userId;
    public String secretEnc;   // Base64 AES-GCM ciphertext
    public String iv;          // Base64 GCM initialisation vector
    public String issuer;
    public int digits = 6;
    public int period = 30;
    public String algo = "HmacSHA1";
    public long createdAt;
}
