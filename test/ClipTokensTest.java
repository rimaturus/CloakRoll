package app.photovault;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PC test of the tokenizer against the ids of the reference one (release clip-v1):
 *  javac -d /tmp/t src/app/photovault/ClipTokens.java test/ClipTokensTest.java && java -cp /tmp/t app.photovault.ClipTokensTest clip-merges.txt clip-tokens-test.json */
public class ClipTokensTest {
    public static void main(String[] a) throws Exception {
        ClipTokens t = new ClipTokens(Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8));
        String json = new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"text\": \"(.*?)\", \"ids\": \\[(.*?)\\]").matcher(json);
        int n = 0;
        while (m.find()) {
            long[] want = Arrays.stream(m.group(2).split(", ")).mapToLong(Long::parseLong).toArray();
            long[] got = t.encode(m.group(1));
            if (!Arrays.equals(want, got)) throw new AssertionError(m.group(1) + ": " + Arrays.toString(got) + " != " + Arrays.toString(want));
            n++;
        }
        if (n < 10) throw new AssertionError("only " + n + " cases");
        System.out.println("OK: " + n + " texts");
    }
}
