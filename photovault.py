"""
PhotoVault PoC: any file -> AES-256-GCM -> valid PNG made of noise pixels, and back.

  py photovault.py enc <file>... -o out   # -> out/<random>.png  (upload these)
  py photovault.py dec <png>...  -o out   # -> out/<original name>
  py photovault.py test <file>            # in-memory roundtrip, timings, size overhead

Same file format as the PhotoVault Android app (PVT2), so this script is also your
independent way to decrypt anything the app uploaded: download the PNGs, run `dec`.
Files over 32 MB are stored as several PNGs ("parts"); `dec` puts them back together
when you give it all of them (e.g. the whole downloaded folder).

Password: prompted, or env var PV_PASSWORD.
Deps: pip install pillow cryptography
"""
import argparse, getpass, hashlib, io, json, math, mimetypes, os, struct, time
from pathlib import Path

from PIL import Image
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"PVT2"
CHUNK = 32 << 20  # same as the app: bigger files become several PNGs
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


def enc_file(f, out, pw):
    """One PNG, or for big files one PNG per 32 MB part: part 0 has name/date/size, every part has group + index."""
    meta, size = meta_of(f), f.stat().st_size
    if size <= CHUNK:
        dst = out / (os.urandom(8).hex() + ".png")
        dst.write_bytes(to_png(encode(f.read_bytes(), meta, pw)))
        return [dst]
    group, parts, done = os.urandom(8).hex(), math.ceil(size / CHUNK), []
    with open(f, "rb") as src:
        for i in range(parts):
            m = dict(meta, group=group, part=0, parts=parts, size=size) if i == 0 else {"group": group, "part": i}
            dst = out / (os.urandom(8).hex() + ".png")
            dst.write_bytes(to_png(encode(src.read(CHUNK), m, pw)))
            done.append(dst)
    return done


def dec_files(files, out, pw):
    """Decrypts every file it can; parts of big files are joined once all of them are there."""
    tmp, heads = out / ".parts", {}
    for f in files:
        t = time.perf_counter()
        try:
            meta, data = decode(Image.open(f), pw)
        except Exception as e:  # other password (e.g. before a password change), or not a PhotoVault file
            print(f"{f.name}: skipped ({type(e).__name__}: wrong password or not a PhotoVault file)")
            continue
        if "group" in meta:
            tmp.mkdir(exist_ok=True)
            (tmp / f"{meta['group']}.{meta['part']}").write_bytes(data)
            if meta["part"] == 0:
                heads[meta["group"]] = meta
            print(f"{f.name} -> part {meta['part'] + 1} of a big file  {time.perf_counter() - t:.3f}s")
        else:
            (out / Path(meta["name"]).name).write_bytes(data)
            print(f"{f.name} -> {meta['name']}  {time.perf_counter() - t:.3f}s")
    for group, meta in heads.items():
        parts = [tmp / f"{group}.{i}" for i in range(meta["parts"])]
        missing = [i + 1 for i, p in enumerate(parts) if not p.exists()]
        if missing:
            print(f"{meta['name']}: parts {missing} not found, give me all the PNGs of the folder")
            continue
        dst = out / Path(meta["name"]).name
        with open(dst, "wb") as o:
            for p in parts:
                o.write(p.read_bytes())
                p.unlink()
        ok = dst.stat().st_size == meta["size"]
        print(f"{meta['name']}: joined {len(parts)} parts, {'OK' if ok else 'SIZE MISMATCH'}")
    if tmp.exists() and not any(tmp.iterdir()):
        tmp.rmdir()


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

    files = [p for f in a.files for p in (sorted(f.iterdir()) if f.is_dir() else [f]) if p.is_file()]  # Windows: no globbing
    if a.cmd == "dec":
        return dec_files(files, a.out, pw)
    for f in files:
        t = time.perf_counter()
        if a.cmd == "enc":
            names = [d.name for d in enc_file(f, a.out, pw)]
            print(f"{f.name} -> {names[0]}" + (f" (+{len(names) - 1} parts)" if len(names) > 1 else "") + f"  {time.perf_counter() - t:.3f}s")
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
