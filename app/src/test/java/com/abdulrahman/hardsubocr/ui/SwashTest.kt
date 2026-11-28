package com.abdulrahman.hardsubocr.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import com.abdulrahman.hardsubocr.ui.theme.AppFonts
import com.abdulrahman.hardsubocr.ui.theme.AppTheme
import com.abdulrahman.hardsubocr.ui.theme.Swash
import com.abdulrahman.hardsubocr.ui.theme.swashTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Thmanyah's swash letters in titles: one word at most, the first whose last letter has a swash form
 * in Thmanyah Sans (the final ت of «ملخصات» in Thmanyah's own app), and the font really draws it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SwashTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun pick(t: String): String? = Swash.word(t)?.let { t.substring(it.first, it.last + 1) }

    @Test
    fun one_word_per_title() {
        assertEquals("ملخصات", pick("ملخصات الجولة الثانية عشر"))
        assertEquals("الإعدادات", pick("الإعدادات"))
        assertEquals("العمليات", pick("سجل العمليات"))
        assertEquals("محرك", pick("محرك القراءة"))
        assertEquals("ملف", pick("ملف SRT"))
        assertEquals("التشخيص", pick("سجل التشخيص"))
        assertEquals("الوقت", pick("الوقت في الفيديو")) // never «في» too: not on neighbouring words
        assertEquals("الشك؟", pick("إعادة القراءة عند الشك؟"))
        assertEquals("رسوم", pick("رسوم")) // a lone م has a swash form
        assertEquals("الخدماتُ", pick("الخدماتُ"))
        assertEquals("مجلدات", pick("مجلدات متعددة"))
        assertEquals("في", pick("لغة الترجمة في الفيديو/الصور"))
        assertEquals("المحرك", pick("المحرك"))
        assertEquals("حذف", pick("حذف المفتاح؟"))
        assertEquals("الآن", pick("لا توجد مهمة الآن"))
        assertEquals("الإعدادات", pick("الإعدادات"))
    }

    @Test
    fun no_swash_where_the_font_has_none() {
        assertEquals(null, pick("تم الاستخراج")) // a final م has no swash form
        assertEquals(null, pick("علم"))
        assertEquals(null, pick("المعالجة"))
        assertEquals(null, pick("الفيديو"))
        assertEquals(null, pick("HardSub OCR"))
        assertEquals(null, pick("الحلقة 12"))
        assertEquals(null, pick("بـ Gemini")) // a tatweel after the letter: not final
        assertEquals(null, pick("كتب")) // a ك inside would take a second swash
        assertEquals(null, pick(""))
        assertEquals(null, pick("المصدر")) // ر has no swash form in Thmanyah Sans
        assertEquals(null, pick("جاهز للاستخراج"))
    }

    @Test
    fun the_font_draws_the_swash() {
        Assume.assumeTrue("Thmanyah font not packed in this build", AppFonts.family(rule.activity) != null)
        rule.setContent {
            AppTheme {
                Column {
                    val st = MaterialTheme.typography.titleMedium
                    Text("ملخصات الجولة", Modifier.testTag("plain"), style = st, fontWeight = FontWeight.Bold)
                    Text(swashTitle("ملخصات الجولة"), Modifier.testTag("swash"), style = st, fontWeight = FontWeight.Bold)
                }
            }
        }
        val plain = rule.onNodeWithTag("plain").fetchSemanticsNode().size
        val swash = rule.onNodeWithTag("swash").fetchSemanticsNode().size
        // the long final ت makes the line wider; the height (one line) stays the same
        assertTrue("swash ${swash.width} vs plain ${plain.width}", swash.width > plain.width)
        assertEquals(plain.height, swash.height)
    }
}
