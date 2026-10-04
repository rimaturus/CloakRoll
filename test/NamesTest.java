import app.photovault.Names;
import java.util.GregorianCalendar;

/** PC test of the dates and kinds read from file names: java NamesTest */
public class NamesTest {
    static void date(String name, int y, int mo, int d, int h, int mi, int s) {
        long want = y == 0 ? 0 : new GregorianCalendar(y, mo - 1, d, h, mi, s).getTimeInMillis();
        if (Names.date(name) != want) throw new AssertionError(name + ": " + new java.util.Date(Names.date(name)));
    }

    static void type(String name, String mime, int want) {
        if (Names.type(name, mime) != want) throw new AssertionError(name + ": " + Names.type(name, mime));
    }

    public static void main(String[] a) {
        date("IMG_20240131_123456.jpg", 2024, 1, 31, 12, 34, 56);
        date("PXL_20240131_123456789.jpg", 2024, 1, 31, 12, 34, 56);
        date("Screenshot_2024-01-31-12-34-56-789_com.app.jpg", 2024, 1, 31, 12, 34, 56);
        date("Screenshot_20240131-123456_Chrome.jpg", 2024, 1, 31, 12, 34, 56);
        date("IMG-20240131-WA0007.jpg", 2024, 1, 31, 0, 0, 0);
        date("WhatsApp Image 2024-01-31 at 12.34.56.jpeg", 2024, 1, 31, 12, 34, 56);
        date("20191225_080000.mp4", 2019, 12, 25, 8, 0, 0);
        date("holiday 2019.12.25.jpg", 2019, 12, 25, 0, 0, 0);
        date("image.jpg", 0, 0, 0, 0, 0, 0);
        date("IMG_1234.jpg", 0, 0, 0, 0, 0, 0);
        date("20241345.jpg", 0, 0, 0, 0, 0, 0);        // no month 13
        date("scan 120240131.jpg", 0, 0, 0, 0, 0, 0);  // inside a longer number
        if (Names.date("FB_IMG_1706700000000.jpg") != 1706700000000L) throw new AssertionError("epoch");
        if (Names.date(null) != 0) throw new AssertionError("null");

        type("Screenshot_20240131-123456_Chrome.jpg", "image/jpeg", Names.SCREENSHOT);
        type("Schermata 2024-01-31.png", "image/png", Names.SCREENSHOT);
        type("Screen_Recording_20240131_123456.mp4", "video/mp4", Names.RECORDING);
        type("IMG-20240131-WA0007.jpg", "image/jpeg", Names.WHATSAPP);
        type("VID-20240131-WA0001.mp4", "video/mp4", Names.WHATSAPP);
        type("FB_IMG_1706700000000.jpg", "image/jpeg", Names.SOCIAL);
        type("IMG_20240131_123456.jpg", "image/jpeg", Names.CAMERA);
        type("PXL_20240131_123456789.mp4", "video/mp4", Names.CAMERA);
        type("20191225_080000.jpg", "image/jpeg", Names.CAMERA);
        type("DSC01234.JPG", "image/jpeg", Names.CAMERA);
        type("clip.mp4", "video/mp4", Names.VIDEO);
        type("image.jpg", "image/jpeg", -1);
        type(null, null, -1);
        System.out.println("names ok");
    }
}
