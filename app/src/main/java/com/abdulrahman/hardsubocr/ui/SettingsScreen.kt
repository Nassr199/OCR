@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import com.abdulrahman.hardsubocr.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abdulrahman.hardsubocr.data.Choice
import com.abdulrahman.hardsubocr.data.CleanChoice
import com.abdulrahman.hardsubocr.data.EnhanceChoice
import com.abdulrahman.hardsubocr.video.VideoEngine
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s = vm.s
    var workersLens by remember { mutableIntStateOf(s.workersLens) }
    var workersAi by remember { mutableIntStateOf(s.workersAi) }
    var retries by remember { mutableIntStateOf(s.retries) }
    var clean by remember { mutableStateOf(s.cleanChoice) }
    var enhance by remember { mutableStateOf(vm.enhanceChoice) }
    var merge by remember { mutableStateOf(s.mergeChoice) }
    var strip by remember { mutableStateOf(s.stripDiacritics) }
    var bom by remember { mutableStateOf(s.bom) }
    var crlf by remember { mutableStateOf(s.crlf) }
    var overwrite by remember { mutableStateOf(s.overwrite) }
    var resume by remember { mutableStateOf(s.resume) }
    var region by remember { mutableStateOf(s.lensRegion) }
    var every by remember { mutableIntStateOf(s.analyzeEvery) }
    var minMs by remember { mutableIntStateOf(s.minDurationMs) }
    var repair by remember { mutableStateOf(s.repairSplits) }
    var vEngine by remember { mutableStateOf(s.videoEngine) }
    var gpu by remember { mutableStateOf(s.gpu) }
    val cacheLabel = remember(vm.settingsVersion) { vm.cacheSizeLabel() }
    val diagLabel = remember(vm.settingsVersion, vm.diagnostics) { vm.diagSizeLabel() }
    val context = LocalContext.current
    val saveDiag = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.saveDiagnostics(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard("القراءة والأداء", "⚡", collapsible = true) {
                SliderRow("عمليات Google Lens / Vision المتوازية", "$workersLens", workersLens.toFloat(), 1f..8f, 6,
                    onChange = { workersLens = it.roundToInt() }, onDone = { s.workersLens = workersLens })
                Hint("يشترك فيه Google Lens وGoogle Vision. الاتصال مباشر فيمكن رفعه، والأكثر من 6 قد يسبب حظرًا مؤقتًا (429).")
                SliderRow("عمليات Gemini / OpenRouter المتوازية", "$workersAi", workersAi.toFloat(), 1f..12f, 10,
                    onChange = { workersAi = it.roundToInt() }, onDone = { s.workersAi = workersAi })
                SliderRow("عدد مرات إعادة المحاولة", "$retries", retries.toFloat(), 0f..10f, 9,
                    onChange = { retries = it.roundToInt() }, onDone = { s.retries = retries })
                Hint("إعادة تلقائية مع تأخير أُسّي (2–20 ث) لأخطاء الشبكة و429 و5xx. الخطأ الدائم (مفتاح خاطئ/رصيد) يوقف المهمة فورًا.")
            }

            SectionCard("الفيديو", "🎬", collapsible = true) {
                SectionLabel("طريقة الكشف")
                ChoiceList(
                    listOf(
                        ChoiceItem(
                            VideoEngine.ENSEMBLE, "الأدق (موصى به)",
                            "ثلاث طرق كشف معًا، وحيث تختلف تُقرأ صورها ويُحسم بالنص المقروء. أبطأ قليلًا."
                        ),
                        ChoiceItem(VideoEngine.V21, "سريع", "طريقة كشف واحدة."),
                        ChoiceItem(VideoEngine.CLASSIC, "حساس لكل تغيير", "يقطع عند أي تغيّر في الشريط: لا يفوّت شيئًا لكن الصور المكررة أكثر.")
                    ),
                    vEngine
                ) { vEngine = it; s.videoEngine = it }

                Spacer(Modifier.height(12.dp))
                SectionLabel("تحليل الإطارات")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((n, label) in listOf(1 to "كل إطار", 2 to "كل 2", 3 to "كل 3", 4 to "كل 4")) {
                        FilterChip(
                            selected = every == n,
                            onClick = { every = n; s.analyzeEvery = n },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Hint("«كل إطار» = أدق توقيت. الباقي أسرع على الأجهزة الضعيفة.")

                Spacer(Modifier.height(8.dp))
                SliderRow(
                    "أقل مدة للترجمة", if (minMs == 0) "بلا حد" else "$minMs مللي ثانية", minMs.toFloat(), 0f..1000f, 0,
                    onChange = { minMs = (it / 10).roundToInt() * 10 }, onDone = { s.minDurationMs = minMs },
                    startLabel = "0", endLabel = "1000"
                )
                Hint("أي ترجمة أقصر من هذا تُحذف. الافتراضي 80.")

                SwitchRow(
                    "إصلاح انقسام السطر الواحد",
                    "إذا انقسمت نفس الترجمة إلى صورتين بسبب ومضة تُعاد صورة واحدة.",
                    repair
                ) { repair = it; s.repairSplits = it }

                Spacer(Modifier.height(12.dp))
                SectionLabel("المعالج الرسومي")
                SwitchRow(
                    "تسريع Vulkan",
                    "التحليل يعمل على معالج جوالك الرسومي (Adreno وغيره) عبر تعريفه. يُقارن بالمعالج المركزي بكسلًا بكسل، وأي اختلاف يوقفه.",
                    gpu
                ) { gpu = it; s.gpu = it }
                OutlinedButton(onClick = { vm.testGpu() }, enabled = !vm.gpuTesting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.gpuTesting) "جارٍ الاختبار…" else "اختبار المعالج الرسومي")
                }
                vm.gpuTest?.let { r ->
                    Spacer(Modifier.height(6.dp))
                    Text(r, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard("تنظيف النص", "🧹", collapsible = true) {
                ChoiceList(
                    listOf(
                        ChoiceItem(CleanChoice.AUTO, vm.cleanLabel(CleanChoice.AUTO), "Lens وVision ← سريع، وGemini/OpenRouter ← حسب الأسلوب المختار. (الإنجليزية لها تنظيف خاص تلقائيًا)"),
                        ChoiceItem(CleanChoice.V26, vm.cleanLabel(CleanChoice.V26), "يحذف التشكيل المشوّه فقط ويُبقي الصحيح، ويصلح الكلمات المتقطعة"),
                        ChoiceItem(CleanChoice.STRICT_OR_PRECISE, vm.cleanLabel(CleanChoice.STRICT_OR_PRECISE), "يحذف الردود والرموز الزائدة فقط ويُبقي الأسطر والتشكيل كما قرأها الموديل"),
                        ChoiceItem(CleanChoice.PRO, vm.cleanLabel(CleanChoice.PRO), "يُبقي العربي فقط في سطر واحد ويحذف التشكيل — مناسب لـ Lens وVision")
                    ),
                    clean
                ) { clean = it; s.cleanChoice = it }
                SwitchRow("إزالة التشكيل نهائيًا", "يحذف كل الحركات من النص الناتج", strip) { strip = it; s.stripDiacritics = it }
            }

            SectionCard("دمج المقاطع", "🔗", collapsible = true) {
                ChoiceList(
                    listOf(
                        ChoiceItem(Choice.AUTO, vm.mergeLabel(Choice.AUTO), "Lens وVision ← دمج التكرار، والذكاء الاصطناعي ← حسب الأسلوب"),
                        ChoiceItem(Choice.PRO, vm.mergeLabel(Choice.PRO), "يجمع المقاطع المتقاربة (أقل من 0.35 ث) المتشابهة 90% أو أكثر في ترجمة واحدة"),
                        ChoiceItem(Choice.STRICT_OR_PRECISE, vm.mergeLabel(Choice.STRICT_OR_PRECISE), "يجمع فقط صورتين متلاصقتين (فرق ≤ 10 مللي ث) بنفس النص، مثل 0_01_55_280__0_01_56_879 و0_01_56_880__… — ولا يدمج نصين مختلفين أبدًا"),
                        ChoiceItem(Choice.NONE, vm.mergeLabel(Choice.NONE), "كل صورة = ترجمة مستقلة بتوقيتها كما هو، حتى لو تكرر النص (مشهدان مختلفان)")
                    ),
                    merge
                ) { merge = it; s.mergeChoice = it }
            }

            SectionCard("تحسين الصور", "🖼", collapsible = true) {
                ChoiceList(
                    listOf(
                        ChoiceItem(EnhanceChoice.AUTO, vm.enhanceLabel(EnhanceChoice.AUTO), "مفعّل مع أسلوب «ذكي + تشكيل»، ومعطّل مع Lens وVision و«سريع»"),
                        ChoiceItem(EnhanceChoice.ON, vm.enhanceLabel(EnhanceChoice.ON), null),
                        ChoiceItem(EnhanceChoice.OFF, vm.enhanceLabel(EnhanceChoice.OFF), null)
                    ),
                    enhance
                ) { enhance = it; vm.chooseEnhanceChoice(it) }
                Hint("قصّ شريط الترجمة (6–94% عرضًا، 35–96% ارتفاعًا) ← تكبير Lanczos للصور الصغيرة ← رمادي ← autocontrast ← تباين 1.12 ← حدّة. مطابق لـ Pillow بكسلًا بكسل.")
            }

            SectionCard("ملف SRT", "📄", collapsible = true) {
                SwitchRow("UTF-8 مع BOM", "مطلوب لبعض برامج ويندوز العربية (الافتراضي كما في النسخة الأصلية)", bom) { bom = it; s.bom = it }
                SwitchRow("نهايات أسطر ويندوز CRLF", null, crlf) { crlf = it; s.crlf = it }
                SwitchRow("استبدال الملف إن وُجد", "عند الإيقاف يُنشأ اسم جديد مثل name (2).srt", overwrite) { overwrite = it; s.overwrite = it }
            }

            SectionCard("Google Lens", "🔎", collapsible = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(region, { region = it; s.lensRegion = it }, label = { Text("المنطقة") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Hint("المنطقة الافتراضية US. لغة Lens تُضبط تلقائيًا من «لغة الترجمة» في الشاشة الرئيسية.")
            }

            SectionCard("الاستئناف والذاكرة المؤقتة", "♻", collapsible = true) {
                SwitchRow("استئناف المهام المتوقفة", "كل صورة تُقرأ تُحفظ فورًا، فإعادة التشغيل لا تعيد قراءة ما تم", resume) { resume = it; s.resume = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("الحجم: $cacheLabel", Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.clearResumeCache() }) { Text("مسح") }
                }
                Hint("تنظيف تلقائي: تُحذف ذاكرة أي مهمة لم تُستخدم 30 يومًا، ولا يتجاوز المجموع 64 MB (الأقدم أولًا). وتُحذف الإطارات المؤقتة لأي مهمة لم تكتمل.")
            }

            SectionCard("سجل التشخيص", "🩺", collapsible = true) {
                SwitchRow(
                    "تسجيل خطوات المعالجة",
                    "ملف نصي على جهازك: مراحل كل مهمة، والأخطاء بتفاصيلها، والانهيارات، ومعلومات الجهاز والذاكرة. لا يُسجَّل أي مفتاح API، ولا يُرسل شيء إلا إذا أرسلته أنت.",
                    vm.diagnostics
                ) { vm.setDiagnosticsOn(it) }
                Hint("الحجم: $diagLabel (يُحتفظ بآخر 3 MB تقريبًا)")
                Spacer(Modifier.height(6.dp))
                RowSpaced {
                    OutlinedButton(onClick = { saveDiag.launch(vm.diagFileName()) }, modifier = Modifier.weight(1f)) { Text("💾 حفظ") }
                    OutlinedButton(onClick = { vm.shareDiagnostics(context) }, modifier = Modifier.weight(1f)) { Text("↗ إرسال") }
                    OutlinedButton(onClick = { vm.clearDiagnostics() }) { Text("مسح") }
                }
            }

            SectionCard("حول", "ℹ️") {
                Text("HardSub Arabic OCR — Android ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Hint(
                    "تطبيق أندرويد أصلي (Kotlin وJetpack Compose) يستخرج الترجمة المدمجة في الفيديو ويحوّلها إلى ملف SRT. " +
                        "يكتشف الترجمة في الفيديو مباشرة بمحرك مصمَّم للخط العربي، ثم يقرأ النص بالمحرك الذي تختاره: " +
                        "Google Lens أو Google Cloud Vision أو Gemini أو OpenRouter. ويقرأ أيضًا مجلدات الصور التي تحمل أسماؤها التوقيت (صيغة VideoSubFinder)."
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "مفاتيح API محفوظة على جهازك مشفّرة بـ Android Keystore، ولا تُرسل إلا إلى الخدمة التي تختارها.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
