package me.wooy.proxy.encryption;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;

public class Aes {
    private static byte[] iv = new byte[]{0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05, 0x05};
    private static IvParameterSpec ivParams = new IvParameterSpec(iv);
    public static byte[] encrypt(byte[] value,byte[] raw,boolean doZip) throws Exception {
        Key sKeySpec = new SecretKeySpec(raw, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, sKeySpec, ivParams);
        if(doZip){
            return cipher.doFinal(Zlib.compress(value));
        }
        return cipher.doFinal(value);
    }

    public static byte[] decrypt(byte[] encrypted,byte[] raw,boolean doZip) throws Exception {
        Key key = new SecretKeySpec(raw, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, ivParams);
        if(doZip){
            return Zlib.decompress(cipher.doFinal(encrypted));
        }
        return cipher.doFinal(encrypted);
    }
}
