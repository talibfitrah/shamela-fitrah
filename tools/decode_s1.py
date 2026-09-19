#!/usr/bin/env python3
"""فك ترميز S1.db (تراجم الرواة في خدمات الشاملة) — استبدال بايتي بسيط اكتُشف بمحاذاة أسماء الرواة
في روابط inr://man-N داخل كتب الحديث — وبناء rowat.db مقروءة:
  rawi(id, name, full, death, jarh_json, details_json)
  rawi_fts (FTS5 على الاسم المطبَّع)
الاستعمال: decode_s1.py <S1.db> <rowat.db>
"""
import json, os, sqlite3, sys, re

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from shamela_common import normalize

T = {int(k): v for k, v in json.load(open(os.path.join(HERE, "s1_table.json"), encoding="utf-8")).items()}

def decode(blob):
    if not blob:
        return ""
    return "".join(T.get(x, "") for x in blob)

AR = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")

def arabize(o):
    if isinstance(o, str):
        return o.translate(AR)
    if isinstance(o, list):
        return [arabize(x) for x in o]
    if isinstance(o, dict):
        return {k: arabize(v) for k, v in o.items()}
    return o

def parse(blob):
    s = decode(blob)
    if not s:
        return None
    try:
        return arabize(json.loads(s))
    except Exception:
        return {"_raw": s}

def main():
    src, dst = sys.argv[1], sys.argv[2]
    if os.path.exists(dst):
        os.remove(dst)
    s1 = sqlite3.connect(src)
    out = sqlite3.connect(dst)
    out.executescript("""
      CREATE TABLE rawi(id INTEGER PRIMARY KEY, name TEXT, full TEXT, death INTEGER, jarh TEXT, details TEXT);
      CREATE VIRTUAL TABLE rawi_fts USING fts5(name, content='');
      CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    n = bad = 0
    for i, s, l, d, a, b in s1.execute("select i,s,l,d,a,b from b"):
        name = decode(s).strip().translate(AR); full = decode(l).strip().translate(AR)
        ja = parse(a); jb = parse(b)
        if (ja and "_raw" in ja) or (jb and "_raw" in jb):
            bad += 1
        # تبسيط البنية: a = {"key": [[ناقد, [[نص, مصدر, رقم], ...]], ...]} → [{"critic":..,"items":[{"text","source"}]}]
        jarh = []
        if isinstance(ja, dict):
            for v in ja.values():
                if not isinstance(v, list):
                    continue
                for ent in v:
                    if isinstance(ent, list) and len(ent) >= 2 and isinstance(ent[1], list):
                        items = [{"text": it[0], "source": it[1]} for it in ent[1] if isinstance(it, list) and len(it) >= 2]
                        jarh.append({"critic": ent[0], "items": items})
        details = []
        if isinstance(jb, dict):
            for v in jb.values():
                if not isinstance(v, list):
                    continue
                for ent in v:
                    if isinstance(ent, list) and len(ent) >= 2:
                        details.append({"label": ent[0], "value": ent[1]})
        out.execute("insert into rawi values(?,?,?,?,?,?)", (i, name, full, d, json.dumps(jarh, ensure_ascii=False), json.dumps(details, ensure_ascii=False)))
        out.execute("insert into rawi_fts(rowid, name) values(?,?)", (i, normalize(name + " " + full)))
        n += 1
    out.execute("insert into meta values('count', ?)", (str(n),))
    out.execute("insert into meta values('source', 'shamela S1.db (خدمة تراجم الرواة)')")
    out.commit()
    print("rows", n, "unparsed", bad)

if __name__ == "__main__":
    main()
