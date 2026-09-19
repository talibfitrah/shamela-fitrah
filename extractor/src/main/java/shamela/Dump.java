package shamela;
import org.apache.lucene.index.*;
import org.apache.lucene.document.*;
import org.apache.lucene.codecs.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.Bits;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** يطبع كل الحقول المخزَّنة في فهرس Lucene سطرًا JSON لكل وثيقة (UTF-8). */
public class Dump {
  static String esc(String s) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) { if (c=='"'||c=='\\') b.append('\\').append(c); else if (c=='\n') b.append("\\n"); else if (c=='\r') b.append("\\r"); else if (c=='\t') b.append("\\t"); else if (c<0x20) b.append(String.format("\\u%04x",(int)c)); else b.append(c);} return b.toString(); }
  public static void main(String[] a) throws Exception {
    Directory dir = FSDirectory.open(Paths.get(a[0]));
    PrintStream out = new PrintStream(new FileOutputStream(a[1]), true, "UTF-8");
    SegmentInfos sis = SegmentInfos.readLatestCommit(dir);
    for (SegmentCommitInfo sci : sis) {
      SegmentInfo si = sci.info;
      Codec codec = si.getCodec();
      Directory d = si.getUseCompoundFile() ? codec.compoundFormat().getCompoundReader(dir, si) : dir;
      FieldInfos fis = codec.fieldInfosFormat().read(d, si, "", IOContext.DEFAULT);
      StoredFieldsReader sr = codec.storedFieldsFormat().fieldsReader(d, si, fis, IOContext.DEFAULT);
      Bits live = sci.hasDeletions() ? codec.liveDocsFormat().readLiveDocs(dir, sci, IOContext.DEFAULT) : null;
      for (int doc = 0; doc < si.maxDoc(); doc++) {
        if (live != null && !live.get(doc)) continue;
        DocumentStoredFieldVisitor v = new DocumentStoredFieldVisitor();
        sr.document(doc, v);
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (IndexableField f : v.getDocument().getFields()) {
          if (!first) b.append(","); first = false;
          String val = f.stringValue(); if (val == null && f.numericValue() != null) val = f.numericValue().toString();
          b.append('"').append(f.name()).append("\":\"").append(esc(val == null ? "" : val)).append('"');
        }
        out.println(b.append("}"));
      }
      sr.close();
    }
    out.close();
  }
}
