import math, random

# ============ 与 Kotlin 完全一致的布局公式 ============
W = H = 480.0
cx = cy = W / 2
size = 480.0
MARGIN_FRACTION, MIN_MARGIN, ROW_SPAN = 0.05, 10.0, 0.88
R = size / 2 - max(MIN_MARGIN, size * MARGIN_FRACTION)

keys = {}   # ch -> (x, y, w, h)


def row(items, y0f, ctrl_edges=False):
    y0 = cy + y0f * R
    dy = y0f * R
    chord = math.sqrt(max(0.0, R * R - dy * dy))
    half = chord * ROW_SPAN
    n = len(items)
    step = 2 * half / n
    kr = min(0.092 * R, step * 0.52)     # 半宽/半高
    for i, it in enumerate(items):
        x = cx - half + step * (i + 0.5)
        if it in ('SHIFT', 'DEL'):
            continue
        keys[it.lower()] = (x, y0, kr * 2, kr * 2)
    return kr


row(list("QWERTYUIOP"), -0.44)
row(list("ASDFGHJKL"), -0.12)
row(['SHIFT'] + list("ZXCVBNM") + ['DEL'], 0.20)

key_w, key_h = keys['a'][2], keys['a'][3]
radius = min(key_w, key_h)
print("R =", round(R, 1), "key =", round(key_w, 1), "radius =", round(radius, 2))

# ============ 词表（含拼音→中文） ============
WORDS = [
    ("hello", "hello", 900), ("help", "help", 500), ("held", "held", 100),
    ("hero", "hero", 200), ("helm", "helm", 50), ("hell", "hell", 80),
    ("world", "world", 800), ("word", "word", 600), ("work", "work", 700),
    ("would", "would", 650), ("wound", "wound", 120),
    ("keyboard", "keyboard", 400), ("key", "key", 550), ("board", "board", 300),
    ("swipe", "swipe", 260), ("swing", "swing", 140), ("spring", "spring", 180),
    ("test", "test", 500), ("text", "text", 520), ("rest", "rest", 300),
    ("best", "best", 480), ("nest", "nest", 90), ("vest", "vest", 60),
    ("nihao", "你好", 700), ("nihaoma", "你好吗", 200), ("wo", "我", 900),
    ("women", "我们", 750), ("zhongguo", "中国", 600), ("shijie", "世界", 400),
    ("pool", "pool", 300), ("poll", "poll", 200), ("lull", "lull", 80), ("lul", "lul", 20),
    ("the", "the", 2000), ("and", "and", 1800), ("you", "you", 1600),
]

MAXF = max(w[2] for w in WORDS)
WORDS = [(k, v, f) for (k, v, f) in WORDS if all('a' <= c <= 'z' for c in k)]

# 端点索引
tree = {}
for k, v, f in WORDS:
    tree.setdefault((k[0], k[-1]), []).append((k, v, f))

# ============ FlorisBoard / SHARK² 算法复刻 ============
SP = 200
SHAPE_STD = 22.08
LOCATION_STD = 0.5109


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def length(pts):
    return sum(dist(pts[i - 1], pts[i]) for i in range(1, len(pts)))


def resample(pts, n):
    if len(pts) == 1:
        return [pts[0]] * n
    inter = length(pts) / n
    if inter <= 1e-4:
        return [pts[0]] * n
    out = [pts[0]]
    lx, ly = pts[0]
    cum = 0.0
    for i in range(len(pts) - 1):
        dx, dy = pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]
        norm = math.hypot(dx, dy)
        if norm <= 0:
            continue
        dx, dy = dx / norm, dy / norm
        npts = norm / inter
        cum += npts - int(npts)
        if cum > 1:
            npts = int(npts) + int(cum)
            cum %= 1
        for _ in range(int(npts)):
            lx += dx * inter
            ly += dy * inter
            out.append((lx, ly))
    return out


def normalize(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    longest = max(max(w, h), 1e-5)
    ox = (w / 2 + min(xs)) / longest
    oy = (h / 2 + min(ys)) / longest
    return [(x / longest - ox, y / longest - oy) for x, y in pts]


def ideal_gestures(key):
    plain, loop, prev, has_loop = [], [], '\0', False
    for c in key:
        if c not in keys:
            continue
        kx, ky, kw, kh = keys[c]
        if prev == c:
            qx, qy = kw / 4, kh / 4
            loop += [(kx + qx, ky + qy), (kx + qx, ky - qy), (kx - qx, ky - qy), (kx - qx, ky + qy)]
            has_loop = True
            plain.append((kx, ky))
        else:
            plain.append((kx, ky))
            loop.append((kx, ky))
        prev = c
    return [plain, loop] if has_loop else [plain]


def ideal_len(key):
    total, prev = 0.0, None
    for c in key:
        if c not in keys:
            continue
        p = (keys[c][0], keys[c][1])
        if prev:
            total += dist(prev, p)
        prev = p
    return total


def gaussian(v, mean, std):
    std = max(std, 1e-4)
    return 1.0 / (std * math.sqrt(2 * math.pi)) * math.exp(-0.5 * ((v - mean) / std) ** 2)


def n_closest(x, y, n):
    return sorted(keys.keys(), key=lambda c: dist((keys[c][0], keys[c][1]), (x, y)))[:n]


def suggest(pts, maxc=3):
    if len(pts) < 2:
        return []
    starts, ends = n_closest(pts[0][0], pts[0][1], 2), n_closest(pts[-1][0], pts[-1][1], 2)
    cands, seen = [], set()
    for a in starts:
        for b in ends:
            for w in tree.get((a, b), []):
                if w[1] not in seen:
                    seen.add(w[1])
                    cands.append(w)
    if not cands:
        return []
    ul = length(pts)
    cands = [w for w in cands if abs(ul - ideal_len(w[0])) < 14.0 * radius]
    if not cands:
        return []
    ur = resample(pts, SP)
    un = normalize(ur)
    best = []
    for k, v, f in cands:
        for ig in ideal_gestures(k):
            wr = resample(ig, SP)
            wn = normalize(wr)
            sd = sum(dist(wn[i], un[i]) for i in range(SP))
            ld = sum(abs(wr[i][0] - ur[i][0]) + abs(wr[i][1] - ur[i][1]) for i in range(SP)) / SP / 2
            fp = max(0.05, (1 + math.log(max(f, 1))) / (1 + math.log(MAXF)))
            conf = 1.0 / max(1e-12, gaussian(sd, 0, SHAPE_STD) * gaussian(ld, 0, LOCATION_STD * radius) * fp)
            best.append((conf, v))
    best.sort(key=lambda t: t[0])
    out = []
    for _, v in best:
        if v not in out:
            out.append(v)
        if len(out) >= maxc:
            break
    return out


# ============ 模拟真实手指滑动（带噪声） ============
def simulate(word, sigma=0.30, curve=0.18):
    """沿理想路径滑动，加横向抖动 + 弧形弯曲 + 起点偏移"""
    path = [(keys[c][0], keys[c][1]) for c in word if c in keys]
    pts = []
    for i in range(len(path) - 1):
        a, b = path[i], path[i + 1]
        for t in [j / 6 for j in range(6)]:
            x = a[0] + (b[0] - a[0]) * t
            y = a[1] + (b[1] - a[1]) * t
            # 垂直方向的弧形偏移（手指不是直线）
            dx, dy = b[0] - a[0], b[1] - a[1]
            nrm = math.hypot(dx, dy) or 1
            px, py = -dy / nrm, dx / nrm
            arc = curve * key_h * math.sin(math.pi * t) * random.choice([-1, 1])
            pts.append((x + px * arc, y + py * arc))
    pts.append(path[-1])
    # 高斯噪声 + 起点/终点偏移
    jitter = [(x + random.gauss(0, sigma * key_w), y + random.gauss(0, sigma * key_h)) for x, y in pts]
    return jitter


random.seed(7)
tests = ["hello", "world", "work", "keyboard", "swipe", "test", "text", "best",
         "nihao", "women", "zhongguo", "pool", "poll", "the", "you"]

hit = 0
for w in tests:
    res = suggest(simulate(w))
    ok = bool(res) and res[0] == dict((k, v) for k, v, _ in WORDS).get(w, w)
    hit += ok
    print(f"{w:10s} -> {res}   {'OK' if ok else 'MISS'}")

print(f"\n命中率 {hit}/{len(tests)}")
