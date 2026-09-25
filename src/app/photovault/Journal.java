package app.photovault;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** In-memory log shown in the app (Info → Log). Never contains cookies, passwords, keys or file names. */
final class Journal {
    private static final ArrayList<String> lines = new ArrayList<>();
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    static synchronized void add(String s) {
        lines.add(F.format(new Date()) + "  " + s);
        if (lines.size() > 400) lines.remove(0);
        android.util.Log.i("PhotoVault", s);
    }

    static synchronized String text() {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.length() == 0 ? "(empty)" : b.toString();
    }
}
