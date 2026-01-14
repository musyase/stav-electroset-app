package com.yourapp.stavelectroset.feature.news.auth.data.local.domain;

import android.util.Base64;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    private PasswordHasher() {}

    private static final int ITER = 150_000;
    private static final int SALT_LEN = 16;
    private static final int KEY_LEN = 256; // bits

    public static String hash(String password) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        byte[] dk = pbkdf2(password.toCharArray(), salt, ITER, KEY_LEN);
        return "pbkdf2$" + ITER + "$" + b64(salt) + "$" + b64(dk);
    }

    public static boolean verify(String password, String stored) throws Exception {
        // format: pbkdf2$ITER$SALT$HASH
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        int it = Integer.parseInt(p[1]);
        byte[] salt = b64d(p[2]);
        byte[] h = b64d(p[3]);
        byte[] dk = pbkdf2(password.toCharArray(), salt, it, h.length*8);
        if (dk.length != h.length) return false;
        int diff = 0;
        for (int i = 0; i < h.length; i++) diff |= (dk[i] ^ h[i]);
        return diff == 0;
    }

    private static byte[] pbkdf2(char[] pass, byte[] salt, int iter, int bits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, iter, bits);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static String b64(byte[] b){ return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] b64d(String s){ return Base64.decode(s, Base64.NO_WRAP); }
}
