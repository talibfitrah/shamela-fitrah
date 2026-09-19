package shamela;

/**
 * تطبيع النص العربي للفهرسة — يطابق ArabicText.normalize في تطبيق الأندرويد حرفًا بحرف،
 * مع إزالة وسوم HTML (تُستبدل بمسافة) قبل التطبيع.
 */
public final class Norm {
  private Norm() {}

  public static boolean removable(char c) {
    return (c >= 0x0610 && c <= 0x061A) || (c >= 0x064B && c <= 0x065F) || c == 0x0670
        || (c >= 0x06D6 && c <= 0x06ED) || c == 0x0640 || (c >= 0x200B && c <= 0x200F)
        || (c >= 0x202A && c <= 0x202E) || c == 0xFEFF;
  }

  public static char map(char c) {
    switch (c) {
      case 'أ': case 'إ': case 'آ': case 'ٱ': return 'ا';
      case 'ى': case 'ی': case 'ئ': return 'ي';
      case 'ة': return 'ه';
      case 'ؤ': return 'و';
      case 'ک': case 'گ': return 'ك';
      default:
        if (c >= '٠' && c <= '٩') return (char) ('0' + (c - '٠'));
        if (c >= '۰' && c <= '۹') return (char) ('0' + (c - '۰'));
        return Character.toLowerCase(c);
    }
  }

  /** إزالة الوسوم ثم التطبيع. */
  public static String normalize(String s) {
    if (s == null || s.isEmpty()) return "";
    StringBuilder sb = new StringBuilder(s.length());
    boolean inTag = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inTag) { if (c == '>') { inTag = false; sb.append(' '); } continue; }
      if (c == '<') { inTag = true; continue; }
      if (removable(c)) continue;
      sb.append(map(c));
    }
    return sb.toString();
  }

  /** نص خالٍ من الوسوم (بلا تطبيع) — للعرض والإحصاء. */
  public static String stripTags(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length());
    boolean inTag = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inTag) { if (c == '>') { inTag = false; } continue; }
      if (c == '<') { inTag = true; continue; }
      sb.append(c);
    }
    return sb.toString();
  }
}
