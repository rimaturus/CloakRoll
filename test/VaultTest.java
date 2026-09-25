import app.photovault.Vault;
import java.io.*;
import java.nio.file.*;

/** PC cross-test: java VaultTest <password> <python-made.png> <original> <out-java.png> */
public class VaultTest {
    public static void main(String[] a) throws Exception {
        String pw = a[0];
        long t = System.currentTimeMillis();
        byte[] salt = Vault.readSalt(new FileInputStream(a[1]));
        byte[] key = Vault.deriveKey(pw, salt);
        System.out.println("pbkdf2 " + (System.currentTimeMillis() - t) + " ms, key " + Vault.sha256(key, 0, 32).substring(0, 16));
        Vault.Opened o = Vault.decryptPng(new FileInputStream(a[1]), key);
        byte[] orig = Files.readAllBytes(Paths.get(a[2]));
        boolean same = Vault.sha256(o.plain, o.dataOff, o.dataLen()).equals(Vault.sha256(orig, 0, orig.length));
        System.out.println("python->java: meta=" + o.meta + " bit-exact=" + same);
        if (!same) System.exit(1);
        try { byte[] wrong = Vault.deriveKey(pw + "x", salt); Vault.decryptPng(new FileInputStream(a[1]), wrong); System.out.println("WRONG PASSWORD ACCEPTED"); System.exit(1); }
        catch (javax.crypto.AEADBadTagException e) { System.out.println("wrong password rejected"); }
        byte[] plain = Vault.plainBuffer("{\"name\":\"java-" + new File(a[2]).getName() + "\",\"taken\":1,\"mime\":\"image/jpeg\"}", orig.length);
        System.arraycopy(orig, 0, plain, plain.length - orig.length, orig.length);
        t = System.currentTimeMillis();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(a[3]))) { Vault.encryptToPng(key, salt, plain, out); }
        System.out.println("java enc " + (System.currentTimeMillis() - t) + " ms");
        byte[] s = Vault.seal(key, "hi".getBytes());
        System.out.println("seal/open " + new String(Vault.open(key, s)));
    }
}
