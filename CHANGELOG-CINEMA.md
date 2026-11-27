# Local Cinema release notes

## Local Cinema v1.0.0 (`versionCode = 20`, 2026-11-27)

### English

#### TV — Stremio add-ons and Live TV

Expanded direct playback for Live TV and channels from Stremio (tv/channel). The player forwards provider-required headers and follows redirects between HTTP and HTTPS. Catalog lookups connect Kitsu, TMDB, and IMDb identifiers (kitsu:, tmdb:, and tt). Seven new curated add-ons are available, and Add-on Settings brings installed add-ons and their logos together in one list.

#### TV — Family Viewing and Skip Ads

Turn on Family Viewing and Skip Ads from Settings or the player. The app checks multiple sources for matching content or ad markers and shows a brief on-screen notice when a rule is applied. Availability depends on the title and source.

#### TV — Continue Watching and persistent watched marks

Continue Watching updates on screen immediately when an item is removed or changed, and a removed item stays off the row unless playback resumes. Replaying a completed title keeps its gold checkmark at the top-left of the poster. Series with no known episodes are no longer shown as watched, and missing posters are fetched automatically.

#### TV — Faster Home, redesigned menus, and network sources

Return to Home without waiting for a full library reload, including in libraries of 150 or more films, with focus and scroll position restored. Home shows Continue Watching and indexed library folders. Context and sort menus use the dark-and-gold design. Browse SMB, UPnP, and WebDAV folders directly from Network & Sources, use Delete from Source in media details, and find language and audio preferences in their own sections.

#### TV and Phone — Unified subtitles and live appearance

Subtitles use a rounded background fitted to the text. Live controls include nine text colors, five outline colors, background opacity from 0% to 100%, and a translucent timing overlay over the video. Smart subtitle search includes the dedicated USS and SubSense add-ons and filters out thumbnail tracks marked #xywh=.

#### Phone — Video and HLS downloads and browser improvements

A gold download button sits beside each supported detected stream quality. Save MP4 and HLS (.m3u8) streams to Local Cinema/Download with a live progress notification, including HLS sources that require request headers or AES-128 decryption. The browser filters misleading ad titles to recover the actual film or episode name, scrolls without pull-to-refresh, and offers a redesigned icon menu with a Desktop Mode switch. The RSS Feeds button is now in RSS Settings.

#### Phone — Libraries, Trakt pairing, and cloud backup

Libraries opens the phone’s Anime, Download, Movies, and TV Shows folders directly. The Trakt account card sends the pairing information to the TV. Backup & Restore Options uses dark-and-gold cards and includes confirmed permanent deletion of Google Drive backups. Background backup sync and restore compare settings versions to protect newer TV settings, and a combined backup can use the last saved TV snapshot if a live snapshot is unavailable.

<div dir="rtl" align="right">

### العربية

#### التلفاز — إضافات Stremio والبث المباشر

توسّع دعم التشغيل المباشر لقنوات Stremio والبث الحي (tv/channel)، مع تمرير الترويسات التي تطلبها المصادر واتباع التحويلات بين HTTP وHTTPS. أصبح البحث يربط معرّفات Kitsu وTMDB وIMDb ‏(kitsu: وtmdb: وtt). أُضيفت سبع إضافات مقترحة، وتجمع «إعدادات الإضافات» الإضافات المثبّتة وشعاراتها في قائمة واحدة.

#### التلفاز — المشاهدة العائلية وتخطّي الإعلانات

يمكن تفعيل «المشاهدة العائلية» و«تخطّي الإعلانات» من الإعدادات أو المشغّل. يبحث التطبيق في عدة مصادر عن قواعد المحتوى وعلامات الإعلانات المطابقة، ويعرض إشعاراً خفيفاً عند تطبيقها. يتوقّف التوفّر على العنوان والمصدر.

#### التلفاز — متابعة المشاهدة وثبات علامة المشاهدة

يتحدّث صف «متابعة المشاهدة» على الشاشة فور حذف عنصر أو تغيير حالته، ويظل العنصر المحذوف خارج الصف حتى استئناف تشغيله. تبقى علامة الصح الذهبية في الركن الأعلى الأيسر من البوستر عند إعادة مشاهدة عمل مكتمل. لا تظهر المسلسلات بلا حلقات معروفة كمشاهَدة، وتُجلب البوسترات الناقصة تلقائياً.

#### التلفاز — عودة أسرع إلى الرئيسية وقوائم ومركز شبكة محدّثة

تعود إلى الرئيسية من دون انتظار إعادة تحميل المكتبة كاملةً، حتى في مكتبات تضم 150 فيلماً أو أكثر، مع استعادة موضع التركيز والتمرير. تعرض الرئيسية «متابعة المشاهدة» ومجلدات المكتبة المفهرسة. أُعيد تصميم قوائم السياق والفرز بالهوية الداكنة والذهبية. يمكنك تصفّح مجلدات SMB وUPnP وWebDAV مباشرةً من «الشبكة والمصادر»، واستخدام «الحذف من المصدر» من تفاصيل الوسائط، والوصول إلى إعدادات اللغة والصوت في أقسامها المخصّصة.

#### التلفاز والهاتف — الترجمة الموحدة والمظهر الحي

أصبحت خلفية الترجمة صندوقاً مستديراً يحيط بسطور النص. تشمل إعدادات المظهر الحي تسعة ألوان للنص وخمسة ألوان للحدود وشفافية من 0% إلى 100% وشريط توقيت شبه شفاف فوق الفيديو. يدعم البحث الذكي إضافتَي USS وSubSense، ويستبعد مسارات الصور المصغّرة التي تحمل ‎#xywh=‎.

#### الهاتف — تنزيل الفيديو وHLS وتطوير المتصفح

أُضيف زر تنزيل ذهبي بجانب كل جودة مدعومة يكتشفها المتصفح. تُحفَظ بثوث MP4 وHLS ‏(.m3u8) في Local Cinema/Download مع إشعار مباشر بالتقدّم، بما في ذلك مصادر HLS التي تتطلّب ترويسات الطلب أو فك تشفير AES-128. يستبعد المتصفح عناوين الإعلانات المموّهة لاستخراج اسم الفيلم أو الحلقة، ويدعم التمرير من دون السحب للتحديث، ويعرض قائمة أيقونات جديدة مع مفتاح وضع سطح المكتب. انتقل زر خلاصات RSS إلى إعدادات RSS.

#### الهاتف — المكتبات وربط Trakt والنسخ الاحتياطي السحابي

تفتح «المكتبات» مجلدات Anime وDownload وMovies وTV Shows على الهاتف مباشرةً. ترسل بطاقة حساب Trakt بيانات الربط إلى التلفاز. تعرض نافذة «خيارات النسخ الاحتياطي والاستعادة» بطاقات داكنة وذهبية وتتيح الحذف النهائي للنسخ من Google Drive بعد التأكيد. تقارن المزامنة في الخلفية والاستعادة إصدارات الإعدادات لحماية إعدادات التلفاز الأحدث، ويمكن للنسخة المشتركة استخدام آخر لقطة محفوظة للتلفاز إذا تعذّرت القراءة الحية.

</div>

## Local Cinema v1.0.0-beta.17 (`versionCode = 17`, 2026-11-23)

### English

#### TV

- Upgraded TV Live with automatic initial focus on the first channel card, clean bilingual categories split from compound M3U groups and saved between visits, merged Favorite Countries channels with a searchable world country picker, and multi-country Nexonic IPTV alongside iptv-org.
- Redesigned Add-ons Settings with high-contrast gold checkboxes, a dedicated Manage Installed Add-ons view instead of an inline card grid, and per-add-on settings inside individual add-ons with instant two-way sync.
- Played live add-on channels directly without the torrent source picker, forwarded full proxy headers, added automatic HLS and MPEG-TS fallback plus alternate stream failover for FayaTV and Persian Stremio, filtered non-playable headers, and automatically cleaned up the paywalled Best Torrents add-on.
- Added an ordered Audio Language Priority list (1, 2, 3...) applied sequentially across Movies and Series, unified Movie and Series details buttons with Language Priority inside Audio Tracks and Trailer next to Mark as Watched, and removed the side audio specs label and redundant action buttons.
- Reorganized Main Settings into clean non-duplicate sections, enabled All Files Access (`MANAGE_EXTERNAL_STORAGE`) for File Browser deletion and moving with a Permissions Wizard card, fixed the licenses link, and prepared OTA updates for future repository migration.

#### Phone

- Updated shared Stremio add-on parsing to forward full request proxy headers, filter non-playable entries, and automatically clean up retired broken add-ons.
- Kept Live TV preferences compatible with Favorite Countries merged mode, fixed the third-party licenses link, and prepared the OTA updater allowlist for upcoming repository migration.

Both apps are open source and free. [Source for these APKs](https://github.com/alnouri96/local-cinema/tree/v1.0.0-beta.17) · [Third-party licenses](https://github.com/alnouri96/local-cinema/blob/v1.0.0-beta.17/docs/THIRD_PARTY.md).

<div dir="rtl" align="right">

### العربية

#### التلفاز

- يطوّر التحديث شاشة البث التلفزيوني المباشر بتركيز أولي تلقائي على أول بطاقة قناة، وتفكيك التصنيفات المركّبة وتعريبها بالكامل مع حفظ آخر تصنيف مختار، ودمج قنوات جميع الدول المفضلة في قائمة واحدة مع منتقي دول عالمي مزوّد بشريط بحث، وترقية مصدر Nexonic IPTV ليعمل عالمياً بجانب iptv-org.
- يعيد التحديث تنظيم إعدادات الإضافات بمربعات اختيار ذهبية عالية التباين وزر مستقل لإدارة الإضافات المثبتة، مع فصل الإعدادات الخاصة بكل إضافة داخل صفحتها وربطها بمزامنة فورية ثنائية الاتجاه.
- يشغّل التطبيق قنوات الإضافات المباشرة فوراً دون نافذة مصادر التورنت، ويمرّر جميع ترويسات البروكسي، ويبدّل تلقائياً بين صيغتي HLS وMPEG-TS مع انتقال تلقائي للروابط البديلة في FayaTV وPersian Stremio وتنظيف تلقائي لإضافة Best Torrents المغلقة.
- يضيف التحديث قائمة أولوية لغات صوت مرتّبة بالتسلسل (1، 2، 3...) تُطبَّق تلقائياً في الأفلام والمسلسلات، ويوحّد أزرار تفاصيل الأفلام والمسلسلات مع نقل «أولوية اللغات» إلى داخل «مسارات الصوت» ووضع «الإعلان» بجانب «تحديد كمشاهد» وإزالة النصوص والأزرار الزائدة.
- يقسّم التحديث الإعدادات الرئيسية إلى مجموعات واضحة بلا تكرار، ويمكّن صلاحية إدارة جميع الملفات لحذف ونقل الملفات من مستعرض الملفات مع بطاقة تفعيل في معالج الصلاحيات، ويجهّز التحديث التلقائي للمرحلة القادمة.

#### الهاتف

- يحدّث التطبيق وحدة الإضافات المشتركة لتمرير ترويسات البروكسي الكاملة وتنقية العناصر غير القابلة للتشغيل وتنظيف الإضافات المعطّلة تلقائياً.
- يحافظ التطبيق على توافق تفضيلات البث المباشر مع وضع الدول المفضلة المدمج، ويصلح رابط تراخيص الجهات الخارجية، ويجهّز قائمة التحديث التلقائي للمرحلة القادمة.

التطبيقان مفتوحا المصدر ومجانيان بالكامل. [الكود المطابق للحزمتين](https://github.com/alnouri96/local-cinema/tree/v1.0.0-beta.17) · [تراخيص الجهات الخارجية](https://github.com/alnouri96/local-cinema/blob/v1.0.0-beta.17/docs/THIRD_PARTY.md).

</div>

## Local Cinema v1.0.0-beta.16 (`versionCode = 16`, 2026-11-23)

### English

#### TV

- Redesigned Add-ons as a square six-column grid with official logos, custom ordering, and a top settings button. Added Persian Stremio, separated TR TV & Mov, fixed FayaTV, and displayed channel icons in a 1:1 square layout.
- Added the Art and Screensaver section. Art Mode includes five museum paintings, resizable mat frames, color filters, an optional clock, and pixel shift protection. YouTube 4K Screensaver adds a category dropdown, built-in search, and looping playback in SmartTube or the built-in player.
- Unified File Browser to manage video, image, and subtitle files in one place. Long-press opens select, copy, move, paste, and delete actions. Art Mode and subtitle picking now open this browser directly.
- Updated movie and series details screens by removing the intermediate Watch Now and Trailer dialog and adding audio language priority, quick audio and trailer buttons, full season titles, and unclipped focus rings. Enabled multi-source subtitle search across OpenSubtitles v3, Wyzie, and Subsource with automatic query pre-fill.
- Sped up torrent playback startup and showed live peer counts and download speed. Removed the exit confirmation dialog on Back, hid seek controls during live channels, removed the 10-second seek buttons from the player bar, and focused TV Live on Favorite Countries.

#### Phone

- Synced TV Live favorite countries automatically from the TV and included them in Google Drive cloud backups.
- Improved initial peer connection settings for the torrent engine and removed duplicate bottom banners after cloud backup uploads finish.

Both apps are open source and free. [Source for these APKs](https://github.com/alnouri96/local-cinema/tree/v1.0.0-beta.16) · [Third-party licenses](https://github.com/alnouri96/local-cinema/blob/v1.0.0-beta.16/docs/THIRD_PARTY.md).

<div dir="rtl" align="right">

### العربية

#### التلفاز

- يعيد قسم الإضافات عرض البطاقات في شبكة مربعة من 6 بطاقات في الصف بشعارات رسمية مع إمكانية ترتيب الإضافات ونقل زر الضبط للأعلى. يضيف التطبيق Persian Stremio ويفصل TR TV & Mov ويصلح FayaTV ويعرض أيقونات القنوات بنسبة مربعة 1:1.
- يضيف التطبيق قسم الفن وشاشة التوقف. يضم وضع الفن خمس لوحات متحفية مدمجة وإطارات ورقية قابلة لتغيير الحجم وفلاتر لونية وساعة اختيارية وحماية بكسل. توفر شاشة توقف يوتيوب 4K قائمة تصنيفات منسدلة وبحثًا مدمجًا وتشغيلاً متكررًا عبر SmartTube أو المشغّل الداخلي.
- يوحّد مستعرض الملفات تصفح ملفات الفيديو والصور والترجمات في مكان واحد. تفتح قائمة الضغط المطوّل خيارات التحديد والنسخ والنقل واللصق والحذف. يرتبط وضع الفن واختيار الترجمات مباشرة بهذا المستعرض.
- تحدّث شاشة تفاصيل الأفلام والمسلسلات بتصميم مضغوط يزيل نافذة «مشاهدة الآن / الإعلان» ويعرض أزرار الصوت والإعلان وأولوية اللغات. يصلح التحديث قصّ إطارات التركيز وأسماء المواسم، ويفعّل البحث متعدد المصادر للترجمات عبر OpenSubtitles v3 وWyzie وSubsource بالتعبئة التلقائية.
- يسرّع المشغّل بدء بث التورنت ويعرض عدد الموزعين وسرعة التحميل الحية. يلغي التحديث نافذة تأكيد الإيقاف عند الضغط على رجوع، ويخفي التقديم والترجيع في البث المباشر، ويزيل أزرار 10 ثوانٍ من شريط المشغّل، ويبقي قسم البث التلفزيوني على الدول المفضلة فقط.

#### الهاتف

- يزامن الهاتف الدول المفضلة للبث التلفزيوني تلقائيًا من التلفاز ويحفظها ضمن النسخة الاحتياطية السحابية على Google Drive.
- يحسّن التطبيق إعدادات الاتصال الأولي لمحرك التورنت ويزيل التنبيهات السفلية المكررة عند اكتمال رفع النسخة السحابية.

التطبيقان مفتوحا المصدر ومجانيان بالكامل. [الكود المطابق للحزمتين](https://github.com/alnouri96/local-cinema/tree/v1.0.0-beta.16) · [تراخيص الجهات الخارجية](https://github.com/alnouri96/local-cinema/blob/v1.0.0-beta.16/docs/THIRD_PARTY.md).

</div>

## Local Cinema v1.0.0-beta.15 (`versionCode = 15`, 2026-11-22)

### English

#### TV

- Install Stremio stream and catalog add-ons. Enable or disable them individually or in groups. The source picker combines results from enabled add-ons and shows the provider, language, quality, file size, and seed count.
- Browse movies and series in TR TV&Mov. Pick an episode or movie to open its source list. Watch official trailers on YouTube and use automatic dual subtitles when matching tracks are available and dual subtitles are enabled.
- TV Live opens with Iraq by default. Search for a country by name or first letter. Favorite countries appear near the top of the picker. Favorite channels appear first in the channel list. Refresh the Iraqi Nexonic playlist automatically or on demand.
- Open IPTV separately from TV Live. Add M3U and M3U8 playlists or Xtream Codes. Enable the Arabic and open channel lists in settings. The app caches channel logos and keeps channel favorites.
- Choose a preferred audio language in Settings. The player selects a matching track when it is available.
- The torrent player prioritizes the first pieces and the media index before playback. Press Back during a live channel to return to the channel list.
- Add-on settings use larger cards and show the active count and per-provider status. You can exclude add-on streams from Continue Watching.
- Open the source code and third-party licenses from Settings. Help and support links open the Local Cinema site.

#### Phone

- Add Stremio stream and catalog add-ons from TV Hub or open a stremio:// install link. Expand or collapse External Add-ons in Connection. Changes to add-ons sync to the paired TV.
- Use the floating TV remote across tabs and during live channels. Swipe down on its header to close the remote panel.
- Movie posters and music covers are larger. Use music settings to show or hide track information on the TV and set how long it stays visible.
- Open RSS from the browser toolbar and Download Center from the Downloader tab. Use Sync Now in TV Hub to send supported settings and channel favorites.
- Fixed a GeckoView startup crash in the optimized release build.
- About shows the Local Cinema Manager logo and credits MEEDX. It also links to the source code and third-party licenses.

Both apps are open source and free. [Source for these APKs](https://github.com/alnouri96/local-cinema/tree/v1.0.0-beta.15) · [Third-party licenses](https://github.com/alnouri96/local-cinema/blob/v1.0.0-beta.15/docs/THIRD_PARTY.md).

<div dir="rtl" align="right">

### العربية

#### التلفاز

- ثبّت إضافات Stremio للبث والفهارس. فعّلها أو عطّلها بشكل فردي أو جماعي. تجمع قائمة المصادر نتائج الإضافات المفعّلة وتعرض اسم المزوّد واللغة والجودة والحجم وعدد الباذرين.
- تصفّح الأفلام والمسلسلات في TR TV&Mov. اختر فيلمًا أو حلقة لعرض المصادر. شاهد الإعلانات الرسمية على يوتيوب واستخدم الترجمة المزدوجة التلقائية عند تفعيلها وتوفر المسارات المطابقة.
- يفتح قسم TV Live على العراق افتراضيًا. ابحث عن الدولة باسمها أو بحرفها الأول. تظهر الدول المفضلة قرب أعلى منتقي الدول. تظهر القنوات المفضلة أولاً في قائمة القنوات. حدّث قائمة Nexonic العراقية تلقائيًا أو عند الطلب.
- افتح قسم IPTV مستقلاً عن البث المباشر. أضف قوائم M3U وM3U8 أو بيانات Xtream Codes. فعّل قوائم القنوات العربية والمفتوحة من الإعدادات. يحفظ التطبيق الشعارات مؤقتًا ويحتفظ بالقنوات المفضلة.
- اختر لغة الصوت المفضلة من الإعدادات. يستخدم المشغّل المسار المطابق عند توفره.
- يعطي مشغّل التورنت الأولوية للقطع الأولى وفهرس الفيديو قبل التشغيل. اضغط رجوع أثناء البث المباشر للعودة إلى قائمة القنوات.
- تعرض إعدادات الإضافات بطاقات أكبر وعدد الإضافات المفعّلة وحالة كل مزوّد. يمكنك استبعاد مشاهدات الإضافات من «متابعة المشاهدة».
- افتح الكود المصدري وتراخيص الجهات الخارجية من الإعدادات. تفتح روابط المساعدة والدعم موقع Local Cinema.

#### الهاتف

- أضف إضافات Stremio للبث والفهارس من TV Hub أو افتح رابط تثبيت stremio://. اطوِ قائمة الإضافات الخارجية أو وسّعها داخل تبويب الاتصال. تتزامن التغييرات عند تعديل الإضافات مع التلفاز المقترن.
- استخدم الريموت العائم بين التبويبات وأثناء مشاهدة القنوات المباشرة. اسحب رأس لوحة الريموت للأسفل لإغلاقها.
- تظهر بوسترات الأفلام وأغلفة الموسيقى بحجم أكبر. تحكّم من إعدادات الموسيقى في ظهور معلومات المقطوعة على التلفاز ومدة بقائها.
- افتح خلاصات RSS من شريط المتصفح ومركز التحميل من تبويب «داونلودر». استخدم «المزامنة الآن» في TV Hub لإرسال الإعدادات المدعومة والقنوات المفضلة.
- أُصلح انهيار GeckoView عند بدء تشغيل نسخة الإصدار المحسّنة.
- تعرض نافذة «حول التطبيق» شعار Local Cinema Manager وتنسب التطوير إلى MEEDX. وتربط بالكود المصدري وتراخيص الجهات الخارجية.

التطبيقان مفتوحا المصدر ومجانيان بالكامل. [الكود المطابق للحزمتين](https://github.com/alnouri96/local-cinema/tree/v1.0.0-beta.15) · [تراخيص الجهات الخارجية](https://github.com/alnouri96/local-cinema/blob/v1.0.0-beta.15/docs/THIRD_PARTY.md).

</div>

## Local Cinema v1.0.0-beta.14 (`versionCode = 14`, 2026-11-21)

### English

- Press OK once to play an episode. Hold OK or press Menu to open its options.
- Continue Watching matches titles with their saved progress more reliably and clears entries when a season is complete.
- Turn Skip Ads on or off in playback controls. Family Watch is marked “Coming Soon.”
- Fixed a crash when the TV app receives a cast while in the background.
- The subtitle picker remembers Dual Subs and shows each track on one line. A single control toggles Dual Subs.
- Casting uses one media notification while keeping the CPU and Wi-Fi awake during background playback.
- Stop playback from the Local Media mini-bar or the notification shade. The phone switches from music controls to the active video card when playback starts on the TV.
- Hold Seek or Volume on the floating remote to repeat the action. The connection indicator follows the TV connection across tabs.
- Type Arabic or English directly into TV fields. Backspace and cursor movement work as you type, without a Send button.
- Scroll inside the Subtitles sheet without closing it. TV Hub connection cards hide the Trakt username.
- Video Grabber detects direct video and subtitle links. Support for relaying protected streams and embeds that require access tokens is planned for a future release.

### العربية

- اضغط زر OK مرة واحدة لتشغيل الحلقة. اضغط مطولًا أو استخدم زر القائمة لعرض الخيارات.
- تعرض «متابعة المشاهدة» تقدّم العمل بدقة أكبر وتزيل إدخال الموسم عند اكتماله.
- يمكن تشغيل «تخطي الإعلانات» أو إيقافه من أدوات المشغّل. تظهر «المشاهدة العائلية» بحالة «قريبًا».
- أُصلح انهيار تطبيق التلفاز عند استقبال بث وهو في الخلفية.
- يحفظ منتقي الترجمات إعداد الترجمة المزدوجة ويعرض كل مسار في سطر واحد. يتحكم زر واحد في تفعيلها.
- يعرض الهاتف إشعار وسائط واحدًا للبث ويحافظ على نشاط المعالج واتصال Wi-Fi أثناء التشغيل في الخلفية.
- أوقف التشغيل من شريط الوسائط المصغّر أو درج الإشعارات. ينتقل الهاتف من واجهة الموسيقى إلى بطاقة الفيديو عند بدء التشغيل على التلفاز.
- يكرّر الريموت العائم التقديم والترجيع وتغيير مستوى الصوت عند الضغط المطوّل. يعكس مؤشر الاتصال حالة التلفاز عبر التبويبات.
- تصل الكتابة بالعربية والإنجليزية مباشرة إلى حقل التلفاز. يعمل الحذف وتحريك المؤشر فورًا دون زر إرسال.
- يمكن التمرير داخل نافذة الترجمات دون إغلاقها. تخفي بطاقات الاتصال في TV Hub اسم حساب Trakt.
- تلتقط أداة Video Grabber روابط الفيديو والترجمة المباشرة. دعم تمرير البث المحمي والمشغّلات التي تتطلب رموز وصول مؤجل إلى إصدار لاحق.

## Local Cinema v1.0.0-beta.13 (`versionCode = 13`, 2026-11-20)

### English
- Home builds and sorts library rows dynamically from each direct top-level folder under your SMB share (`Cartoon`, `Movies`, `OTHERS`, `Shows`, and any new folder) using the exact folder name without splitting `Shows` or `Movies` into per-series rows or duplicating rows.
- SMB indexing and deep recursive scanning (`RECURSION_LIMIT = 20`) unify `IP` and `Hostname` paths (`coveredByHost`, `smbRelativeKey`, `canonicalUriKey`) so switching between server IP and computer name never duplicates shortcuts, cards, or episodes.
- Series cards merge duplicate entries that differ only by a year suffix like `(2022)` and combine folder prefix queries with `SCRAPER_SHOW_ID` so all seasons and episodes appear together and refresh automatically when scanning finishes.
- Clicking a season poster card in the `Show` tab switches directly to that season tab and focuses the in-progress episode or `E1` while blocking async metadata callbacks from stealing focus back to top tabs.
- The `Subtitles` action chip hides on the `Show` tab and on the full-season poster card (`Watch season`), and appears only when an individual episode card is focused.
- Returning from a movie or series detail screen keeps posters displayed in `0ms` from a 16MB in-memory bitmap cache and updates watched badges in place with `DiffUtil` instead of clearing adapters.
- SMB browsing and indexing run in strict read-only mode unless you trigger a manual file operation, blocking automatic creation of `Movies` subfolders inside network shares.

### العربية
- تبني الشاشة الرئيسية صفوف المكتبة وترتّبها ديناميكياً من كل مجلد فرعي مباشر تحت المشاركة (`Cartoon` و `Movies` و `OTHERS` و `Shows` وأي مجلد جديد) بنفس اسم المجلد الفعلي دون تقسيم `Shows` أو `Movies` إلى صفوف فرعية أو تكرارها.
- يوحّد الفحص العميق المتكرر لمشاركات SMB (`RECURSION_LIMIT = 20`) المسارات بين عنوان `IP` واسم الجهاز `Hostname` لمنع تكرار المجلدات المفهرسة أو البطاقات أو الحلقات.
- تدمج واجهة المسلسلات البطاقات المزدوجة التي تختلف بوجود سنة الإصدار مثل `(2022)` وتجمع استعلام المجلد مع `SCRAPER_SHOW_ID` لعرض جميع المواسم والحلقات في صفحة واحدة مع تحديث تلقائي فور انتهاء الفحص.
- ينقل الضغط على أي بوستر موسم في تبويب `Show` الواجهة فوراً إلى تبويب ذلك الموسم ويضع التركيز مباشرة على الحلقة قيد المشاهدة أو الحلقة الأولى `E1` مع منع الاستدعاءات الخلفية من سرقة التركيز.
- يختفي زر الترجمة `Subtitles` في تبويب `Show` وعند الوقوف على بطاقة بوستر الموسم الكامل (`Watch season`)، ويظهر فقط عند التركيز على بطاقة حلقة فردية.
- تحافظ العودة من صفحة التفاصيل إلى الشاشة الرئيسية على البوسترات المعروضة في `0ms` عبر كاش ذاكرة بحجم 16MB وتحدّث شارات المشاهدة موضعياً عبر `DiffUtil` دون وميض رمادي.
- تعمل عمليات تصفح وفحص مشاركات `smb://` بوضع القراءة فقط وتمنع إنشاء أي مجلدات `Movies` تلقائياً داخل مشاركات الشبكة.

## Local Cinema v1.0.0-beta.12 (`versionCode = 12`, 2026-11-19)

### English
- Starting playback directly from a season row opens the episode player with no intermediate dialog. Exiting playback restores focus to that episode card.
- Player controls add Previous Episode and Next Episode buttons during series playback. Episode rows hide the trailer button so trailers stay on the show page.
- Subtitle selection restarts the player HUD auto-hide timer so both bars hide cleanly after playback resumes.
- Focusing a season card loads the season trailer first and falls back to the show trailer when absent.
- Continue Watching cards show season and episode badges on posters and start episodes directly on click. Focusing an episode displays the show title, episode title, and remaining time in the top header.
- Player services write exact millisecond positions to local storage every five seconds during playback, after seeks, on pause, and on exit.
- Movies and episodes mark as watched automatically at 85% progress. Reopening a completed item briefly without a seek below 85% keeps it out of Continue Watching.
- Local millisecond resume timestamps stay protected against rounded percentage estimates from Trakt unless remote progress is clearly newer.
- Artwork loading fetches Arabic and English metadata together and caches images in a 200MB HTTP disk cache. Parallel workers decode posters to card size and display fallback artwork with no black cards.
- Settings adds a Storage & Cache page that displays cache usage in megabytes with options to clear posters and metadata cache or all temporary app cache.
- Show, season, and movie screens align cleanly in Arabic RTL layout without revealing the season row below episodes. The Back button closes the sidebar rail, returns to Home top, or focuses the Exit button in the exit confirmation dialog.
- Phone playback groups built-in tracks under one Embedded Subtitles button that appears only when embedded tracks exist.
- Horizontal touchpad swipes on Phone send 10-second or 30-second seek commands with light haptic feedback and cancel long-press hold detection on finger motion.

### العربية
- يبدأ النقر على أي حلقة داخل صف الموسم تشغيلها مباشرة دون شاشة وسيطة، ويعود التركيز إلى بطاقة الحلقة نفسها عند الخروج.
- يضيف مشغل الشاشة زري الحلقة السابقة والحلقة التالية أثناء عرض المسلسلات، بينما يقتصر زر الإعلان على واجهة المسلسل الرئيسية دون صفوف الحلقات.
- يعيد اختيار أي ترجمة تفعيل مؤقت الإخفاء التلقائي لواجهة المشغل حتى يختفي الشريط العلوي وشريط التقدم تلقائياً بعد الاستئناف.
- يجلب التركيز على بطاقة أي موسم إعلان الموسم أولاً، ويعتمد إعلان المسلسل بديلاً تلقائياً عند غياب إعلان خاص بالموسم.
- تعرض بطاقات متابعة المشاهدة شارة الموسم والحلقة وتعمل مباشرة عند النقر، مع إظهار اسم المسلسل واسم الحلقة والوقت المتبقي في الهيدر العلوي.
- يحفظ المشغل موضع التشغيل الدقيق بالملي ثانية على القرص كل خمس ثوانٍ وعند التقديم والترجيع والإيقاف والخروج، ويستأنف التشغيل من الثانية نفسها بدقة.
- تكتمل الأفلام والحلقات تلقائياً عند بلوغ نسبة خمسة وثمانين في المئة، ولا تعود العناصر المكتملة إلى متابعة المشاهدة عند فتحها بالخطأ والخروج دون ترجيع المؤشر.
- يحمي النظام موضع الاستئناف المحلي الدقيق من الاستبدال بنسب Trakt التقريبية ما لم يكن التقدم السحابي أحدث بفارق واضح.
- يجلب كاش الصور بيانات اللغتين العربية والإنجليزية في طلب واحد، ويستخدم مخزناً قرصياً بسعة مئتي ميجابايت مع فك تشفير بأبعاد البطاقات لعرض فوري دون بطاقات سوداء.
- يضيف قسم الإعدادات صفحة التخزين والكاش لعرض الحجم الفعلي بالميجابايت ومسح كاش البوسترات والبيانات أو كاش التطبيق كاملاً دون المساس بسجل المشاهدة.
- أُصلحت محاذاة الواجهة العربية ومنع ظهور صف المواسم أسفل الحلقات عند التصفح يساراً، كما يطوي زر الرجوع القائمة الجانبية أو يعود لأعلى الرئيسية أو يفتح نافذة التأكيد مع تحديد زر الخروج تلقائياً.
- يجمع تطبيق الهاتف الترجمات المدمجة في زر واحد يظهر عند توفرها فقط ويفتح قائمة مستقلة لاختيار الترجمة الأساسية أو المزدوجة.
- يستجيب السحب الأفقي على لوحة اللمس للتقديم والترجيع الفوري بمقدار عشر أو ثلاثين ثانية مع اهتزاز خفيف، ويلغي مؤقت الضغط المطوّل فور تحرك الإصبع.

## Local Cinema v1.0.0-beta.11 (`versionCode = 11`) — 2026-11-19

### English
- Extracted `TvHubMusicController`, `TvHubSubtitlesController`, `TvHubLibraryController`, and `TvHubConnectionController` from `TvHubActivity`.
- Split `CinemaBrowser` into `CinemaBrowserSettingsSheet` and `CinemaBrowserMediaBridge` to separate settings UI from media discovery.
- Set `CinemaBrowser` startup to a `100%` mobile viewport and added Desktop and Mobile mode switching.
- Added a persistent `50%–150%` Page Zoom slider in `CinemaBrowserSettingsSheet` and `discover.js`, alongside the `BrowserExtensions` manager.
- Capped the GeckoView disk cache at `100MB` and purged temporary stream files at startup and after each cast through `CinemaCacheManager`. This reduces installed phone app storage from `1.54GB` to `514MB`.
- Added a `Storage & Cache` section (`StorageCacheHeaderPreference`) in Settings with live metrics and a `Clear Cache` button that keeps logins, bookmarks, and TV pairing.
- Suspended background browser tabs with `GeckoSession.setActive(false)`, tied `playbackPoll` and WebSocket reconnect timers to foreground state, and stopped idle HTTP media servers when no cast is active.
- Updated Primary, Dual, and Off subtitle buttons in `TvHubSubtitlesController` with Gold (`#E5B94C`) and Crimson (`#EF4444`) active fills and borders.
- Assigned positive `900M+` synthetic MediaDB IDs in `CinemaHomeFragment` and `CinemaWatchlistActivity` on TV. Removed duplicate subtitle overlays and restricted Trakt OAuth 2.0 PKCE sync to manual requests or setting changes.

### العربية
- فصل وحدات التحكم `TvHubMusicController` و `TvHubSubtitlesController` و `TvHubLibraryController` و `TvHubConnectionController` عن `TvHubActivity`.
- تقسيم المتصفح `CinemaBrowser` إلى `CinemaBrowserSettingsSheet` لإعدادات العرض و `CinemaBrowserMediaBridge` لاكتشاف الوسائط.
- ضبط متصفح `CinemaBrowser` ليفتح بأبعاد الهاتف الطبيعية بنسبة `100%` مع التبديل المباشر بين وضعي الهاتف وسطح المكتب.
- إضافة شريط تمرير دائم لتكبير الصفحة بنسبة `50%–150%` داخل `CinemaBrowserSettingsSheet` و `discover.js` إلى جانب مدير الإضافات `BrowserExtensions`.
- تحديد سقف الكاش لمحرك `GeckoView` عند `100MB` وحذف ملفات البث والترجمة المؤقتة تلقائياً عند التشغيل وبعد انتهاء البث عبر `CinemaCacheManager`. يقلّص هذا الإجراء حجم التطبيق من `1.54GB` إلى `514MB`.
- إضافة قسم التخزين والكاش (`Storage & Cache`) داخل الإعدادات عبر `StorageCacheHeaderPreference` مع زر `Clear Cache` لتنظيف الملفات المؤقتة مع الحفاظ على الحسابات والمفضلة والاقتران.
- إيقاف نشاط جلسات المتصفح في الخلفية عبر `GeckoSession.setActive(false)`، وربط مؤقتات `playbackPoll` وإعادة اتصال WebSocket بحالة الواجهة، وإغلاق خوادم البث المحلية عند الخمول.
- تلوين أزرار تفعيل الترجمة الأساسية (`Primary`) والمزدوجة (`Dual`) والإيقاف (`Off`) في `TvHubSubtitlesController` باللونين الذهبي (`#E5B94C`) والأحمر القرمزي (`#EF4444`).
- تثبيت معرّفات موجبة (`900M+`) للعناصر السحابية على التلفاز داخل `CinemaHomeFragment` و `CinemaWatchlistActivity`، ومنع تكرار طبقات الترجمة، وقصر مزامنة `Trakt OAuth 2.0 PKCE` على التغيير الفعلي أو الطلب اليدوي.

## User revision v7 — 8 November 2026

- Reorganize the native in-player subtitle appearance panel for TV remotes.
- Add opt-in automatic black/white text contrast from bounded in-memory video
  samples, hysteresis, inverse outline and safe fallback; keep manual palettes.
- Preserve manual colour and styled text when automatic mode is disabled, and
  stop sampling with the activity lifecycle. No frame storage or network service.

## User revision v6 — 8 November 2026

- Merge the details subtitle capsule into the Subtitles action, with embedded
  tracks first, file-folder/Subsource actions, and Left/Right/Back dismissal.
- Make measured audio interactive and place it left of video dimensions; persist
  explicit per-file choices into track selection during playback.
- Use circular film action states and a thicker, larger gold portrait focus ring.
- Add a cinematic native series/seasons browser with real episode stills, episode
  detail links, Watch/Resume and restored focus; refresh playback state on return.

## User revision v5

- Center closed navigation icons and increase drawer opacity; restore 0–9 digits.
- Keep runtime units explicit and use measured file duration with h/m/s display.
- Reveal resolved titles instead of flashing temporary names; circular crew focus.
- Add poster-size manual file-selection cards under `other videos`, with DPAD flow.
- Search Subsource on entry and directly load subtitles for unique verified identities;
  keep ambiguous title selection and explicit download confirmation.
- Consolidate advanced settings beneath System.

## User revision v4

- Split home into Continue Watching, recent movies and recent TV shows; migrate
  existing order once and scroll/focus the target row on vertical DPAD movement.
- Use Arabic-Indic display digits and RTL date while retaining stored values.
- Remove circular idle icon tiles and increase overlay transparency.
- Add an anchored subtitle dropdown beside Trailer with actual folder/Subsource actions.
- Remove provisional calculated technical labels; show measured pixel dimensions,
  reported audio and native format without guessing Main 10/HDR from filenames.

## Soft Black / Gold and provider revision

- Studied the installed Plex 2026.20.0 using actual ADB remote input; recorded
  observed navigation, source-rating presentation and geometry in PLEX_UI_STUDY.md.
- Applied soft black surfaces, selective gold focus, compact five-action rail
  and an expanded menu containing only actual user libraries.
- Added independent provider-id-first matching, cached TMDb film/episode details,
  English artwork priority, certification, cast/crew and actual episode identity.
- Added optional personal-use IMDb dataset download and separately encrypted OMDb
  key settings; source-labelled ratings never substitute one provider for another.
- Verified final build, installation, real TMDb/IMDb results, exact episode matching,
  regression smoke checks and preserved Subsource access on the attached TV.
- See METADATA_PROVIDERS.md for sources, licences and untested live OMDb limits.

- Added an independent Android TV package and provider authorities.
- Replaced the TV home with a real-library cinematic hero, poster rows, western
  clock/date digits, palette shading and a focus-expanding left navigation rail.
- Added persistent home row order/visibility, poster size/density and appearance controls.
- Added cinematic film details, English logos, cast portraits, watchlist and
  playback and watched actions. Available trailers open YouTube.
- Added explicit Subsource Arabic search/download, evidence-based release ranking,
  encrypted key management, TXT import, pagination, limits and retry handling.
- Added safe subtitle ZIP/text processing, adjacent-storage/local fallback,
  per-media bindings, remote-friendly file selection and Arabic track priority.
- Preserved the upstream engine, protocols, scanner and advanced settings.
- Fixed encoded SMB paths used by JCIFS and removed a scanner dependency on a
  nullable global application context.
- Preserved Android's BKS provider while adding supplemental crypto algorithms,
  fixing a reproducible TLS initialization failure on the attached television.
- Added a checksum-verified Windows ARMv7 build helper, device smoke checks,
  standalone subtitle-matching checks and EGM component documentation.

## Supplemental native TV design revision

- Installed project-local UI UX Pro Max and Better Design skills; added DESIGN.md.
- Set English as the development default while retaining selectable Arabic.
- Added native design tokens, vector navigation/action icons, ripple/press states,
  dark rounded dialogs with explicit initial focus, and a detail navigation rail.
- Rebalanced hero, synopsis, action rows and circular cast at 1080p; aligned remote
  row navigation and retained focus after home updates and appearance changes.
- Added source-labelled TMDB ratings, English film/director enrichment and actual
  file-probed video/audio/subtitle-availability capsules with a session cache.
- Applied neutral dark theme aliases to network and advanced screens.

## UI v8 final — 2026-11-08
User approved final delivery: matched subtitle panels, lighter player menu focus transitions, and audio/resolution capsule height parity. See docs/UI-V8-FINAL.md and HANDOFF.md for checks and continuation instructions.

## Subtitle automation preview — 2026-11-08
- Correct pre-play embedded subtitle persistence; stale saved language no longer overrides explicit track choices.
- Automatic Arabic embedded-first selection, then authorized Subsource fallback on workers with identity/release ranking and no-overwrite SMB/private storage.
- Persist provider selection and pin saved Arabic subtitle before other Subsource results.
- See docs/archive/SUBTITLE-AUTO-PREVIEW.md for live tests and limits; pending user review before final packaging.


## Watchlist preview — 2026-11-08
- Replace text-only entries with a native poster grid, selected artwork backdrop and gold focus ring.
- Preserve real saved membership and details return focus; identify show/season/episode for episode entries.
- Add long-OK/Menu contextual actions, loading skeletons and empty/error recovery.
- See docs/archive/WATCHLIST-PREVIEW.md for actual device evidence and untested states. Pending final user review.


## Trailer discovery preview — 2026-11-08
- Add verified trailer action to series pages; inherit the series trailer in episode details opened from Continue Watching.
- Make film/episode Trailer action visibly labelled; retain nearby subtitle action and native focus.
- Reuse English fallback for the same provider identity; continue rejecting unverified videos.
- See docs/archive/TRAILER-PREVIEW.md for live YouTube-link and UI checks. Pending final user review.

## Final v9 — 2026-11-08
User approved final delivery and safe temporary-file cleanup after subtitle automation, Watchlist and trailer review. See docs/FINAL-V9.md and HANDOFF.md. Full source snapshot includes all initialized recursive submodules and existing licences. Rebuild from extracted source succeeded; private runtime state and signing keys are excluded.

Final packaging note: local temporary-file deletion was rejected by automatic execution review (blocked by policy), including the narrower named-file attempt. No deletion was performed; caches/private scratch state are excluded from the final archives. See CLEANUP.json in the delivery.


## Fresh-start hotfix preview — 2026-11-08
- Fix optional IMDb null unboxing that crashed details after clearing data; unavailable rating providers no longer block opening media.
- Only reorder badges that were actually added, including the zero-vote case.
- Restore empty-home navigation to manual file cards and distinguish missing sources from saved folders awaiting matching.
- Actual clean-state library/metadata, movie/episode details and SMB playback checks: docs/archive/FRESH-START-FIX.md. Historical v9 remains unchanged; pending user review before a new final release.


## Final v10 — 2026-11-08
User confirmed the fresh-start repair works and approved final packaging. Fix null unboxing of absent IMDb scores and invalid-badge reordering, with clean-state movie/episode/SMB and optional-rating regression checks. Retain previous subtitle, Watchlist and trailer improvements. Current final delivery: deliverables/final-v10/.
