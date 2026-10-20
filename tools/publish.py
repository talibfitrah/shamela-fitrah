#!/usr/bin/env python3
"""نشر بيانات المكتبة الشاملة والتطبيق إلى NAS (المجلد /downloads/shamela) وبناء manifest.json.

الاستعمال:
  publish.py init                      إنشاء المجلد ورابط manifest.json الدائم (يُطبع الرابط لوضعه في بناء التطبيق)
  publish.py data <outdir> <master.db> [--version 1448-1]
                                       رفع master.db.gz و index.json والحزم packs/*.pack وإنشاء روابطها
  publish.py app <apk-dir> <version_code> <version_name> [--notes "..."]
                                       رفع ملفات APK وتحديث قسم app في manifest
  publish.py file <local> [remote-name] رفع ملف عادي (README، المشروع المصدري…) وإنشاء رابط له
  publish.py manifest                  إعادة توليد manifest.json من الحالة المحفوظة ورفعه
الحالة تُحفظ في publish_state.json (محليًا وعلى NAS).
"""
import gzip, json, os, shutil, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from shamela_common import Nas, NAS_DIR, NAS_BASE, sha256_file

STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "publish_state.json")

def load_state():
    if os.path.exists(STATE):
        return json.load(open(STATE, encoding="utf-8"))
    return {"dir": NAS_DIR, "files": {}, "packs": {}, "app": {}, "data": {}}

def save_state(st):
    json.dump(st, open(STATE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

def upload_and_share(nas, st, local, name=None, force=False):
    name = name or os.path.basename(local)
    remote = f"{st['dir']}/{name}"
    existing = nas.list(st["dir"]).get(name)
    if not os.path.exists(local):
        prev = st["files"].get(name)
        if prev and existing == prev.get("size"):
            print(f"  {name} غير موجود محليًا لكنه على NAS بالحجم المسجَّل — يُعاد استعماله")
            return prev
        raise FileNotFoundError(local)
    size = os.path.getsize(local); sha = sha256_file(local)
    # الحجم نفسه لا يعني المحتوى نفسه: يُعاد الرفع إن اختلفت البصمة المسجَّلة (أو لم تُسجَّل)
    if existing != size or force or (st["files"].get(name) or {}).get("sha256") != sha:
        print(f"  رفع {name} ({size/1e6:.1f} MB)…", flush=True)
        nas.upload(local, st["dir"], name)
    else:
        print(f"  {name} موجود بالحجم نفسه — تخطٍّ", flush=True)
    link = nas.share(remote)
    st["files"][name] = {"size": size, "sha256": sha, "url": link["url"], "page": link["page"], "id": link["id"], "updated": time.strftime("%Y-%m-%d")}
    save_state(st)
    return st["files"][name]

def build_manifest(st):
    d = st["dir"]
    packs = {k: {"url": v["url"], "page": v["page"], "path": f"{d}/{k}", "size": v["size"], "sha256": v["sha256"]} for k, v in st["packs"].items()}
    m = {
        "format": "shamela-nas-manifest", "version": 1, "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "app": st.get("app", {}),
        "data": {
            "version": st["data"].get("version", ""), "source": st["data"].get("source", ""), "books_count": st["data"].get("books_count", 0), "pages_count": st["data"].get("pages_count", 0),
            "master": st["files"].get("master.db.gz", {}) | {"raw_size": st["data"].get("master_raw_size", 0), "path": f"{d}/master.db.gz"} if "master.db.gz" in st["files"] else None,
            "index": (st["files"].get("index.json") or {}) | {"path": f"{d}/index.json"} if "index.json" in st["files"] else None,
            "packs": packs,
            "services": (st["files"].get("services.db.gz") or {}) | {"raw_size": st["data"].get("services_raw_size", 0), "path": f"{d}/services.db.gz"} if "services.db.gz" in st["files"] else None,
        },
        "site": {"recent": "https://shamela.ws", "book": "https://shamela.ws/book/{id}"},
        "folder": f"{NAS_BASE}/sharing/ehniFEwJg",
    }
    for f in ("master", "index", "services"):
        if m["data"][f]:
            m["data"][f] = {k: v for k, v in m["data"][f].items() if k in ("url", "page", "path", "size", "sha256", "raw_size")}
    return m

def require_data(st):
    """يرفض نشر manifest بلا كتب: حالة فارغة (publish_state.json مفقود) تُفقد كل التطبيقات المثبَّتة books.pack"""
    if not st["packs"] or "master.db.gz" not in st["files"]:
        raise SystemExit("رفض: الحالة المحلية بلا packs أو master.db.gz — publish_state.json مفقود أو ناقص. انسخه من NAS إلى tools/ قبل النشر (نشر manifest فارغ يعطّل كل التطبيقات المثبَّتة).")

def push_manifest(nas, st, empty_ok=False):
    if not empty_ok:
        require_data(st)
    m = build_manifest(st)
    local = os.path.join(os.path.dirname(STATE), "manifest.json")
    json.dump(m, open(local, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    nas.upload(local, st["dir"], "manifest.json")
    link = nas.share(f"{st['dir']}/manifest.json")
    st["manifest_url"] = link["url"]; st["manifest_page"] = link["page"]
    save_state(st)
    nas.upload(STATE, st["dir"], "publish_state.json")
    print("manifest:", link["url"])
    return link

def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); return
    nas = Nas(); nas.login()
    st = load_state()
    cmd = a[0]
    if cmd in ("app", "services", "manifest"):
        require_data(st)  # قبل أي رفع: لا يُستبدل APK أو services.db.gz ثم يُرفض manifest
    if cmd == "data" and not os.path.exists(STATE) and "manifest.json" in nas.list(st["dir"]):
        raise SystemExit("رفض: publish_state.json مفقود وعلى NAS manifest منشور — النشر الآن يمحو app وservices من manifest. انسخ الحالة من NAS أولًا.")
    if cmd == "init":
        nas.mkdir(st["dir"])
        if "manifest_url" not in st:
            local = os.path.join(os.path.dirname(STATE), "manifest.json")
            if not os.path.exists(local):
                json.dump({"format": "shamela-nas-manifest", "version": 1, "app": {}, "data": {}}, open(local, "w"))
        # manifest فارغ مسموح فقط لمجلد NAS جديد لم يُنشر فيه manifest بعد
        push_manifest(nas, st, empty_ok="manifest.json" not in nas.list(st["dir"]))
    elif cmd == "data":
        outdir, master = a[1], a[2]
        version = a[a.index("--version") + 1] if "--version" in a else time.strftime("%Y%m%d")
        idx = json.load(open(os.path.join(outdir, "index.json"), encoding="utf-8"))
        # قبل أي رفع: حزمة محلية تالفة بالحجم نفسه كانت ستستبدل السليمة على NAS ثم يُرفض النشر
        remote = nas.list(st["dir"])
        for p in idx["packs"]:
            lp = os.path.join(outdir, "packs", p["file"])
            prev = st["files"].get(p["file"])
            if os.path.exists(lp):
                if sha256_file(lp) != p["sha256"]:
                    raise SystemExit(f"بصمة الحزمة {p['file']} لا تطابق index.json — لم يُرفع شيء")
            elif not prev or prev.get("sha256") != p["sha256"] or remote.get(p["file"]) != prev.get("size"):
                raise SystemExit(f"الحزمة {p['file']} غير موجودة محليًا ولا مطابقة على NAS — لم يُرفع شيء")
        gz = master + ".gz"
        with open(master, "rb") as i, gzip.open(gz, "wb", compresslevel=9) as o:
            shutil.copyfileobj(i, o)
        upload_and_share(nas, st, gz, "master.db.gz", force=True)
        st["data"]["master_raw_size"] = os.path.getsize(master)
        upload_and_share(nas, st, os.path.join(outdir, "index.json"), "index.json", force=True)
        for p in idx["packs"]:
            info = upload_and_share(nas, st, os.path.join(outdir, "packs", p["file"]), p["file"])
            if info["sha256"] != p["sha256"]:
                raise SystemExit(f"بصمة الحزمة {p['file']} لا تطابق index.json")
            st["packs"][p["file"]] = info
        st["data"].update({"version": version, "source": idx.get("source", ""), "books_count": idx.get("books_count", 0), "pages_count": idx.get("pages_count", 0)})
        save_state(st)
        push_manifest(nas, st)
    elif cmd == "app":
        apk_dir, code, name = a[1], int(a[2]), a[3]
        notes = a[a.index("--notes") + 1] if "--notes" in a else ""
        apks = {}
        for f in sorted(os.listdir(apk_dir)):
            if f.endswith(".apk"):
                apks[f] = upload_and_share(nas, st, os.path.join(apk_dir, f), f)
        uni = next((v for k, v in apks.items() if "universal" in k), None) or next(iter(apks.values()))
        st["app"] = {"version_code": code, "version_name": name, "apk": uni["url"], "apk_page": uni["page"], "notes": notes,
                     "apks": {k: {"url": v["url"], "page": v["page"], "size": v["size"], "sha256": v["sha256"]} for k, v in apks.items()}}
        save_state(st)
        push_manifest(nas, st)
    elif cmd == "services":
        db = a[1]; gz = db + ".gz"
        with open(db, "rb") as i, gzip.open(gz, "wb", compresslevel=9) as o:
            shutil.copyfileobj(i, o)
        upload_and_share(nas, st, gz, "services.db.gz", force=True)
        st["data"]["services_raw_size"] = os.path.getsize(db)
        save_state(st); push_manifest(nas, st)
    elif cmd == "file":
        info = upload_and_share(nas, st, a[1], a[2] if len(a) > 2 else None, force=True)
        print(info["url"]); print(info["page"])
    elif cmd == "manifest":
        push_manifest(nas, st)
    else:
        print(__doc__)

if __name__ == "__main__":
    main()
