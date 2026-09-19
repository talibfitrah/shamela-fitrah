#!/usr/bin/env python3
"""يبني master.db الخاص بالتطبيق من master.db الرسمي للشاملة + index.json الناتج من المستخرج.
يضيف الجداول: book_file (مواضع الكتب في الحزم)، book_norm (أسماء مطبَّعة + مفتاح تاريخ الإضافة)، author_norm.
الاستعمال: make_master.py <official master.db> <index.json> <out master.db> [--extra extra_books.json]
"""
import json, os, shutil, sqlite3, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from shamela_common import normalize

def added_key(meta):
    try:
        d = json.loads(meta or "{}").get("date", "")
        if len(d) == 8:
            return int(d[4:8] + d[2:4] + d[0:2])
    except Exception:
        pass
    return 0

def build(src, index_path, out, extra=None):
    if os.path.exists(out):
        os.remove(out)
    shutil.copyfile(src, out)
    c = sqlite3.connect(out)
    c.execute("DROP TABLE IF EXISTS book_file")
    c.execute("CREATE TABLE book_file(book_id INTEGER PRIMARY KEY, pack TEXT, offset INTEGER, length INTEGER, size INTEGER, sha256 TEXT, pages INTEGER, titles INTEGER)")
    idx = json.load(open(index_path, encoding="utf-8"))
    rows = [(b["id"], b["pack"], b["offset"], b["length"], b["size"], b["sha256"], b["pages"], b["titles"]) for b in idx["books"]]
    c.executemany("INSERT OR REPLACE INTO book_file VALUES(?,?,?,?,?,?,?,?)", rows)
    # كتب مضافة من الموقع (تحديثات) — تُدمج في جداول book/author إن لم تكن موجودة
    if extra:
        for e in json.load(open(extra, encoding="utf-8")):
            if c.execute("select 1 from book where book_id=?", (e["id"],)).fetchone() is None:
                aid = e.get("author_id") or 0
                if aid and c.execute("select 1 from author where author_id=?", (aid,)).fetchone() is None:
                    c.execute("INSERT INTO author(author_id, author_name, death_number, death_text, alpha) VALUES(?,?,?,?,NULL)", (aid, e.get("author_name", ""), 99999, None))
                c.execute("INSERT INTO book(book_id, book_name, book_category, book_type, book_date, authors, main_author, printed, group_id, hidden, major_online, minor_online, major_ondisk, minor_ondisk, pdf_links, pdf_ondisk, pdf_online, cover_ondisk, cover_online, meta_data, parent, alpha, group_order, book_up) VALUES(?,?,?,?,?,?,?,?,NULL,0,1,0,1,0,NULL,0,NULL,NULL,NULL,?,NULL,NULL,NULL,NULL)",
                          (e["id"], e["name"], e.get("category_id", 0), 1, 99999, str(aid), aid, 1, json.dumps({"date": e.get("date", ""), "card": e.get("card", "")}, ensure_ascii=False)))
                if aid:
                    c.execute("INSERT INTO author_book(author_id, book_id) VALUES(?,?)", (aid, e["id"]))
    c.execute("DROP TABLE IF EXISTS book_norm")
    c.execute("CREATE TABLE book_norm(book_id INTEGER PRIMARY KEY, name_norm TEXT, author_norm TEXT, added_key INTEGER)")
    c.execute("DROP TABLE IF EXISTS author_norm")
    c.execute("CREATE TABLE author_norm(author_id INTEGER PRIMARY KEY, name_norm TEXT)")
    authors = {r[0]: r[1] for r in c.execute("select author_id, author_name from author")}
    c.executemany("INSERT INTO author_norm VALUES(?,?)", [(a, normalize(n)) for a, n in authors.items()])
    bn = []
    for bid, name, auth, meta in c.execute("select book_id, book_name, authors, meta_data from book"):
        names = " ".join(authors.get(int(x), "") for x in str(auth or "").replace(",", " ").split() if x.strip().isdigit())
        bn.append((bid, normalize(name), normalize(names), added_key(meta)))
    c.executemany("INSERT INTO book_norm VALUES(?,?,?,?)", bn)
    c.execute("CREATE INDEX IF NOT EXISTS book_cat ON book(book_category)")
    c.execute("CREATE INDEX IF NOT EXISTS book_group ON book(group_id)")
    c.execute("CREATE INDEX IF NOT EXISTS ab_author ON author_book(author_id)")
    c.execute("CREATE INDEX IF NOT EXISTS ab_book ON author_book(book_id)")
    c.execute("CREATE INDEX IF NOT EXISTS bn_added ON book_norm(added_key)")
    c.execute("CREATE TABLE IF NOT EXISTS app_meta(key TEXT PRIMARY KEY, value TEXT)")
    c.execute("INSERT OR REPLACE INTO app_meta VALUES('format','shamela-nas-master')")
    c.execute("INSERT OR REPLACE INTO app_meta VALUES('books', ?)", (str(len(rows)),))
    c.execute("INSERT OR REPLACE INTO app_meta VALUES('pages', ?)", (str(idx.get("pages_count", 0)),))
    c.commit()
    c.execute("VACUUM")
    c.close()
    n = sqlite3.connect(out).execute("select count(*) from book where hidden=0").fetchone()[0]
    print(f"master.db: {n} books, {len(rows)} files, {os.path.getsize(out)/1e6:.1f} MB")

if __name__ == "__main__":
    a = sys.argv[1:]
    extra = None
    if "--extra" in a:
        i = a.index("--extra"); extra = a[i + 1]; del a[i:i + 2]
    build(a[0], a[1], a[2], extra)
