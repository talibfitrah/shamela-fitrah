#!/usr/bin/env python3
"""تحديث مكتبة NAS بالمستجدات من موقع shamela.ws:
يقرأ قائمة «آخر الكتب المضافة» من الصفحة الرئيسة (وأي أرقام كتب تُمرَّر في سطر الأوامر)، ويجلب كل كتاب
غير موجود في index.json صفحةً صفحة من الموقع، ويبني له قاعدة SQLite بالصيغة نفسها التي ينتجها المستخرج
(page/title/FTS5/meta)، ثم يضم الكتب الجديدة في حزمة تحديث books-upd-YYYYMMDD-HHMMSS.pack، ويحدّث index.json
و master.db و manifest.json على NAS.

الاستعمال:
  update_from_web.py <workdir> [--ids 30198,30199] [--dry-run] [--no-upload] [--max N]
<workdir> يحوي (أو سيحوي): index.json، master-official.db (master.db الرسمي)، extra_books.json، packs/
إن لم توجد الملفات محليًا تُنزَّل من NAS (index.json، master.db.gz).
"""
import gzip, hashlib, io, json, os, re, shutil, sqlite3, subprocess, sys, time, urllib.parse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from shamela_common import Nas, NAS_DIR, NAS_BASE, SITE, normalize, strip_tags, sha256_file
import make_master

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36 ShamelaNasUpdater/1.0"

def fetch(url, attempts=4):
    for i in range(attempts):
        r = subprocess.run(["curl", "-sS", "-f", "-m", "60", "-A", UA, "-H", "X-Requested-With: XMLHttpRequest", url], capture_output=True)
        if r.returncode == 0 and r.stdout:
            return r.stdout.decode("utf-8", errors="replace")
        time.sleep(2 * (i + 1))
    raise RuntimeError("fetch failed: " + url)

def unescape(s):
    import html
    return html.unescape(s or "")

def recent_books():
    h = fetch(SITE + "/")
    i = h.find('id="cat_books"')
    sec = h[i:] if i >= 0 else h
    out = []
    for m in re.finditer(r'<div class="book_item[^"]*">\s*<a href="[^"]*/book/(\d+)"[^>]*>(.*?)</a>\s*\[<a href="[^"]*/author/(\d+)"[^>]*>(.*?)</a>\]\s*<p[^>]*>(.*?)</p>', sec, re.S):
        out.append({"id": int(m.group(1)), "name": unescape(strip_tags(m.group(2))).strip(), "author_id": int(m.group(3)),
                    "author_name": unescape(strip_tags(m.group(4))).strip(), "card": unescape(strip_tags(re.sub(r"<br\s*/?>", "\n", m.group(5)))).strip()})
    return out

def book_info(bid):
    h = fetch(f"{SITE}/book/{bid}")
    t = re.search(r"<title>(.*?)</title>", h)
    name = unescape(t.group(1)).replace("كتاب ", "", 1).split(" - المكتبة الشاملة")[0].strip() if t else f"كتاب {bid}"
    a = re.search(r'href="[^"]*/author/(\d+)"[^>]*>(.*?)</a>', h)
    card = re.search(r"الكتاب:(.*?)(</div>|<div class=\"betaka-index\")", h, re.S)
    cat = re.search(r'href="[^"]*/category/(\d+)"[^>]*>(.*?)</a>', h)
    pages = re.search(r"عدد الصفحات:\s*([٠-٩0-9]+)", h)
    first = min((int(x) for x in re.findall(rf'href="[^"]*/book/{bid}/(\d+)"', h)), default=1)
    return {"id": bid, "name": name, "author_id": int(a.group(1)) if a else 0, "author_name": unescape(strip_tags(a.group(2))).strip() if a else "",
            "card": ("الكتاب:" + unescape(strip_tags(re.sub(r"<br\s*/?>", "\n", card.group(1)))).strip()) if card else "",
            "category_id": int(cat.group(1)) if cat else 0, "category_name": unescape(strip_tags(cat.group(2))).strip() if cat else "",
            "pages": int(normalize(pages.group(1))) if pages else 0, "first": first, "date": time.strftime("%d%m") + "1448"}

def toc(bid):
    h = fetch(f"{SITE}/book/{bid}")
    i = h.find('class="betaka-index"')
    block = h[i:h.find("</div>", i)] if i >= 0 else ""
    out = []; synthetic = [1_000_000]
    def parse(ul, parent, depth):
        for m in re.finditer(r"<li>(.*?)</li>", ul, re.S):
            s = m.group(1)
            did = re.search(r'data-id="(\d+)"', s)
            link = re.search(rf'href="[^"]*/book/{bid}/(\d+)"[^>]*>(.*?)</a>', s)
            if not link:
                continue
            tid = int(did.group(1)) if did else synthetic[0]
            if not did:
                synthetic[0] += 1
            out.append((tid, int(link.group(1)), parent, unescape(strip_tags(link.group(2))).strip()))
            if did and depth < 8:
                try:
                    parse(fetch(f"{SITE}/ajax/titlechilds/{bid}/{did.group(1)}"), tid, depth + 1)
                except Exception:
                    pass
    parse(block, 0, 0)
    return out

def convert_nass(nass):
    h = nass.replace("\r", "").replace("\n", " ")
    foot = None
    hm = re.search(r"<hr\s*/?>\s*<p class=\"hamesh\">(.*?)</p>", h, re.S)
    if hm:
        foot = unescape(strip_tags(re.sub(r"<br\s*/?>", "\r", hm.group(1)))).strip() or None
        h = h[:hm.start()] + h[hm.end():]
    h = re.sub(r'<a [^>]*class="btn_tag[^"]*"[^>]*>.*?</a>', "", h, flags=re.S)
    h = re.sub(r'<span[^>]*class="anchor"[^>]*></span>', "", h)
    h = re.sub(r"</p[^>]*>", "\r", h); h = re.sub(r"<p[^>]*>", "", h)
    h = re.sub(r"<br\s*/?>", "\r", h)
    h = re.sub(r'<span class="(c\d|special)">', lambda m: f'<span data-type="web-{m.group(1)}">', h)
    h = re.sub(r"<(?!/?span)[^>]+>", "", h)
    body = "\r".join(x.strip() for x in unescape(h).split("\r")).strip("\r ")
    return body, foot

def import_book(bid, dest, log=print):
    info = book_info(bid)
    titles = toc(bid)
    if os.path.exists(dest):
        os.remove(dest)
    c = sqlite3.connect(dest)
    c.execute("PRAGMA journal_mode=OFF"); c.execute("PRAGMA synchronous=OFF"); c.execute("PRAGMA page_size=4096")
    c.execute("CREATE TABLE page(id INTEGER PRIMARY KEY, part TEXT, page INTEGER, number INTEGER, body TEXT, foot TEXT, services TEXT)")
    c.execute("CREATE TABLE title(id INTEGER PRIMARY KEY, page INTEGER, parent INTEGER, text TEXT)")
    c.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    c.execute("CREATE VIRTUAL TABLE page_fts USING fts5(body, foot, content='', tokenize='unicode61 remove_diacritics 2')")
    c.execute("CREATE VIRTUAL TABLE title_fts USING fts5(text, content='', tokenize='unicode61 remove_diacritics 2')")
    pid = info["first"]; last_num = -1; part = None; count = 0
    while count < 200000:
        j = json.loads(fetch(f"{SITE}/ajax/pageContent/{bid}/{pid}"))  # الفشل يُرمى: كتاب مبتور لا يُنشر (نهاية الكتاب تُعرف من nextId أدناه)
        num = j.get("pageNum") or 0
        if (0 < num < last_num) or part is None:
            hh = fetch(f"{SITE}/book/{bid}/{pid}")
            m = re.search(r'id="fld_part_top" value="([^"]*)"', hh)
            part = (m.group(1).strip() if m else "") or None
        last_num = num
        body, foot = convert_nass(j.get("nass", ""))
        c.execute("INSERT OR REPLACE INTO page(id, part, page, number, body, foot, services) VALUES(?,?,?,?,?,?,NULL)", (pid, part, num or None, None, body, foot))
        c.execute("INSERT INTO page_fts(rowid, body, foot) VALUES(?,?,?)", (pid, normalize(body), normalize(foot)))
        count += 1
        if count % 50 == 0:
            log(f"    {info['name'][:40]}: {count} صفحة"); c.commit()
        nxt = j.get("nextId")
        try:
            nxt = int(nxt)
        except Exception:
            break
        if nxt <= pid:
            break
        pid = nxt
    if count == 0:
        c.close(); os.remove(dest); raise RuntimeError(f"لا صفحات للكتاب {bid}")
    for tid, page, parent, text in titles:
        c.execute("INSERT OR REPLACE INTO title(id, page, parent, text) VALUES(?,?,?,?)", (tid, page, parent, text))
        c.execute("INSERT INTO title_fts(rowid, text) VALUES(?,?)", (tid, normalize(text)))
    c.execute("CREATE INDEX title_page ON title(page)"); c.execute("CREATE INDEX title_parent ON title(parent)"); c.execute("CREATE INDEX page_partpage ON page(part, page)")
    for k, v in {"book_id": str(bid), "format": "shamela-book", "format_version": "1", "pages": str(count), "titles": str(len(titles)), "source": "shamela.ws",
                 "normalizer": "arabic-v1", "book_name": info["name"], "author_name": info["author_name"], "author_id": str(info["author_id"]), "card": info["card"], "category_name": info["category_name"]}.items():
        c.execute("INSERT OR REPLACE INTO meta VALUES(?,?)", (k, v))
    c.execute("INSERT INTO page_fts(page_fts) VALUES('optimize')"); c.execute("INSERT INTO title_fts(title_fts) VALUES('optimize')")
    c.commit(); c.execute("VACUUM"); c.close()
    info["pages_count"] = count; info["titles_count"] = len(titles)
    return info

def gz_append(pack_path, db_path):
    """يضيف قاعدة مضغوطة gzip إلى نهاية الحزمة ويعيد (offset, length, sha256)"""
    buf = io.BytesIO()
    with open(db_path, "rb") as i, gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9) as o:
        shutil.copyfileobj(i, o)
    data = buf.getvalue()
    with open(pack_path, "ab") as f:
        off = f.tell(); f.write(data)
    return off, len(data), hashlib.sha256(data).hexdigest()

def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); return
    work = a[0]; os.makedirs(os.path.join(work, "packs"), exist_ok=True); os.makedirs(os.path.join(work, "raw"), exist_ok=True)
    ids = [int(x) for x in a[a.index("--ids") + 1].split(",")] if "--ids" in a else []
    dry = "--dry-run" in a; no_upload = "--no-upload" in a
    max_n = int(a[a.index("--max") + 1]) if "--max" in a else 50
    nas = Nas(); nas.login()
    # الحالة والملفات
    import publish
    st = publish.load_state()
    idx_path = os.path.join(work, "index.json")
    if not os.path.exists(idx_path):
        subprocess.run(["curl", "-sS", "-c", "/tmp/jar", "-o", "/dev/null", st["files"]["index.json"]["page"]])
        subprocess.run(["curl", "-sS", "-b", "/tmp/jar", "-o", idx_path, st["files"]["index.json"]["url"]], check=True)
    idx = json.load(open(idx_path, encoding="utf-8"))
    have = {b["id"] for b in idx["books"]}
    extra_path = os.path.join(work, "extra_books.json")
    extra = json.load(open(extra_path, encoding="utf-8")) if os.path.exists(extra_path) else []
    master_official = os.path.join(work, "master-official.db")
    if not os.path.exists(master_official):
        raise SystemExit("ضع master.db الرسمي للشاملة في " + master_official)
    # المرشحون
    cands = []
    for r in recent_books():
        if r["id"] not in have: cands.append(r["id"])
    for i in ids:
        if i not in have and i not in cands: cands.append(i)
    cands = cands[:max_n]
    print(f"كتب جديدة غير موجودة على NAS: {len(cands)} — {cands}")
    if not cands or dry:
        return
    stamp = time.strftime("%Y%m%d-%H%M%S")  # ختم واحد للحزمة وللإصدار: تشغيل ثانٍ في اليوم نفسه يجب أن يغيّر data.version
    pack_name = f"books-upd-{stamp}.pack"
    pack_path = os.path.join(work, "packs", pack_name)
    if os.path.exists(pack_path):
        os.remove(pack_path)
    new_books = []
    for bid in cands:
        dest = os.path.join(work, "raw", f"{bid}.db")
        try:
            print(f"  جلب الكتاب {bid}…", flush=True)
            info = import_book(bid, dest)
        except Exception as e:
            print(f"  تعذر {bid}: {e}", flush=True); continue
        off, ln, sha = gz_append(pack_path, dest)
        size = os.path.getsize(dest)
        idx["books"].append({"id": bid, "pack": pack_name, "offset": off, "length": ln, "size": size, "sha256": sha, "pages": info["pages_count"], "titles": info["titles_count"]})
        extra.append(info); new_books.append(bid)
        os.remove(dest)
        print(f"  ✓ {info['name']} — {info['pages_count']} صفحة ({ln/1e6:.1f} MB)", flush=True)
    if not new_books:
        print("لم يُضف شيء"); return
    idx["packs"].append({"file": pack_name, "size": os.path.getsize(pack_path), "sha256": sha256_file(pack_path)})
    idx["books_count"] = len(idx["books"]); idx["pages_count"] = sum(b["pages"] for b in idx["books"]); idx["generated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    json.dump(idx, open(idx_path, "w", encoding="utf-8"), ensure_ascii=False)
    json.dump(extra, open(extra_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    master_out = os.path.join(work, "master.db")
    make_master.build(master_official, idx_path, master_out, extra_path)
    if no_upload:
        print("تم البناء محليًا بلا رفع"); return
    version = st["data"].get("version", "").split("+")[0] + "+" + stamp
    sys.argv = ["publish.py", "data", work, master_out, "--version", version]
    publish.main()
    print("تم التحديث:", new_books)

if __name__ == "__main__":
    main()
