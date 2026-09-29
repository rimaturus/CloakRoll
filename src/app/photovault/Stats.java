package app.photovault;

import android.content.SharedPreferences;
import java.util.Locale;

/**
 * Measured speeds of this phone (encryption, decryption) and of the connection to the storage in use (upload,
 * download), kept in the settings. They give the time estimates and the "Transfer statistics" screen.
 * Each kind keeps: count, bytes, milliseconds and a moving average of the speed that follows recent conditions.
 */
final class Stats {
    static final String ENC = "enc", DEC = "dec", UP = "up", DOWN = "down";
    static final String[] KINDS = {UP, DOWN, ENC, DEC};

    private final Store st;
    private final SharedPreferences p;

    Stats(Store st) { this.st = st; p = st.prefs; }

    /** Upload and download speeds depend on the storage, so they are kept per storage. */
    private String key(String kind) { return "stat_" + (UP.equals(kind) || DOWN.equals(kind) ? st.backend() + "_" : "") + kind; }

    private double[] get(String kind) {
        String[] v = p.getString(key(kind), "0,0,0,0").split(",");
        double[] d = new double[4];
        for (int i = 0; i < 4 && i < v.length; i++) try { d[i] = Double.parseDouble(v[i]); } catch (NumberFormatException ignored) { }
        return d;
    }

    /** One measurement. Files under 64 KB say more about the connection's delay than its speed: not counted. */
    synchronized void add(String kind, long bytes, long ms) {
        if (bytes < (64 << 10)) return;
        ms = Math.max(1, ms);
        double[] d = get(kind);
        double rate = bytes * 1000.0 / ms; // bytes per second
        double avg = d[3] <= 0 ? rate : 0.7 * d[3] + 0.3 * rate;
        p.edit().putString(key(kind), String.format(Locale.ROOT, "%d,%d,%d,%.1f", (long) d[0] + 1, (long) d[1] + bytes, (long) d[2] + ms, avg)).apply();
    }

    /** Recent speed in bytes per second, 0 if never measured. */
    double speed(String kind) { return get(kind)[3]; }

    /** Speed of a transfer under way, from its own bytes and time once they say something; until then the remembered one. */
    double speed(String kind, long bytes, long ms) { return ms >= 2000 && bytes >= (1 << 20) ? bytes * 1000.0 / ms : speed(kind); }

    long count(String kind) { return (long) get(kind)[0]; }

    long bytes(String kind) { return (long) get(kind)[1]; }

    long millis(String kind) { return (long) get(kind)[2]; }

    /** Seconds to take `bytes` through all the given steps, or -1 if one of them was never measured. */
    long seconds(long bytes, String... kinds) {
        double s = 0;
        for (String k : kinds) {
            double r = speed(k);
            if (r <= 0) return -1;
            s += bytes / r;
        }
        return Math.round(Math.ceil(s));
    }

    /** Same, for a transfer running at `rate` bytes per second, followed by the given steps. */
    long seconds(long bytes, double rate, String... kinds) {
        long more = seconds(bytes, kinds);
        return rate <= 0 || more < 0 ? -1 : Math.round(Math.ceil(bytes / rate)) + more;
    }

    synchronized void reset() {
        SharedPreferences.Editor e = p.edit();
        for (String k : KINDS) e.remove(key(k));
        e.apply();
    }
}
