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
    var lang by remember { mutableStateOf(s.lensLanguage) }
    var region by remember { mutableStateOf(s.lensRegion) }
    var every by remember { mutableIntStateOf(s.analyzeEvery) }
    var minMs by remember { mutableIntStateOf(s.minDurationMs) }
    var change by remember { mutableFloatStateOf(s.changeThreshold) }
    var export by remember { mutableStateOf(s.exportFrames) }
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
                        ChoiceItem(CleanChoice.AUTO, vm.cleanLabel(CleanChoice.AUTO), "Lens ← PRO، وGemini/OpenRouter ← حسب الأسلوب المختار"),
                        ChoiceItem(CleanChoice.V26, vm.cleanLabel(CleanChoice.V26), "يحذف التشكيل المشوّه فقط ويُبقي الصحيح، ويصلح الكلمات المتقطعة"),
                        ChoiceItem(CleanChoice.STRICT_OR_PRECISE, vm.cleanLabel(CleanChoice.STRICT_OR_PRECISE), "clean_ocr_output كما في v2.1"),
                        ChoiceItem(CleanChoice.PRO, vm.cleanLabel(CleanChoice.PRO), "arabic_filter كما في نسخة PRO")
                    ),
                    clean
                ) { clean = it; s.cleanChoice = it }
                SwitchRow("إزالة التشكيل نهائيًا", "يحذف كل الحركات من النص الناتج (في الوضع الدقيق وv2.6)", strip) { strip = it; s.stripDiacritics = it }
            }

            SectionCard("دمج المقاطع", "🔗") {
                ChoiceList(
                    listOf(
                        ChoiceItem(Choice.AUTO, vm.mergeLabel(Choice.AUTO), "Lens ← PRO، والذكاء الاصطناعي ← حسب الأسلوب"),
                        ChoiceItem(Choice.PRO, vm.mergeLabel(Choice.PRO), "merge_entries من نسخة PRO"),
                        ChoiceItem(Choice.STRICT_OR_PRECISE, vm.mergeLabel(Choice.STRICT_OR_PRECISE), "merge_ocr_entries من v2.1 بقواعد 2A–2E")
                    ),
                    merge
                ) { merge = it; s.mergeChoice = it }
            }

            SectionCard("تحسين الصور (v2.6)", "🖼") {
                ChoiceList(
                    listOf(
                        ChoiceItem(EnhanceChoice.AUTO, vm.enhanceLabel(EnhanceChoice.AUTO), "مفعّل مع أسلوب v2.6، ومعطّل مع Lens وPRO"),
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
                    OutlinedTextField(lang, { lang = it; s.lensLanguage = it }, label = { Text("اللغة") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(region, { region = it; s.lensRegion = it }, label = { Text("المنطقة") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Hint("القيم الافتراضية: ar و US (نفس chrome-lens-py).")
            }

            SectionCard("الفيديو", "🎬") {
                SliderRow("تحليل كل", if (every == 1) "إطار" else "$every إطارات", every.toFloat(), 1f..4f, 2,
                    onChange = { every = it.roundToInt() }, onDone = { s.analyzeEvery = every })
                Hint("كل إطار = دقة توقيت كاملة. 2 أو أكثر = أسرع على الأجهزة الضعيفة.")
                SliderRow("أقل مدة للترجمة", "$minMs مللي ث", minMs.toFloat(), 80f..1000f, 0,
                    onChange = { minMs = (it / 10).roundToInt() * 10 }, onDone = { s.minDurationMs = minMs })
                SliderRow("حساسية تغيّر الترجمة", String.format(Locale.US, "%.2f", change), change, 0.15f..0.60f, 0,
                    onChange = { change = it }, onDone = { s.changeThreshold = change })
                Hint("أقل = يكتشف تغييرات أصغر (قد يقسم الترجمة الواحدة، والدمج يعالج ذلك). أكبر = أكثر تحفظًا.")
                SwitchRow("حفظ صور الترجمات المقتطعة", "مجلد name_frames بأسماء VideoSubFinder يمكن فتحه في نسخة ويندوز", export) { export = it; s.exportFrames = it }
            }

            SectionCard("حول", "ℹ️") {
                Text("HardSub Arabic OCR — Android ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                Hint("تحويل أصلي (Kotlin + Jetpack Compose) لنسخة ويندوز v2 PRO وخط Gemini v2.1 وسكربت v2.6، مع منطق التنظيف والدمج والتشابه ومعالجة الصور مطابق للنسخ الأصلية. يدعم Gemini 4.")
                Spacer(Modifier.height(4.dp))
                Text("المفاتيح مشفّرة بـ Android Keystore ولا تُرسل إلا للخدمة المختارة.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
