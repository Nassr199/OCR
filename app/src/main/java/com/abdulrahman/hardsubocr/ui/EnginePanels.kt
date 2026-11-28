@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.GeminiEngine
import com.abdulrahman.hardsubocr.core.ModelInfo
import com.abdulrahman.hardsubocr.core.OpenRouterEngine
import com.abdulrahman.hardsubocr.core.ThinkingLevel

fun engineName(e: EngineKind) = when (e) {
    EngineKind.LENS -> "Google Lens"
    EngineKind.VISION -> "Google Vision"
    EngineKind.GEMINI -> "Gemini"
    EngineKind.OPENROUTER -> "OpenRouter"
}

// ============================================================================ engine card

@Composable
fun EngineCard(vm: MainViewModel, enabled: Boolean, modifier: Modifier = Modifier) {
    SectionCard("محرك القراءة", "🔎", modifier) {
        val engines = listOf(EngineKind.LENS, EngineKind.VISION, EngineKind.GEMINI, EngineKind.OPENROUTER)
        LiquidTiles(
            listOf(
                TileSpec("⚡", "Google Lens", "بلا مفتاح"),
                TileSpec("👁", "Google Vision", "Cloud Vision API"),
                TileSpec("✨", "Gemini", "يحفظ التشكيل", badge = "Gemini 4"),
                TileSpec("🌐", "OpenRouter", "نماذج متعددة")
            ),
            selected = engines.indexOf(vm.engine),
            enabled = enabled
        ) { vm.chooseEngine(engines[it]) }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = vm.engine,
            transitionSpec = {
                (fadeIn(tween(Motion.NORMAL, delayMillis = 60)) togetherWith fadeOut(tween(Motion.FAST)))
                    .using(SizeTransform(clip = false))
            },
            label = "enginePanel"
        ) { e ->
            Column(Modifier.fillMaxWidth()) {
                when (e) {
                    EngineKind.LENS -> {
                        CheckLine("بلا مفتاح API ولا حساب")
                        CheckLine("اتصال مباشر بخدمة Google Lens")
                        CheckLine("يقرأ عدة صور في الوقت نفسه")
                        CheckLine("يقرأ الصورة كما هي ويحافظ على شكل الترجمة: الأسطر وشرطات الحوار وعلامات الترقيم")
                        Spacer(Modifier.height(6.dp))
                        SwitchRow(
                            "إعادة القراءة عند الشك",
                            "إذا لم تُقرأ الترجمة، أو قُرئت كلمة قصيرة، أو ظهرت حروف من الخلفية، تُقرأ الصورة نفسها مرة أخرى مكبّرة ومن حول الترجمة، ويُعتمد ما تتفق عليه القراءات. يلتقط الكلمات القصيرة التي تضيع بين أسماء الطاقم.",
                            vm.lensMultiView,
                            enabled
                        ) { vm.chooseLensMultiView(it) }
                    }
                    EngineKind.VISION -> VisionPanel(vm, enabled)
                    EngineKind.GEMINI -> AiPanel(vm, enabled, gemini = true)
                    EngineKind.OPENROUTER -> AiPanel(vm, enabled, gemini = false)
                }
            }
        }
    }
}

// ============================================================================ Google Vision

@Composable
private fun VisionPanel(vm: MainViewModel, enabled: Boolean) {
    KeyPanel(
        vm, EngineKind.VISION, enabled,
        placeholder = "AIza…",
        steps = listOf(
            "افتح Google Cloud Console واختر مشروعًا (أو أنشئ واحدًا).",
            "فعّل «Cloud Vision API» من APIs & Services ← Library.",
            "تأكد أن الفوترة (Billing) مفعّلة للمشروع؛ تطلبها Google Cloud لهذه الخدمة.",
            "من Credentials ← Create credentials ← API key، ويُفضّل تقييد المفتاح بـ Cloud Vision API.",
            "الصق المفتاح هنا ثم اضغط «اختبار»."
        ),
        linkLabel = "فتح Google Cloud Console",
        linkUrl = "https://console.cloud.google.com/apis/library/vision.googleapis.com",
        testable = true
    )
    Spacer(Modifier.height(12.dp))
    Text("نوع القراءة", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Segmented(
        listOf(true to "مفصّلة — الأدق", false to "سريعة"),
        vm.visionDocument,
        enabled = enabled
    ) { vm.chooseVisionDocument(it) }
    Hint(
        if (vm.visionDocument) "تفحص الصورة كلها بتفصيل وتعطي درجة ثقة لكل كلمة. السعر نفسه."
        else "مصممة للنص القليل داخل الصور، وقد تفوّت بعض الأسطر.",
        Modifier.padding(top = 6.dp, start = 4.dp)
    )
    Spacer(Modifier.height(12.dp))
    Text("نموذج القراءة", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Segmented(
        listOf("" to "المعتاد", "builtin/weekly" to "الأحدث"),
        vm.visionModel,
        enabled = enabled
    ) { vm.chooseVisionModel(it) }
    Hint(
        if (vm.visionModel.isEmpty()) "نموذج القراءة الذي تستخدمه Google افتراضيًا."
        else "آخر تحديث تنشره Google لنموذج القراءة كل أسبوع. إن لم يتوفر لحسابك يكمل التطبيق بالمعتاد تلقائيًا ويذكر ذلك في التقرير.",
        Modifier.padding(top = 6.dp, start = 4.dp)
    )
    Spacer(Modifier.height(10.dp))
    CheckLine("يقرأ الصورة كما هي ويحافظ على شكل الترجمة: الأسطر وشرطات الحوار وعلامات الترقيم")
    CheckLine("يحفظ بجانب ملف الترجمة تقريرًا بما قرأه من كل صورة")
    Hint(
        "التسعير من Google: أول 1000 صورة كل شهر بلا تكلفة، ثم 1.50$ لكل 1000 صورة.",
        Modifier.padding(top = 4.dp, start = 4.dp)
    )
}

// ============================================================================ Gemini / OpenRouter

private val thinkingOptions = listOf(
    ThinkingLevel.MINIMAL to "أدنى",
    ThinkingLevel.LOW to "منخفض",
    ThinkingLevel.MEDIUM to "متوسط",
    ThinkingLevel.HIGH to "عالٍ",
    ThinkingLevel.DEFAULT to "تلقائي"
)

private fun thinkingHint(t: ThinkingLevel): String = when (t) {
    ThinkingLevel.MINIMAL -> "الأسرع والأقل تكلفة، وتوصي به Google للاستخراج بالجملة. Gemini 4 و3.8 Flash لا يدعمانه فيعملان بـ«منخفض»."
    ThinkingLevel.LOW -> "تفكير قصير قبل الإجابة: أبطأ قليلًا وأعلى تكلفة."
    ThinkingLevel.MEDIUM -> "أبطأ وأعلى تكلفة."
    ThinkingLevel.HIGH -> "الأبطأ والأعلى تكلفة."
    ThinkingLevel.DEFAULT -> "مستوى النموذج المختار نفسه (في Gemini 4 Hydrogen وNeon: عالٍ)."
}

/**
 * Gemini and OpenRouter: one panel — key, model, thinking level, the cross-check. Both read the
 * same way (AiRecipe): the subtitle band, enlarged and sharpened as in ocr_windows_v2.6.py, with its
 * instructions. Only the key and the model list differ.
 */
@Composable
private fun AiPanel(vm: MainViewModel, enabled: Boolean, gemini: Boolean) {
    if (gemini) {
        KeyPanel(
            vm, EngineKind.GEMINI, enabled,
            placeholder = "AIza…",
            steps = listOf(
                "افتح Google AI Studio وسجّل الدخول بحساب Google.",
                "اضغط «Create API key» واختر مشروعًا.",
                "الصق المفتاح هنا ثم اضغط «حفظ»."
            ),
            linkLabel = "فتح Google AI Studio",
            linkUrl = "https://aistudio.google.com/apikey",
            testable = true
        )
    } else {
        KeyPanel(
            vm, EngineKind.OPENROUTER, enabled,
            placeholder = "sk-or-…",
            steps = listOf(
                "افتح openrouter.ai وسجّل الدخول.",
                "من Keys اضغط «Create Key».",
                "الصق المفتاح هنا ثم اضغط «حفظ»."
            ),
            linkLabel = "فتح OpenRouter Keys",
            linkUrl = "https://openrouter.ai/keys",
            testable = true
        )
    }

    Spacer(Modifier.height(14.dp))
    ModelPicker(
        vm, enabled,
        models = if (gemini) GeminiEngine.MODELS else OpenRouterEngine.MODELS,
        selected = if (gemini) vm.geminiModel else vm.openRouterModel
    )

    Spacer(Modifier.height(14.dp))
    SectionLabel("مستوى التفكير")
    Segmented(thinkingOptions, vm.thinking, enabled) { vm.chooseThinking(it) }
    Hint(thinkingHint(vm.thinking), Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))

    Spacer(Modifier.height(8.dp))
    SwitchRow(
        "التحقق المزدوج",
        "يقرأ النموذج كل صورة مرتين بنظرتين مختلفتين: الصورة الأصلية، ونسخة محسّنة منها. إن اتفقت القراءتان قُبلت، وإن اختلفتا حسمت قراءة ثالثة للترجمة وحدها. أدق، وتكلفته قرابة الضعف.",
        vm.aiCrossCheck,
        enabled
    ) { vm.chooseAiCrossCheck(it) }
}

// ============================================================================ API key

private fun masked(key: String): String =
    if (key.length <= 10) "••••••" else key.take(4) + "••••••••" + key.takeLast(4)

/**
 * Key setup for one engine: a saved key shows as a masked line with «اختبار / تغيير / حذف»;
 * no key shows the field, paste/save and a short "how to get a key" guide with a link.
 */
@Composable
private fun KeyPanel(
    vm: MainViewModel,
    e: EngineKind,
    enabled: Boolean,
    placeholder: String,
    steps: List<String>,
    linkLabel: String,
    linkUrl: String,
    testable: Boolean
) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val saved = remember(vm.keyVersion, e) { vm.savedKey(e) }
    var editing by remember(vm.keyVersion, e) { mutableStateOf(saved.isBlank()) }
    var showKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showGuide by remember(e) { mutableStateOf(saved.isBlank()) }
    val key = vm.keyOf(e)

    SectionLabel("مفتاح API")
    AnimatedContent(
        targetState = editing || saved.isBlank(),
        transitionSpec = { (fadeIn(tween(Motion.NORMAL)) togetherWith fadeOut(tween(Motion.FAST))).using(SizeTransform(clip = false)) },
        label = "key"
    ) { edit ->
        if (!edit) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("مفتاح محفوظ ومشفّر", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(masked(saved), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { editing = true }, enabled = enabled) { Text("تغيير") }
                IconButton(onClick = { confirmDelete = true }, enabled = enabled) {
                    Icon(Icons.Filled.Delete, "حذف المفتاح", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { vm.setKey(e, it) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    enabled = enabled,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) { Icon(Icons.Filled.Lock, contentDescription = if (showKey) "إخفاء" else "إظهار") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                RowSpaced {
                    OutlinedButton(
                        onClick = {
                            val t = clipboard.getText()?.text?.trim().orEmpty()
                            if (t.isEmpty()) vm.message = "الحافظة لا تحتوي على نص." else vm.setKey(e, t)
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("📋 لصق") }
                    FilledTonalButton(
                        onClick = {
                            if (key.isBlank()) vm.message = "الصق المفتاح أولاً"
                            else {
                                vm.saveKeys(); editing = false
                                if (testable) vm.testKey()
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("💾 حفظ") }
                    if (saved.isNotBlank()) TextButton(onClick = { vm.setKey(e, saved); editing = false }) { Text("إلغاء") }
                }
            }
        }
    }

    if (testable && saved.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { vm.testKey() }, enabled = enabled && !vm.keyTesting) {
                Text(if (vm.keyTesting) "جارٍ الاختبار…" else "✔ اختبار المفتاح")
            }
            if (vm.keyTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        val t = vm.keyTest?.takeIf { it.engine == e }
        ResultLine(t?.text, t?.ok == true)
    }

    // short guide, open by default only while there is no key
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { showGuide = !showGuide }
            .padding(top = 6.dp, bottom = 2.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (if (showGuide) "▾ " else "▸ ") + "طريقة الحصول على المفتاح",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
        )
    }
    AnimatedVisibility(
        visible = showGuide,
        enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp)) {
            for ((i, s) in steps.withIndex()) {
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("${i + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                    Hint(s, Modifier.weight(1f))
                }
            }
            TextButton(onClick = { runCatching { uri.openUri(linkUrl) } }) { Text("↗ $linkLabel") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف المفتاح؟") },
            text = { Text("سيُحذف مفتاح ${engineName(e)} من هذا الجهاز. يمكنك لصقه مرة أخرى في أي وقت.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteKey(e) }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } }
        )
    }
}

// ============================================================================ model picker

/**
 * The chosen model on one line, with «تغيير». The list opens below it: current models by family
 * (Gemini 4, Gemini 3), the account's other models after «تحديث من حسابك», and a typed name.
 * Choosing closes the list again.
 */
@Composable
private fun ModelPicker(vm: MainViewModel, enabled: Boolean, models: List<ModelInfo>, selected: String) {
    var open by remember { mutableStateOf(selected.isBlank()) }
    var custom by remember { mutableStateOf(false) }
    var showExtras by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val extras = vm.extraModels()
    val chosen = models.firstOrNull { it.id == selected }
    val choose: (String) -> Unit = { vm.chooseModel(it); open = false; custom = false }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("النموذج", Modifier.weight(1f))
        if (open) {
            if (vm.loadingModels) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            TextButton(onClick = { vm.fetchModels() }, enabled = enabled && !vm.loadingModels) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text("تحديث من حسابك", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (selected.isNotBlank()) {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) { open = !open }
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(chosen?.name ?: selected, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val note = chosen?.note ?: if (selected in extras) "من نماذج حسابك" else "اسم أُدخل يدويًا"
                Hint(note, maxLines = 2)
                if (chosen == null || chosen.id != chosen.name) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(selected, fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            TextButton(onClick = { open = !open }, enabled = enabled) { Text(if (open) "إغلاق" else "تغيير") }
        }
    }

    AnimatedVisibility(
        visible = open,
        enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            var family = ""
            for (m in models) {
                if (m.family != family) {
                    family = m.family
                    Text(
                        family, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp)
                    )
                }
                ModelRow(m.name, m.id, m.note, m.id == selected, enabled) { choose(m.id) }
            }

            if (extras.isNotEmpty()) {
                TextButton(onClick = { showExtras = !showExtras }) {
                    Text((if (showExtras) "▾ " else "▸ ") + "نماذج أخرى في حسابك (${extras.size})")
                }
                AnimatedVisibility(
                    visible = showExtras,
                    enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
                ) {
                    Column {
                        if (extras.size > 20) {
                            OutlinedTextField(
                                value = filter, onValueChange = { filter = it }, singleLine = true,
                                placeholder = { Text("بحث…") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        val shown = extras.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }
                        for (id in shown.take(60)) ModelRow(id, id, null, id == selected, enabled) { choose(id) }
                        if (shown.size > 60) Hint("و${shown.size - 60} نموذجًا آخر — اكتب جزءًا من الاسم للبحث", Modifier.padding(start = 8.dp))
                    }
                }
            }

            TextButton(onClick = { custom = !custom }) { Text((if (custom) "▾ " else "▸ ") + "كتابة اسم نموذج") }
            AnimatedVisibility(
                visible = custom,
                enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
                exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
            ) {
                OutlinedTextField(
                    value = selected,
                    onValueChange = { vm.chooseModel(it.trim()) },
                    singleLine = true,
                    enabled = enabled,
                    label = { Text("اسم النموذج كما في الـ API") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ModelRow(name: String, id: String, note: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (selected) scheme.primaryContainer.copy(alpha = 0.40f) else Color.Transparent, Motion.quick(), label = "model"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Hint(note, maxLines = 2)
            if (id != name) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(id, fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
