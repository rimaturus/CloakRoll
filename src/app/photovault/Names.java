package app.photovault;

import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a file's name says about it: when it was taken and what kind of file it is. Plain Java (test/NamesTest.java). */
public final class Names {
    /** 2024-01-31 or 20240131 (also with _ or .), then maybe the time: 12-34-56, 123456, "at 12.34.56". */
    private static final Pattern DATE = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})([-_.]?)(0[1-9]|1[0-2])\\2(0[1-9]|[12]\\d|3[01])"
            + "(?:(?:[-_ T.]| at )?([01]\\d|2[0-3])[-_.:]?([0-5]\\d)[-_.:]?([0-5]\\d))?");
    /** Milliseconds since 1970, as in FB_IMG_1706700000000.jpg (13 digits: years 2001 to 2033). */
    private static final Pattern EPOCH = Pattern.compile("(?<!\\d)(1\\d{12})(?!\\d)");

    /** The date (and time, if there) written in a file name, in this phone's time zone; 0 if there is none. */
    public static long date(String name) {
        if (name == null) return 0;
        Matcher m = DATE.matcher(name);
        if (m.find()) {
            boolean time = m.group(5) != null;
            return new GregorianCalendar(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)) - 1, Integer.parseInt(m.group(4)),
                    time ? Integer.parseInt(m.group(5)) : 0, time ? Integer.parseInt(m.group(6)) : 0, time ? Integer.parseInt(m.group(7)) : 0).getTimeInMillis();
        }
        m = EPOCH.matcher(name);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /**
     * Android's photo picker may hide a file's name and give only its media id ("1000000034.jpg"): then the name is made
     * from the date the picker reports ("2026-08-02 17.13.07.jpg"), so the item still sorts by date. Other names are kept.
     */
    public static String pickerName(String name, String id, long taken) {
        if (name == null || id == null || taken <= 0 || !id.matches("\\d+") || !name.startsWith(id + ".")) return name;
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(new java.util.Date(taken)) + name.substring(id.length());
    }

    public static final int SCREENSHOT = 0, RECORDING = 1, WHATSAPP = 2, TELEGRAM = 3, SOCIAL = 4, CAMERA = 5, VIDEO = 6, TYPES = 7;

    private static final Pattern[] TYPE = {
            Pattern.compile("screenshot|screen_shot|schermata|bildschirmfoto|captura|capture d"),
            Pattern.compile("screen[ _-]?record|xrecorder"),
            Pattern.compile("whatsapp|-wa\\d{4}"),
            Pattern.compile("telegram"),
            Pattern.compile("^fb_img|facebook|instagram|snapchat|messenger|^received_|^signal-"),
            Pattern.compile("^(img|pxl|dscn?|dscf|mvimg|vid|mov|pano|burst)[_-]?\\d|^\\d{8}_\\d{6}"),
    };

    /**
     * The kind of file its name (as phones and apps write them) says it is: one of the constants above, or -1.
     * A video nothing else is known about is VIDEO.
     */
    public static int type(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        // ponytail: a fixed list of name patterns; a table the user can edit if people ask for their own rules
        for (int i = 0; i < TYPE.length; i++) if (TYPE[i].matcher(n).find()) return i;
        return mime != null && mime.startsWith("video/") ? VIDEO : -1;
    }
}
