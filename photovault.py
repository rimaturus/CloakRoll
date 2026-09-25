"""
PhotoVault PoC: any file -> AES-256-GCM -> valid PNG made of noise pixels, and back.

  py photovault.py enc <file>... -o out   # -> out/<random>.png  (upload these)
  py photovault.py dec <png>...  -o out   # -> out/<original name>
  py photovault.py test <file>            # in-memory roundtrip, timings, size overhead

Same file format as the PhotoVault Android app (PVT2), so this script is also your
independent way to decrypt anything the app uploaded: download the PNG, run `dec`.

Password: prompted, or env var PV_PASSWORD.
Deps: pip install pillow cryptography
"""
import argparse, getpass, hashlib, io, json, math, mimetypes, os, struct, time
from pathlib import Path

from PIL import Image
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"PVT2"
HDR = struct.Struct(">4s16s12sQ")  # magic, salt, nonce, ciphertext length
SALT_FILE = Path(__file__).with_name("vault.salt")
Image.MAX_IMAGE_PIXELS = None  # our own files, not decompression bombs
_keys = {}


def get_key(salt, password):
    if salt not in _keys:  # PBKDF2-HMAC-SHA256, 600k iterations: slow on purpose, derived once per salt
        _keys[salt] = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600_000, 32)
    return _keys[salt]


def vault_salt():
    if not SALT_FILE.exists():
        SALT_FILE.write_bytes(os.urandom(16))
    return SALT_FILE.read_bytes()


def encode(data, meta, password):
    """meta: dict with name, taken (ms), mime. Stored encrypted inside the PNG."""
    salt, nonce = vault_salt(), os.urandom(12)
    m = json.dumps(meta).encode()
    plain = struct.pack(">H", len(m)) + m + data
    hdr = HDR.pack(MAGIC, salt, nonce, len(plain) + 16)  # +16 = GCM tag
    blob = hdr + AESGCM(get_key(salt, password)).encrypt(nonce, plain, hdr)
    w = math.ceil(math.sqrt(len(blob) / 3))
    h = math.ceil(len(blob) / (3 * w))
    blob += os.urandom(w * h * 3 - len(blob))  # random padding: uniform noise to the last pixel
    return Image.frombytes("RGB", (w, h), blob)


def decode(img, password):
    if img.mode != "RGB":
        raise ValueError(f"not a vault image (mode {img.mode})")
    raw = img.tobytes()
    magic, salt, nonce, ct_len = HDR.unpack_from(raw)
    if magic != MAGIC:
        raise ValueError("not a vault image")
    hdr = raw[:HDR.size]
    # GCM tag check: fails if the password is wrong or if a single byte was altered server-side
    plain = AESGCM(get_key(salt, password)).decrypt(nonce, raw[HDR.size:HDR.size + ct_len], hdr)
    n = struct.unpack_from(">H", plain)[0]
    return json.loads(plain[2:2 + n]), plain[2 + n:]


def to_png(img):
    buf = io.BytesIO()
    img.save(buf, "PNG", compress_level=0)  # random data doesn't compress: store only, fastest
    return buf.getvalue()


def meta_of(f):
    return {"name": f.name, "taken": int(f.stat().st_mtime * 1000),
            "mime": mimetypes.guess_type(f.name)[0] or "application/octet-stream"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["enc", "dec", "test"])
    ap.add_argument("files", nargs="+", type=Path)
    ap.add_argument("-o", "--out", type=Path, default=Path("out"))
    a = ap.parse_args()
    pw = os.environ.get("PV_PASSWORD") or getpass.getpass("Password: ")

    t = time.perf_counter()
    get_key(vault_salt(), pw)
    print(f"key derivation: {time.perf_counter() - t:.2f}s (once per session)")
    a.out.mkdir(exist_ok=True)

    files = [p for f in a.files for p in (sorted(f.iterdir()) if f.is_dir() else [f])]  # Windows: no shell globbing
    for f in files:
        t = time.perf_counter()
        if a.cmd == "enc":
            dst = a.out / (os.urandom(8).hex() + ".png")
            dst.write_bytes(to_png(encode(f.read_bytes(), meta_of(f), pw)))
            print(f"{f.name} -> {dst.name}  {time.perf_counter() - t:.3f}s")
        elif a.cmd == "dec":
            meta, data = decode(Image.open(f), pw)
            (a.out / Path(meta["name"]).name).write_bytes(data)
            print(f"{f.name} -> {meta['name']}  {time.perf_counter() - t:.3f}s")
        else:
            data = f.read_bytes()
            png = to_png(encode(data, meta_of(f), pw))
            t_enc = time.perf_counter() - t
            t = time.perf_counter()
            meta, back = decode(Image.open(io.BytesIO(png)), pw)
            t_dec = time.perf_counter() - t
            ok = hashlib.sha256(back).digest() == hashlib.sha256(data).digest() and meta["name"] == f.name
            print(f"{f.name}: {'OK bit-exact' if ok else 'MISMATCH'} | "
                  f"{len(data) / 1e6:.2f} MB -> {len(png) / 1e6:.2f} MB (+{100 * (len(png) / len(data) - 1):.2f}%) | "
                  f"enc {t_enc:.3f}s  dec {t_dec:.3f}s")


if __name__ == "__main__":
    main()
