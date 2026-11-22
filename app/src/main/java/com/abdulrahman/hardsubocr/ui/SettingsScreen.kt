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
import com.abdulrahman.hardsubocr.video.BoxMode
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
    var boxMode by remember { mutableStateOf(s.boxMode) }
    var composite by remember { mutableStateOf(s.composite) }
    var saveClean by remember { mutableStateOf(s.saveClean) }
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
                SectionLabel("محرك كشف الترجمة")
                ChoiceList(
                    listOf(
                        ChoiceItem(
                            VideoEngine.ENSEMBLE, "الأقصى (موصى به)",
                            "نتيجة 2.1 كما هي، و2.0 و2.2 شاهدان: لا يتغير سطر إلا إذا أثبتت القراءة أنه ناقص أو مدموج أو بتوقيت زائد. يقرأ صورًا أكثر. تقرير name_engines.txt يذكر كل تصحيح."
                        ),
                        ChoiceItem(VideoEngine.V21, "SubScan 2.1", "قناع بعتبتين + تثبيت + إصلاح الانقسام"),
                        ChoiceItem(VideoEngine.V22, "SubScan 2.2", "2.1 + ثبات البكسل وفلتر الحركة (أسنان، حلي، أوراق)"),
                        ChoiceItem(VideoEngine.CLASSIC, "الكلاسيكي 2.0", "أبسط محرك: نص ساطع بحدود داكنة")
                    ),
                    vEngine
                ) { vEngine = it; s.videoEngine = it }

                Spacer(Modifier.height(10.dp))
                SectionLabel("ترجمة داخل صندوق أسود")
                ChoiceList(
                    listOf(
                        ChoiceItem(
                            BoxMode.AUTO, "تلقائي (موصى به)",
                            "قبل البدء يفحص 24 لقطة من الفيديو: إن كانت الترجمة داخل صندوق أسود تُتجاهل أسماء الطاقم وأي كتابة خارج الصندوق."
                        ),
                        ChoiceItem(BoxMode.ON, "نعم، دائمًا", "يُكشف ويُقرأ ما داخل الصندوق فقط، وما حوله في الصور يصبح أسود."),
                        ChoiceItem(BoxMode.OFF, "لا", "للترجمة المكتوبة مباشرة على الصورة بلا صندوق.")
                    ),
                    boxMode
                ) { boxMode = it; s.boxMode = it }

                Spacer(Modifier.height(10.dp))
                SectionLabel("الدقة والسرعة")
                Text("تحليل الإطارات", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
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
                Hint("«كل إطار» = توقيت دقيق تمامًا (الأفضل). الأرقام الأكبر أسرع على الأجهزة الضعيفة وأقل دقة.")
                SliderRow(
                    "أقل مدة للترجمة", "$minMs مللي ثانية", minMs.toFloat(), 80f..1000f, 0,
                    onChange = { minMs = (it / 10).roundToInt() * 10 }, onDone = { s.minDurationMs = minMs },
                    startLabel = "80", endLabel = "1000"
                )
                Hint("أي قطعة أقصر من هذا تُحذف كومضة (الافتراضي 160). إذا اختفت أسطر فالسبب غالبًا كتابة أخرى كأسماء الطاقم تقطع الترجمة إلى قطع قصيرة — «ترجمة داخل صندوق» تعالج ذلك من أصله.")
                SwitchRow(
                    "تسريع بالمعالج الرسومي (Adreno وغيره)",
                    "OpenGL ES 3.1. يُقارن بالمعالج المركزي بكسلًا بكسل في أول 30 إطارًا ثم مرة كل 256 إطارًا، وأي اختلاف يوقفه تلقائيًا.",
                    gpu
                ) { gpu = it; s.gpu = it }

                Spacer(Modifier.height(10.dp))
                SectionLabel("صور الترجمة")
                SwitchRow(
                    "إصلاح انقسام السطر الواحد",
                    "إذا انقسمت نفس الترجمة إلى صورتين بسبب ومضة أو تغيّر المشهد خلفها تُعاد صورة واحدة. أطفئه إن أردت كل جزء منفصلًا.",
                    repair
                ) { repair = it; s.repairSplits = it }
                SwitchRow(
                    "تنظيف الخلفية بدمج عدة إطارات",
                    "دمج حتى 5 إطارات من نفس الترجمة يمحو الخلفية المتحركة ويُبقي الحروف — قراءة أدق.",
                    composite
                ) { composite = it; s.composite = it }
                SwitchRow(
                    "حفظ نسخة منظفة أبيض/أسود",
                    "مجلد name_TXTImages بجانب name_RGBImages (مثل VideoSubFinder).",
                    saveClean
                ) { saveClean = it; s.saveClean = it }
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
