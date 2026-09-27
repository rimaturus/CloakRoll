import app.photovault.Vault;
import java.io.*;
import java.nio.file.*;

/**
 * Writes a big file as parts exactly like SyncService.uploadParts (same metadata fields and order), then checks
 * that photovault.py joins them:  java PartsTest <password> <file> <outdir>;  PV_PASSWORD=... python photovault.py dec <outdir>
 */
public class PartsTest {
    public static void main(String[] a) throws Exception {
        String pw = a[0]; byte[] data = Files.readAllBytes(Paths.get(a[1])); File out = new File(a[2]); out.mkdirs();
        int CHUNK = 32 << 20;
        byte[] salt = Vault.random(16), key = Vault.deriveKey(pw, salt);
        String group = "0123456789abcdef";
        int parts = (data.length + CHUNK - 1) / CHUNK;
        StringBuilder ids = new StringBuilder("[");
        for (int p = 1; p < parts; p++) {
            int off = p * CHUNK, len = Math.min(CHUNK, data.length - off);
            byte[] plain = Vault.plainBuffer("{\"group\":\"" + group + "\",\"part\":" + p + "}", len);
            System.arraycopy(data, off, plain, plain.length - len, len);
            try (OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(out, "p" + p + ".png")))) { Vault.encryptToPng(key, salt, plain, o); }
            ids.append(p > 1 ? "," : "").append("\"fakeAmazonId-").append(p).append("\"");
        }
        ids.append("]");
        String meta = "{\"name\":\"java-movie.mp4\",\"taken\":1,\"mime\":\"video\\/mp4\",\"group\":\"" + group + "\",\"part\":0,\"parts\":" + parts
                + ",\"size\":" + data.length + ",\"ids\":" + ids + ",\"thumb\":\"/9j/4AAQSkZJRg==\"}";
        int len = Math.min(CHUNK, data.length);
        byte[] plain = Vault.plainBuffer(meta, len);
        System.arraycopy(data, 0, plain, plain.length - len, len);
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(out, "p0.png")))) { Vault.encryptToPng(key, salt, plain, o); }
        // and back in Java: each part decrypts and says where it belongs
        for (int p = 0; p < parts; p++) {
            Vault.Opened o = Vault.decryptPng(new FileInputStream(new File(out, "p" + p + ".png")), key);
            System.out.println("java part " + p + " meta " + (o.meta.length() > 90 ? o.meta.substring(0, 90) + "..." : o.meta) + " data " + o.dataLen());
        }
    }
}
