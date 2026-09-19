package shamela;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.util.Bits;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * يستخرج نصوص صفحات الكتب وعناوينها من فهارس Lucene الخاصة بالمكتبة الشاملة (store/page و store/title)
 * ويبني لكل كتاب قاعدة SQLite مستقلة مع فهرس بحث FTS5، ثم يضمّها في حزم (packs) قابلة للتنزيل
 * بالمدى (HTTP Range) من NAS، ويكتب index.json و master.db للتطبيق.
 *
 * الاستعمال: Extract <dataDir> <outDir> [--only 1,2,3] [--packSize 1000000000]
 */
public class Extract {

  // ---------- قراءة الفهارس ----------
  static final class Seg {
    final String name; final long order; final SegmentCommitInfo sci; final Directory cfsDir;
    final StoredFieldsReader reader; final Bits live; final int maxDoc;
    Seg(String name, long order, SegmentCommitInfo sci, Directory cfsDir, StoredFieldsReader reader, Bits live, int maxDoc) {
      this.name = name; this.order = order; this.sci = sci; this.cfsDir = cfsDir; this.reader = reader; this.live = live; this.maxDoc = maxDoc;
    }
  }

  static boolean allowMissing = false;

  static List<Seg> openSegments(Directory dir) throws IOException {
    SegmentInfos sis = SegmentInfos.readLatestCommit(dir);
    List<Seg> segs = new ArrayList<>();
    for (SegmentCommitInfo sci : sis) {
      SegmentInfo si = sci.info;
      try {
      Codec codec = si.getCodec();
      Directory d = si.getUseCompoundFile() ? codec.compoundFormat().getCompoundReader(dir, si) : dir;
      FieldInfos fis = codec.fieldInfosFormat().read(d, si, "", IOContext.DEFAULT);
      StoredFieldsReader sr = codec.storedFieldsFormat().fieldsReader(d, si, fis, IOContext.DEFAULT);
      Bits live = sci.hasDeletions() ? codec.liveDocsFormat().readLiveDocs(dir, sci, IOContext.DEFAULT) : null;
      long order = Long.parseLong(si.name.substring(1), 36);
      segs.add(new Seg(si.name, order, sci, d == dir ? null : d, sr, live, si.maxDoc()));
      } catch (NoSuchFileException e) {
        if (!allowMissing) throw e;
        System.out.println("  WARN segment " + si.name + " skipped: missing " + e.getMessage());
      }
    }
    segs.sort(Comparator.comparingLong(s -> s.order)); // الأقدم أولًا؛ الأحدث يغلب عند التكرار
    return segs;
  }

  /** موضع مستند: فهرس المقطع ورقم المستند ورقم الصفحة/العنوان داخل الكتاب. */
  static final class DocRef { final int seg, doc, num; DocRef(int s, int d, int n) { seg = s; doc = d; num = n; } }

  /** المرور الأول: قراءة حقل id فقط لكل مستند حي وبناء خريطة كتاب → مواضع مستنداته. */
  static Map<Integer, List<DocRef>> scanIds(List<Seg> segs, String label) throws IOException {
    Map<Integer, List<DocRef>> map = new HashMap<>();
    long total = 0, t0 = System.currentTimeMillis();
    for (int s = 0; s < segs.size(); s++) {
      Seg seg = segs.get(s);
      StoredFieldsReader r = seg.reader.getMergeInstance();
      IdVisitor v = new IdVisitor();
      for (int d = 0; d < seg.maxDoc; d++) {
        if (seg.live != null && !seg.live.get(d)) continue;
        v.id = null;
        r.document(d, v);
        if (v.id == null) continue;
        int dash = v.id.indexOf('-');
        if (dash <= 0) continue;
        int book, num;
        try { book = Integer.parseInt(v.id, 0, dash, 10); num = Integer.parseInt(v.id, dash + 1, v.id.length(), 10); }
        catch (NumberFormatException e) { continue; }
        map.computeIfAbsent(book, k -> new ArrayList<>()).add(new DocRef(s, d, num));
        total++;
      }
      System.out.printf("  [%s] segment %s: %d docs scanned (%.0fs)%n", label, seg.name, seg.maxDoc, (System.currentTimeMillis() - t0) / 1000.0);
    }
    System.out.printf("  [%s] %d live docs in %d books%n", label, total, map.size());
    return map;
  }

  static final class IdVisitor extends StoredFieldVisitor {
    String id;
    @Override public Status needsField(FieldInfo f) { return f.name.equals("id") ? Status.YES : (id != null ? Status.STOP : Status.NO); }
    @Override public void stringField(FieldInfo f, String value) { id = value; }
  }

  static final class DocVisitor extends StoredFieldVisitor {
    String id, body, foot;
    void reset() { id = body = foot = null; }
    @Override public Status needsField(FieldInfo f) {
      switch (f.name) { case "id": case "body": case "foot": return Status.YES; default: return Status.NO; }
    }
    @Override public void stringField(FieldInfo f, String value) {
      switch (f.name) { case "id": id = value; break; case "body": body = value; break; case "foot": foot = value; break; }
    }
  }

  // ---------- حزم الإخراج ----------
  static final class Packer implements Closeable {
    final Path dir; final long maxSize; final List<Map<String, Object>> packs = new ArrayList<>();
    int packNo = -1; FileOutputStream out; long pos; String packName; MessageDigest packDigest;
    Packer(Path dir, long maxSize) { this.dir = dir; this.maxSize = maxSize; }
    void nextPack() throws Exception {
      closePack();
      packNo++;
      packName = String.format("books-%02d.pack", packNo);
      out = new FileOutputStream(dir.resolve(packName).toFile());
      pos = 0; packDigest = MessageDigest.getInstance("SHA-256");
    }
    void closePack() throws IOException {
      if (out != null) {
        out.close();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", packName); m.put("size", pos); m.put("sha256", hex(packDigest.digest()));
        packs.add(m);
        out = null; pos = 0;
      }
    }
    /** يضيف ملفًا مضغوطًا gzip إلى الحزمة الحالية ويعيد [pack, offset, length, sha256(gz)]. */
    Object[] add(Path file) throws Exception {
      if (out == null || pos >= maxSize) nextPack();
      long start = pos;
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      CountingOutputStream cos = new CountingOutputStream(out, md, packDigest);
      try (GZIPOutputStream gz = new GZIPOutputStream(cos, 1 << 16) {{ def.setLevel(9); }};
           InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
        byte[] buf = new byte[1 << 20]; int n;
        while ((n = in.read(buf)) > 0) gz.write(buf, 0, n);
        gz.finish();
      }
      pos += cos.count;
      return new Object[]{packName, start, cos.count, hex(md.digest())};
    }
    @Override public void close() throws IOException { closePack(); }
  }

  static final class CountingOutputStream extends OutputStream {
    final OutputStream o; final MessageDigest a, b; long count;
    CountingOutputStream(OutputStream o, MessageDigest a, MessageDigest b) { this.o = o; this.a = a; this.b = b; }
    @Override public void write(int x) throws IOException { o.write(x); a.update((byte) x); b.update((byte) x); count++; }
    @Override public void write(byte[] buf, int off, int len) throws IOException { o.write(buf, off, len); a.update(buf, off, len); b.update(buf, off, len); count += len; }
    @Override public void close() { /* لا نغلق ملف الحزمة */ }
  }

  static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

  // ---------- بيانات الكتاب من قاعدة الشاملة الصغيرة ----------
  static final class PageMeta { final String part; final Integer page, number; final String services; PageMeta(String p, Integer pg, Integer n, String s) { part = p; page = pg; number = n; services = s; } }
  static final class TitleMeta { final int page, parent; TitleMeta(int p, int pa) { page = p; parent = pa; } }

  static Path bookDbPath(Path dataDir, int id) { return dataDir.resolve("book").resolve(String.format("%03d", id % 1000)).resolve(id + ".db"); }

  // ---------- البرنامج الرئيسي ----------
  public static void main(String[] args) throws Exception {
    Path dataDir = Paths.get(args[0]); Path outDir = Paths.get(args[1]);
    Set<Integer> only = null; long packSize = 1_000_000_000L; boolean keepRaw = false;
    for (int i = 2; i < args.length; i++) {
      if (args[i].equals("--only")) { only = new HashSet<>(); for (String s : args[++i].split(",")) only.add(Integer.parseInt(s.trim())); }
      else if (args[i].equals("--packSize")) packSize = Long.parseLong(args[++i]);
      else if (args[i].equals("--keepRaw")) keepRaw = true;
      else if (args[i].equals("--allowMissing")) allowMissing = true;
    }
    Files.createDirectories(outDir.resolve("raw"));
    Files.createDirectories(outDir.resolve("packs"));
    Class.forName("org.sqlite.JDBC");

    Directory pageDir = FSDirectory.open(dataDir.resolve("store/page"));
    Directory titleDir = FSDirectory.open(dataDir.resolve("store/title"));
    List<Seg> pageSegs = openSegments(pageDir), titleSegs = openSegments(titleDir);
    System.out.println("page segments: " + pageSegs.size() + ", title segments: " + titleSegs.size());

    System.out.println("Pass 0: scanning ids...");
    Map<Integer, List<DocRef>> pageRefs = scanIds(pageSegs, "page");
    Map<Integer, List<DocRef>> titleRefs = scanIds(titleSegs, "title");

    // ترتيب الكتب بحيث تكون القراءة تسلسلية قدر الإمكان: حسب آخر مقطع يحوي الكتاب ثم أول مستند فيه
    List<Integer> books = new ArrayList<>(pageRefs.keySet());
    if (only != null) books.retainAll(only);
    Map<Integer, long[]> orderKey = new HashMap<>();
    for (int b : books) {
      int maxSeg = -1, minDoc = Integer.MAX_VALUE;
      for (DocRef r : pageRefs.get(b)) { if (r.seg > maxSeg) { maxSeg = r.seg; minDoc = r.doc; } else if (r.seg == maxSeg && r.doc < minDoc) minDoc = r.doc; }
      orderKey.put(b, new long[]{maxSeg, minDoc});
    }
    books.sort((x, y) -> { long[] a = orderKey.get(x), c = orderKey.get(y); return a[0] != c[0] ? Long.compare(a[0], c[0]) : Long.compare(a[1], c[1]); });

    // مثيلات القراءة التسلسلية
    List<StoredFieldsReader> pageReaders = new ArrayList<>(), titleReaders = new ArrayList<>();
    for (Seg s : pageSegs) pageReaders.add(s.reader.getMergeInstance());
    for (Seg s : titleSegs) titleReaders.add(s.reader.getMergeInstance());

    Packer packer = new Packer(outDir.resolve("packs"), packSize);
    List<Map<String, Object>> index = new ArrayList<>();
    long t0 = System.currentTimeMillis(), totalPages = 0, totalTitles = 0, totalRaw = 0;
    int done = 0;
    DocVisitor v = new DocVisitor();
    PrintWriter log = new PrintWriter(new FileWriter(outDir.resolve("extract.log").toFile(), true), true);

    for (int bookId : books) {
      List<DocRef> prefs = pageRefs.get(bookId);
      List<DocRef> trefs = titleRefs.getOrDefault(bookId, Collections.emptyList());
      prefs.sort(Comparator.<DocRef>comparingInt(r -> r.seg).thenComparingInt(r -> r.doc));
      trefs.sort(Comparator.<DocRef>comparingInt(r -> r.seg).thenComparingInt(r -> r.doc));

      // بيانات الصفحات والعناوين من قاعدة الكتاب الصغيرة
      Map<Integer, PageMeta> pmeta = new HashMap<>(); Map<Integer, TitleMeta> tmeta = new HashMap<>();
      Path bdb = bookDbPath(dataDir, bookId);
      if (Files.exists(bdb)) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + bdb)) {
          try (ResultSet rs = c.createStatement().executeQuery("select id, part, page, number, services from page")) {
            while (rs.next()) pmeta.put(rs.getInt(1), new PageMeta(rs.getString(2), (Integer) rs.getObject(3), (Integer) rs.getObject(4), rs.getString(5)));
          }
          try (ResultSet rs = c.createStatement().executeQuery("select id, page, parent from title")) {
            while (rs.next()) tmeta.put(rs.getInt(1), new TitleMeta(rs.getInt(2), rs.getInt(3)));
          }
        }
      } else log.println("WARN book db missing for " + bookId);

      Path raw = outDir.resolve("raw").resolve(bookId + ".db");
      Files.deleteIfExists(raw);
      int pages = 0, titles = 0, dup = 0;
      try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + raw)) {
        Statement st = c.createStatement();
        st.execute("PRAGMA journal_mode=OFF"); st.execute("PRAGMA synchronous=OFF"); st.execute("PRAGMA page_size=4096"); st.execute("PRAGMA cache_size=-200000");
        st.execute("CREATE TABLE page(id INTEGER PRIMARY KEY, part TEXT, page INTEGER, number INTEGER, body TEXT, foot TEXT, services TEXT)");
        st.execute("CREATE TABLE title(id INTEGER PRIMARY KEY, page INTEGER, parent INTEGER, text TEXT)");
        st.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)");
        c.setAutoCommit(false);
        Set<Integer> seen = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO page(id,part,page,number,body,foot,services) VALUES(?,?,?,?,?,?,?)")) {
          for (DocRef r : prefs) {
            v.reset();
            pageReaders.get(r.seg).document(r.doc, v);
            if (!seen.add(r.num)) dup++;
            PageMeta m = pmeta.get(r.num);
            ps.setInt(1, r.num);
            ps.setString(2, m == null ? null : m.part);
            if (m == null || m.page == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, m.page);
            if (m == null || m.number == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, m.number);
            ps.setString(5, v.body == null ? "" : v.body);
            ps.setString(6, v.foot);
            ps.setString(7, m == null ? null : m.services);
            ps.addBatch();
            if (++pages % 2000 == 0) ps.executeBatch();
          }
          ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO title(id,page,parent,text) VALUES(?,?,?,?)")) {
          for (DocRef r : trefs) {
            v.reset();
            titleReaders.get(r.seg).document(r.doc, v);
            TitleMeta m = tmeta.get(r.num);
            ps.setInt(1, r.num);
            if (m == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, m.page);
            if (m == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, m.parent);
            ps.setString(4, Norm.stripTags(v.body == null ? "" : v.body).trim());
            ps.addBatch();
            if (++titles % 2000 == 0) ps.executeBatch();
          }
          ps.executeBatch();
        }
        c.commit();
        // فهرس البحث: بلا محتوى (contentless) على النص المطبَّع؛ التطبيق يُظلّل بنفسه
        st.execute("CREATE VIRTUAL TABLE page_fts USING fts5(body, foot, content='', tokenize='unicode61 remove_diacritics 2')");
        st.execute("CREATE VIRTUAL TABLE title_fts USING fts5(text, content='', tokenize='unicode61 remove_diacritics 2')");
        try (PreparedStatement ins = c.prepareStatement("INSERT INTO page_fts(rowid, body, foot) VALUES(?,?,?)");
             ResultSet rs = st.executeQuery("select id, body, foot from page order by id")) {
          int n = 0;
          while (rs.next()) { ins.setInt(1, rs.getInt(1)); ins.setString(2, Norm.normalize(rs.getString(2))); ins.setString(3, Norm.normalize(rs.getString(3))); ins.addBatch(); if (++n % 2000 == 0) ins.executeBatch(); }
          ins.executeBatch();
        }
        try (PreparedStatement ins = c.prepareStatement("INSERT INTO title_fts(rowid, text) VALUES(?,?)");
             ResultSet rs = st.executeQuery("select id, text from title order by id")) {
          int n = 0;
          while (rs.next()) { ins.setInt(1, rs.getInt(1)); ins.setString(2, Norm.normalize(rs.getString(2))); ins.addBatch(); if (++n % 2000 == 0) ins.executeBatch(); }
          ins.executeBatch();
        }
        st.execute("INSERT INTO page_fts(page_fts) VALUES('optimize')");
        st.execute("INSERT INTO title_fts(title_fts) VALUES('optimize')");
        st.execute("CREATE INDEX title_page ON title(page)");
        st.execute("CREATE INDEX title_parent ON title(parent)");
        st.execute("CREATE INDEX page_partpage ON page(part, page)");
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?)")) {
          String[][] kv = {{"book_id", "" + bookId}, {"format", "shamela-book"}, {"format_version", "1"}, {"pages", "" + pages}, {"titles", "" + titles}, {"source", "shamela-database-1448"}, {"normalizer", "arabic-v1"}};
          for (String[] p : kv) { ps.setString(1, p[0]); ps.setString(2, p[1]); ps.addBatch(); }
          ps.executeBatch();
        }
        c.commit();
        c.setAutoCommit(true);
        st.execute("VACUUM");
      }
      long rawSize = Files.size(raw);
      Object[] loc = packer.add(raw);
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("id", bookId); e.put("pack", loc[0]); e.put("offset", loc[1]); e.put("length", loc[2]); e.put("size", rawSize); e.put("sha256", loc[3]);
      e.put("pages", pages); e.put("titles", titles);
      index.add(e);
      if (!keepRaw) Files.delete(raw);
      totalPages += pages; totalTitles += titles; totalRaw += rawSize; done++;
      if (dup > 0) log.println("book " + bookId + ": " + dup + " duplicate page ids (newest kept)");
      if (done % 50 == 0 || done == books.size())
        System.out.printf("  %d/%d books, %d pages, %d titles, raw %.2f GB, packed %.2f GB, %.0fs%n", done, books.size(), totalPages, totalTitles, totalRaw / 1e9, packedTotal(packer) / 1e9, (System.currentTimeMillis() - t0) / 1000.0);
    }
    packer.close();

    // index.json
    try (Writer w = Files.newBufferedWriter(outDir.resolve("index.json"))) {
      w.write("{\n  \"format\": \"shamela-nas-index\",\n  \"format_version\": 1,\n  \"source\": \"shamela-database-1448\",\n  \"generated\": \"" + java.time.Instant.now() + "\",\n");
      w.write("  \"books_count\": " + index.size() + ",\n  \"pages_count\": " + totalPages + ",\n  \"titles_count\": " + totalTitles + ",\n");
      w.write("  \"packs\": [\n");
      for (int i = 0; i < packer.packs.size(); i++) { Map<String, Object> p = packer.packs.get(i); w.write("    {\"file\": \"" + p.get("file") + "\", \"size\": " + p.get("size") + ", \"sha256\": \"" + p.get("sha256") + "\"}" + (i + 1 < packer.packs.size() ? ",\n" : "\n")); }
      w.write("  ],\n  \"books\": [\n");
      for (int i = 0; i < index.size(); i++) {
        Map<String, Object> e = index.get(i);
        w.write("    {\"id\": " + e.get("id") + ", \"pack\": \"" + e.get("pack") + "\", \"offset\": " + e.get("offset") + ", \"length\": " + e.get("length") + ", \"size\": " + e.get("size") + ", \"sha256\": \"" + e.get("sha256") + "\", \"pages\": " + e.get("pages") + ", \"titles\": " + e.get("titles") + "}" + (i + 1 < index.size() ? ",\n" : "\n"));
      }
      w.write("  ]\n}\n");
    }
    for (Seg s : pageSegs) { s.reader.close(); if (s.cfsDir != null) s.cfsDir.close(); }
    for (Seg s : titleSegs) { s.reader.close(); if (s.cfsDir != null) s.cfsDir.close(); }
    log.close();
    System.out.printf("DONE: %d books, %d pages, %d titles, raw %.2f GB, packed %.2f GB in %.0fs%n", done, totalPages, totalTitles, totalRaw / 1e9, packedTotal(packer) / 1e9, (System.currentTimeMillis() - t0) / 1000.0);
  }

  static long packedTotal(Packer p) { long t = p.pos; for (Map<String, Object> m : p.packs) t += (Long) m.get("size"); return t; }
}
