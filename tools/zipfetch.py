#!/usr/bin/env python3
"""Fetch individual entries out of the remote shamela-database zip via HTTP range requests.
Usage: zipfetch.py <outdir> <pattern-regex> [pattern-regex ...]
Entries are written under <outdir>/<entry path without 'database/'>. Existing files with correct size are skipped.
"""
import os, re, struct, subprocess, sys, zlib, pickle, time

URL = "https://dev.shamela.ws/downloads/shamela-database-1448.zip"
HERE = os.path.dirname(os.path.abspath(__file__))
ENTS = os.path.join(HERE, "ents.pkl")

def rng(a, b, out=None):
    for attempt in range(6):
        cmd = ["curl", "-sS", "-m", "3600", "--retry", "3", "-r", f"{a}-{b}", URL]
        if out:
            r = subprocess.run(cmd + ["-o", out])
            if r.returncode == 0 and os.path.getsize(out) == b - a + 1:
                return None
        else:
            r = subprocess.run(cmd, capture_output=True)
            if r.returncode == 0 and len(r.stdout) == b - a + 1:
                return r.stdout
        time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"range fetch failed {a}-{b}")

def load_entries():
    return pickle.load(open(ENTS, "rb"))

def fetch(ents, name, outdir):
    m, c, u, o = ents[name]
    rel = name[len("database/"):] if name.startswith("database/") else name
    out = os.path.join(outdir, rel)
    if name.endswith("/"):
        os.makedirs(out, exist_ok=True); return
    if os.path.exists(out) and os.path.getsize(out) == u:
        return
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tmp = out + ".part"
    if u == 0:
        open(out, "wb").close(); return
    if c < 8_000_000:
        blob = rng(o, o + 30 + len(name.encode()) + 128 + c - 1)
        fn, el = struct.unpack("<HH", blob[26:30])
        data = blob[30 + fn + el: 30 + fn + el + c]
        if len(data) != c:
            raise RuntimeError("short single fetch " + name)
        if m == 8: data = zlib.decompress(data, -15)
        elif m != 0: raise RuntimeError(f"unsupported method {m} for {name}")
        if len(data) != u: raise RuntimeError(f"size mismatch {name}")
        open(tmp, "wb").write(data); os.replace(tmp, out); return
    h = rng(o, o + 29)
    fn, el = struct.unpack("<HH", h[26:30])
    start = o + 30 + fn + el
    if m == 0:
        rng(start, start + c - 1, tmp)
    elif m == 8:
        # stream-decompress to disk
        p = subprocess.Popen(["curl", "-sS", "-m", "7200", "--retry", "3", "-r", f"{start}-{start+c-1}", URL], stdout=subprocess.PIPE)
        d = zlib.decompressobj(-15)
        with open(tmp, "wb") as f:
            while True:
                chunk = p.stdout.read(1 << 20)
                if not chunk: break
                f.write(d.decompress(chunk))
            f.write(d.flush())
        p.wait()
        if p.returncode != 0:
            raise RuntimeError("curl failed for " + name)
    else:
        raise RuntimeError(f"unsupported method {m} for {name}")
    if os.path.getsize(tmp) != u:
        raise RuntimeError(f"size mismatch {name}: {os.path.getsize(tmp)} != {u}")
    os.replace(tmp, out)

if __name__ == "__main__":
    outdir = sys.argv[1]; pats = [re.compile(p) for p in sys.argv[2:]]
    ents = load_entries()
    names = [n for n in ents if any(p.search(n) for p in pats)]
    names.sort(key=lambda n: ents[n][1])
    total = sum(ents[n][2] for n in names)
    print(f"{len(names)} entries, {total/1e9:.2f} GB uncompressed", flush=True)
    done = 0
    from concurrent.futures import ThreadPoolExecutor, as_completed
    small = [n for n in names if ents[n][1] < 8_000_000]
    big = [n for n in names if ents[n][1] >= 8_000_000]
    with ThreadPoolExecutor(max_workers=12) as ex:
        futs = {ex.submit(fetch, ents, n, outdir): n for n in small}
        k = 0
        for f in as_completed(futs):
            f.result(); k += 1
            if k % 500 == 0: print(f"  {k}/{len(small)} small entries", flush=True)
    print(f"  small entries done ({len(small)})", flush=True)
    with ThreadPoolExecutor(max_workers=3) as ex:
        futs = {ex.submit(fetch, ents, n, outdir): n for n in big}
        for f in as_completed(futs):
            n = futs[f]; f.result(); done += ents[n][2]
            print(f"  {n} ok  ({done/1e9:.2f} GB of big entries)", flush=True)
    print("done", flush=True)
