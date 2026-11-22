@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import com.abdulrahman.hardsubocr.BuildConfig
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
import androidx.compose.ui.Modifier
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
            SectionCard("الأداء", "⚡") {
                SliderRow("عمليات Google Lens المتوازية", "$workersLens", workersLens.toFloat(), 1f..8f, 6,
                    onChange = { workersLens = it.roundToInt() }, onDone = { s.workersLens = workersLens })
                Hint("ويندوز كان يستخدم 3 بسبب تشغيل chrome-lens.exe لكل صورة؛ هنا الاتصال مباشر فيمكن رفعه. الأكثر من 6 قد يسبب حظرًا مؤقتًا (429).")
                SliderRow("عمليات Gemini / OpenRouter المتوازية", "$workersAi", workersAi.toFloat(), 1f..12f, 10,
                    onChange = { workersAi = it.roundToInt() }, onDone = { s.workersAi = workersAi })
                SliderRow("عدد مرات إعادة المحاولة", "$retries", retries.toFloat(), 0f..10f, 9,
                    onChange = { retries = it.roundToInt() }, onDone = { s.retries = retries })
                Hint("إعادة تلقائية مع تأخير أُسّي (2–20 ث) لأخطاء الشبكة و429 و5xx. الخطأ الدائم (مفتاح خاطئ/رصيد) يوقف المهمة فورًا.")
            }

            SectionCard("تنظيف النص", "🧹") {
                ChoiceList(
                    listOf(
                        ChoiceItem(CleanChoice.AUTO, vm.cleanLabel(CleanChoice.AUTO), "Lens ← سريع، وGemini/OpenRouter ← حسب الأسلوب المختار. (الإنجليزية لها تنظيف خاص تلقائيًا)"),
                        ChoiceItem(CleanChoice.V26, vm.cleanLabel(CleanChoice.V26), "يحذف التشكيل المشوّه فقط ويُبقي الصحيح، ويصلح الكلمات المتقطعة"),
                        ChoiceItem(CleanChoice.STRICT_OR_PRECISE, vm.cleanLabel(CleanChoice.STRICT_OR_PRECISE), "يحذف الردود والرموز الزائدة فقط ويُبقي الأسطر والتشكيل كما قرأها الموديل"),
                        ChoiceItem(CleanChoice.PRO, vm.cleanLabel(CleanChoice.PRO), "يُبقي العربي فقط في سطر واحد ويحذف التشكيل — مناسب لـ Lens")
                    ),
                    clean
                ) { clean = it; s.cleanChoice = it }
                SwitchRow("إزالة التشكيل نهائيًا", "يحذف كل الحركات من النص الناتج", strip) { strip = it; s.stripDiacritics = it }
            }

            SectionCard("دمج المقاطع", "🔗") {
                ChoiceList(
                    listOf(
                        ChoiceItem(Choice.AUTO, vm.mergeLabel(Choice.AUTO), "Lens ← دمج التكرار، والذكاء الاصطناعي ← حسب الأسلوب"),
                        ChoiceItem(Choice.PRO, vm.mergeLabel(Choice.PRO), "يجمع المقاطع المتقاربة (أقل من 0.35 ث) المتشابهة 90% أو أكثر في ترجمة واحدة"),
                        ChoiceItem(Choice.STRICT_OR_PRECISE, vm.mergeLabel(Choice.STRICT_OR_PRECISE), "يجمع فقط صورتين متلاصقتين (فرق ≤ 10 مللي ث) بنفس النص، مثل 0_01_55_280__0_01_56_879 و0_01_56_880__… — ولا يدمج نصين مختلفين أبدًا"),
                        ChoiceItem(Choice.NONE, vm.mergeLabel(Choice.NONE), "كل صورة = ترجمة مستقلة بتوقيتها كما هو، حتى لو تكرر النص (مشهدان مختلفان)")
                    ),
                    merge
                ) { merge = it; s.mergeChoice = it }
            }

            SectionCard("تحسين الصور", "🖼") {
                ChoiceList(
                    listOf(
                        ChoiceItem(EnhanceChoice.AUTO, vm.enhanceLabel(EnhanceChoice.AUTO), "مفعّل مع أسلوب «ذكي + تشكيل»، ومعطّل مع Lens و«سريع»"),
                        ChoiceItem(EnhanceChoice.ON, vm.enhanceLabel(EnhanceChoice.ON), null),
                        ChoiceItem(EnhanceChoice.OFF, vm.enhanceLabel(EnhanceChoice.OFF), null)
                    ),
                    enhance
                ) { enhance = it; vm.chooseEnhanceChoice(it) }
                Hint("قصّ شريط الترجمة (6–94% عرضًا، 35–96% ارتفاعًا) ← تكبير Lanczos للصور الصغيرة ← رمادي ← autocontrast ← تباين 1.12 ← حدّة. مطابق لـ Pillow بكسلًا بكسل.")
            }

            SectionCard("ملف SRT", "📄") {
                SwitchRow("UTF-8 مع BOM", "مطلوب لبعض برامج ويندوز العربية (الافتراضي كما في النسخة الأصلية)", bom) { bom = it; s.bom = it }
                SwitchRow("نهايات أسطر ويندوز CRLF", null, crlf) { crlf = it; s.crlf = it }
                SwitchRow("استبدال الملف إن وُجد", "عند الإيقاف يُنشأ اسم جديد مثل name (2).srt", overwrite) { overwrite = it; s.overwrite = it }
            }

            SectionCard("الاستئناف", "♻") {
                SwitchRow("استئناف المهام المتوقفة", "كل صورة تُقرأ تُحفظ فورًا، فإعادة التشغيل لا تعيد قراءة ما تم", resume) { resume = it; s.resume = it }
                Row {
                    Hint("حجم الذاكرة: $cacheLabel", Modifier.weight(1f).padding(top = 10.dp))
                    OutlinedButton(onClick = { vm.clearResumeCache() }) { Text("مسح") }
                }
            }

            SectionCard("Google Lens", "🔎") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(region, { region = it; s.lensRegion = it }, label = { Text("المنطقة") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Hint("المنطقة الافتراضية US. لغة Lens تُضبط تلقائيًا من «لغة الترجمة» في الشاشة الرئيسية.")
            }

            SectionCard("الفيديو", "🎬") {
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

            SectionCard("حول", "ℹ️") {
                Text("HardSub Arabic OCR — Android ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                Hint("تطبيق أندرويد أصلي (Kotlin + Jetpack Compose) لاستخراج الترجمة المدمجة: محرك فيديو عربي أولًا أقوى من VideoSubFinder، وقراءة بـ Google Lens أو Gemini 4 أو OpenRouter.")
                Spacer(Modifier.height(4.dp))
                Text("المفاتيح مشفّرة بـ Android Keystore ولا تُرسل إلا للخدمة المختارة.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
