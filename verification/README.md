# Verification harness (optional, not part of the Gradle build)

Paths inside the scripts point to the machine where they were run; adjust them.

| Script | What it proves |
|---|---|
| `gen_vectors.py` + `CoreTest.kt` | Runs the ORIGINAL Python functions (arabic_filter, norm_text, _clean_suspicious_numbers, clean_ocr_output, difflib ratio, merge_entries, merge_ocr_entries) on 11,432 generated cases and checks the Kotlin port gives identical output. Result: 0 differences. |
| `lens_proto.py` + `LensTest.kt` | Decodes the Kotlin-built Lens request with the official protobuf runtime (Chromium field numbers) and re-serialises it byte-identically; builds a Lens response for the Kotlin parser. |
| `gen_video*.py` + `SegTest.kt` | Synthetic 720p/1080p hard-sub video (moving bright background, white flash, yellow text, fades, back-to-back changes); all 7 subtitles found, boundaries within 1 frame. |
| `PipeTest.kt` | Parallel workers, 429 back-off, resume cache, fatal stop with partial results, cancellation, failure breaker. |
| `ApiTest.kt` | Gemini / OpenRouter / Lens request bodies, response parsing and error classification. |
| `vision/VisionTest.kt` | Google Cloud Vision request (key in header, DOCUMENT_TEXT_DETECTION by default or TEXT_DETECTION, language hint of the subtitles), parsing and error classification. |
| `vision/FocusTest.kt` | Google Vision report next to the SRT (`report_sample.txt`) and two-stage reading: when the subtitle shares its row with other text (crew names) or is read unsure, the subtitle's area is cropped from the same picture and read again; the better reading is kept. Areas checked on the measured geometry of real frames. |
| `lens/LensOrderTest.kt` | Google Lens lines in reading order (rows top to bottom, right to left in Arabic) and the same place read twice. |
| `vision/LayoutTest.kt` + `LayoutCommon.kt` + `LayoutFuzz.kt` | Vision text rebuilt from word boxes: same place read twice, blocks laid out left to right, punctuation, two lines, credits, Latin runs; 14,000 random lines (clean reads unchanged, copies removed). |

`kc.sh` compiles Kotlin with the compiler embedded in a Gradle distribution:
`./kc.sh out ../app/src/main/java/com/abdulrahman/hardsubocr/core/*.kt CoreTest.kt`
