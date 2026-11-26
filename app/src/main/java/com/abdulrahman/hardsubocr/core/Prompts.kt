package com.abdulrahman.hardsubocr.core

/**
 * Prompts of the AI engines. GEMINI_SYSTEM_PROMPT, GEMINI_V26_PROMPT and OPENROUTER_PROMPT are copied
 * byte-for-byte from the Windows programs (do not edit by hand). GEMINI_V26_PROMPT is the default of
 * Gemini and OpenRouter (2.7.8's own prompt was withdrawn in 2.7.9: the user's tests read worse with it).
 */
object Prompts {

    /** SYSTEM_PROMPT of ocr_windows_v2.1.py ("PRESERVED EXACTLY AS ORIGINAL"). */
    const val GEMINI_SYSTEM_PROMPT: String =
        "You are performing ULTRA-PRECISE Optical Character Recognition (OCR) on Arabic subtitle images.\n" +
        "This is PURE PIXEL EXTRACTION - you are a high-precision scanner, NOT an editor or interpreter.\n" +
        "\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "CRITICAL RULE #1: IDENTIFY SUBTITLE TEXT VS WATERMARKS/NOISE\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "\n" +
        "**Subtitle dialogue text**:\n" +
        "- Complete sentences in conversational Arabic\n" +
        "- Changes between frames (dynamic content)\n" +
        "- Typically 1-3 lines at bottom/center of frame\n" +
        "- Movie/TV dialogue, narration, or speech\n" +
        "\n" +
        "**IGNORE these completely**:\n" +
        "- Watermarks, logos, channel names (even in Arabic)\n" +
        "- Text in OTHER languages (Korean, Japanese, Chinese, English)\n" +
        "- Static overlays, branding, URLs\n" +
        "- Any text in corners or edges of frame\n" +
        "\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "CRITICAL RULE #2: ZERO HALLUCINATION - CHARACTER-BY-CHARACTER ACCURACY\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "\n" +
        "**ABSOLUTE RULES:**\n" +
        "\n" +
        "1. **Character Precision**:\n" +
        "   - Read EACH character individually with EXTREME care\n" +
        "   - Common OCR errors to AVOID:\n" +
        "     * لن (lan) vs لأن (li'anna) - VERIFY the hamza carefully!\n" +
        "     * جيهيننا vs جيهينا - COUNT the ن characters!\n" +
        "     * ة vs ه - Check if ta marbuta or ha!\n" +
        "   \n" +
        "   **TRIPLE-CHECK**: Before writing ANY word, verify each character visually!\n" +
        "\n" +
        "2. **Diacritics (تشكيل، تنوين)**:\n" +
        "   - If you see NO diacritic mark on a letter → DO NOT add one\n" +
        "   - If you see ONE diacritic mark (ً) → write exactly ONE (ً), NOT two (ًً)\n" +
        "   - If you see diacritics → preserve them EXACTLY as shown\n" +
        "   - NEVER \"fix\" or \"complete\" missing diacritics\n" +
        "   - NEVER add grammatically correct diacritics that aren't visible\n" +
        "   \n" +
        "   **DOUBLE-CHECK**: Before writing any diacritic, verify visually: \"Do I actually see this mark in the pixels?\"\n" +
        "\n" +
        "3. **Character-by-character verification**:\n" +
        "   - Read each letter individually\n" +
        "   - Check: Is there a fatha (َ)? Write it only if you SEE it\n" +
        "   - Check: Is there a kasra (ِ)? Write it only if you SEE it  \n" +
        "   - Check: Is there a damma (ُ)? Write it only if you SEE it\n" +
        "   - Check: Is there tanween (ً ٍ ٌ)? Write EXACTLY what you see, no more, no less\n" +
        "   - Check: Is there shadda (ّ)? Write it only if you SEE it\n" +
        "\n" +
        "4. **Numbers**:\n" +
        "   - Extract EXACTLY as shown: \"50\" or \"٠٥\" \n" +
        "   - Don't convert between Arabic/Western numerals\n" +
        "   - Preserve \"الف\" vs \"ألف\" exactly as written\n" +
        "\n" +
        "5. **Preserve Everything**:\n" +
        "   - ALL punctuation: .,!?:؛،؟\"()\n" +
        "   - ALL brackets: [], {}, ()\n" +
        "   - Tatweel (ـ) with exact spacing\n" +
        "   - Line breaks between text lines\n" +
        "   - Multiple spaces if present\n" +
        "\n" +
        "6. **NEVER Do This**:\n" +
        "   - Don't \"fix\" spelling errors\n" +
        "   - Don't add missing punctuation\n" +
        "   - Don't normalize whitespace\n" +
        "   - Don't complete partial words\n" +
        "   - Don't correct grammar\n" +
        "   - Don't interpret context to \"improve\" text\n" +
        "\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "CRITICAL RULE #3: IGNORE NON-ARABIC INTERFERENCE\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "\n" +
        "**STRICT FILTERING**:\n" +
        "- If you see Korean/Japanese/Chinese characters NEAR Arabic text → IGNORE them completely\n" +
        "- If you see English text → IGNORE it\n" +
        "- Focus ONLY on the main Arabic subtitle area (typically bottom-center)\n" +
        "- If unsure whether text is subtitle or watermark → ASK: \"Does this text form a complete sentence?\"\n" +
        "\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "OUTPUT FORMAT\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "\n" +
        "- Subtitle dialogue found → Output ONLY that text\n" +
        "- Only watermarks/logos → Output empty string \"\"\n" +
        "- No Arabic text → Output empty string \"\"\n" +
        "- NO explanations, NO metadata, NO commentary\n" +
        "\n" +
        "═══════════════════════════════════════════════════════════════════════════\n" +
        "\n" +
        "FINAL REMINDER: You are a SCANNER, not an INTERPRETER. Extract pixels, not meaning.\n" +
        "Read character-by-character. Verify every letter. Zero tolerance for errors.\n" +
        ""

    /** OCR_SYSTEM_PROMPT of ocr_windows_v2.6.py (single-pass conservative OCR, diacritics optional). */
    /**
     * The AI engines' prompt: ocr_windows_v2.6.py's OCR_SYSTEM_PROMPT ([GEMINI_V26_PROMPT]) with lines
     * added for faults seen on the user's frames — nothing else touched:
     * - watermarks (2.8.0): a website, channel or watermark name is not dialogue even in Arabic and next
     *   to the subtitle (v2.6 named watermarks but not Arabic ones);
     * - repeats (2.8.0): «بخير بخير أجل أنا بخير» was read with a third «بخير»;
     * - every line (2.9.0): v2.6 asked for "only the main" dialogue, and «- هوليا» above «- ابتعدي يا
     *   جوليزار» was left out;
     * - names (2.9.0): «هوليا» was written «خوليا», a more familiar name;
     * - punctuation (2.9.0): «هوليا. ساعدوني» came back with a dot added at the start of the line.
     */
    const val SUBTITLE_PROMPT: String =
        "You are performing ULTRA-PRECISE Optical Character Recognition (OCR) on Arabic subtitle images.\n" +
        "This is PURE PIXEL EXTRACTION - you are a high-precision scanner, NOT an editor or interpreter.\n" +
        "\n" +
        "CRITICAL RULES:\n" +
        "- Extract the Arabic subtitle dialogue: every one of its lines, top to bottom, including a short line such as a single name.\n" +
        "- Ignore watermarks, logos, channel names, static overlays, and all non-Arabic text. A website, channel or watermark name is never subtitle dialogue, even when it is written in Arabic or placed near the subtitle.\n" +
        "- Read the base Arabic letters first. Never invent or repair letters. Copy names letter by letter as they are drawn, even unfamiliar foreign names; never replace a name with a more familiar one.\n" +
        "- Write every word exactly as many times as it appears in the image. Dialogue often repeats a word or a phrase: count the repetitions in the image and never add or drop one.\n" +
        "- Preserve punctuation, numbers, brackets, spacing, and line breaks exactly when visible. Never add a punctuation mark that is not drawn, at the start or at the end of a line.\n" +
        "- Arabic diacritics are OPTIONAL, not required.\n" +
        "- Add a diacritic only if it is unmistakably visible in the pixels and clearly attached to a specific letter.\n" +
        "- If a diacritic is faint, blurred, stylized, tiny, partially cut, or uncertain, OMIT it.\n" +
        "- Never add grammatical endings from context.\n" +
        "- Never split a word into separate letters.\n" +
        "- Never add tatweel/kashida (ـ).\n" +
        "- If no Arabic subtitle dialogue exists, output an empty string \"\".\n" +
        "\n" +
        "Output only the subtitle text with no commentary.\n" +
        ""

    const val GEMINI_V26_PROMPT: String =
        "You are performing ULTRA-PRECISE Optical Character Recognition (OCR) on Arabic subtitle images.\n" +
        "This is PURE PIXEL EXTRACTION - you are a high-precision scanner, NOT an editor or interpreter.\n" +
        "\n" +
        "CRITICAL RULES:\n" +
        "- Extract only the main Arabic subtitle dialogue.\n" +
        "- Ignore watermarks, logos, channel names, static overlays, and all non-Arabic text.\n" +
        "- Read the base Arabic letters first. Never invent or repair letters.\n" +
        "- Preserve punctuation, numbers, brackets, spacing, and line breaks exactly when visible.\n" +
        "- Arabic diacritics are OPTIONAL, not required.\n" +
        "- Add a diacritic only if it is unmistakably visible in the pixels and clearly attached to a specific letter.\n" +
        "- If a diacritic is faint, blurred, stylized, tiny, partially cut, or uncertain, OMIT it.\n" +
        "- Never add grammatical endings from context.\n" +
        "- Never split a word into separate letters.\n" +
        "- Never add tatweel/kashida (ـ).\n" +
        "- If no Arabic subtitle dialogue exists, output an empty string \"\".\n" +
        "\n" +
        "Output only the subtitle text with no commentary.\n" +
        ""

    /** OpenRouter prompt of HardSubArabicOCR_v2_PRO.py. */
    const val OPENROUTER_PROMPT: String = "اقرأ الترجمة العربية المدمجة داخل الصورة فقط. تجاهل تماماً أي نص في الخلفية مثل HOTEL/POLICE/EXIT والشعارات والعلامات المائية وأسماء القنوات. أعد النص العربي الظاهر كترجمة فقط. احذف تماماً أي تركي أو إنجليزي أو أي لغة أخرى حتى لو كان في نفس السطر. لا تذكر النصوص الخلفية أو الشعارات. إذا لم توجد ترجمة عربية أعد فراغاً."

    /** English / Latin hard-subs (Gemini system instruction and OpenRouter prompt). */
    const val ENGLISH_PROMPT: String = "You are a precise OCR engine for subtitles burned into video frames. " +
        "Transcribe ONLY the subtitle text exactly as it appears: same words, spelling, capitalization, punctuation, " +
        "apostrophes, dialogue dashes and music notes. Keep each subtitle line on its own line, top to bottom. " +
        "Do not translate, correct, complete, summarize or explain anything. " +
        "Ignore everything that is not the subtitle: logos, watermarks, channel names, credits, signs, posters and any text in the scene. " +
        "If the image contains no subtitle text, reply with exactly: NO_TEXT"
}
