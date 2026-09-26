"""PhotoVault slide deck (16:9 PDF). From the repo root: python docs/make_presentation.py -> docs/PhotoVault-presentation.pdf
Needs: pip install reportlab pillow, and the DejaVu fonts (Ubuntu: fonts-dejavu-core)."""
import os, random, tempfile
from PIL import Image
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.utils import simpleSplit, ImageReader
from reportlab.lib.colors import HexColor

W, H = 960, 540
D = "/usr/share/fonts/truetype/dejavu/"
pdfmetrics.registerFont(TTFont("R", D + "DejaVuSans.ttf"))
pdfmetrics.registerFont(TTFont("B", D + "DejaVuSans-Bold.ttf"))
pdfmetrics.registerFont(TTFont("L", D + "DejaVuSans-ExtraLight.ttf"))
pdfmetrics.registerFont(TTFont("M", D + "DejaVuSansMono.ttf"))

BG, PANEL, EDGE = HexColor("#0B1220"), HexColor("#131D33"), HexColor("#243150")
TEXT, MUTED = HexColor("#E8ECF3"), HexColor("#93A1B8")
TEAL, GREEN, AMBER, RED = HexColor("#2DD4BF"), HexColor("#4ADE80"), HexColor("#FBBF24"), HexColor("#F87171")
REPO = "github.com/rimaturus/PhotoVault"
TMP = tempfile.mkdtemp()

random.seed(3)
def noise_img(w, h, name, dim=1.0):
    im = Image.frombytes("RGB", (w, h), bytes(int(random.randrange(256) * dim) for _ in range(w * h * 3)))
    p = f"{TMP}/{name}.png"
    im.save(p)
    return p

STRIP = noise_img(240, 3, "strip")
BAND = noise_img(96, 54, "band", 0.55)

c = canvas.Canvas("docs/PhotoVault-presentation.pdf", pagesize=(W, H))
c.setTitle("PhotoVault: private photo backup on Amazon Photos")
c.setAuthor("PhotoVault contributors")
c.setSubject("Encrypted photos on Amazon Photos, stored as noise. Open source, no ads, no tracking, donation-funded.")
page = [0]


def text(x, y, s, font="R", size=16, color=TEXT, align="left"):
    c.setFont(font, size)
    c.setFillColor(color)
    {"left": c.drawString, "center": c.drawCentredString, "right": c.drawRightString}[align](x, y, s)


def para(x, y, s, width, font="R", size=15, color=TEXT, leading=None):
    """Wrapped text starting at baseline y; returns the y below the last line."""
    leading = leading or size * 1.38
    for line in simpleSplit(s, font, size, width):
        text(x, y, line, font, size, color)
        y -= leading
    return y


def box(x, y, w, h, fill=PANEL, stroke=EDGE, r=10):
    c.setFillColor(fill)
    if stroke:
        c.setStrokeColor(stroke)
    c.setLineWidth(1)
    c.roundRect(x, y, w, h, r, fill=1, stroke=1 if stroke else 0)


def slide(kicker=None, title=None):
    if page[0]:
        c.showPage()
    page[0] += 1
    c.setFillColor(BG)
    c.rect(0, 0, W, H, fill=1, stroke=0)
    c.drawImage(STRIP, 0, H - 5, W, 5)  # thin noise band: the motif of the app
    if page[0] > 1:
        text(48, 22, "PhotoVault", "B", 9, MUTED)
        text(W - 48, 22, str(page[0]), "R", 9, MUTED, "right")
    if kicker:
        text(48, H - 58, kicker.upper(), "B", 11, TEAL)
    if title:
        text(48, H - 96, title, "B", 30, TEXT)


def bullets(x, y, items, width, size=15, gap=10, mark="•", mark_color=TEAL):
    for it in items:
        head, _, rest = it.partition("|")
        text(x, y, mark, "B", size, mark_color)
        yy = y
        if rest:
            text(x + 20, yy, head, "B", size, TEXT)
            yy = para(x + 20, yy - size * 1.35, rest, width - 20, "R", size - 2, MUTED)
        else:
            yy = para(x + 20, yy, head, width - 20, "R", size, TEXT)
        y = yy - gap
    return y


def card(x, y, w, h, title, body, color=TEAL, size=13):
    box(x, y, w, h)
    c.setFillColor(color)
    c.rect(x, y + h - 4, w, 4, fill=1, stroke=0)
    text(x + 18, y + h - 34, title, "B", 16, TEXT)
    para(x + 18, y + h - 58, body, w - 36, "R", size, MUTED)


def arrow(x1, y1, x2, y2, color=MUTED):
    c.setStrokeColor(color)
    c.setFillColor(color)
    c.setLineWidth(2)
    c.line(x1, y1, x2 - 8, y2)
    p = c.beginPath()
    p.moveTo(x2, y2); p.lineTo(x2 - 10, y2 + 5); p.lineTo(x2 - 10, y2 - 5); p.close()
    c.drawPath(p, fill=1, stroke=0)


# 1 ---------------------------------------------------------------- title
slide()
c.drawImage(BAND, 0, 0, W, H)
c.setFillColor(HexColor("#0B1220"))
c.setFillAlpha(0.86)
c.rect(0, 0, W, H, fill=1, stroke=0)
c.setFillAlpha(1)
c.drawImage("res/mipmap-xxxhdpi/ic_launcher.png", 64, H - 200, 104, 104, mask="auto")
text(64, 250, "PhotoVault", "B", 64, TEXT)
para(64, 196, "Your photos on Amazon Photos. Amazon only sees noise.", 860, "L", 27, TEXT)
text(64, 120, "Free  ·  Open source  ·  No ads  ·  No tracking  ·  Donation-funded", "B", 15, TEAL)
text(64, 64, REPO + "   ·   v1.3 beta   ·   Android 13+", "R", 12, MUTED)
c.linkURL("https://" + REPO, (64, 58, 300, 78), relative=0)

# 2 ---------------------------------------------------------------- problem
slide("The problem", "Cloud photos are read, not just stored")
cw, ch, gy = 272, 200, 196
card(48, gy, cw, ch, "Scanned", "Faces, places, objects and text in your pictures are analysed and indexed as soon as they are uploaded.", RED, 15)
card(48 + cw + 20, gy, cw, ch, "Reused", "That analysis can feed search, advertising profiles and the training of AI models under terms you accepted once.", AMBER, 15)
card(48 + 2 * (cw + 20), gy, cw, ch, "Exposed", "A breach, a leaked password or a hijacked account gives someone else your whole photo history, with GPS and dates.", RED, 15)
para(48, 150, "And yet Amazon Prime already includes unlimited full-resolution photo storage. The space is great; handing over the content is the problem.", 860, "R", 16, MUTED)

# 3 ---------------------------------------------------------------- idea
slide("The idea", "Keep the storage. Remove the content.")
c.drawImage("docs/comparison.png", 150, 110, 660, 660 * 700 / 1508)
para(48, 78, "Each photo is encrypted on the phone and uploaded as a valid PNG made of random pixels. Only PhotoVault, with your password, turns it back into your photo.", 864, "R", 14, MUTED)

# 4 ---------------------------------------------------------------- how it works
slide("How it works", "Encrypted on the phone, before it leaves")
steps = [("1", "Pick", "Android photo picker: the app sees only what you choose"),
         ("2", "Encrypt", "AES-256-GCM with a key derived from your password"),
         ("3", "Pack", "Ciphertext becomes the pixels of a PNG with a random name"),
         ("4", "Upload", "Only that PNG goes to your Amazon Photos")]
bw, bh, y0 = 196, 170, 200
for i, (n, t, s) in enumerate(steps):
    x = 48 + i * (bw + 26)
    box(x, y0, bw, bh)
    text(x + 18, y0 + bh - 44, n, "B", 28, TEAL)
    text(x + 52, y0 + bh - 42, t, "B", 20, TEXT)
    para(x + 18, y0 + bh - 80, s, bw - 36, "R", 14, MUTED)
    if i < 3:
        arrow(x + bw + 3, y0 + bh / 2, x + bw + 24, y0 + bh / 2, TEAL)
box(48, 76, 864, 96, HexColor("#0F2A2A"), HexColor("#1F4D4A"))
text(68, 140, "Viewing is the same path backwards", "B", 16, TEXT)
para(68, 114, "Download the PNG, verify that not a single bit changed (the GCM tag), decrypt in memory. A small encrypted preview on the phone shows up instantly while the original downloads.", 824, "R", 14, MUTED)

# 5 ---------------------------------------------------------------- crypto
slide("Security design", "Standard, boring, well-tested cryptography")
rows = [("Key derivation", "PBKDF2-HMAC-SHA256, 600,000 iterations (OWASP), random 16-byte salt"),
        ("Encryption", "AES-256-GCM, fresh random nonce for every file"),
        ("Integrity", "128-bit GCM tag over data and header: tampering or a wrong key is rejected"),
        ("Metadata", "File name, date and EXIF are inside the encrypted part"),
        ("Implementation", "Android's built-in crypto only: no custom cipher, no third-party library"),
        ("Two implementations", "Java (app) and Python (PC recovery tool), cross-tested byte for byte")]
y = 392
for k, v in rows:
    box(48, y - 14, 864, 46, PANEL, None, 8)
    text(68, y + 3, k, "B", 14, TEAL)
    text(260, y + 3, v, "R", 14, TEXT)
    y -= 54
para(48, 52, "The key exists only on your phone. Nobody can reset your password: not Amazon, not the developer.", 864, "B", 14, AMBER)

# 6 ---------------------------------------------------------------- what Amazon sees
slide("Transparency", "What Amazon can and cannot see")
box(48, 150, 420, 250)
text(70, 364, "Amazon can see", "B", 19, AMBER)
bullets(70, 326, ["That you upload PNG files of random noise",
                  "How many, how big, and when",
                  "That they are probably encrypted",
                  "Your Amazon account, as with any upload"], 380, 16, 14, "•", AMBER)
box(492, 150, 420, 250)
text(514, 364, "Amazon cannot see", "B", 19, GREEN)
bullets(514, 326, ["Any content of your photos and videos",
                   "Faces, places, objects, text",
                   "File names, dates, GPS, camera, any EXIF",
                   "Your vault password or key"], 380, 16, 14, "✓", GREEN)
para(48, 110, "There is nothing to scan and nothing to train AI on: only uniform random bytes.", 864, "R", 16, MUTED)

# 7 ---------------------------------------------------------------- on the phone
slide("On the phone", "Hardened by default")
left = ["Key in memory only|Wiped on lock. Auto-lock 60 s after you leave the app.",
        "Fingerprint unlock|Key wrapped by a hardware-backed Android Keystore key.",
        "Everything local is encrypted|Item list, previews and cached downloads.",
        "Decrypted video|Exists on disk only while you watch it."]
right = ["No backups, no transfers|Android backup and device-to-device copy disabled.",
         "No screenshots|Blank in recent apps (can be allowed temporarily).",
         "Locked-down login page|Only Amazon's sign-in sites; other links open outside.",
         "5 permissions only|No storage, contacts, location, camera or microphone."]
bullets(48, 392, left, 420, 18, 22)
bullets(500, 392, right, 420, 18, 22)

# 8 ---------------------------------------------------------------- threat model
slide("Threat model", "Honest about what it protects")
box(48, 120, 420, 290)
text(70, 374, "Protects your photos against", "B", 19, GREEN)
bullets(70, 336, ["Amazon scanning or AI training",
                  "A breach of Amazon's storage",
                  "Someone getting into your Amazon account",
                  "Silent modification of stored files"], 380, 16, 14, "✓", GREEN)
box(492, 120, 420, 290)
text(514, 374, "Does not protect against", "B", 19, RED)
bullets(514, 336, ["A weak password (offline guessing)",
                   "Malware on an unlocked phone",
                   "Metadata: count, size, timing",
                   "Losing your password: no recovery, by design",
                   "Amazon deleting files: keep a second backup"], 380, 16, 14, "✗", RED)
text(48, 80, "No independent audit yet. Code review and reports are welcome.", "R", 15, MUTED)

# 9 ---------------------------------------------------------------- promise
slide("Our promise", "Your photos are not the product")
promises = [("No ads", "Not now, not later."),
            ("No tracking", "No analytics, no crash reporting, no SDKs. Zero third-party libraries."),
            ("No data selling", "No servers, no accounts of ours. We never receive anything, so there is nothing to sell or leak."),
            ("Open source", "GPL-3.0. Anyone can read, build and verify the whole app."),
            ("Donation-funded", "Free for everyone. Donations are voluntary and unlock nothing.")]
y = 392
for t, s in promises:
    c.setFillColor(TEAL)
    c.circle(62, y + 5, 5, fill=1, stroke=0)
    text(80, y, t, "B", 20, TEXT)
    para(290, y + 1, s, 620, "R", 15, MUTED)
    y -= 62

# 10 --------------------------------------------------------------- features
slide("Features", "Built for everyday use")
feats = [("Background uploads", "Add many photos at once and leave: a notification shows progress and has a Stop button."),
         ("Instant previews", "Tap a photo: its preview appears at once, the original follows. Swipe to the next."),
         ("Folders", "Organise photos in folders. Their names are encrypted too, and restored on a new phone."),
         ("New phone? Sync", "Sign in, same password: the vault is rebuilt from Amazon."),
         ("Self-test", "8 checks on your own account before you store anything."),
         ("PC recovery", "A 115-line Python script decrypts everything, no app needed.")]
for i, (t, s) in enumerate(feats):
    x = 48 + (i % 3) * 296
    y = 262 - (i // 3) * 170
    card(x, y, 272, 150, t, s, TEAL, 13)

# 11 --------------------------------------------------------------- numbers
slide("By the numbers", "Small enough to read, fast enough to forget")
nums = [("0", "third-party libraries"), ("0", "servers, accounts, trackers"), ("280", "lines of crypto code"),
        ("+0.3 %", "size overhead (0.5 MB photo)"), ("600k", "PBKDF2 iterations"), ("~30 ms", "to encrypt a photo*")]
for i, (n, s) in enumerate(nums):
    x = 48 + (i % 3) * 296
    y = 270 - (i // 3) * 150
    box(x, y, 272, 128)
    text(x + 22, y + 62, n, "B", 40, TEAL)
    text(x + 22, y + 28, s, "R", 14, MUTED)
text(48, 52, "* Measured on one desktop CPU core. The in-app self-test shows the real timings on your phone.", "R", 11, MUTED)

# 12 --------------------------------------------------------------- limits
slide("Know before you use it", "Limits and risks, in plain words")
bullets(48, 392, [
    "Unofficial API|Amazon has no public Photos API. PhotoVault uses the website's own requests; if Amazon changes them, uploads pause until an update. Stored files stay decryptable.",
    "Videos and Amazon's terms|Prime is unlimited for photos, 5 GB for videos. Encrypted videos stored as images go against the spirit of the offer.",
    "Password = only key|It can't be recovered. Changing it re-encrypts the whole vault in the background.",
    "Beta|Up to 100 MB per item. Not independently audited yet."], 864, 18, 22, "!", AMBER)

# 13 --------------------------------------------------------------- get it / support
slide("Get involved", "Free, open, and supported by people like you")
box(48, 150, 420, 250)
text(70, 366, "Get it", "B", 20, TEXT)
bullets(70, 326, ["APK and source: " + REPO,
                  "Android 13 or newer",
                  "Recover anytime with photovault.py",
                  "Report bugs, review the code, share it"], 380, 15, 14)
c.linkURL("https://" + REPO, (70, 290, 450, 342), relative=0)
box(492, 150, 420, 250, HexColor("#1B1630"), HexColor("#3B2F63"))
text(514, 366, "♥  Support PhotoVault", "B", 20, TEXT)
para(514, 330, "No ads, no tracking, no paid tier: ever. If PhotoVault is useful to you, a voluntary donation funds development and, one day, an independent security audit.", 376, "R", 14, MUTED)
text(514, 206, "buymeacoffee.com/rimaturus", "B", 15, TEAL)
c.linkURL("https://buymeacoffee.com/rimaturus", (514, 200, 900, 224), relative=0)
text(514, 184, "Also in the app: Menu > Support PhotoVault", "R", 13, TEXT)
text(514, 162, "Donations unlock nothing: everything stays free for all.", "R", 12, MUTED)
para(48, 96, "Not affiliated with or endorsed by Amazon. Amazon, Amazon Photos and Prime are trademarks of Amazon.com, Inc. or its affiliates.", 864, "R", 11, MUTED)

c.save()
print("pages:", page[0])
