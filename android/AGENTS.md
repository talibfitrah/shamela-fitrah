# READ FIRST — where the Shamela app's source lives

**For every developer and AI agent (including the DSM account "manus") who changes, builds or publishes this app.**

This folder may be an extracted `shamela-android-project.tar.gz`. That archive is a **read-only snapshot**, regenerated from GitHub on every NAS release. **Do not edit it, build from it, or publish from it.**

The single source of truth is the GitHub repository **https://github.com/talibfitrah/shamela-fitrah** (branch `main`). It holds the app (`android/`), the publish scripts (`tools/`) and the rules (`CLAUDE.md`, `README-DEVELOPER.md`).

1. **Start from GitHub:** `git clone https://github.com/talibfitrah/shamela-fitrah.git`, or `git pull` an existing clone. Work on a branch, commit, push, and merge into `main`. Ask the owner for push access.
2. **Bump the version on every release:** `versionCode` and `versionName` in `android/app/build.gradle.kts`, higher than what the NAS manifest and Google Play already have.
3. **Publish to the NAS only with** `python3 tools/publish.py app <apk_dir> <versionCode> <versionName>`, run from a clean, pushed commit. It refuses anything else, and it uploads this archive from that same commit.
4. **Google Play gets the `play` flavor built from the same commits.** The NAS and Play must never run code that is not on GitHub.
5. **Have work that is not on GitHub?** Push it to a branch first, or hand it to the owner. Never publish it. Builds 1.4.2–1.5.0 were published from a private copy, and the code forked: two different "1.4.2"s exist, and fixes had to be merged back by hand.

---

## اقرأ أولًا — أين يوجد مصدر تطبيق الشاملة

**لكل مطوّر أو وكيل ذكاء اصطناعي (ومنه حساب «manus») يعدّل التطبيق أو يبنيه أو ينشره.**

قد يكون هذا المجلد نسخة مستخرجة من `shamela-android-project.tar.gz`. هذا الأرشيف **لقطة للقراءة فقط**، يُعاد توليده من GitHub مع كل نشر على NAS. **لا تعدّل فيه، ولا تبنِ منه، ولا تنشر منه.**

المصدر الوحيد المعتمد هو مستودع GitHub: **https://github.com/talibfitrah/shamela-fitrah** (الفرع `main`).

١. **ابدأ من GitHub:** انسخ المستودع (`git clone`) أو حدّث نسختك (`git pull`)، واعمل في فرع، ثم التزم وادفع وادمج في `main`. اطلب صلاحية الدفع من صاحب المشروع.
٢. **ارفع رقم الإصدار في كل نشر:** `versionCode` و`versionName` في `android/app/build.gradle.kts`، أعلى مما على NAS وGoogle Play.
٣. **لا تنشر على NAS إلا بـ** `tools/publish.py app` من commit نظيف مدفوع إلى GitHub. الأداة ترفض غير ذلك، وترفع هذا الأرشيف من الـ commit نفسه.
٤. **نسخة Google Play** (نكهة `play`) تُبنى من الـ commits نفسها، فلا يعمل على NAS أو Play كود ليس على GitHub.
٥. **إن كان عندك عمل ليس على GitHub** فادفعه إلى فرع أولًا أو سلّمه لصاحب المشروع، ولا تنشره. الإصدارات من 1.4.2 إلى 1.5.0 نُشرت من نسخة خاصة فتفرّع الكود: صار هناك نسختان مختلفتان تحملان رقم 1.4.2، واحتاجت الإصلاحات دمجًا يدويًّا.
