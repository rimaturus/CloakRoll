package app.photovault;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

/**
 * Look and feel, built in code (no resources, no libraries): one palette for light and one for dark mode,
 * cards, pill buttons, inputs and a small set of icons drawn as paths, so no font can show them as empty boxes.
 * Texts come from the strings.xml files in res (one per language); the only symbol used in them is the bullet.
 */
final class Ui {
    static final int PRIMARY = 0, TONAL = 1, TEXT = 2, DANGER = 3;

    final Context c;
    final float density;
    final boolean night;
    final int bg, surface, field, text, muted, line, accent, accentSoft, onAccent, ok, bad, warn, okSoft, badSoft, warnSoft;

    Ui(Context c) {
        this.c = c;
        density = c.getResources().getDisplayMetrics().density;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            bg = 0xFF0E1113; surface = 0xFF1A1F23; field = 0xFF242A2F; text = 0xFFE7EAEC; muted = 0xFF9BA5AD; line = 0x24FFFFFF;
            accent = 0xFF3DD9C1; accentSoft = 0xFF153B37; onAccent = 0xFF00201C;
            ok = 0xFF7FD48A; bad = 0xFFFF9A93; warn = 0xFFFFC069; okSoft = 0xFF1B3320; badSoft = 0xFF3D1E1C; warnSoft = 0xFF3A2C12;
        } else {
            bg = 0xFFF3F5F6; surface = 0xFFFFFFFF; field = 0xFFEEF1F2; text = 0xFF182024; muted = 0xFF5B676F; line = 0x1F000000;
            accent = 0xFF0B7A6C; accentSoft = 0xFFD3EFEA; onAccent = 0xFFFFFFFF;
            ok = 0xFF22863A; bad = 0xFFC62828; warn = 0xFFB25E00; okSoft = 0xFFE3F4E6; badSoft = 0xFFFBE4E2; warnSoft = 0xFFFDF0DC;
        }
    }

    int dp(float v) { return Math.round(v * density); }

    // ---------------------------------------------------------------- backgrounds

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Touch feedback on top of `content` (or on nothing), clipped to the same rounded shape. */
    Drawable ripple(Drawable content, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x33FFFFFF : 0x22000000), content, round(0xFFFFFFFF, radiusPx));
    }

    // ---------------------------------------------------------------- layout

    LinearLayout vbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout hbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Content of a normal screen: a column with side margins. */
    LinearLayout page() {
        LinearLayout l = vbox();
        l.setPadding(dp(16), dp(8), dp(16), dp(28));
        return l;
    }

    ScrollView scroll(View v) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.addView(v);
        return s;
    }

    LinearLayout.LayoutParams wide(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    /** A rounded surface that groups related things. */
    LinearLayout card(LinearLayout parent) {
        LinearLayout k = vbox();
        k.setBackground(round(surface, dp(20)));
        k.setPadding(dp(16), dp(14), dp(16), dp(14));
        parent.addView(k, wide(dp(12)));
        return k;
    }

    /** Same, tinted: an ok / warning / error message. */
    LinearLayout notice(LinearLayout parent, int soft) {
        LinearLayout k = card(parent);
        k.setBackground(round(soft, dp(16)));
        return k;
    }

    /** Setup progress: `of` segments, the first `done` filled. */
    void steps(LinearLayout parent, int done, int of) {
        LinearLayout row = hbox();
        for (int i = 0; i < of; i++) {
            View s = new View(c);
            s.setBackground(round(i < done ? accent : line, dp(2)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            row.addView(s, lp);
        }
        parent.addView(row, wide(dp(4)));
    }

    // ---------------------------------------------------------------- text

    TextView label(ViewGroup parent, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.18f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY); // gravity decides, whatever the phone's theme sets
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (parent != null) parent.addView(t);
        return t;
    }

    TextView title(LinearLayout p, String s) {
        TextView t = label(p, s, 26, text, true);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    TextView heading(LinearLayout p, String s) {
        TextView t = label(p, s, 17, text, true);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    /** Small label above a group of cards. */
    TextView section(LinearLayout p, String s) {
        TextView t = label(p, s, 13, accent, true);
        t.setPadding(dp(4), dp(20), 0, 0);
        t.setLetterSpacing(0.06f);
        return t;
    }

    TextView body(LinearLayout p, String s) {
        TextView t = label(p, s, 15, text, false);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    TextView note(LinearLayout p, String s) {
        TextView t = label(p, s, 14, muted, false);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    // ---------------------------------------------------------------- controls

    /** Pill button. Disabled buttons are dimmed. */
    TextView button(LinearLayout parent, String s, int kind, View.OnClickListener l) {
        TextView b = new TextView(c) {
            @Override public void setEnabled(boolean e) { super.setEnabled(e); setAlpha(e ? 1f : 0.4f); }
        };
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        b.setIncludeFontPadding(false); // optically centered also with fonts that have uneven built-in padding
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(dp(52));
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        int fill = kind == PRIMARY ? accent : kind == TONAL ? accentSoft : kind == DANGER ? badSoft : 0;
        b.setTextColor(kind == PRIMARY ? onAccent : kind == DANGER ? bad : accent);
        b.setBackground(ripple(fill == 0 ? null : round(fill, dp(26)), dp(26)));
        b.setOnClickListener(l);
        if (parent != null) parent.addView(b, wide(dp(kind == TEXT ? 2 : 10)));
        return b;
    }

    /** Password field with a Show / Hide switch. `done` runs on the keyboard's action key (may be null). */
    EditText password(LinearLayout parent, String hint, final Runnable done) {
        LinearLayout box = hbox();
        box.setBackground(round(field, dp(14)));
        final EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(muted);
        e.setTextColor(text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(null);
        e.setSingleLine();
        e.setPadding(dp(16), dp(14), dp(8), dp(14));
        final int hidden = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        e.setInputType(hidden);
        e.setImeOptions(done == null ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        if (done != null) e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, android.view.KeyEvent ev) {
                if (a == EditorInfo.IME_ACTION_DONE) { done.run(); return true; }
                return false;
            }
        });
        box.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final String showText = c.getString(R.string.show), hideText = c.getString(R.string.hide);
        final TextView show = label(box, showText, 14, accent, true);
        show.setPadding(dp(14), dp(14), dp(16), dp(14));
        show.setBackground(ripple(null, dp(14)));
        show.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            boolean visible = e.getInputType() != hidden;
            e.setInputType(visible ? hidden : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            e.setTypeface(Typeface.DEFAULT);
            e.setSelection(e.getText().length());
            show.setText(visible ? showText : hideText);
        }});
        parent.addView(box, wide(dp(10)));
        return e;
    }

    /** A line under a new password: too short / ok / strong. Advice only; the length rule is checked on submit. */
    void strength(final EditText e, LinearLayout parent) {
        final TextView t = label(parent, c.getString(R.string.strength_hint), 13, muted, false);
        t.setPadding(dp(6), dp(6), 0, 0);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            public void onTextChanged(CharSequence s, int a, int b, int d) { }
            public void afterTextChanged(Editable s) {
                int n = s.length(), words = s.toString().trim().split("[\\s\\-_.]+").length;
                if (n == 0) { t.setText(R.string.strength_hint); t.setTextColor(muted); }
                else if (n < 10) { t.setText(c.getString(R.string.strength_short, n)); t.setTextColor(bad); }
                else if (n < 16 && words < 4) { t.setText(R.string.strength_ok); t.setTextColor(warn); }
                else { t.setText(R.string.strength_strong); t.setTextColor(ok); }
            }
        });
    }

    CheckBox check(LinearLayout parent, String s) {
        CheckBox b = new CheckBox(c);
        b.setText(s);
        b.setTextColor(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setButtonTintList(ColorStateList.valueOf(accent));
        b.setPadding(dp(6), dp(8), 0, dp(8));
        parent.addView(b, wide(dp(8)));
        return b;
    }

    Switch toggle(boolean on) {
        Switch s = new Switch(c);
        s.setChecked(on);
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{accent, night ? 0xFFB0B8BE : 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(st, new int[]{accentSoft, night ? 0xFF3A4247 : 0xFFC4CCD0}));
        s.setClickable(false); // the whole row is the button
        s.setFocusable(false);
        return s;
    }

    /** Icon on a tinted circle. */
    ImageView badge(int icon, int color, int soft, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(icon, color));
        v.setBackground(oval(soft));
        int pad = dp(sizeDp) / 4;
        v.setPadding(pad, pad, pad, pad);
        return v;
    }

    /** Settings-style row: icon, title, optional detail, optional control at the end. The row itself is the button. */
    LinearLayout row(LinearLayout parent, int icon, String title, String detail, View end, View.OnClickListener l) {
        LinearLayout r = hbox();
        r.setPadding(dp(8), dp(10), dp(8), dp(10));
        r.setMinimumHeight(dp(60));
        if (l != null) { r.setBackground(ripple(null, dp(14))); r.setOnClickListener(l); }
        r.addView(badge(icon, accent, accentSoft, 40), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = vbox();
        texts.setPadding(dp(14), 0, dp(8), 0);
        label(texts, title, 16, text, true);
        if (detail != null) label(texts, detail, 13, muted, false);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) r.addView(end);
        parent.addView(r, wide(0));
        return r;
    }

    /** Welcome-screen style: icon, bold title, explanation. */
    void feature(LinearLayout parent, int icon, String title, String detail) {
        LinearLayout r = hbox();
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(10), 0, dp(10));
        r.addView(badge(icon, accent, accentSoft, 44), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout texts = vbox();
        texts.setPadding(dp(14), 0, 0, 0);
        label(texts, title, 16, text, true);
        label(texts, detail, 14, muted, false).setPadding(0, dp(2), 0, 0);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(r, wide(0));
    }

    /** 48 dp touch target with a drawn icon. */
    ImageView iconButton(int icon, int color, String description, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(icon, color));
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setContentDescription(description);
        v.setBackground(ripple(null, dp(24)));
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return v;
    }

    /** Icon above a short label, for bottom action bars. */
    LinearLayout action(LinearLayout parent, int icon, String s, int color, View.OnClickListener l) {
        LinearLayout a = new LinearLayout(c) {
            @Override public void setEnabled(boolean e) { super.setEnabled(e); setAlpha(e ? 1f : 0.35f); }
        };
        a.setOrientation(LinearLayout.VERTICAL);
        a.setGravity(Gravity.CENTER);
        a.setPadding(0, dp(8), 0, dp(8));
        a.setBackground(ripple(null, dp(16)));
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new Icon(icon, color));
        a.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = label(a, s, 12, color, true);
        t.setPadding(0, dp(4), 0, 0);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        a.setOnClickListener(l);
        parent.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return a;
    }

    // ---------------------------------------------------------------- icons

    static final int BACK = 1, CLOSE = 2, MORE = 3, PLUS = 4, CHECK = 5, LOCK = 6, KEY = 7, FOLDER = 8, MOVE = 9, TRASH = 10,
            SAVE = 11, EYE = 12, CLOUD = 13, SYNC = 14, HEART = 15, SHIELD = 16, INFO = 17, ALERT = 18, LIST = 19, CODE = 20,
            FINGER = 21, IMAGE = 22, NOISE = 23, CIRCLE = 24, GLOBE = 25, PHONE = 26, CHART = 27, SEARCH = 28, FILTER = 29;

    /** Line icons on a 24 x 24 grid, stroked in one color. */
    static final class Icon extends Drawable {
        final int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int kind, int color) {
            this.kind = kind;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            cv.save();
            cv.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s);
            cv.scale(s, s);
            Path a = new Path();
            switch (kind) {
                case BACK: a.moveTo(19, 12); a.lineTo(5, 12); a.moveTo(11, 6); a.lineTo(5, 12); a.lineTo(11, 18); break;
                case CLOSE: a.moveTo(6, 6); a.lineTo(18, 18); a.moveTo(18, 6); a.lineTo(6, 18); break;
                case MORE: dot(cv, 12, 5, 1.9f); dot(cv, 12, 12, 1.9f); dot(cv, 12, 19, 1.9f); break;
                case PLUS: a.moveTo(12, 5); a.lineTo(12, 19); a.moveTo(5, 12); a.lineTo(19, 12); break;
                case CHECK: a.moveTo(5, 12.5f); a.lineTo(10, 17.5f); a.lineTo(19, 7); break;
                case LOCK:
                    a.addRoundRect(new RectF(5, 11, 19, 21), 2.5f, 2.5f, Path.Direction.CW);
                    a.moveTo(8, 11); a.lineTo(8, 8); a.arcTo(new RectF(8, 4, 16, 12), 180, 180, false); a.lineTo(16, 11);
                    dot(cv, 12, 16, 1.4f);
                    break;
                case KEY:
                    a.addCircle(8, 15.5f, 4, Path.Direction.CW);
                    a.moveTo(10.9f, 12.6f); a.lineTo(20, 3.5f); a.moveTo(16.5f, 7); a.lineTo(19, 9.5f); a.moveTo(14, 9.5f); a.lineTo(16, 11.5f);
                    break;
                case FOLDER: case MOVE:
                    a.moveTo(3, 7); a.quadTo(3, 5, 5, 5); a.lineTo(9, 5); a.lineTo(11, 7); a.lineTo(19, 7); a.quadTo(21, 7, 21, 9);
                    a.lineTo(21, 17); a.quadTo(21, 19, 19, 19); a.lineTo(5, 19); a.quadTo(3, 19, 3, 17); a.close();
                    if (kind == MOVE) { a.moveTo(8.5f, 13); a.lineTo(15.5f, 13); a.moveTo(13, 10.5f); a.lineTo(15.5f, 13); a.lineTo(13, 15.5f); }
                    break;
                case TRASH:
                    a.moveTo(4, 7); a.lineTo(20, 7); a.moveTo(9, 7); a.lineTo(9, 4.5f); a.lineTo(15, 4.5f); a.lineTo(15, 7);
                    a.moveTo(6, 7); a.lineTo(7, 20); a.lineTo(17, 20); a.lineTo(18, 7); a.moveTo(10, 11); a.lineTo(10, 16); a.moveTo(14, 11); a.lineTo(14, 16);
                    break;
                case SAVE: a.moveTo(12, 4); a.lineTo(12, 15); a.moveTo(7, 10); a.lineTo(12, 15); a.lineTo(17, 10); a.moveTo(5, 20); a.lineTo(19, 20); break;
                case EYE:
                    a.moveTo(2, 12); a.quadTo(12, 1.5f, 22, 12); a.quadTo(12, 22.5f, 2, 12); a.close();
                    a.addCircle(12, 12, 3, Path.Direction.CW);
                    break;
                case CLOUD: {
                    Path u = new Path(), t = new Path();
                    u.addCircle(8, 14, 4.5f, Path.Direction.CW);
                    t.addCircle(13, 10.5f, 5.5f, Path.Direction.CW);
                    u.op(t, Path.Op.UNION);
                    t.reset(); t.addCircle(17.5f, 14.5f, 3.5f, Path.Direction.CW); u.op(t, Path.Op.UNION);
                    t.reset(); t.addRect(8, 13, 17.5f, 18.5f, Path.Direction.CW); u.op(t, Path.Op.UNION);
                    a = u;
                    break;
                }
                case SYNC:
                    a.addArc(new RectF(5, 5, 19, 19), 200, 130);
                    head(a, 18.06f, 8.5f, 0.5f, 0.866f);
                    a.addArc(new RectF(5, 5, 19, 19), 20, 130);
                    head(a, 5.94f, 15.5f, -0.5f, -0.866f);
                    break;
                case HEART:
                    a.moveTo(12, 20.5f); a.cubicTo(5, 15.5f, 2.5f, 11.5f, 4.5f, 7.5f); a.cubicTo(6.3f, 4.2f, 10.3f, 4.6f, 12, 7.8f);
                    a.cubicTo(13.7f, 4.6f, 17.7f, 4.2f, 19.5f, 7.5f); a.cubicTo(21.5f, 11.5f, 19, 15.5f, 12, 20.5f); a.close();
                    break;
                case SHIELD:
                    a.moveTo(12, 3); a.lineTo(19.5f, 6); a.lineTo(19.5f, 11.5f); a.cubicTo(19.5f, 16, 16.3f, 19.5f, 12, 21);
                    a.cubicTo(7.7f, 19.5f, 4.5f, 16, 4.5f, 11.5f); a.lineTo(4.5f, 6); a.close();
                    a.moveTo(8.8f, 12); a.lineTo(11, 14.2f); a.lineTo(15.3f, 9.8f);
                    break;
                case INFO: a.addCircle(12, 12, 9, Path.Direction.CW); a.moveTo(12, 11); a.lineTo(12, 16.5f); dot(cv, 12, 7.8f, 1.3f); break;
                case ALERT: a.moveTo(12, 3.5f); a.lineTo(21.5f, 20); a.lineTo(2.5f, 20); a.close(); a.moveTo(12, 10); a.lineTo(12, 14); dot(cv, 12, 17, 1.3f); break;
                case LIST:
                    for (int y = 7; y <= 17; y += 5) { a.moveTo(9, y); a.lineTo(20, y); dot(cv, 4.5f, y, 1.3f); }
                    break;
                case SEARCH: a.addCircle(10.5f, 10.5f, 6, Path.Direction.CW); a.moveTo(15, 15); a.lineTo(20, 20); break;
                case FILTER: a.moveTo(4, 7); a.lineTo(20, 7); a.moveTo(7, 12); a.lineTo(17, 12); a.moveTo(10, 17); a.lineTo(14, 17); break;
                case CODE: a.moveTo(9, 7); a.lineTo(4, 12); a.lineTo(9, 17); a.moveTo(15, 7); a.lineTo(20, 12); a.lineTo(15, 17); break;
                case FINGER:
                    a.addArc(new RectF(4, 3.5f, 20, 19.5f), 200, 140);
                    a.moveTo(7, 15); a.lineTo(7, 12); a.arcTo(new RectF(7, 7, 17, 17), 180, 180, false); a.lineTo(17, 14);
                    a.moveTo(10, 20); a.lineTo(10, 12); a.arcTo(new RectF(10, 10, 14, 14), 180, 180, false); a.lineTo(14, 18);
                    break;
                case IMAGE:
                    a.addRoundRect(new RectF(3, 5, 21, 19), 2.5f, 2.5f, Path.Direction.CW);
                    a.moveTo(3.5f, 16.5f); a.lineTo(8.5f, 11.5f); a.lineTo(13, 16); a.lineTo(15.5f, 13.5f); a.lineTo(20.5f, 18.5f);
                    a.addCircle(16, 9.5f, 1.6f, Path.Direction.CW);
                    break;
                case NOISE: { // a square of static: what the cloud sees
                    a.addRoundRect(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 2.5f, 2.5f, Path.Direction.CW);
                    Paint f = new Paint(p);
                    f.setStyle(Paint.Style.FILL);
                    int bits = 0x5A3C9;
                    for (int i = 0; i < 16; i++) if ((bits >> i & 1) == 1) cv.drawRect(6 + i % 4 * 3.1f, 6 + i / 4 * 3.1f, 8.6f + i % 4 * 3.1f, 8.6f + i / 4 * 3.1f, f);
                    break;
                }
                case CIRCLE: a.addCircle(12, 12, 8, Path.Direction.CW); break;
                case GLOBE:
                    a.addCircle(12, 12, 9, Path.Direction.CW);
                    a.addOval(new RectF(8, 3, 16, 21), Path.Direction.CW);
                    a.moveTo(3, 12); a.lineTo(21, 12);
                    break;
                case PHONE:
                    a.addRoundRect(new RectF(6.5f, 2.5f, 17.5f, 21.5f), 2.5f, 2.5f, Path.Direction.CW);
                    a.moveTo(10.5f, 18.5f); a.lineTo(13.5f, 18.5f);
                    break;
                case CHART:
                    a.moveTo(4, 20); a.lineTo(20, 20);
                    a.moveTo(7, 17); a.lineTo(7, 12); a.moveTo(12, 17); a.lineTo(12, 6); a.moveTo(17, 17); a.lineTo(17, 9.5f);
                    break;
            }
            cv.drawPath(a, p);
            cv.restore();
        }

        private void dot(Canvas cv, float x, float y, float r) {
            Paint f = new Paint(p);
            f.setStyle(Paint.Style.FILL);
            cv.drawCircle(x, y, r, f);
        }

        /** Arrow head at (x, y) pointing along (dx, dy). */
        private static void head(Path a, float x, float y, float dx, float dy) {
            float len = 3.2f, cos = 0.8f, sin = 0.6f;
            a.moveTo(x - len * (dx * cos - dy * sin), y - len * (dx * sin + dy * cos));
            a.lineTo(x, y);
            a.lineTo(x - len * (dx * cos + dy * sin), y - len * (-dx * sin + dy * cos));
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }
    }
}
