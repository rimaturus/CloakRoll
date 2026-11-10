"""Makes the smart-search model files (run by .github/workflows/clip.yml, published as release clip-v3).

OpenAI CLIP ViT-B/32 (MIT license) split in two ONNX files, weights quantized (4 or 8 bit, see check.txt):
  clip-image.onnx  pixels float[1,3,224,224] -> embedding float[1,512] (unit length)
  clip-text.onnx   ids    int64[1,n]         -> embedding float[1,512] (unit length)
  clip-merges.txt  the tokenizer's BPE merges (Clip.java rebuilds the vocabulary from them)
  clip-tokens-test.json  texts and their token ids, to test Clip.java's tokenizer
Then checks the int8 models against the original on a few photos, preprocessed like the app does.
"""
import io, json, os, sys
import numpy as np, torch, requests
from PIL import Image, ImageDraw
from transformers import CLIPModel, CLIPTokenizer
import onnx
from onnxruntime.quantization import quantize_dynamic
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer, DefaultWeightOnlyQuantConfig
import onnxruntime as ort

NAME = "openai/clip-vit-base-patch32"
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
model = CLIPModel.from_pretrained(NAME, attn_implementation="eager").eval()
tok = CLIPTokenizer.from_pretrained(NAME)


class Image_(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, x): e = s.m.get_image_features(pixel_values=x); return e / e.norm(dim=-1, keepdim=True)


class Text(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, ids): e = s.m.get_text_features(input_ids=ids); return e / e.norm(dim=-1, keepdim=True)


with torch.no_grad():
    torch.onnx.export(Image_(model), torch.randn(1, 3, 224, 224), "img32.onnx", input_names=["pixels"],
                      output_names=["embedding"], opset_version=17, dynamo=False)
    torch.onnx.export(Text(model), torch.tensor([tok("a photo of a dog")["input_ids"]]), "txt32.onnx", input_names=["ids"],
                      output_names=["embedding"], dynamic_axes={"ids": {1: "n"}}, opset_version=17, dynamo=False)

# merges.txt: "#version" line, then one merge per line
tok.save_vocabulary(".")
with open("merges.txt", encoding="utf-8") as f, open(os.path.join(out, "clip-merges.txt"), "w", encoding="utf-8") as g:
    g.write("".join(l for l in f if not l.startswith("#version")))
tests = ["a dog", "A photo of a DOG!", "family lunch", "frigorifero", "pranzo di famiglia", "don't", "it's 3:45pm",
         "naïve café", "emoji 🐶 here", "screenshot of a chat", "  many   spaces  ", "a" * 40]
with open(os.path.join(out, "clip-tokens-test.json"), "w") as f:
    json.dump([{"text": t, "ids": tok(t)["input_ids"]} for t in tests], f, ensure_ascii=False)

# ---- checks: the app's preprocessing (480 px preview, bilinear to 224 on the short side, centre crop)
MEAN, STD = np.array([0.48145466, 0.4578275, 0.40821073]), np.array([0.26862954, 0.26130258, 0.27577711])


def pixels(im):
    im = im.convert("RGB"); im.thumbnail((480, 480))
    w, h = im.size; s = 224 / min(w, h)
    im = im.resize((max(224, round(w * s)), max(224, round(h * s))), Image.BILINEAR)
    w, h = im.size; l, t = (w - 224) // 2, (h - 224) // 2
    a = (np.asarray(im.crop((l, t, l + 224, t + 224)), dtype=np.float32) / 255 - MEAN) / STD
    return a.transpose(2, 0, 1)[None].astype(np.float32)


images = {}
for n in ["000000039769", "000000000285", "000000000632", "000000000724", "000000000785", "000000000872", "000000002153"]:
    try: images[n] = Image.open(io.BytesIO(requests.get(f"http://images.cocodataset.org/val2017/{n}.jpg", timeout=30).content))
    except Exception as e: print("skipped", n, e)
shot = Image.new("RGB", (720, 1560), "white"); d = ImageDraw.Draw(shot)
for i in range(30): d.text((40, 60 + 48 * i), "Message %d: ok see you tomorrow at the office" % i, fill="black")
images["fake-chat-screenshot"] = shot

labels = ["a cat", "a dog", "a bear", "a man", "a woman", "a refrigerator", "a bed", "a stop sign", "a landscape",
          "a family lunch", "people playing baseball", "skiing", "a screenshot of a phone", "a meme", "a document"]
s32 = {k: ort.InferenceSession(v) for k, v in (("i", "img32.onnx"), ("t", "txt32.onnx"))}
report = []


def nbits(src, dst, bits):  # weights only, in blocks of 32: activations stay float
    cfg = DefaultWeightOnlyQuantConfig(block_size=32, is_symmetric=True, accuracy_level=4, bits=bits,
                                       op_types_to_quantize=("MatMul", "Gather"), quant_axes=(("MatMul", 0), ("Gather", 1)))
    q = MatMulNBitsQuantizer(onnx.load(src), algo_config=cfg)
    q.process()
    q.model.save_model_to_file(dst, use_external_data_format=False)


def dynamic(src, dst): quantize_dynamic(src, dst, op_types_to_quantize=["MatMul", "Gemm"])


def agreement(kind, path):  # least cosine between the fp32 and the quantized model's vectors
    s = ort.InferenceSession(path)
    if kind == "i": return min(float(s.run(None, {"pixels": x})[0][0] @ s32["i"].run(None, {"pixels": x})[0][0]) for x in [pixels(im) for im in images.values()])
    ids = [np.array([tok(l)["input_ids"]], dtype=np.int64) for l in labels]
    return min(float(s.run(None, {"ids": x})[0][0] @ s32["t"].run(None, {"ids": x})[0][0]) for x in ids)


# smallest first: the first that agrees with the original to 0.98 is published, else the one that agrees best
for kind, src, dst in (("i", "img32.onnx", "clip-image.onnx"), ("t", "txt32.onnx", "clip-text.onnx")):
    best = (-1.0, src, "fp32")
    for name, make in (("4-bit", lambda a, b: nbits(a, b, 4)), ("8-bit", lambda a, b: nbits(a, b, 8)), ("int8-dynamic", dynamic)):
        f = f"{kind}-{name}.onnx"
        try:
            make(src, f); c = agreement(kind, f)
            report.append(f"{dst} {name}: {os.path.getsize(f) >> 20} MB, cosine to fp32 {c:.4f}")
            if c > best[0]: best = (c, f, name)
            if c >= 0.98: break
        except Exception as e: report.append(f"{dst} {name}: failed {type(e).__name__}: {e}")
    os.replace(best[1], os.path.join(out, dst)); report.append(f"{dst}: published {best[2]}")
s8 = {k: ort.InferenceSession(os.path.join(out, v)) for k, v in (("i", "clip-image.onnx"), ("t", "clip-text.onnx"))}
for name, sess in (("fp32", s32), ("published", s8)):
    T = np.concatenate([sess["t"].run(None, {"ids": np.array([tok(l)["input_ids"]], dtype=np.int64)})[0] for l in labels])
    for n, im in images.items():
        e = sess["i"].run(None, {"pixels": pixels(im)})[0][0]
        sims = T @ e
        best = np.argsort(-sims)[:3]
        report.append(f"{name} {n}: " + ", ".join(f"{labels[i]} {sims[i]:.3f}" for i in best))
    if name == "fp32": T32 = T; I32 = [s32["i"].run(None, {"pixels": pixels(im)})[0][0] for im in images.values()]
I8 = [s8["i"].run(None, {"pixels": pixels(im)})[0][0] for im in images.values()]
report.append("image fp32 vs published cosine: min %.4f" % min(float(a @ b) for a, b in zip(I32, I8)))
report.append("text fp32 vs published cosine: min %.4f" % float(np.min(np.sum(T32 * T, axis=1))))
print("\n".join(report))
with open(os.path.join(out, "check.txt"), "w") as f: f.write("\n".join(report) + "\n")
