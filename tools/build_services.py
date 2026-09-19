#!/usr/bin/env python3
"""بناء services.db — قاعدة خدمات الشاملة (كما في نسخة ويندوز) في ملف واحد:
  rawi / rawi_fts        تراجم الرواة (من S1.db بعد فك الترميز — rowat.db)
  hadith_link            تخريج الحديث: مفتاح الحديث → مواضعه في الكتب العشرة (hadeeth.db)
  esnad / rawi_page      أسانيد أحاديث الكتب العشرة → مرويات كل راوٍ (store/esnad)
  aya / sura             آيات القرآن (store/aya) مع رقم السورة والآية
  tafsir_link            تفسير الآية: الآية → مواضعها في ١٤ تفسيرًا (tafseer.db)
  roots                  البحث بالجذر/الاشتقاق: الكلمة → جذرها (S2.db)
الاستعمال: build_services.py <raw/service dir> <rowat.db> <aya.jsonl> <esnad.jsonl> <services.db>
"""
import json, os, sqlite3, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from shamela_common import normalize

SURAS = [("الفاتحة",7),("البقرة",286),("آل عمران",200),("النساء",176),("المائدة",120),("الأنعام",165),("الأعراف",206),("الأنفال",75),("التوبة",129),("يونس",109),("هود",123),("يوسف",111),("الرعد",43),("إبراهيم",52),("الحجر",99),("النحل",128),("الإسراء",111),("الكهف",110),("مريم",98),("طه",135),("الأنبياء",112),("الحج",78),("المؤمنون",118),("النور",64),("الفرقان",77),("الشعراء",227),("النمل",93),("القصص",88),("العنكبوت",69),("الروم",60),("لقمان",34),("السجدة",30),("الأحزاب",73),("سبأ",54),("فاطر",45),("يس",83),("الصافات",182),("ص",88),("الزمر",75),("غافر",85),("فصلت",54),("الشورى",53),("الزخرف",89),("الدخان",59),("الجاثية",37),("الأحقاف",35),("محمد",38),("الفتح",29),("الحجرات",18),("ق",45),("الذاريات",60),("الطور",49),("النجم",62),("القمر",55),("الرحمن",78),("الواقعة",96),("الحديد",29),("المجادلة",22),("الحشر",24),("الممتحنة",13),("الصف",14),("الجمعة",11),("المنافقون",11),("التغابن",18),("الطلاق",12),("التحريم",12),("الملك",30),("القلم",52),("الحاقة",52),("المعارج",44),("نوح",28),("الجن",28),("المزمل",20),("المدثر",56),("القيامة",40),("الإنسان",31),("المرسلات",50),("النبأ",40),("النازعات",46),("عبس",42),("التكوير",29),("الانفطار",19),("المطففين",36),("الانشقاق",25),("البروج",22),("الطارق",17),("الأعلى",19),("الغاشية",26),("الفجر",30),("البلد",20),("الشمس",15),("الليل",21),("الضحى",11),("الشرح",8),("التين",8),("العلق",19),("القدر",5),("البينة",8),("الزلزلة",8),("العاديات",11),("القارعة",11),("التكاثر",8),("العصر",3),("الهمزة",9),("الفيل",5),("قريش",4),("الماعون",7),("الكوثر",3),("الكافرون",6),("النصر",3),("المسد",5),("الإخلاص",4),("الفلق",5),("الناس",6)]
assert sum(n for _, n in SURAS) == 6236

def main():
    svc, rowat, ayaf, esnadf, dst = sys.argv[1:6]
    if os.path.exists(dst):
        os.remove(dst)
    db = sqlite3.connect(dst)
    db.executescript("""
      PRAGMA page_size=4096;
      CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
      CREATE TABLE rawi(id INTEGER PRIMARY KEY, name TEXT, full TEXT, death INTEGER, jarh TEXT, details TEXT);
      CREATE VIRTUAL TABLE rawi_fts USING fts5(name, content='');
      CREATE TABLE hadith_link(key_id INTEGER, book_id INTEGER, page_id INTEGER);
      CREATE TABLE hadith_book(book_id INTEGER PRIMARY KEY);
      CREATE TABLE esnad(book_id INTEGER, page_id INTEGER, hadith INTEGER, chain TEXT);
      CREATE TABLE rawi_page(rawi_id INTEGER, book_id INTEGER, page_id INTEGER, hadith INTEGER);
      CREATE TABLE sura(id INTEGER PRIMARY KEY, name TEXT, ayat INTEGER, first_aya INTEGER);
      CREATE TABLE aya(id INTEGER PRIMARY KEY, sura INTEGER, aya INTEGER, text TEXT, uthmani TEXT);
      CREATE VIRTUAL TABLE aya_fts USING fts5(text, content='');
      CREATE TABLE tafsir_link(aya_id INTEGER, book_id INTEGER, page_id INTEGER);
      CREATE TABLE tafsir_book(book_id INTEGER PRIMARY KEY);
      CREATE TABLE roots(token TEXT, root TEXT);
    """)
    # الرواة
    r = sqlite3.connect(rowat)
    db.executemany("insert into rawi values(?,?,?,?,?,?)", r.execute("select id,name,full,death,jarh,details from rawi"))
    db.executemany("insert into rawi_fts(rowid,name) values(?,?)", ((i, normalize(n + " " + f)) for i, n, f in r.execute("select id,name,full from rawi")))
    # تخريج الحديث
    h = sqlite3.connect(os.path.join(svc, "hadeeth.db"))
    db.executemany("insert into hadith_link values(?,?,?)", h.execute("select key_id,book_id,page_id from service"))
    db.executemany("insert into hadith_book values(?)", h.execute("select book from inservice"))
    # الأسانيد
    n = 0
    with open(esnadf, encoding="utf-8") as f:
        for line in f:
            o = json.loads(line)
            bid, pid = o["id"].split("-")
            chain = o.get("esnad", "").strip()
            hk = int(o.get("hadeeth") or 0)
            db.execute("insert into esnad values(?,?,?,?)", (int(bid), int(pid), hk, chain))
            for tok in set(chain.split()):
                if tok.isdigit():
                    db.execute("insert into rawi_page values(?,?,?,?)", (int(tok), int(bid), int(pid), hk)); n += 1
    print("rawi_page rows", n)
    # القرآن
    first = 1
    for i, (name, cnt) in enumerate(SURAS, 1):
        db.execute("insert into sura values(?,?,?,?)", (i, name, cnt, first)); first += cnt
    bounds = [(i, s, s + c - 1) for i, (s, c) in enumerate(((fa, c) for (_, c), fa in zip(SURAS, [row[3] for row in db.execute("select * from sura order by id")])), 1)]
    def loc(aid):
        for i, a, b in bounds:
            if a <= aid <= b:
                return i, aid - a + 1
        raise ValueError(aid)
    with open(ayaf, encoding="utf-8") as f:
        for line in f:
            o = json.loads(line); aid = int(o["id"]); s, a = loc(aid)
            db.execute("insert into aya values(?,?,?,?,?)", (aid, s, a, o.get("body", ""), o.get("majma", "")))
            db.execute("insert into aya_fts(rowid,text) values(?,?)", (aid, normalize(o.get("body", ""))))
    t = sqlite3.connect(os.path.join(svc, "tafseer.db"))
    db.executemany("insert into tafsir_link values(?,?,?)", t.execute("select key_id,book_id,page_id from service"))
    db.executemany("insert into tafsir_book values(?)", t.execute("select book from inservice"))
    # الجذور (cp1256)
    s2 = sqlite3.connect(os.path.join(svc, "S2.db"))
    n = 0
    batch = []
    for tok, root in s2.execute("select token, root from roots"):
        try:
            batch.append((tok.decode("cp1256"), root.decode("cp1256")))
        except Exception:
            continue
        if len(batch) >= 50000:
            db.executemany("insert into roots values(?,?)", batch); n += len(batch); batch = []
    if batch:
        db.executemany("insert into roots values(?,?)", batch); n += len(batch)
    print("roots", n)
    db.executescript("""
      CREATE INDEX ix_hl_key ON hadith_link(key_id);
      CREATE INDEX ix_hl_page ON hadith_link(book_id, page_id);
      CREATE INDEX ix_es_page ON esnad(book_id, page_id);
      CREATE INDEX ix_rp_rawi ON rawi_page(rawi_id);
      CREATE INDEX ix_tl_aya ON tafsir_link(aya_id);
      CREATE INDEX ix_tl_page ON tafsir_link(book_id, page_id);
      CREATE INDEX ix_roots_tok ON roots(token);
      CREATE INDEX ix_roots_root ON roots(root);
    """)
    db.execute("insert into meta values('version','1')")
    db.execute("insert into meta values('source','shamela-database-1448 services')")
    db.commit()
    db.execute("VACUUM")
    print("done", os.path.getsize(dst))

if __name__ == "__main__":
    main()
