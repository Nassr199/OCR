@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import com.abdulrahman.hardsubocr.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.abdulrahman.hardsubocr.data.Choice
import com.abdulrahman.hardsubocr.data.EnhanceChoice
import com.abdulrahman.hardsubocr.data.Settings
import com.abdulrahman.hardsubocr.video.VideoEngine
import kotlin.math.roundToInt

/** Settings pages, in the order of the list. */
enum class SettingsPage(val title: String, val emoji: String) {
    READING("القراءة والأداء", "⚡"),
    VIDEO("الفيديو", "🎬"),
    CLEAN("الأسطر والتشكيل", "🧹"),
    MERGE("دمج المقاطع", "🔗"),
    SRT("ملف SRT", "📄"),
    LENS("Google Lens", "🔎"),
    CACHE("الاستئناف والذاكرة", "♻"),
    DIAG("سجل التشخيص", "🩺"),
    ABOUT("حول التطبيق", "ℹ️")
}

/** The values shown on the settings pages; every change is saved at once, as before. */
@Stable
class SettingsUi(s: Settings) {
    var workersLens by mutableIntStateOf(s.workersLens)
    var workersAi by mutableIntStateOf(s.workersAi)
    var retries by mutableIntStateOf(s.retries)
    var merge by mutableStateOf(s.mergeChoice)
    var strip by mutableStateOf(s.stripDiacritics)
    var classicOneLine by mutableStateOf(s.classicOneLine)
    var bom by mutableStateOf(s.bom)
    var crlf by mutableStateOf(s.crlf)
    var overwrite by mutableStateOf(s.overwrite)
    var resume by mutableStateOf(s.resume)
    var region by mutableStateOf(s.lensRegion)
    var every by mutableIntStateOf(s.analyzeEvery)
    var minMs by mutableIntStateOf(s.minDurationMs)
    var repair by mutableStateOf(s.repairSplits)
    var vEngine by mutableStateOf(s.videoEngine)
    var gpu by mutableStateOf(s.gpu)
}

/**
 * Settings: a list of categories, each with what it is set to now; a category opens its own page.
 * Pages move along the reading direction (shared axis): forward = in from the left in Arabic.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, asTab: Boolean = false) {
    val u = remember { SettingsUi(vm.s) }
    val chrome = LocalChrome.current
    if (asTab && chrome.twoPane) {
        SettingsTwoPane(vm, u)
        return
    }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val distance = with(LocalDensity.current) { Motion.AXIS_DISTANCE.roundToPx() }

    AnimatedContent(
        targetState = page,
        transitionSpec = { Motion.sharedAxisX(forward = targetState != null, rtl = rtl, distancePx = distance) },
        label = "settingsPage"
    ) { p ->
        if (p == null) {
            SettingsHub(vm, u, onBack = if (asTab) null else onBack, onOpen = { page = it })
        } else {
            SettingsPageScreen(p.title, onBack = { page = null }) { PageContent(p, vm, u) }
        }
    }
}

/**
 * Settings on a wide screen: the list of categories and the open page side by side (the first
 * category open at first); choosing another category changes the page with a fade-through.
 */
@Composable
private fun SettingsTwoPane(vm: MainViewModel, u: SettingsUi) {
    val chrome = LocalChrome.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.READING) }
    // the panes sit beside the rail already: their pages take no room for it again
    CompositionLocalProvider(LocalChrome provides chrome.copy(start = 0.dp)) {
    Row(Modifier.fillMaxSize().padding(start = chrome.start)) {
        Box(Modifier.weight(0.42f).fillMaxHeight()) {
            SettingsHub(vm, u, onBack = null, onOpen = { page = it }, selected = page)
        }
        Box(Modifier.weight(0.58f).fillMaxHeight()) {
            AnimatedContent(targetState = page, transitionSpec = { Motion.fadeThrough() }, label = "settingsDetail") { p ->
                SettingsPageScreen(p.title, onBack = null) { PageContent(p, vm, u) }
            }
        }
    }
    }
}

// ============================================================================ list of categories

@Composable
private fun SettingsHub(vm: MainViewModel, u: SettingsUi, onBack: (() -> Unit)?, onOpen: (SettingsPage) -> Unit, selected: SettingsPage? = null) {
    val cacheLabel = remember(vm.settingsVersion) { vm.cacheSizeLabel() }
    val diagLabel = remember(vm.settingsVersion, vm.diagnostics) { vm.diagSizeLabel() }

    SettingsPageScreen("الإعدادات", onBack) {
        SettingsGroup("المعالجة", Modifier.reveal(1)) {
            Entry(SettingsPage.READING, onOpen, selected,
                "Lens وVision ${u.workersLens} • الذكاء الاصطناعي ${u.workersAi} • إعادة ${u.retries}")
            Entry(SettingsPage.VIDEO, onOpen, selected, listOf(
                videoEngineLabel(u.vEngine),
                if (u.every == 1) "كل إطار" else "كل ${u.every} إطارات",
                if (u.gpu) "Vulkan" else "المعالج المركزي"
            ).joinToString(" • "), last = true)
        }
        SettingsGroup("النص والملف", Modifier.reveal(2)) {
            Entry(SettingsPage.CLEAN, onOpen, selected, (if (u.classicOneLine) "Lens وVision: سطر واحد" else "Lens وVision: كما في الصورة") + (if (u.strip) " • التشكيل محذوف" else ""))
            Entry(SettingsPage.MERGE, onOpen, selected, vm.mergeLabel(u.merge))
            Entry(SettingsPage.SRT, onOpen, selected, listOf(
                if (u.bom) "UTF-8 مع BOM" else "UTF-8",
                if (u.crlf) "CRLF" else "LF",
                if (u.overwrite) "استبدال الملف" else "اسم جديد"
            ).joinToString(" • "), last = true)
        }
        SettingsGroup("الخدمات والبيانات", Modifier.reveal(3)) {
            Entry(SettingsPage.LENS, onOpen, selected, "رمز الدولة ${u.region.ifBlank { "US" }}")
            Entry(SettingsPage.CACHE, onOpen, selected, (if (u.resume) "الاستئناف مفعّل" else "الاستئناف متوقف") + " • $cacheLabel")
            Entry(SettingsPage.DIAG, onOpen, selected, (if (vm.diagnostics) "مفعّل" else "متوقف") + " • $diagLabel", last = true)
        }
        SettingsGroup("التطبيق", Modifier.reveal(4)) {
            Entry(SettingsPage.ABOUT, onOpen, selected, "الإصدار ${BuildConfig.VERSION_NAME}", last = true)
        }
    }
}

@Composable
private fun Entry(p: SettingsPage, onOpen: (SettingsPage) -> Unit, selected: SettingsPage?, summary: String, last: Boolean = false) {
    SettingsRow(p.emoji, p.title, summary, divider = !last, selected = p == selected) { onOpen(p) }
}

private fun videoEngineLabel(e: VideoEngine) = when (e) {
    VideoEngine.ENSEMBLE -> "الأدق"
    VideoEngine.V21, VideoEngine.V22 -> "سريع"
    VideoEngine.CLASSIC -> "حساس لكل تغيير"
}

/**
 * One settings page: large title that folds into the bar while scrolling, content in 16 dp margins.
 * A phone on its side has little height: a one-line bar that stays (its own pinned scroll behaviour;
 * a collapsing behaviour with no large bar attached to it would swallow every upward scroll).
 */
@Composable
internal fun SettingsPageScreen(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val chrome = LocalChrome.current
    val bar = if (chrome.short) TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    else TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.padding(start = chrome.start).nestedScroll(bar.nestedScrollConnection),
        // the tab's own surface (the app background) shows through, under the frosted bar
        containerColor = Color.Transparent,
        topBar = {
            val titleText: @Composable () -> Unit = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val back: @Composable () -> Unit = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
            }
            // a phone on its side has little height: a one-line bar instead of the large title
            if (chrome.short) {
                TopAppBar(
                    title = titleText, navigationIcon = back,
                    // the page scrolls below the bar (its content is clipped there): the bar stays clear when it
                    // scrolls, instead of turning into a dark box over the app's background
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    scrollBehavior = bar
                )
            } else {
                LargeTopAppBar(
                    title = titleText, navigationIcon = back,
                    colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                    scrollBehavior = bar
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = maxOf(32.dp, chrome.bottom))
                .reveal(0),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) { content() }
    }
}

// ============================================================================ pages

@Composable
private fun PageContent(p: SettingsPage, vm: MainViewModel, u: SettingsUi) {
    val s = vm.s
    when (p) {
        SettingsPage.READING -> {
            SettingsCard("العمليات المتوازية") {
                SliderRow("Google Lens / Vision", "${u.workersLens}", u.workersLens.toFloat(), 1f..8f, 6,
                    onChange = { u.workersLens = it.roundToInt() }, onDone = { s.workersLens = u.workersLens })
                Hint("يشترك فيه Google Lens وGoogle Vision. الأكثر من 6 قد يجعل Google توقف الطلبات مؤقتًا.")
                Spacer(Modifier.height(8.dp))
                SliderRow("Gemini / OpenRouter", "${u.workersAi}", u.workersAi.toFloat(), 1f..12f, 10,
                    onChange = { u.workersAi = it.roundToInt() }, onDone = { s.workersAi = u.workersAi })
                Hint("إذا كانت الخدمة مزدحمة (429) يقلّ العدد تلقائيًا ثم يعود تدريجيًا.")
            }
            SettingsCard("الأخطاء") {
                SliderRow("عدد مرات إعادة المحاولة", "${u.retries}", u.retries.toFloat(), 0f..10f, 9,
                    onChange = { u.retries = it.roundToInt() }, onDone = { s.retries = u.retries })
                Hint("تُعاد الصورة إذا انقطعت الشبكة أو تعطلت الخدمة مؤقتًا. إذا كانت الخدمة مزدحمة (429) يطول الانتظار وتتضاعف المحاولات. المفتاح الخاطئ أو نفاد الرصيد يوقف المهمة فورًا.")
            }
        }

        SettingsPage.VIDEO -> {
            SettingsCard("طريقة الكشف") {
                ChoiceList(
                    listOf(
                        ChoiceItem(
                            VideoEngine.ENSEMBLE, "الأدق (موصى به)",
                            "ثلاث طرق كشف معًا، وحيث تختلف تُقرأ صورها ويُحسم بالنص المقروء. أبطأ قليلًا."
                        ),
                        ChoiceItem(VideoEngine.V21, "سريع", "طريقة كشف واحدة."),
                        ChoiceItem(VideoEngine.CLASSIC, "حساس لكل تغيير", "يقطع عند أي تغيّر في الشريط: لا يفوّت شيئًا لكن الصور المكررة أكثر.")
                    ),
                    u.vEngine
                ) { u.vEngine = it; s.videoEngine = it }
            }
            SettingsCard("تحليل الإطارات") {
                Segmented(
                    listOf(1 to "كل إطار", 2 to "كل 2", 3 to "كل 3", 4 to "كل 4"),
                    u.every,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) { u.every = it; s.analyzeEvery = it }
                Hint("«كل إطار» = أدق توقيت. الباقي أسرع على الأجهزة الضعيفة.")
                Spacer(Modifier.height(12.dp))
                SliderRow(
                    "أقل مدة للترجمة", if (u.minMs == 0) "بلا حد" else "${u.minMs} مللي ثانية", u.minMs.toFloat(), 0f..1000f, 0,
                    onChange = { u.minMs = (it / 10).roundToInt() * 10 }, onDone = { s.minDurationMs = u.minMs },
                    startLabel = "0", endLabel = "1000"
                )
                Hint("أي ترجمة أقصر من هذا تُحذف. الافتراضي 80.")
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    "إصلاح انقسام السطر الواحد",
                    "إذا انقسمت نفس الترجمة إلى صورتين بسبب ومضة تُعاد صورة واحدة.",
                    u.repair
                ) { u.repair = it; s.repairSplits = it }
            }
            SettingsCard("المعالج الرسومي") {
                SwitchRow(
                    "تسريع Vulkan",
                    "التحليل يعمل على معالج جوالك الرسومي (Adreno وغيره) عبر تعريفه. يُقارن بالمعالج المركزي بكسلًا بكسل، وأي اختلاف يوقفه.",
                    u.gpu
                ) { u.gpu = it; s.gpu = it }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { vm.testGpu() }, enabled = !vm.gpuTesting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.gpuTesting) "جارٍ الاختبار…" else "اختبار المعالج الرسومي")
                }
                val gpu = vm.gpuTest
                ResultLine(gpu, ok = gpu != null && !gpu.contains("✖") && !gpu.contains("⚠"))
            }
        }

        SettingsPage.CLEAN -> {
            SettingsCard("Google Lens وGoogle Vision") {
                ChoiceList(
                    listOf(
                        ChoiceItem(false, "كما في الصورة (موصى به)", "تبقى الأسطر وشرطات الحوار وعلامات الترقيم كما تظهر في الترجمة. تُحذف أسماء الطاقم اللاتينية والحروف الشاردة من الخلفية."),
                        ChoiceItem(true, "سطر واحد بلا تشكيل", "تُجمع الأسطر في سطر واحد، وتُحذف شرطات الحوار والتشكيل.")
                    ),
                    u.classicOneLine
                ) { u.classicOneLine = it; s.classicOneLine = it }
            }
            SettingsCard("كل المحركات") {
                SwitchRow("حذف التشكيل", "يحذف كل الحركات من الترجمة.", u.strip) { u.strip = it; s.stripDiacritics = it }
            }

        }

        SettingsPage.MERGE -> SettingsCard("طريقة الدمج") {
            ChoiceList(
                listOf(
                    ChoiceItem(Choice.AUTO, vm.mergeLabel(Choice.AUTO), "Lens وVision: دمج التكرار. Gemini وOpenRouter: دمج الانقسام فقط."),
                    ChoiceItem(Choice.PRO, vm.mergeLabel(Choice.PRO), "يجمع المقاطع المتتالية المتطابقة تقريبًا (90% فأكثر) التي يفصلها أقل من ثلث ثانية في ترجمة واحدة"),
                    ChoiceItem(Choice.STRICT_OR_PRECISE, vm.mergeLabel(Choice.STRICT_OR_PRECISE), "يجمع فقط مقطعين متلاصقين بالنص نفسه (ترجمة انقسمت إلى صورتين)، ولا يدمج نصين مختلفين أبدًا"),
                    ChoiceItem(Choice.NONE, vm.mergeLabel(Choice.NONE), "كل صورة ترجمة مستقلة بتوقيتها، حتى لو تكرر النص")
                ),
                u.merge
            ) { u.merge = it; s.mergeChoice = it }
        }

        SettingsPage.SRT -> SettingsCard(null) {
            SwitchRow("UTF-8 مع BOM", "تحتاجه بعض برامج ويندوز لتظهر العربية صحيحة", u.bom) { u.bom = it; s.bom = it }
            SwitchRow("نهايات أسطر ويندوز (CRLF)", "لبرامج ويندوز القديمة", u.crlf) { u.crlf = it; s.crlf = it }
            SwitchRow("استبدال الملف إن وُجد", "عند الإيقاف يُحفظ باسم جديد مثل «الاسم (2).srt»", u.overwrite) { u.overwrite = it; s.overwrite = it }
        }

        SettingsPage.LENS -> SettingsCard(null) {
            OutlinedTextField(
                u.region, { u.region = it; s.lensRegion = it },
                label = { Text("رمز الدولة") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Hint("رمز الدولة الذي يُرسل مع كل طلب (الافتراضي US). لغة القراءة تُضبط تلقائيًا من «لغة الترجمة» في الشاشة الرئيسية.")
        }

        SettingsPage.CACHE -> {
            val cacheLabel = remember(vm.settingsVersion) { vm.cacheSizeLabel() }
            SettingsCard(null) {
                SwitchRow("استئناف المهام المتوقفة", "كل صورة تُقرأ تُحفظ فورًا، فإذا توقفت المهمة تكمل من حيث وقفت دون قراءة ما تم. مع Lens وVision تُمسح القراءات حين تكتمل المهمة، فتشغيل الفيديو نفسه مرة أخرى يقرؤه من جديد.", u.resume) { u.resume = it; s.resume = it }
            }
            SettingsCard("الذاكرة المؤقتة") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الحجم: $cacheLabel", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.clearResumeCache() }) { Text("مسح") }
                }
                Spacer(Modifier.height(6.dp))
                Hint("تنظيف تلقائي: تُحذف ذاكرة أي مهمة لم تُستخدم 30 يومًا، ولا يتجاوز المجموع 64 MB (الأقدم أولًا). وتُحذف الإطارات المؤقتة لأي مهمة لم تكتمل.")
            }
        }

        SettingsPage.DIAG -> {
            val diagLabel = remember(vm.settingsVersion, vm.diagnostics) { vm.diagSizeLabel() }
            val context = LocalContext.current
            val saveDiag = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.saveDiagnostics(it) }
            SettingsCard(null) {
                SwitchRow(
                    "تسجيل خطوات المعالجة",
                    "ملف نصي على جهازك: مراحل كل مهمة، والأخطاء بتفاصيلها، والانهيارات، ومعلومات الجهاز والذاكرة. لا يُسجَّل أي مفتاح API، ولا يُرسل شيء إلا إذا أرسلته أنت.",
                    vm.diagnostics
                ) { vm.setDiagnosticsOn(it) }
            }
            SettingsCard("الملف") {
                Text("الحجم: $diagLabel", style = MaterialTheme.typography.bodyLarge)
                Hint("يُحتفظ بآخر 3 MB تقريبًا")
                Spacer(Modifier.height(10.dp))
                RowSpaced {
                    OutlinedButton(onClick = { saveDiag.launch(vm.diagFileName()) }, modifier = Modifier.weight(1f)) { Text("💾 حفظ") }
                    OutlinedButton(onClick = { vm.shareDiagnostics(context) }, modifier = Modifier.weight(1f)) { Text("↗ إرسال") }
                    OutlinedButton(onClick = { vm.clearDiagnostics() }) { Text("مسح") }
                }
            }
        }

        SettingsPage.ABOUT -> SettingsCard(null) {
            Text("HardSub Arabic OCR — Android ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Hint(
                "يستخرج الترجمة المدمجة في الفيديو ويحوّلها إلى ملف SRT. " +
                    "يكتشف الترجمة في الفيديو مباشرة بطريقة مصمَّمة للخط العربي، ثم يقرأ النص بالمحرك الذي تختاره: " +
                    "Google Lens أو Google Cloud Vision أو Gemini أو OpenRouter. ويقرأ أيضًا مجلدات صور الترجمة التي تحمل أسماؤها التوقيت (كما يحفظها VideoSubFinder)."
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "مفاتيح API محفوظة مشفّرة على جهازك، ولا تُرسل إلا إلى الخدمة التي تختارها.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            val context = LocalContext.current
            if (remember { com.abdulrahman.hardsubocr.ui.theme.AppFonts.family(context) } != null) {
                Hint("خط التطبيق: ثمانية سانس (thmanyah sans)، © ثمانية للنشر والتوزيع، مستخدم وفق ترخيصه.")
            }
            Hint("الزجاج السائل: تظليل خاص بالتطبيق على معالج الجوال الرسومي، مستوحى من مشروع liquidglass مفتوح المصدر (Apache 2.0).")
        }
    }
}
