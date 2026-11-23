@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import com.abdulrahman.hardsubocr.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TopAppBarDefaults
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
import com.abdulrahman.hardsubocr.data.CleanChoice
import com.abdulrahman.hardsubocr.data.EnhanceChoice
import com.abdulrahman.hardsubocr.data.Settings
import com.abdulrahman.hardsubocr.video.VideoEngine
import kotlin.math.roundToInt

/** Settings pages, in the order of the list. */
enum class SettingsPage(val title: String, val emoji: String) {
    READING("القراءة والأداء", "⚡"),
    VIDEO("الفيديو", "🎬"),
    CLEAN("تنظيف النص", "🧹"),
    MERGE("دمج المقاطع", "🔗"),
    ENHANCE("تحسين الصور", "🖼"),
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
    var rescue by mutableStateOf(s.rescueEmpty)
    var clean by mutableStateOf(s.cleanChoice)
    var merge by mutableStateOf(s.mergeChoice)
    var strip by mutableStateOf(s.stripDiacritics)
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
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val u = remember { SettingsUi(vm.s) }
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
            SettingsHub(vm, u, onBack = onBack, onOpen = { page = it })
        } else {
            SettingsPageScreen(p.title, onBack = { page = null }) { PageContent(p, vm, u) }
        }
    }
}

// ============================================================================ list of categories

@Composable
private fun SettingsHub(vm: MainViewModel, u: SettingsUi, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val cacheLabel = remember(vm.settingsVersion) { vm.cacheSizeLabel() }
    val diagLabel = remember(vm.settingsVersion, vm.diagnostics) { vm.diagSizeLabel() }

    SettingsPageScreen("الإعدادات", onBack) {
        SettingsGroup("المعالجة") {
            Entry(SettingsPage.READING, onOpen,
                "Lens وVision ${u.workersLens} • الذكاء الاصطناعي ${u.workersAi} • إعادة ${u.retries}" +
                    if (u.rescue) " • قراءة ثانية" else "")
            Entry(SettingsPage.VIDEO, onOpen, listOf(
                videoEngineLabel(u.vEngine),
                if (u.every == 1) "كل إطار" else "كل ${u.every} إطارات",
                if (u.gpu) "Vulkan" else "المعالج المركزي"
            ).joinToString(" • "), last = true)
        }
        SettingsGroup("النص والملف") {
            Entry(SettingsPage.CLEAN, onOpen, vm.cleanLabel(u.clean) + if (u.strip) " • بلا تشكيل" else "")
            Entry(SettingsPage.MERGE, onOpen, vm.mergeLabel(u.merge))
            Entry(SettingsPage.ENHANCE, onOpen, vm.enhanceLabel(vm.enhanceChoice))
            Entry(SettingsPage.SRT, onOpen, listOf(
                if (u.bom) "UTF-8 مع BOM" else "UTF-8",
                if (u.crlf) "CRLF" else "LF",
                if (u.overwrite) "استبدال الملف" else "اسم جديد"
            ).joinToString(" • "), last = true)
        }
        SettingsGroup("الخدمات والبيانات") {
            Entry(SettingsPage.LENS, onOpen, "المنطقة ${u.region.ifBlank { "US" }}")
            Entry(SettingsPage.CACHE, onOpen, (if (u.resume) "الاستئناف مفعّل" else "الاستئناف متوقف") + " • $cacheLabel")
            Entry(SettingsPage.DIAG, onOpen, (if (vm.diagnostics) "مفعّل" else "متوقف") + " • $diagLabel", last = true)
        }
        SettingsGroup("التطبيق") {
            Entry(SettingsPage.ABOUT, onOpen, "الإصدار ${BuildConfig.VERSION_NAME}", last = true)
        }
    }
}

@Composable
private fun Entry(p: SettingsPage, onOpen: (SettingsPage) -> Unit, summary: String, last: Boolean = false) {
    SettingsRow(p.emoji, p.title, summary, divider = !last) { onOpen(p) }
}

private fun videoEngineLabel(e: VideoEngine) = when (e) {
    VideoEngine.ENSEMBLE -> "الأدق"
    VideoEngine.V21, VideoEngine.V22 -> "سريع"
    VideoEngine.CLASSIC -> "حساس لكل تغيير"
}

/** One settings page: large title that folds into the bar while scrolling, content in 16 dp margins. */
@Composable
private fun SettingsPageScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                },
                scrollBehavior = bar
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
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
                Hint("يشترك فيه Google Lens وGoogle Vision. الاتصال مباشر فيمكن رفعه، والأكثر من 6 قد يسبب حظرًا مؤقتًا (429).")
                Spacer(Modifier.height(8.dp))
                SliderRow("Gemini / OpenRouter", "${u.workersAi}", u.workersAi.toFloat(), 1f..12f, 10,
                    onChange = { u.workersAi = it.roundToInt() }, onDone = { s.workersAi = u.workersAi })
            }
            SettingsCard("الأخطاء") {
                SliderRow("عدد مرات إعادة المحاولة", "${u.retries}", u.retries.toFloat(), 0f..10f, 9,
                    onChange = { u.retries = it.roundToInt() }, onDone = { s.retries = u.retries })
                Hint("إعادة تلقائية مع تأخير أُسّي (2–20 ث) لأخطاء الشبكة و429 و5xx. الخطأ الدائم (مفتاح خاطئ/رصيد) يوقف المهمة فورًا.")
            }
            SettingsCard("Google Lens وVision") {
                SwitchRow(
                    "قراءة ثانية للصور الفارغة",
                    "إذا لم يُقرأ نص في صورة، تُقرأ مرة ثانية بعد تحسينها (تكبير وتباين وحدّة) دون قصّها. الصور المقروءة لا تُمسّ. مع Vision تُحتسب القراءة الثانية من حصتك.",
                    u.rescue
                ) { u.rescue = it; s.rescueEmpty = it }
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
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((n, label) in listOf(1 to "كل إطار", 2 to "كل 2", 3 to "كل 3", 4 to "كل 4")) {
                        FilterChip(
                            selected = u.every == n,
                            onClick = { u.every = n; s.analyzeEvery = n },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
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
                vm.gpuTest?.let { r ->
                    Spacer(Modifier.height(6.dp))
                    Text(r, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SettingsPage.CLEAN -> {
            SettingsCard("طريقة التنظيف") {
                ChoiceList(
                    listOf(
                        ChoiceItem(CleanChoice.AUTO, vm.cleanLabel(CleanChoice.AUTO), "Lens وVision ← سريع، وGemini/OpenRouter ← حسب الأسلوب المختار. (الإنجليزية لها تنظيف خاص تلقائيًا)"),
                        ChoiceItem(CleanChoice.V26, vm.cleanLabel(CleanChoice.V26), "يحذف التشكيل المشوّه فقط ويُبقي الصحيح، ويصلح الكلمات المتقطعة"),
                        ChoiceItem(CleanChoice.STRICT_OR_PRECISE, vm.cleanLabel(CleanChoice.STRICT_OR_PRECISE), "يحذف الردود والرموز الزائدة فقط ويُبقي الأسطر والتشكيل كما قرأها الموديل"),
                        ChoiceItem(CleanChoice.PRO, vm.cleanLabel(CleanChoice.PRO), "يُبقي العربي فقط في سطر واحد ويحذف التشكيل — مناسب لـ Lens وVision")
                    ),
                    u.clean
                ) { u.clean = it; s.cleanChoice = it }
            }
            SettingsCard(null) {
                SwitchRow("إزالة التشكيل نهائيًا", "يحذف كل الحركات من النص الناتج", u.strip) { u.strip = it; s.stripDiacritics = it }
            }
        }

        SettingsPage.MERGE -> SettingsCard("طريقة الدمج") {
            ChoiceList(
                listOf(
                    ChoiceItem(Choice.AUTO, vm.mergeLabel(Choice.AUTO), "Lens وVision ← دمج التكرار، والذكاء الاصطناعي ← حسب الأسلوب"),
                    ChoiceItem(Choice.PRO, vm.mergeLabel(Choice.PRO), "يجمع المقاطع المتقاربة (أقل من 0.35 ث) المتشابهة 90% أو أكثر في ترجمة واحدة"),
                    ChoiceItem(Choice.STRICT_OR_PRECISE, vm.mergeLabel(Choice.STRICT_OR_PRECISE), "يجمع فقط صورتين متلاصقتين (فرق ≤ 10 مللي ث) بنفس النص، مثل 0_01_55_280__0_01_56_879 و0_01_56_880__… — ولا يدمج نصين مختلفين أبدًا"),
                    ChoiceItem(Choice.NONE, vm.mergeLabel(Choice.NONE), "كل صورة = ترجمة مستقلة بتوقيتها كما هو، حتى لو تكرر النص (مشهدان مختلفان)")
                ),
                u.merge
            ) { u.merge = it; s.mergeChoice = it }
        }

        SettingsPage.ENHANCE -> SettingsCard("قبل الإرسال") {
            ChoiceList(
                listOf(
                    ChoiceItem(EnhanceChoice.AUTO, vm.enhanceLabel(EnhanceChoice.AUTO), "مفعّل مع أسلوب «ذكي + تشكيل»، ومعطّل مع Lens وVision و«سريع»"),
                    ChoiceItem(EnhanceChoice.ON, vm.enhanceLabel(EnhanceChoice.ON), null),
                    ChoiceItem(EnhanceChoice.OFF, vm.enhanceLabel(EnhanceChoice.OFF), null)
                ),
                vm.enhanceChoice
            ) { vm.chooseEnhanceChoice(it) }
            Spacer(Modifier.height(4.dp))
            Hint("قصّ شريط الترجمة (6–94% عرضًا، 35–96% ارتفاعًا) ← تكبير Lanczos للصور الصغيرة ← رمادي ← autocontrast ← تباين 1.12 ← حدّة. مطابق لـ Pillow بكسلًا بكسل.")
        }

        SettingsPage.SRT -> SettingsCard(null) {
            SwitchRow("UTF-8 مع BOM", "مطلوب لبعض برامج ويندوز العربية (الافتراضي كما في النسخة الأصلية)", u.bom) { u.bom = it; s.bom = it }
            SwitchRow("نهايات أسطر ويندوز CRLF", null, u.crlf) { u.crlf = it; s.crlf = it }
            SwitchRow("استبدال الملف إن وُجد", "عند الإيقاف يُنشأ اسم جديد مثل name (2).srt", u.overwrite) { u.overwrite = it; s.overwrite = it }
        }

        SettingsPage.LENS -> SettingsCard(null) {
            OutlinedTextField(
                u.region, { u.region = it; s.lensRegion = it },
                label = { Text("المنطقة") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Hint("المنطقة الافتراضية US. لغة Lens تُضبط تلقائيًا من «لغة الترجمة» في الشاشة الرئيسية.")
        }

        SettingsPage.CACHE -> {
            val cacheLabel = remember(vm.settingsVersion) { vm.cacheSizeLabel() }
            SettingsCard(null) {
                SwitchRow("استئناف المهام المتوقفة", "كل صورة تُقرأ تُحفظ فورًا، فإعادة التشغيل لا تعيد قراءة ما تم", u.resume) { u.resume = it; s.resume = it }
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
                "تطبيق أندرويد أصلي (Kotlin وJetpack Compose) يستخرج الترجمة المدمجة في الفيديو ويحوّلها إلى ملف SRT. " +
                    "يكتشف الترجمة في الفيديو مباشرة بمحرك مصمَّم للخط العربي، ثم يقرأ النص بالمحرك الذي تختاره: " +
                    "Google Lens أو Google Cloud Vision أو Gemini أو OpenRouter. ويقرأ أيضًا مجلدات الصور التي تحمل أسماؤها التوقيت (صيغة VideoSubFinder)."
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "مفاتيح API محفوظة على جهازك مشفّرة بـ Android Keystore، ولا تُرسل إلا إلى الخدمة التي تختارها.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
