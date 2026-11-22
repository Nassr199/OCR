@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
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
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s = vm.s
    var workersLens by remember { mutableIntStateOf(s.workersLens) }
    var workersAi by remember { mutableIntStateOf(s.workersAi) }
    var retries by remember { mutableIntStateOf(s.retries) }
    var clean by remember { mutableStateOf(s.cleanChoice) }
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
                ChoiceChips(Choice.entries, clean, { vm.choiceLabel(it, merge = false) }) { clean = it; s.cleanChoice = it }
                Hint("تلقائي: Lens وOpenRouter ← arabic_filter (PRO)، وGemini ← clean_ocr_output (v2.1).")
                SwitchRow("إزالة التشكيل في الوضع الدقيق", "يُبقي النص كما هو مع حذف الحركات فقط", strip) { strip = it; s.stripDiacritics = it }
            }

            SectionCard("دمج المقاطع", "🔗") {
                ChoiceChips(Choice.entries, merge, { vm.choiceLabel(it, merge = true) }) { merge = it; s.mergeChoice = it }
                Hint("تلقائي: Lens وOpenRouter ← merge_entries (PRO)، وGemini ← merge_ocr_entries (v2.1) بقواعد 2A–2E كما هي.")
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
                Text("HardSub Arabic OCR — Android 1.0.0", fontWeight = FontWeight.Bold)
                Hint("تحويل أصلي (Kotlin + Jetpack Compose) لنسخة ويندوز v2 PRO وخط Gemini v2.1، مع منطق التنظيف والدمج والتشابه مطابق حرفيًا للنسخة الأصلية.")
                Spacer(Modifier.height(4.dp))
                Text("المفاتيح مشفّرة بـ Android Keystore ولا تُرسل إلا للخدمة المختارة.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChoiceChips(values: List<Choice>, selected: Choice, label: (Choice) -> String, onSelect: (Choice) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in values) FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) })
        }
    }
}
