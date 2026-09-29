package app.photovault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Receives the browser's return from the Microsoft or Google sign-in (io.github.rimaturus.photovault:...) and hands it to
 * the app screen already open, instead of opening a second one. MainActivity checks the answer.
 */
public class AuthRedirect extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startActivity(new Intent(this, MainActivity.class).setData(getIntent().getData())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
