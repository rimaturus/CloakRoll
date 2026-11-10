package app.photovault;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.graphics.Bitmap;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/**
 * Smart search: OpenAI's CLIP (ViT-B/32) on the phone, through ONNX Runtime. Each item's preview becomes a vector of
 * 512 numbers, and so does a text ("dog", "beach at sunset"): the closer the two, the better the match. The vectors are
 * kept on the phone only, sealed with the vault key like the previews. Nothing about the photos leaves the phone.
 */
final class Clip {
    /** The model: release clip-v4 of the app's repository (made by tools/clip_export.py), checked against these SHA-256. */
    static final String URL = "https://github.com/rimaturus/CloakRoll/releases/download/clip-v4/";
    static final String IMAGE = "clip-image.onnx", TEXT = "clip-text.onnx", MERGES = "clip-merges.txt";
    static final String[][] FILES = { // name, SHA-256, bytes
            {IMAGE, "c0d0ab0ed3a7382b80756325b8e2ea8b0fe01196f0db2df16265145088a2c30a", "106212272"},
            {TEXT, "daf1cb9ce4cbefd93ddc53d554e0529885bedafd775955d783e5c4c58c2ffafe", "40127267"},
            {MERGES, "d308b7377a8ceaa9707a21614fe8c831b9196e197b7aeb69833359362907af02", "524605"}};
    static final float MATCH = 0.2f; // search: the least similarity shown
    static final int MAX_HITS = 300;
    /** Junk: an item whose preview is more like these than like the KEEP ones (all together) is flagged. */
    static final String[] JUNK = {"a screenshot of a phone screen", "a screenshot of a chat conversation", "a meme",
            "a funny meme with text", "a good morning greeting image with flowers and text", "a cartoon sticker"};
    static final String[] KEEP = {"a photo of people", "a selfie", "a group photo", "a photo of a child", "a photo of a landscape",
            "a photo of a city", "a photo of food", "a photo of an animal", "a photo of a room", "a photo of an object",
            "a photo of a car", "a photo of a document", "a photo taken at night"};
    private static final float[] MEAN = {0.48145466f, 0.4578275f, 0.40821073f}, STD = {0.26862954f, 0.26130258f, 0.27577711f};

    private final Store st;
    private Map<String, float[]> vectors; // item id -> unit vector (empty: no preview to look at); only while unlocked
    private OrtSession image, text;
    private ClipTokens tokens;
    private float[][] classes;            // vectors of JUNK, then KEEP

    Clip(Store st) { this.st = st; }

    boolean on() { return st.prefs.getBoolean("smart_search", false); }

    private File dir() { File d = new File(st.app.getFilesDir(), "clip"); d.mkdirs(); return d; }
    private File vectorFile() { return new File(st.app.getFilesDir(), "clip.bin"); }

    /** The model is on the phone. */
    boolean ready() {
        for (String[] f : FILES) if (!new File(dir(), f[0]).exists()) return false;
        return true;
    }

    static long modelBytes() { long n = 0; for (String[] f : FILES) n += Long.parseLong(f[2]); return n; }

    /** Downloads the missing model files, each checked against its SHA-256. Background thread. */
    void download(Cloud.Progress p) throws Exception {
        long done = 0, total = modelBytes();
        for (String[] f : FILES) {
            File dst = new File(dir(), f[0]), part = new File(dir(), f[0] + ".part");
            if (dst.exists()) { done += Long.parseLong(f[2]); continue; }
            HttpURLConnection c = (HttpURLConnection) new URL(URL + f[0]).openConnection();
            c.setConnectTimeout(30_000);
            c.setReadTimeout(60_000);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try {
                if (c.getResponseCode() != 200) throw new IOException("search model: HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] b = new byte[1 << 16];
                    for (int n; (n = in.read(b)) > 0; ) { out.write(b, 0, n); md.update(b, 0, n); done += n; p.on(done, total); }
                }
            } finally { c.disconnect(); }
            if (!Store.hex(md.digest()).equals(f[1])) { part.delete(); throw new IOException("search model: download damaged, try again"); }
            if (!part.renameTo(dst)) throw new IOException("search model: cannot save it");
        }
    }

    /** Smart search turned off: model and vectors deleted. */
    synchronized void remove() {
        forget();
        try { if (image != null) image.close(); } catch (OrtException ignored) { }
        image = null;
        File[] fs = dir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
        vectorFile().delete();
    }

    /** On lock: the vectors (they say what the photos show) are dropped from memory, with the text model. */
    synchronized void forget() {
        vectors = null;
        classes = null;
        try { if (text != null) text.close(); } catch (OrtException ignored) { }
        text = null;
    }

    // ---------------------------------------------------------------- vectors, sealed on the phone

    private Map<String, float[]> vectors(byte[] k) {
        if (vectors != null) return vectors;
        vectors = new HashMap<>();
        File f = vectorFile();
        if (f.exists()) try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(st.openSealed(k, Store.readFile(f))))) {
            for (int n = in.readInt(); n > 0; n--) {
                String id = in.readUTF();
                float[] v = new float[in.readShort()];
                for (int i = 0; i < v.length; i++) v[i] = in.readFloat();
                vectors.put(id, v);
            }
        } catch (Exception e) { Journal.add("search index unreadable, made again: " + e); vectors.clear(); }
        return vectors;
    }

    /** A copy, to read without holding the lock. */
    synchronized Map<String, float[]> snapshot(byte[] k) { return new HashMap<>(vectors(k)); }

    synchronized void put(byte[] k, String id, float[] v) { vectors(k).put(id, v); }

    /** Drops the vectors of items no longer in the vault. True if any. */
    synchronized boolean keepOnly(byte[] k, Set<String> ids) { return vectors(k).keySet().retainAll(ids); }

    synchronized void save(byte[] k) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        Map<String, float[]> m = vectors(k);
        o.writeInt(m.size());
        for (Map.Entry<String, float[]> e : m.entrySet()) {
            o.writeUTF(e.getKey());
            o.writeShort(e.getValue().length);
            for (float x : e.getValue()) o.writeFloat(x);
        }
        File tmp = new File(vectorFile().getPath() + ".new");
        Store.writeSynced(tmp, Vault.seal(k, b.toByteArray()));
        if (!tmp.renameTo(vectorFile())) throw new IOException("cannot save the search index");
    }

    /** Items with a preview on this phone that have not been looked at yet. */
    int missing(byte[] k, List<Store.Item> items) {
        Map<String, float[]> v = snapshot(k);
        int n = 0;
        for (Store.Item it : items) if (!v.containsKey(it.id) && st.thumbFile(it.id).exists()) n++;
        return n;
    }

    // ---------------------------------------------------------------- the model

    private OrtSession session(String name) throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(4);
        return OrtEnvironment.getEnvironment().createSession(new File(dir(), name).getPath(), o);
    }

    private static float[] run(OrtSession s, String input, OnnxTensor t) throws OrtException {
        try (OnnxTensor in = t; OrtSession.Result r = s.run(Collections.singletonMap(input, in))) {
            return ((float[][]) r.get(0).getValue())[0];
        }
    }

    /** The vector of a preview: scaled to 224 px on its short side, centre cut to 224 x 224, as CLIP was trained. */
    float[] image(Bitmap b) throws Exception {
        OrtSession m;
        synchronized (this) { if (image == null) image = session(IMAGE); m = image; }
        int w = b.getWidth(), h = b.getHeight(), side = 224, plane = side * side;
        float s = (float) side / Math.min(w, h);
        int sw = Math.max(side, Math.round(w * s)), sh = Math.max(side, Math.round(h * s));
        Bitmap r = Bitmap.createScaledBitmap(b, sw, sh, true);
        int[] px = new int[plane];
        r.getPixels(px, 0, side, (sw - side) / 2, (sh - side) / 2, side, side);
        float[] f = new float[3 * plane];
        for (int i = 0; i < plane; i++)
            for (int c = 0; c < 3; c++) f[c * plane + i] = ((px[i] >> (16 - 8 * c) & 255) / 255f - MEAN[c]) / STD[c];
        return run(m, "pixels", OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(f), new long[]{1, 3, side, side}));
    }

    /** After indexing: the image model's memory is given back. */
    synchronized void closeImage() {
        try { if (image != null) image.close(); } catch (OrtException ignored) { }
        image = null;
    }

    synchronized float[] text(String q) throws Exception {
        if (text == null) text = session(TEXT);
        if (tokens == null) tokens = new ClipTokens(Files.readAllLines(new File(dir(), MERGES).toPath(), StandardCharsets.UTF_8));
        long[] ids = tokens.encode(q);
        return run(text, "ids", OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), LongBuffer.wrap(ids), new long[]{1, ids.length}));
    }

    static float dot(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    // ---------------------------------------------------------------- search and junk

    /** Ids of the items that match the text, best first. Background thread. */
    List<String> search(byte[] k, String q) throws Exception {
        float[] t = text(q);
        final Map<String, Float> score = new HashMap<>();
        for (Map.Entry<String, float[]> e : snapshot(k).entrySet()) {
            float s = dot(t, e.getValue());
            if (s >= MATCH) score.put(e.getKey(), s);
        }
        List<String> ids = new ArrayList<>(score.keySet());
        Collections.sort(ids, new Comparator<String>() { public int compare(String a, String b) { return Float.compare(score.get(b), score.get(a)); } });
        return ids.size() > MAX_HITS ? new ArrayList<>(ids.subList(0, MAX_HITS)) : ids;
    }

    /** Of these items, the ones that look like junk (screenshots, memes, greeting images). Background thread. */
    List<String> junk(byte[] k, Collection<String> ids) throws Exception {
        float[][] c;
        synchronized (this) {
            if (classes == null) {
                float[][] n = new float[JUNK.length + KEEP.length][];
                for (int i = 0; i < n.length; i++) n[i] = text(i < JUNK.length ? JUNK[i] : KEEP[i - JUNK.length]);
                classes = n;
            }
            c = classes;
        }
        Map<String, float[]> v = snapshot(k);
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            float[] x = v.get(id);
            if (x == null || x.length == 0) continue;
            double all = 0, junk = 0;
            for (int i = 0; i < c.length; i++) {
                double e = Math.exp(100 * dot(c[i], x)); // CLIP's own scale: a softmax over all the descriptions
                all += e;
                if (i < JUNK.length) junk += e;
            }
            if (junk / all > 0.5) out.add(id);
        }
        return out;
    }
}
