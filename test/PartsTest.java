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
        int CHUNK = 16 << 20;
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
        // a header that claims more than any file holds (here: over 2 GB, in a 65536 x 65536 image) is refused
        // before memory is set aside for it. The pixels start at byte 49 of our PNGs; the length is at 32 of the header.
        byte[] png = Files.readAllBytes(new File(out, "p0.png").toPath());
        if (png[49] != 'P' || png[52] != '2') throw new IllegalStateException("vault header not where the test expects it");
        for (int i = 16; i < 24; i++) png[i] = (byte) (i % 4 == 1 ? 1 : 0);
        png[49 + 32 + 4] = (byte) 0x80;
        try { Vault.decryptPng(new ByteArrayInputStream(png), key); System.out.println("HUGE LENGTH ACCEPTED"); System.exit(1); }
        catch (IOException e) { System.out.println("huge length refused: " + e.getMessage()); }
    }
}
