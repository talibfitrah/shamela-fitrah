#!/usr/bin/env python3
"""أدوات مشتركة لسكربتات المكتبة الشاملة على NAS: التطبيع العربي (مطابق لـ Norm.java و ArabicText.kt)،
وواجهة DSM FileStation (رفع، روابط مشاركة، قوائم)، وبناء manifest.json."""
import hashlib, json, os, re, subprocess, sys, time, urllib.parse

NAS_BASE = os.environ.get("NAS_BASE", "https://files.murabbie.org")
NAS_USER = os.environ.get("NAS_USER", "manus")
NAS_PASS = os.environ.get("NAS_PASS", "")  # سرّي: يُمرَّر عبر متغير البيئة NAS_PASS فقط
NAS_DIR = os.environ.get("NAS_DIR", "/downloads/shamela")
SITE = "https://shamela.ws"

# ---------------- التطبيع ----------------
_MAP = {"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ی": "ي", "ئ": "ي", "ة": "ه", "ؤ": "و", "ک": "ك", "گ": "ك"}

def _removable(c):
    o = ord(c)
    return (0x0610 <= o <= 0x061A) or (0x064B <= o <= 0x065F) or o == 0x0670 or (0x06D6 <= o <= 0x06ED) or o == 0x0640 or (0x200B <= o <= 0x200F) or (0x202A <= o <= 0x202E) or o == 0xFEFF

def normalize(s):
    """إزالة الوسوم ثم التطبيع — حرفًا بحرف كما في Norm.java"""
    if not s:
        return ""
    out = []
    in_tag = False
    for c in s:
        if in_tag:
            if c == ">":
                in_tag = False; out.append(" ")
            continue
        if c == "<":
            in_tag = True; continue
        if _removable(c):
            continue
        if c in _MAP:
            out.append(_MAP[c])
        elif "٠" <= c <= "٩":
            out.append(chr(ord("0") + ord(c) - ord("٠")))
        elif "۰" <= c <= "۹":
            out.append(chr(ord("0") + ord(c) - ord("۰")))
        else:
            out.append(c.lower())
    return "".join(out)

def strip_tags(s):
    return re.sub(r"<[^>]+>", "", s or "")

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

# ---------------- DSM ----------------
class Nas:
    def __init__(self, base=NAS_BASE, user=NAS_USER, password=NAS_PASS):
        self.base = base.rstrip("/") + "/webapi"
        self.user, self.password = user, password
        self.sid = None

    def _curl(self, args, timeout=600):
        r = subprocess.run(["curl", "-sS", "-m", str(timeout)] + args, capture_output=True)
        if r.returncode != 0:
            raise RuntimeError("curl failed: " + r.stderr.decode(errors="replace"))
        return r.stdout

    def login(self):
        out = self._curl(["-G", f"{self.base}/auth.cgi", "--data-urlencode", "api=SYNO.API.Auth", "--data-urlencode", "version=3",
                          "--data-urlencode", "method=login", "--data-urlencode", f"account={self.user}", "--data-urlencode", f"passwd={self.password}",
                          "--data-urlencode", "session=FileStation", "--data-urlencode", "format=sid"])
        j = json.loads(out)
        if not j.get("success"):
            raise RuntimeError("login failed: " + out.decode(errors="replace"))
        self.sid = j["data"]["sid"]
        return self.sid

    def api(self, api, method, version, **params):
        if not self.sid:
            self.login()
        args = ["-G", f"{self.base}/entry.cgi", "--data-urlencode", f"api={api}", "--data-urlencode", f"version={version}", "--data-urlencode", f"method={method}", "--data-urlencode", f"_sid={self.sid}"]
        for k, v in params.items():
            args += ["--data-urlencode", f"{k}={v}"]
        j = json.loads(self._curl(args))
        if not j.get("success"):
            raise RuntimeError(f"{api}.{method} failed: {j}")
        return j.get("data", {})

    def mkdir(self, path):
        parent, name = path.rsplit("/", 1)
        try:
            self.api("SYNO.FileStation.CreateFolder", "create", 2, folder_path=json.dumps([parent]), name=json.dumps([name]))
        except RuntimeError as e:
            if "1100" not in str(e) and "already" not in str(e).lower():
                # 1100 = موجود مسبقًا
                pass

    def list(self, folder):
        d = self.api("SYNO.FileStation.List", "list", 2, folder_path=folder, additional=json.dumps(["size"]), limit=5000)
        return {f["name"]: f.get("additional", {}).get("size", 0) for f in d.get("files", [])}

    def upload(self, local, remote_dir, remote_name=None, overwrite=True, retries=4):
        if not self.sid:
            self.login()
        remote_name = remote_name or os.path.basename(local)
        size = os.path.getsize(local)
        for attempt in range(retries):
            try:
                out = self._curl(["-X", "POST", f"{self.base}/entry.cgi?api=SYNO.FileStation.Upload&version=2&method=upload&_sid={self.sid}",
                                  "-F", f"path={remote_dir}", "-F", "create_parents=true", "-F", f"overwrite={'true' if overwrite else 'false'}",
                                  "-F", f"file=@{local};filename={remote_name}"], timeout=7200)
                j = json.loads(out)
                if j.get("success"):
                    # تحقق من الحجم
                    have = self.list(remote_dir).get(remote_name)
                    if have == size:
                        return True
                    raise RuntimeError(f"size mismatch after upload: {have} != {size}")
                raise RuntimeError(str(j))
            except Exception as e:
                print(f"    upload {remote_name} attempt {attempt+1} failed: {e}", file=sys.stderr)
                time.sleep(5 * (attempt + 1))
        raise RuntimeError("upload failed: " + remote_name)

    def share_links(self):
        d = self.api("SYNO.FileStation.Sharing", "list", 3, limit=2000)
        return {l["path"]: l for l in d.get("links", [])}

    def share(self, path):
        """رابط مشاركة دائم لملف — يعيد رابطًا موجودًا إن وُجد للمسار نفسه"""
        links = self.share_links()
        if path in links and not links[path].get("expire_times"):
            l = links[path]
        else:
            d = self.api("SYNO.FileStation.Sharing", "create", 3, path=json.dumps([path]))
            l = d["links"][0]
        sid = l["id"]
        name = path.rsplit("/", 1)[1]
        return {"id": sid, "page": f"{NAS_BASE}/sharing/{sid}", "url": f"{NAS_BASE}/fsdownload/{sid}/{urllib.parse.quote(name)}"}

    def delete(self, path):
        self.api("SYNO.FileStation.Delete", "delete", 2, path=json.dumps([path]))

def arabic_digits(n):
    return str(n).translate(str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩"))
