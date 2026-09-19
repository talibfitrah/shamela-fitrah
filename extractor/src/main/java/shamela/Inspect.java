package shamela;
import org.apache.lucene.index.*;
import org.apache.lucene.document.*;
import org.apache.lucene.codecs.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.Bits;
import java.nio.file.*;
import java.util.*;

public class Inspect {
  public static void main(String[] a) throws Exception {
    Directory dir = FSDirectory.open(Paths.get(a[0]));
    int limit = a.length > 1 ? Integer.parseInt(a[1]) : 3;
    SegmentInfos sis = SegmentInfos.readLatestCommit(dir);
    System.out.println("segments=" + sis.size() + " version=" + sis.getCommitLuceneVersion() + " userData=" + sis.getUserData());
    for (SegmentCommitInfo sci : sis) {
      SegmentInfo si = sci.info;
      try {
      Codec codec = si.getCodec();
      Directory d = si.getUseCompoundFile() ? codec.compoundFormat().getCompoundReader(dir, si) : dir;
      FieldInfos fis = codec.fieldInfosFormat().read(d, si, "", IOContext.DEFAULT);
      System.out.println("== " + si.name + " docs=" + si.maxDoc() + " del=" + sci.getDelCount() + " cfs=" + si.getUseCompoundFile());
      for (FieldInfo fi : fis) System.out.println("   field " + fi.name + " dv=" + fi.getDocValuesType() + " idx=" + fi.getIndexOptions() + " pts=" + fi.getPointDimensionCount());
      StoredFieldsReader sr = codec.storedFieldsFormat().fieldsReader(d, si, fis, IOContext.DEFAULT);
      Bits live = sci.hasDeletions() ? codec.liveDocsFormat().readLiveDocs(dir, sci, IOContext.DEFAULT) : null;
      int shown = 0;
      for (int doc = 0; doc < si.maxDoc() && shown < limit; doc++) {
        if (live != null && !live.get(doc)) continue;
        DocumentStoredFieldVisitor v = new DocumentStoredFieldVisitor();
        sr.document(doc, v);
        System.out.println("   doc " + doc + ":");
        for (IndexableField f : v.getDocument().getFields()) {
          String s = f.stringValue(); if (s == null && f.numericValue()!=null) s = f.numericValue().toString(); if (s==null && f.binaryValue()!=null) s="<bin "+f.binaryValue().length+">";
          if (s != null && s.length() > 300) s = s.substring(0, 300) + "...";
          System.out.println("      " + f.name() + " = " + s);
        }
        shown++;
      }
      sr.close();
      if (d != dir) d.close();
      } catch (java.nio.file.NoSuchFileException e) { System.out.println("== "+si.name+" skipped (missing "+e.getMessage()+")"); }
    }
  }
}
