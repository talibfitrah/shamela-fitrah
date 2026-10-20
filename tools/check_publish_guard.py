#!/usr/bin/env python3
"""فحص محلي بلا شبكة لحارس `publish.py app`: يبني مستودع git مؤقتًا بـ remote محلي، وNAS وهميًا لا يتصل بشيء،
ويتأكد أن كل حالة رفض تقع قبل nas.login() وأن النشر السليم يرفع مصدر android/ عند HEAD ويسجّل الـ commit.
الاستعمال: python3 tools/check_publish_guard.py"""
import importlib.util, json, os, shutil, subprocess, sys, tempfile
TOOLS = os.path.dirname(os.path.abspath(__file__))
G = ["git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"]

def sh(cwd, *a): subprocess.run([*G, *a], cwd=cwd, check=True, capture_output=True)

class FakeNas:
    base, sid = "http://nas.invalid/webapi", "sid"
    def __init__(s, remote, listing=None): s.calls, s.tar, s.remote, s.listing = [], None, remote, listing or {}
    def login(s): s.calls.append("login")
    def _curl(s, args, timeout=600):  # قراءة publish_state.json من NAS فقط
        assert "api=SYNO.FileStation.Download" in args, args
        s.calls.append("read"); return json.dumps(s.remote).encode()
    def list(s, d): return s.listing
    def mkdir(s, d): pass
    def upload(s, local, d, name=None, **k):
        s.calls.append(name)
        if name.endswith(".tar.gz"): s.tar = subprocess.run(["tar", "-tzf", local], capture_output=True, text=True, check=True).stdout.split()
    def share(s, path): n = path.rsplit("/", 1)[1]; return {"id": n, "page": "p/" + n, "url": "u/" + n}

def load(root):
    spec = importlib.util.spec_from_file_location("publish", os.path.join(root, "tools", "publish.py"))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m

def run(mod, code, nas, cmd="app"):
    mod.Nas = lambda: nas
    sys.argv = ["publish.py", "app", APKS, str(code), "1.5.1"] if cmd == "app" else ["publish.py", *cmd.split()]
    mod.main()

def refuses(label, mod, code, want, remote=None, before=(), cmd="app", listing=None):
    nas = FakeNas(remote or state, listing)
    try:
        run(mod, code, nas, cmd)
    except SystemExit as e:
        assert want in str(e), f"{label}: رسالة غير متوقعة: {e}"
        assert nas.calls == list(before), f"{label}: رُفض بعد نداءات NAS غير متوقعة: {nas.calls}"
        print(f"RED  ok  {label}: {e}"); return
    raise AssertionError(f"{label}: لم يُرفض (نداءات NAS: {nas.calls})")

t = tempfile.mkdtemp(); R, APKS = os.path.join(t, "repo"), os.path.join(t, "apks")
try:
    subprocess.run(["git", "init", "-q", "--bare", "-b", "main", os.path.join(t, "remote.git")], check=True)
    os.makedirs(os.path.join(R, "tools")); os.makedirs(os.path.join(R, "android", "app", "src")); os.makedirs(APKS)
    for f in ("publish.py", "shamela_common.py"): shutil.copy(os.path.join(TOOLS, f), os.path.join(R, "tools"))
    shutil.copy(os.path.join(TOOLS, "..", ".gitignore"), R)  # الحالة المنسوخة من NAS مُتجاهَلة كما في المستودع الحقيقي
    open(os.path.join(R, "android", "app", "build.gradle.kts"), "w").write("android {\n    defaultConfig {\n        versionCode = 21\n        versionName = \"1.5.1\"\n    }\n}\n")
    for f in ("build.gradle.kts", "settings.gradle.kts", "app/src/Main.kt"): open(os.path.join(R, "android", f), "w").write("// x\n")
    open(os.path.join(R, "tools", "manifest.json"), "w").write("{}\n")  # متتبَّع كما في المستودع الحقيقي
    open(os.path.join(APKS, "app-universal-release.apk"), "wb").write(b"apk")
    state = {"dir": "/d", "packs": {"books.pack": {"url": "u", "page": "p", "size": 1, "sha256": "s"}},
             "files": {"master.db.gz": {"url": "u", "page": "p", "size": 1, "sha256": "s"}, "tashkeel.db.gz": {"url": "ut", "page": "pt", "size": 3, "sha256": "st"}}, "app": {"version_code": 20}, "data": {}}
    STATE = os.path.join(R, "tools", "publish_state.json")
    sh(R, "init", "-q", "-b", "main"); sh(R, "add", ".gitignore", "tools", "android"); sh(R, "commit", "-qm", "base")
    sh(R, "remote", "add", "origin", os.path.join(t, "remote.git")); sh(R, "push", "-q", "origin", "main")
    json.dump(state, open(STATE, "w"))  # بعد الـ commit: غير متتبَّع عمدًا، كما على جهاز الناشر
    pub = load(R)
    pub.apk_version = lambda p: 21  # aapt2 لا يقرأ APK وهميًا؛ البحث عن aapt2 نفسه مفحوص أدناه

    open(os.path.join(R, "android", "app", "src", "New.kt"), "w").write("// untracked\n")
    refuses("شجرة غير نظيفة", pub, 21, "غير ملتزَمة")
    os.remove(os.path.join(R, "android", "app", "src", "New.kt"))

    sh(R, "commit", "-q", "--allow-empty", "-m", "local only")
    sh(R, "push", "-q", "origin", "HEAD:side")  # فرع بعيد آخر يحويه — لا يكفي، النشر من main فقط
    refuses("HEAD في فرع جانبي لا في main", pub, 21, "origin/main")
    sh(R, "push", "-q", "origin", "main")
    sh(t, "--git-dir", "remote.git", "update-ref", "refs/heads/main", "main~1")  # main على GitHub أُعيد للخلف؛ origin/main المحلي قديم
    refuses("origin/main المحلي قديم", pub, 21, "origin/main")
    sh(R, "push", "-q", "origin", "main")

    refuses("versionCode لا يطابق", pub, 22, "versionCode")

    json.dump(state | {"app": {"version_code": 21}}, open(STATE, "w"))
    refuses("ليس أحدث من المنشور", pub, 21, "publish.py manifest")
    json.dump(state, open(STATE, "w"))

    open(os.path.join(APKS, "app-nas-universal-release-unsigned.apk"), "wb").write(b"apk")
    refuses("APK غير موقَّع", pub, 21, "غير موقَّع")
    os.remove(os.path.join(APKS, "app-nas-universal-release-unsigned.apk"))
    pub.apk_version = lambda p: 20
    refuses("APK من بناء آخر", pub, 21, "APK")
    pub.apk_version, env = load(R).apk_version, dict(os.environ)  # البحث الحقيقي عن aapt2، في HOME وSDK فارغين
    os.environ.update(HOME=t, ANDROID_HOME=t, ANDROID_SDK_ROOT=t)
    refuses("aapt2 غير موجود", pub, 21, "aapt2")
    os.environ.clear(); os.environ.update(env); pub.apk_version = lambda p: 21

    P = os.path.join(t, "plain"); shutil.copytree(os.path.join(R, "tools"), os.path.join(P, "tools"))
    refuses("نسخة خارج git", load(P), 21, "فشل")

    refuses("حالة NAS أحدث من المحلية", pub, 21, "انسخ", remote=state | {"app": {"version_code": 21}}, before=["login", "read"])
    refuses("حزم NAS أحدث من المحلية", pub, 21, "انسخ", remote=state | {"packs": state["packs"] | {"books-upd-x.pack": {"url": "u2", "page": "p2", "size": 2, "sha256": "s2"}}}, before=["login", "read"])
    refuses("manifest بحالة محلية قديمة", pub, 21, "انسخ", remote=state | {"packs": state["packs"] | {"books-upd-x.pack": {"url": "u2", "page": "p2", "size": 2, "sha256": "s2"}}}, before=["login", "read"], cmd="manifest")
    refuses("data بحالة محلية قديمة", pub, 21, "انسخ", remote=state | {"packs": state["packs"] | {"books-upd-x.pack": {"url": "u2", "page": "p2", "size": 2, "sha256": "s2"}}}, before=["login", "read"], cmd="data /nonexistent /nonexistent.db")
    refuses("manifest يعيد التطبيق إلى إصدار أقدم", pub, 21, "أقدم", remote=state | {"app": {"version_code": 21}}, before=["login", "read"], cmd="manifest")
    refuses("init على مجلد منشور", pub, 21, "init", before=["login"], cmd="init", listing={"manifest.json": 1})
    nas = FakeNas(state); run(pub, 21, nas, cmd=f"file {os.path.join(APKS, 'app-universal-release.apk')} README.txt")
    assert nas.calls == ["login", "read", "README.txt", "publish_state.json"], nas.calls  # الحالة على NAS تتبع الملف المرفوع
    print("GREEN ok  file يرفع الحالة إلى NAS:", nas.calls); os.remove(STATE)
    refuses("file بلا حالة محلية", pub, 21, "انسخ", before=["login", "read"], cmd=f"file {os.path.join(APKS, 'app-universal-release.apk')} README.txt")
    json.dump(state, open(STATE, "w"))
    refuses("حالة NAS غير مقروءة", pub, 21, "تعذّرت", remote={"error": {"code": 408}, "success": False}, before=["login", "read"])

    open(os.path.join(R, "tools", "manifest.json"), "w").write('{"stale": 1}\n')  # ناتج نشر سابق لم يُلتزَم بعد — لا يمنع النشر
    nas = FakeNas(state); run(pub, 21, nas)
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=R, capture_output=True, text=True).stdout.strip()
    st = json.load(open(STATE))
    assert nas.calls[:3] == ["login", "read", "app-universal-release.apk"] and "shamela-android-project.tar.gz" in nas.calls, nas.calls
    assert {"app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "app/src/Main.kt"} <= set(nas.tar), nas.tar
    assert st["app"]["commit"] == head and st["app"]["version_code"] == 21, st["app"]
    m = json.load(open(os.path.join(R, "tools", "manifest.json")))
    assert "shamela-android-project" not in json.dumps(m), "الأرشيف تسرّب إلى manifest العام"
    assert m["app"]["version_code"] == 21 and m["app"]["commit"] == head and m["data"]["tashkeel"]["path"] == "/d/tashkeel.db.gz", m
    assert nas.calls[-2:] == ["manifest.json", "publish_state.json"], nas.calls
    print(f"GREEN ok  نشر سليم: commit={head[:12]} نداءات NAS={nas.calls} أرشيف={nas.tar}")
finally:
    shutil.rmtree(t, ignore_errors=True)  # git قد يكتب في الخلفية (gc تلقائي) — لا يُخفى بسببه خطأ الفحص الحقيقي
