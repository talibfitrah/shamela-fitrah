# المكتبة الشاملة — نسخة الأندرويد: دليل المبرمج للتسليم والنشر

الإصدار الحالي: 1.5.1 (versionCode 21؛ المسلَّم أصلًا 1.2.0 / 11، ومصدر 1.5.0 مستورد من أرشيف NAS) — الحزمة: `org.murabbie.shamela` — تاريخ التسليم: ١٤ / ٩ / ٢٠٢٦.

هذا الدليل يكفي مبرمجًا لم يرَ المشروع من قبل ليبنيه ويطوّره وينشره على Google Play. اقرأه كاملًا قبل أي تعديل.

---

## ١. محتويات حزمة التسليم

| المجلد / الملف | ما فيه |
|---|---|
| `android/` | مشروع Android Studio كاملًا (Kotlin + Jetpack Compose). يُفتح مباشرة. |
| `signing/` | مفتاح التوقيع `shamela-release.keystore` وملف `keystore.properties`. **سرّي — لا يُرفع إلى أي مستودع عام.** |
| `apk/` | ملفات APK الموقَّعة للإصدار 1.2.0 (نكهة `nas`: universal / arm64 / arm32) |
| `aab/` | `app-play-release.aab` — حزمة Google Play (نكهة `play`) موقَّعة بالمفتاح نفسه |
| `tools/` | سكربتات بايثون لبناء البيانات ونشرها على الخادم (NAS) |
| `extractor/` | مستخرج Java (Lucene → SQLite) الذي يُنتج قواعد الكتب من الحزمة الرسمية |
| `jvmtest/` | اختبار على JVM يشغّل شيفرة الاتصال نفسها في التطبيق ضد الخادم والموقع |
| `play-store/` | نصوص المتجر (الوصف القصير والطويل)، سياسة الخصوصية، إجابات «سلامة البيانات» |
| `docs/` | هذا الدليل بصيغة Word أيضًا |

---

## ٢. الفكرة العامة والبنية

التطبيق قارئ كامل للمكتبة الشاملة (shamela.ws) لا يحمل الكتب داخله، بل:

1. **الفهرس** (`master.db`، نحو ٦ م.ب): مضمَّن في APK (`assets/master.gzdb`) فيفتح التطبيق فورًا بلا شبكة، ويُحدَّث من الخادم عند صدور فهرس أحدث.
2. **الكتب** (٨٬٥٩١ كتابًا، ٧٫٦ مليون صفحة): في ملف واحد `books.pack` (٨٫٩ غ.ب) على خادم NAS. كل كتاب قاعدة SQLite مضغوطة gzip داخل الملف، ويعرف الفهرس موضعها (`book_file`: offset/length)، فيُجلب الكتاب بطلب HTTP Range عند أول فتح ثم يُقرأ ويُبحث فيه دون اتصال.
3. **الخدمات** (`services.db`، ٢٢٥ م.ب / ٧٠ م.ب مضغوطًا): تراجم الرواة، تخريج الحديث، الأسانيد، القرآن والتفسير، الجذور. تُنزَّل مرة واحدة عند الطلب.
4. **المستجدات**: التطبيق يقرأ أقسام shamela.ws كلها ويقارنها بالفهرس، فيعرض الكتب الجديدة ويجلبها من الموقع صفحةً صفحة (`ShamelaWeb.kt`) إلى أن تُضاف إلى الحزمة.

كل ذلك بلا حساب ولا كلمة سر في التطبيق: الملفات على الخادم منشورة بروابط مشاركة عامة (Synology DSM) مذكورة في `manifest.json`.

### ملف الوصف manifest.json

الرابط الدائم: `https://files.murabbie.org/fsdownload/FnGtHqpG9/manifest.json` (مضمَّن أيضًا في `BuildConfig.DEFAULT_MANIFEST_URL` ونسخة منه في `assets/manifest.json`). يحوي:

- `app`: `version_code`, `version_name`, روابط APK, `notes` (للتحديث الذاتي في نكهة nas).
- `data.version`: إصدار البيانات (حاليًا `1448-2`)، `data.master` (الفهرس)، `data.packs["books.pack"]`، `data.services`، ولكلٍّ `url` و`size` و`sha256`.

### الخادم (NAS)

Synology DSM؛ المجلد `/downloads/shamela`؛ الواجهة `https://files.murabbie.org` (HTTPS فقط؛ أُزيل البديل `http://nas.fitrahmedia.nl:5000` لأنه يتيح تزوير manifest على الشبكة، وهو العنوان نفسه فلا يضيف توفّرًا). روابط المشاركة على مسار `/fsdownload/<id>/<name>` وتحتاج قبلها إلى زيارة `/sharing/<id>` لأخذ كعكة `sharing_sid`؛ **ويجب أن يكون الطلبان على اتصال TCP واحد** وإلا ردّ الخادم 404 متقطعًا (عاملا DSM مختلفان). `NasHttp.kt` يفعل ذلك (عميل OkHttp مخصَّص لكل عملية مع مخزن كعكات في الذاكرة). لا تغيّر هذا السلوك.

الرفع إلى الخادم يتم من `tools/publish.py` عبر DSM WebAPI بحساب رفع (يُطلب من صاحب المشروع؛ ليس في التطبيق ولا في هذه الحزمة).

---

## ٣. بناء التطبيق

المتطلبات: Android Studio (Ladybug أو أحدث)، JDK 17، Android SDK 36 (AGP 8.9.3)، Gradle 8.14 (المشروع بلا wrapper؛ Android Studio يستعمل نسخته أو ثبّت gradle).

1. افتح `android/` في Android Studio.
2. انسخ `signing/shamela-release.keystore` إلى جذر `android/` وانسخ `signing/keystore.properties` إلى جذر `android/` (المسار فيه `../shamela-release.keystore` نسبةً إلى مجلد app — أو عدّل `storeFile`).
3. النكهات (`productFlavors`, البُعد `dist`):
   - `nas`: التوزيع المباشر بـ APK من الخادم؛ فيها إذن `REQUEST_INSTALL_PACKAGES` وزر «تنزيل الإصدار الجديد».
   - `play`: نسخة Google Play؛ بلا هذا الإذن وبلا التحديث الذاتي (`BuildConfig.PLAY_STORE = true`).
4. الأوامر:
   - APK للتوزيع المباشر: `gradle assembleNasRelease` → `app/build/outputs/apk/nas/release/`
   - حزمة Google Play: `gradle bundlePlayRelease` → `app/build/outputs/bundle/playRelease/app-play-release.aab`
   - شغّل أمرَي `assemble` و`bundle` في استدعاءين منفصلين: تقسيم المعماريات (`splits.abi`) يُعطَّل تلقائيًا متى طُلبت مهمة `bundle` (الحزمة تقسّم بنفسها، والجمع بينهما يُفشل AGP)، فالجمع في استدعاء واحد يُنتج APK واحدًا لنكهة nas بلا ملفات arm64/arm32/universal التي ينتظرها `publish.py app`.
5. التوقيع تلقائي من `keystore.properties` (release). `minifyEnabled=true` مع R8؛ قواعد ProGuard في `app/proguard-rules.pro`.

رقم الإصدار في `app/build.gradle.kts` (`versionCode`, `versionName`) — زده مع كل نشر.

---

## ٤. خريطة الشيفرة (`app/src/main/java/org/murabbie/shamela`)

| الملف | الوظيفة |
|---|---|
| `App.kt` | Application: ينشئ `Settings`, `Library`, `UserDb`, `Downloads`, `NewBooks`, `Services` |
| `data/NasHttp.kt` | الاتصال بالخادم (تهيئة + تنزيل على اتصال واحد، مدى Range، تناوب المضيفين، سجل تشخيص يُعرض على الشاشة) |
| `data/Library.kt` | الفهرس master.db: أقسام/مؤلفون/كتب/بحث بالأسماء؛ تثبيت الفهرس المضمَّن؛ جلب manifest |
| `data/Models.kt` | النماذج: Book, Author, Category, Page, Title, Manifest… |
| `data/BookStore.kt` | `BookReader`: قراءة صفحات الكتاب وفهرسه والبحث FTS5 فيه؛ مخزن LRU للقواعد المفتوحة |
| `data/Downloads.kt` | طابور التنزيل بعمّال متعددين، خدمة أمامية، جلب فوري عند القراءة (`ensureInstalled`)، تنزيل المكتبة كلها |
| `data/Search.kt` | البحث في الكتب المثبَّتة (كل الكلمات / أي كلمة / عبارة / بادئات / الهوامش / العناوين / **بالجذر**) |
| `data/ArabicText.kt` | التطبيع العربي (يطابق `tools/shamela_common.py` و`extractor/Norm.java` — **لا يُعدَّل أحدها دون الآخرين**) |
| `data/ShamelaWeb.kt` | جلب كتاب من shamela.ws (ajax/pageContent, ajax/titlechilds) وبناء قاعدة له بالصيغة نفسها |
| `data/NewBooks.kt` | فحص أقسام الموقع الأربعين ومقارنتها بالفهرس (المستجدات) |
| `data/Services.kt` | قاعدة الخدمات: رواة، تخريج، أسانيد، قرآن/تفسير، جذور |
| `data/Scholars.kt` | الأقسام الخاصة بالأعلام (ابن تيمية 54، ابن القيم 14، الألباني 414، ابن باز 67، ابن عثيمين 57) — تُعرض بعد قسم 19 |
| `data/Exporter.kt` | الإخراج إلى Word (OOXML مباشر) وPDF (`PdfDocument`) |
| `data/UserDb.kt` | بيانات المستخدم: علامات، تعليقات، تاريخ القراءة، مفضلة، كتب إضافية من الموقع |
| `data/Settings.kt` | الإعدادات (SharedPreferences) |
| `ui/AppRoot.kt` | التنقل (Navigation Compose) والشريط السفلي |
| `ui/PageRenderer.kt` | تحويل HTML الصفحة إلى AnnotatedString (عناوين، آيات، روابط الرواة `inr://man-N`) |
| `ui/screens/*` | الشاشات: المكتبة، القسم، المؤلف، العَلَم، الكتاب، القارئ، البحث، التنزيلات، المستجدات، الخدمات، القرآن، الرواة، الإخراج، الإعدادات |

### صيغة قاعدة الكتاب (SQLite)

- `page(id, part, page, number, body, foot, services)` — `body` HTML مبسَّط (أسطر بـ `\r`، وسوم span بـ data-type، روابط `<a href="inr://man-N">` للرواة)، `services` JSON: `{"hadeeth":[..],"esnad":[..]}` أو `{"ayat":[..]}`.
- `title(id, page, parent, text)`؛ `page_fts(body, foot)` و`title_fts(text)` FTS5 بلا محتوى على نص مطبَّع؛ `meta(key, value)`.

---

## ٥. خط أنابيب البيانات (`tools/` و`extractor/`)

يلزم فقط عند تحديث المكتبة من حزمة رسمية جديدة أو إضافة كتب. الترتيب:

1. `zipfetch.py` — يجلب من الحزمة الرسمية (`shamela-database-1448.zip`، ١٣٫٣ غ.ب) الملفات اللازمة فقط بطلبات مدى (فهرس `ents.pkl`).
2. `extractor` (Java، lucene-core 10.5.1) — يقرأ نصوص الصفحات من فهرس Lucene (`store/page`, `store/title`) ويبني قاعدة كل كتاب ويجمعها في حزم؛ `--packSize` للحجم؛ يكتب `index.json`.
3. `make_master.py` — يبني `master.db` من الجداول الرسمية + `book_file`, `book_norm`, `author_norm`.
4. `decode_s1.py` + `build_services.py` — قاعدة الخدمات من `database/service/*` و`store/esnad` و`store/aya` (يحتاج `Dump.java` لطباعة فهارس Lucene بصيغة JSONL). جدول فك ترميز تراجم الرواة في `s1_table.json`.
5. `publish.py` — الرفع إلى NAS وإنشاء روابط المشاركة وتحديث `manifest.json`: `publish.py data <out> <master.db> --version X`، `publish.py services <services.db>`، `publish.py app <apkdir> <code> <name> --notes "..."`.
6. `update_from_web.py` — يجلب مستجدات الموقع ويبني حزمة تحديث.

عند تعديل قاعدة التطبيع لا بد من تعديلها في المواضع الثلاثة معًا وإعادة بناء فهارس FTS.

---

## ٦. النشر على Google Play — خطوات

1. **حساب مطوّر** على Play Console (رسم لمرة واحدة) وإنشاء تطبيق جديد، اللغة الافتراضية: العربية. **أُرسل للمراجعة** في ٢٦ سبتمبر ٢٠٢٦ باسم «المكتبة الشاملة — قارئ مستقل» من حساب Stichting Tarbiyah Consultancy؛ لا تستعمل «المكتبة الشاملة» وحدها فهو عنوان التطبيق الرسمي (com.arabdt.shamla) وقد يُرفض التطبيق بسياسة انتحال الهوية. الخطوات المجرَّبة كاملة: `~/Development/PLAY-STORE-PUBLISHING-GUIDE.md`.
2. **التوقيع**: عند أول رفع فعّل *Play App Signing* وارفع `app-play-release.aab` الموقَّع بمفتاح `signing/`. احفظ المفتاح: هو مفتاح الرفع (upload key) لكل الإصدارات اللاحقة.
3. **الملف المرفوع**: دائمًا AAB من نكهة `play` (`gradle bundlePlayRelease`). لا ترفع نكهة `nas` (إذن تثبيت الحزم مرفوض في المتجر بلا مسوّغ).
4. **قائمة المتجر**: النصوص في `play-store/listing.md`؛ يلزم أيقونة ٥١٢×٥١٢، صورة مميزة ١٠٢٤×٥٠٠، و٢–٨ لقطات شاشة للهاتف (خذها من التطبيق: المكتبة، القارئ، البحث، الخدمات).
5. **سياسة الخصوصية**: منشورة على https://opvoedconsult.com/shamela/privacy.html (المصدر `play-store/privacy/index.html`، والنص في `play-store/privacy-policy.md`). الرابط مكتوب أيضًا داخل التطبيق في `MoreScreens.kt` (بند «سياسة الخصوصية»)؛ يشترط المتجر وجوده في التطبيق وفي صفحة المتجر معًا، فإن نُقلت الصفحة فغيّر الموضعين وPlay Console.
6. **سلامة البيانات (Data safety)**: الإجابات في `play-store/data-safety.md` (التطبيق لا يجمع ولا يشارك بيانات شخصية؛ الاتصال بالخادم لجلب الكتب فقط).
7. **تصنيف المحتوى** (IARC): استبيان بسيط — تطبيق كتب/مرجع، لا محتوى حساسًا.
8. **الفئة**: Books & Reference. **الجمهور**: ١٣+ (لا يستهدف الأطفال).
9. **الاختبار**: أنشئ مسار اختبار داخلي أولًا، ثبّت من الرابط على جهاز حقيقي، وتحقق من: الفتح الأول بلا شبكة، فتح كتاب (يُجلب من الخادم)، البحث، تنزيل قاعدة الخدمات، الإخراج إلى Word.
10. **الإنتاج**: ارفع إلى Production. أول مراجعة قد تستغرق أيامًا.

### ملاحظات امتثال

- الأذونات في نكهة play: `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (للتنزيل في الخلفية؛ في نموذج الإعلان عن الخدمات الأمامية اختر «data sync: تنزيل ملفات يطلبها المستخدم» وأرفق فيديو قصيرًا إن طُلب)، `POST_NOTIFICATIONS`.
- targetSdk = 36 (يشترط المتجر ٣٦ فأعلى للتطبيقات الجديدة والتحديثات منذ ٣١ أغسطس ٢٠٢٦).
- المحتوى: نصوص الكتب من المكتبة الشاملة (مشروع مفتوح). اذكر ذلك في وصف المتجر وفي «عن التطبيق».
- التطبيق يشغّل خدمة أمامية للتنزيل بإشعار؛ لا إعلانات ولا مشتريات.

---

## ٧. ما يجب أن يعرفه المبرمج التالي (قرارات ودروس)

- **لا حساب في التطبيق**؛ كل ما على الخادم عام بروابط مشاركة. لا تُدخل أي بيانات اعتماد في الشيفرة.
- **ملف كتب واحد** (`books.pack`)؛ أي كتاب يُنزَّل بطلب مدى. عند إضافة كتب: أبنِ حزمة جديدة أو ألحق بالحزمة وحدّث `book_file` في الفهرس وارفع فهرسًا جديدًا (`data.version` جديد) — التطبيق يعرض «تحديث الفهرس».
- `NasHttp.fetchText` يرفض ردود HTML على **روابط المشاركة فقط** (صفحة خطأ DSM)؛ صفحات shamela.ws HTML طبيعي. لا تُعمّم الفحص (كانت علة سابقة).
- التطبيق يختار الأحدث بين manifest المخزون والمضمَّن بحقل `generated`؛ عند تغيير manifest على الخادم انسخ نسخته إلى `assets/manifest.json` قبل بناء الإصدار.
- الاختبار الصحيح للاتصال: `jvmtest/` (يشغّل `NasHttp` و`ShamelaWeb` أنفسهما على JVM) — `gradle run` بعد ضبط `mainClass` (`NasTestKt` أو `WebTestKt`).
- «شروح الحديث» (ربط الحديث بشروحه) غير موجودة في بيانات الحزمة الرسمية؛ ما هو موجود: التخريج في الكتب العشرة والأسانيد والرواة والتفسير والجذور.
- الأقسام الخاصة بالأعلام تُعرَّف في `Scholars.kt` (سطر لكل عَلَم) وتظهر في قائمة الأقسام بعد «مسائل فقهية».

### مقترحات للتطوير

- مزامنة العلامات والتعليقات (اختياري) عبر حساب.
- عرض الصفحات المصوَّرة (PDF) للكتب التي لها روابط في `book.pdf_links`.
- بحث عبر الكتب غير المثبَّتة عن طريق فهرس FTS عام على الخادم.
- تحميل الخطوط العربية (مثل Amiri/Scheherazade) داخل التطبيق لتحسين القراءة والـ PDF.

---

## ٨. جهات الاتصال والمسؤوليات

- صاحب المشروع: صُهَيب سلام — يملك مفتاح التوقيع وحساب الخادم وحساب Play.
- كل ما يخص الرفع إلى الخادم (حساب DSM) يُطلب منه ولا يُخزَّن في المشروع.
