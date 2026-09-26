package app.photovault;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * File format "PVT2", identical to photovault.py. Pure Java, no Android code, so it is unit-tested on a PC.
 *
 * Pixel bytes of an 8-bit RGB PNG = MAGIC(4) | salt(16) | nonce(12) | ctLen(u64) | AES-256-GCM(plain) | random padding
 * plain = metaLen(u16) | meta JSON (name, taken, mime) | original file bytes
 * The 36-byte header is authenticated as GCM associated data.
 */
public final class Vault {
    static final byte[] MAGIC = {'P', 'V', 'T', '2'};
    static final int HDR = 4 + 16 + 12 + 8;
    static final int ITERATIONS = 600_000;
    static final SecureRandom RNG = new SecureRandom();
    private static final byte[] PNG_SIG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public static byte[] random(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

    /** PBKDF2-HMAC-SHA256 (RFC 8018), one 32-byte block, password as UTF-8. Same as Python hashlib.pbkdf2_hmac. */
    public static byte[] deriveKey(String password, byte[] salt) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(salt);
        mac.update(new byte[]{0, 0, 0, 1});
        byte[] u = mac.doFinal(), t = u.clone();
        for (int i = 1; i < ITERATIONS; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < 32; j++) t[j] ^= u[j];
        }
        return t;
    }

    /** Buffer [metaLen | meta | space for `size` data bytes]; caller reads the file into it at offset length - size. */
    public static byte[] plainBuffer(String meta, int size) {
        byte[] m = meta.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[2 + m.length + size];
        p[0] = (byte) (m.length >> 8);
        p[1] = (byte) m.length;
        System.arraycopy(m, 0, p, 2, m.length);
        return p;
    }

    /** Encrypts `plain` (from plainBuffer) and writes it as a noise PNG to `out`. */
    public static void encryptToPng(byte[] key, byte[] salt, byte[] plain, OutputStream out) throws IOException, GeneralSecurityException {
        byte[] nonce = random(12);
        long ctLen = plain.length + 16L;
        byte[] blob = new byte[(int) (HDR + ctLen)];
        System.arraycopy(MAGIC, 0, blob, 0, 4);
        System.arraycopy(salt, 0, blob, 4, 16);
        System.arraycopy(nonce, 0, blob, 20, 12);
        for (int i = 0; i < 8; i++) blob[32 + i] = (byte) (ctLen >>> (56 - 8 * i));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(blob, 0, HDR);
        c.doFinal(plain, 0, plain.length, blob, HDR);
        writePng(blob, out);
    }

    public static final class Opened {
        public final String meta;
        public final byte[] plain, salt; // salt: which vault key this file was made with
        public final int dataOff;
        Opened(String meta, byte[] plain, int dataOff, byte[] salt) { this.meta = meta; this.plain = plain; this.dataOff = dataOff; this.salt = salt; }
        public int dataLen() { return plain.length - dataOff; }
    }

    /** Salt stored in a vault PNG (needed to derive the key on a new phone). */
    public static byte[] readSalt(InputStream png) throws IOException {
        byte[] hdr = new byte[HDR];
        new PngReader(png).readFully(hdr, 0, HDR);
        checkMagic(hdr);
        return Arrays.copyOfRange(hdr, 4, 20);
    }

    /** Decrypts a vault PNG. Throws AEADBadTagException on wrong key or if any byte was altered. */
    public static Opened decryptPng(InputStream png, byte[] key) throws IOException, GeneralSecurityException {
        PngReader r = new PngReader(png);
        byte[] hdr = new byte[HDR];
        r.readFully(hdr, 0, HDR);
        checkMagic(hdr);
        long ctLen = 0;
        for (int i = 0; i < 8; i++) ctLen = (ctLen << 8) | (hdr[32 + i] & 0xFF);
        if (ctLen < 18 || ctLen > r.capacity() - HDR) throw new IOException("corrupt vault header");
        byte[] ct = new byte[(int) ctLen];
        r.readFully(ct, 0, ct.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, hdr, 20, 12));
        c.updateAAD(hdr);
        byte[] plain = c.doFinal(ct);
        int m = ((plain[0] & 0xFF) << 8) | (plain[1] & 0xFF);
        return new Opened(new String(plain, 2, m, StandardCharsets.UTF_8), plain, 2 + m, Arrays.copyOfRange(hdr, 4, 20));
    }

    /** Small local secrets (thumbnails, password check): nonce(12) | AES-GCM(data). */
    public static byte[] seal(byte[] key, byte[] data) throws GeneralSecurityException {
        byte[] nonce = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(data), out = new byte[12 + ct.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    public static byte[] open(byte[] key, byte[] sealed) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }

    public static String sha256(byte[] b, int off, int len) throws GeneralSecurityException {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(b, off, len);
        StringBuilder s = new StringBuilder();
        for (byte x : d.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static void checkMagic(byte[] hdr) throws IOException {
        for (int i = 0; i < 4; i++) if (hdr[i] != MAGIC[i]) throw new IOException("not a PhotoVault file");
    }

    // ---------------------------------------------------------------- PNG (8-bit RGB, no interlace)

    private static void writePng(byte[] blob, OutputStream out) throws IOException {
        int w = (int) Math.ceil(Math.sqrt(blob.length / 3.0));
        int h = (int) Math.ceil(blob.length / (3.0 * w));
        DataOutputStream d = new DataOutputStream(out);
        d.write(PNG_SIG);
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream ih = new DataOutputStream(ihdr);
        ih.writeInt(w); ih.writeInt(h); ih.write(new byte[]{8, 2, 0, 0, 0});
        chunk(d, "IHDR", ihdr.toByteArray(), ihdr.size());
        IdatStream idat = new IdatStream(d);
        Deflater def = new Deflater(Deflater.NO_COMPRESSION); // random data does not compress: store = fastest
        DeflaterOutputStream z = new DeflaterOutputStream(idat, def, 1 << 16);
        int row = w * 3;
        byte[] pad = new byte[row];
        for (int y = 0; y < h; y++) {
            z.write(0); // filter: none
            int start = y * row, n = Math.max(0, Math.min(row, blob.length - start));
            if (n > 0) z.write(blob, start, n);
            if (n < row) { RNG.nextBytes(pad); z.write(pad, 0, row - n); } // random padding: noise to the last pixel
        }
        z.finish();
        def.end();
        idat.flushChunk();
        chunk(d, "IEND", new byte[0], 0);
        d.flush();
    }

    private static void chunk(DataOutputStream d, String type, byte[] data, int len) throws IOException {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data, 0, len);
        d.writeInt(len);
        d.write(t);
        d.write(data, 0, len);
        d.writeInt((int) crc.getValue());
    }

    /** Buffers compressed data into 1 MB IDAT chunks. */
    private static final class IdatStream extends OutputStream {
        final DataOutputStream d;
        final byte[] buf = new byte[1 << 20];
        int n;
        IdatStream(DataOutputStream d) { this.d = d; }
        @Override public void write(int b) throws IOException { if (n == buf.length) flushChunk(); buf[n++] = (byte) b; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (n == buf.length) flushChunk();
                int k = Math.min(len, buf.length - n);
                System.arraycopy(b, off, buf, n, k);
                n += k; off += k; len -= k;
            }
        }
        void flushChunk() throws IOException { if (n > 0) { chunk(d, "IDAT", buf, n); n = 0; } }
    }

    /** Streams the unfiltered pixel bytes of an 8-bit RGB PNG (any filter types, e.g. from Pillow). */
    static final class PngReader {
        final int w, h, row;
        final InflaterInputStream z;
        byte[] prev, cur;
        int pos, y;

        PngReader(InputStream in) throws IOException {
            final DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16));
            byte[] sig = new byte[8];
            d.readFully(sig);
            if (!Arrays.equals(sig, PNG_SIG)) throw new IOException("not a PNG");
            int len = d.readInt();
            byte[] t = new byte[4];
            d.readFully(t);
            if (!"IHDR".equals(new String(t, StandardCharsets.US_ASCII))) throw new IOException("bad PNG");
            w = d.readInt(); h = d.readInt();
            byte depth = d.readByte(), color = d.readByte();
            d.skipBytes(2);
            byte interlace = d.readByte();
            d.skipBytes(len - 13 + 4); // rest + CRC
            if (depth != 8 || color != 2 || interlace != 0 || w <= 0 || h <= 0 || w > 1 << 16 || h > 1 << 16)
                throw new IOException("not a PhotoVault PNG (needs 8-bit RGB)");
            row = w * 3;
            prev = new byte[row];
            cur = new byte[row];
            pos = row;
            z = new InflaterInputStream(new InputStream() { // concatenated IDAT payloads
                int left = 0;
                boolean done;
                boolean next() throws IOException {
                    while (!done) {
                        int n = d.readInt();
                        byte[] ty = new byte[4];
                        d.readFully(ty);
                        String s = new String(ty, StandardCharsets.US_ASCII);
                        if (s.equals("IDAT")) { left = n; if (n > 0) return true; d.skipBytes(4); continue; }
                        if (s.equals("IEND")) { done = true; break; }
                        d.skipBytes(n + 4);
                    }
                    return false;
                }
                @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (left == 0 && !next()) return -1;
                    int k = d.read(b, off, Math.min(len, left));
                    if (k < 0) throw new EOFException();
                    left -= k;
                    if (left == 0) d.skipBytes(4); // CRC
                    return k;
                }
            }, new Inflater(), 1 << 16);
        }

        long capacity() { return (long) row * h; }

        void readFully(byte[] dst, int off, int len) throws IOException {
            while (len > 0) {
                if (pos == row) nextRow();
                int k = Math.min(len, row - pos);
                System.arraycopy(cur, pos, dst, off, k);
                pos += k; off += k; len -= k;
            }
        }

        private void nextRow() throws IOException {
            if (y++ >= h) throw new EOFException("PNG too short");
            byte[] t = prev; prev = cur; cur = t;
            int f = z.read();
            if (f < 0) throw new EOFException("PNG too short");
            for (int n = 0; n < row; ) { int k = z.read(cur, n, row - n); if (k < 0) throw new EOFException(); n += k; }
            for (int i = 0; i < row; i++) {
                int a = i >= 3 ? cur[i - 3] & 0xFF : 0, b = prev[i] & 0xFF, c = i >= 3 ? prev[i - 3] & 0xFF : 0, p;
                switch (f) {
                    case 0: continue;
                    case 1: p = a; break;
                    case 2: p = b; break;
                    case 3: p = (a + b) >>> 1; break;
                    case 4: { int q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c);
                              p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; break; }
                    default: throw new IOException("bad PNG filter " + f);
                }
                cur[i] = (byte) (cur[i] + p);
            }
            pos = 0;
        }
    }
}
