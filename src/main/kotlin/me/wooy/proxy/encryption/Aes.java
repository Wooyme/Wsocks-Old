package me.wooy.proxy.encryption;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;

public class Aes {
  public static byte[] raw = new byte[]{'C', 'a', 'o', 'N', 'i', 'M', 'a', ',', 'N', 'M', 'S', 'L', '!', 'G', 'F', 'W'};
  public static byte[] encrypt(byte[] value) {
    byte[] encrypted = null;
    try {
      Key skeySpec = new SecretKeySpec(raw, "AES");
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      byte[] iv = new byte[cipher.getBlockSize()];

      IvParameterSpec ivParams = new IvParameterSpec(iv);
      cipher.init(Cipher.ENCRYPT_MODE, skeySpec,ivParams);
      encrypted  = cipher.doFinal(value);

    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return encrypted;
  }

  public static  byte[]  decrypt(byte[] encrypted) {
    byte[] original = null;
    Cipher cipher;
    try {
      Key key = new SecretKeySpec(raw, "AES");
      cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      byte[] ivByte = new byte[cipher.getBlockSize()];
      IvParameterSpec ivParamsSpec = new IvParameterSpec(ivByte);
      cipher.init(Cipher.DECRYPT_MODE, key, ivParamsSpec);
      original= cipher.doFinal(encrypted);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return original;
  }

}
