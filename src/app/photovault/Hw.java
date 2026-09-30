package app.photovault;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small secrets kept on the phone (sign-in tokens, the key copy for automatic backup), sealed with AES-GCM under a
 * key that never leaves the phone's secure hardware and needs no fingerprint. Stored as "iv:ciphertext" in Base64.
 */
final class Hw {
    private static SecretKey key(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey k = (SecretKey) ks.getKey(alias, null);
        if (k != null) return k;
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return g.generateKey();
    }

    static String seal(String alias, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(alias));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(data), Base64.NO_WRAP);
    }

    /** The data sealed as `stored`; null if there is none. Throws if the hardware key is gone or the data was altered. */
    static byte[] open(String alias, String stored) throws Exception {
        int i = stored == null ? -1 : stored.indexOf(':');
        if (i < 0) return null;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(alias), new GCMParameterSpec(128, Base64.decode(stored.substring(0, i), Base64.NO_WRAP)));
        return c.doFinal(Base64.decode(stored.substring(i + 1), Base64.NO_WRAP));
    }

    static void delete(String alias) {
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(alias); } catch (Exception ignored) { }
    }
}
