package app.photovault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CLIP's tokenizer (byte-level BPE, as in OpenAI's simple_tokenizer.py): text -> token ids for the text model. */
final class ClipTokens {
    static final int SOT = 49406, EOT = 49407, MAX = 77;
    private static final Pattern WORD = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private final String[] byteChar = new String[256];
    private final Map<String, Integer> ids = new HashMap<>(), ranks = new HashMap<>();

    /** `merges`: the lines of clip-merges.txt ("a b"), best first. The vocabulary is rebuilt from them. */
    ClipTokens(List<String> merges) {
        List<Integer> bs = new ArrayList<>();
        for (int b = '!'; b <= '~'; b++) bs.add(b);
        for (int b = 0xA1; b <= 0xAC; b++) bs.add(b);
        for (int b = 0xAE; b <= 0xFF; b++) bs.add(b);
        List<Integer> cs = new ArrayList<>(bs);
        for (int b = 0, n = 0; b < 256; b++) if (!bs.contains(b)) { bs.add(b); cs.add(256 + n++); }
        List<String> vocab = new ArrayList<>();
        for (int i = 0; i < 256; i++) { byteChar[bs.get(i)] = String.valueOf((char) (int) cs.get(i)); vocab.add(byteChar[bs.get(i)]); }
        for (int i = 0; i < 256; i++) vocab.add(vocab.get(i) + "</w>");
        for (String m : merges) {
            if (m.isEmpty()) continue;
            ranks.put(m, ranks.size());
            vocab.add(m.replace(" ", ""));
        }
        vocab.add("<|startoftext|>");
        vocab.add("<|endoftext|>");
        for (int i = 0; i < vocab.size(); i++) ids.put(vocab.get(i), i);
    }

    /** Start token, the text's tokens (at most 75), end token. */
    long[] encode(String text) {
        List<Integer> out = new ArrayList<>();
        out.add(SOT);
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " "));
        while (m.find() && out.size() < MAX - 1) {
            StringBuilder b = new StringBuilder();
            for (byte x : m.group().getBytes(StandardCharsets.UTF_8)) b.append(byteChar[x & 0xff]);
            for (String p : bpe(b.toString())) if (out.size() < MAX - 1) out.add(ids.get(p));
        }
        out.add(EOT);
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Merges the word's symbols, best-ranked pair first, until no pair is in the list. */
    private List<String> bpe(String token) {
        List<String> w = new ArrayList<>();
        for (int i = 0; i < token.length(); i++) w.add(String.valueOf(token.charAt(i)));
        w.set(w.size() - 1, w.get(w.size() - 1) + "</w>");
        while (w.size() > 1) {
            int best = -1, rank = Integer.MAX_VALUE;
            for (int i = 0; i + 1 < w.size(); i++) {
                Integer r = ranks.get(w.get(i) + " " + w.get(i + 1));
                if (r != null && r < rank) { rank = r; best = i; }
            }
            if (best < 0) break;
            String a = w.get(best), b = w.get(best + 1);
            List<String> n = new ArrayList<>();
            for (int i = 0; i < w.size(); i++) {
                if (i + 1 < w.size() && w.get(i).equals(a) && w.get(i + 1).equals(b)) { n.add(a + b); i++; }
                else n.add(w.get(i));
            }
            w = n;
        }
        return w;
    }
}
