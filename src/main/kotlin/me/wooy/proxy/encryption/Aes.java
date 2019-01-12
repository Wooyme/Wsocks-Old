package me.wooy.proxy.encryption;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;

public class Aes {
    public static byte[] raw = new byte[]{0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06};
    public static byte[] iv = new byte[]{0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05};

    public static byte[] encrypt(byte[] value) throws Exception {
        byte[] encrypted = null;
        Key skeySpec = new SecretKeySpec(raw, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivParams = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec, ivParams);
        encrypted = cipher.doFinal(value);

        return encrypted;
    }

    public static byte[] decrypt(byte[] encrypted) throws Exception {
        byte[] original = null;
        Cipher cipher;
        Key key = new SecretKeySpec(raw, "AES");
        cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivParamsSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, key, ivParamsSpec);
        original = cipher.doFinal(encrypted);
        return original;
    }

}
