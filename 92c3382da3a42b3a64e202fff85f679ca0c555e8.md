# OCR bench — commit 92c3382da3a42b3a64e202fff85f679ca0c555e8 — 2026-11-27T01:42:11.304138620Z

Vision: no VISION_API_KEY secret — Lens only
AI: OpenRouter google/gemini-3.5-flash-lite — versions of the reading: 2.8.0 · 2.9.0 one reading · 2.9.0 cross-check (each 3 times)

## 01_habibi_credits_bg0.jpg — credits both sides, background variant 0
truth: «حبيبي، هل أنت بخير؟»

## 01_habibi_credits_bg1.jpg — credits both sides, background variant 1
truth: «حبيبي، هل أنت بخير؟»

## 01_habibi_credits_bg2.jpg — credits both sides, background variant 2
truth: «حبيبي، هل أنت بخير؟»

## 02_habibi_alone.jpg — no credits
truth: «حبيبي، هل أنت بخير؟»

## 03_abi_over_credit_a255.jpg — box covers the middle of a credit name, credit alpha 255
truth: «أبي»

## 03_abi_over_credit_a150.jpg — box covers the middle of a credit name, credit alpha 150
truth: «أبي»

## 04_two_lines.jpg — two lines
truth: «أجل، أنا بخير. ⏎ لا تقلق عليّ.»

## 05_long_line_credits.jpg — long line, short credits at the edges
truth: «لا أعرف ماذا سأفعل الآن، كل شيء تغيّر»

## 06_short_words.jpg — short words, one credit
truth: «لا، لم يحدث شيء»

## 07_short_0_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «نعم»

## 07_short_0_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «نعم»

## 07_short_1_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «أمي»

## 07_short_1_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «أمي»

## 07_short_2_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «ماذا؟»

## 07_short_2_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «ماذا؟»

## 07_short_3_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «حسنًا»

## 07_short_3_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «حسنًا»

## 07_short_4_a255.jpg — short word in a small box over a credit name, alpha 255
truth: «لماذا؟»

## 07_short_4_a170.jpg — short word in a small box over a credit name, alpha 170
truth: «لماذا؟»

## 08_dialogue_dashes.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- openrouter pictures: V26 2166×314, V26_FRAMED 1088×314, V26_FRAMED_NO_UPSCALE 544×157, COLOR 1230×230, COLOR_FRAMED 544×205
  - openrouter 2.8.0 #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.8.0 #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.8.0 #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»

## 09_two_lines_period.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «هوليا. ساعدوني ⏎ ماذا حدث؟»

- openrouter pictures: V26 2166×314, V26_FRAMED 974×314, V26_FRAMED_NO_UPSCALE 487×157, COLOR 1230×230, COLOR_FRAMED 487×197
  - openrouter 2.8.0 #1 ✗ (1) shape ✗ «هوليا، ساعدوني ⏎ ماذا حدث؟» «هوليا، ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.8.0 #2 ✗ (1) shape ✗ «هوليا، ساعدوني ماذا حدث؟» «هوليا، ساعدوني ماذا حدث؟»
  - openrouter 2.8.0 #3 ✗ (1) shape ✗ «هوليا، ساعدوني ⏎ ماذا حدث؟» «هوليا، ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 one reading #1 ✗ (1) shape ✗ «هوليا، ساعدوني ⏎ ماذا حدث؟» «هوليا، ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 one reading #2 ✗ (1) shape ✗ «هوليا، ساعدوني ⏎ ماذا حدث؟» «هوليا، ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 one reading #3 ✗ (1) shape ✗ «هوليا، ساعدوني ⏎ ماذا حدث؟» «هوليا، ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 cross-check #1 ✗ (1) shape ✗ «هوليا، ساعدوني ⏎ ماذا حدث؟» «هوليا، ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 cross-check #2 ✓ shape ✓ «هوليا. ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 cross-check #3 ✗ (1) shape ✗ «هوليا، ساعدوني ⏎ ماذا حدث؟» «هوليا، ساعدوني ⏎ ماذا حدث؟»

## 10_dash_one_line.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «- لا أعرف»

## 11_ellipsis.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «انتظر... لا تذهب!»

## 12_dialogue_question.jpg — outlined white text, the shape (lines, dashes, punctuation) must be kept
truth: «- هل أنت بخير؟ ⏎ - نعم، أنا بخير»

## 13_dialogue_box.jpg — two-line subtitle in the box, second line wider
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

## 14_dialogue_box_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

## 15_dialogue_box_thin_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

## 16_dialogue_box_long2_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- ماذا؟ ⏎ - لا شيء، عودي إلى البيت الآن»

## 17_dialogue_box_nospace_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

## 18_one_speaker_wrap_credits.jpg — two-line subtitle in the box, second line wider, credits on its rows
truth: «- لا أعرف ماذا سأفعل ⏎ بعد كل ما حدث»

## 19_shadow_hear_me.jpg — white text, thin outline, soft drop shadow, no box (bright scene, dark suit)
truth: «هل تسمعينني؟ هل تسمعينني؟ انظري إلي»

## 20_shadow_hear_me_serif.jpg — white text, thin outline, soft drop shadow, no box (bright scene, dark suit)
truth: «هل تسمعينني؟ هل تسمعينني؟ انظري إلي»

## 21_shadow_wound.jpg — white text, thin outline, soft drop shadow, no box (bright scene, dark suit)
truth: «قمت بالضغط المباشر على الجرح»

## 22_shadow_wound_serif.jpg — white text, thin outline, soft drop shadow, no box (bright scene, dark suit)
truth: «قمت بالضغط المباشر على الجرح»

## 23_frame_hear_me.jpg — whole frame 1500×844, shadowed subtitle at the bottom
truth: «هل تسمعينني؟ هل تسمعينني؟ انظري إلي»

## 24_frame_wound.jpg — whole frame 1500×844, shadowed subtitle at the bottom
truth: «قمت بالضغط المباشر على الجرح»

## 25_frame_dialogue.jpg — whole frame 1500×844, shadowed subtitle at the bottom
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- openrouter pictures: V26 1320×515, V26_FRAMED 745×330, V26_FRAMED_NO_UPSCALE 497×220, COLOR 1500×844, COLOR_FRAMED 497×295
  - openrouter 2.8.0 #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.8.0 #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.8.0 #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»

## 26_roi_wake_tiles.jpg — video mode area 1920×432, outlined white subtitle on a bright scene (tiles)
truth: «هوليا. استيقظي»

- openrouter pictures: V26 1920×432, V26_FRAMED 1178×312, V26_FRAMED_NO_UPSCALE 589×156, COLOR 1920×432, COLOR_FRAMED 589×156
  - openrouter 2.8.0 #1 ✗ (3) shape ✗ «أهوليا، استيقظي.» «أهوليا، استيقظي.»
  - openrouter 2.8.0 #2 ✗ (3) shape ✗ «هوليام، استيقظي.» «هوليام، استيقظي.»
  - openrouter 2.8.0 #3 ✗ (2) shape ✗ «هوليام، استيقظي» «هوليام، استيقظي»
  - openrouter 2.9.0 one reading #1 ✗ (1) shape ✗ «هوليا، استيقظي» «هوليا، استيقظي»
  - openrouter 2.9.0 one reading #2 ✗ (1) shape ✗ «هوليا، استيقظي» «هوليا، استيقظي»
  - openrouter 2.9.0 one reading #3 ✗ (1) shape ✗ «هوليا، استيقظي» «هوليا، استيقظي»
  - openrouter 2.9.0 cross-check #1 ✗ (1) shape ✗ «هوليا، استيقظي» «هوليا، استيقظي»
  - openrouter 2.9.0 cross-check #2 ✗ (1) shape ✗ «هوليا، استيقظي» «هوليا، استيقظي»
  - openrouter 2.9.0 cross-check #3 ✗ (1) shape ✗ «هوليا، استيقظي» «هوليا، استيقظي»

## 27_roi_last_worry_lace.jpg — video mode area 1920×432, outlined white subtitle on a bright scene (lace)
truth: «هوليا هي آخر همي الآن»

- openrouter pictures: V26 1920×432, V26_FRAMED 1630×350, V26_FRAMED_NO_UPSCALE 815×175, COLOR 1920×432, COLOR_FRAMED 815×175
  - openrouter 2.8.0 #1 ✗ (1) shape ✗ «خوليا هي آخر همي الآن» «خوليا هي آخر همي الآن»
  - openrouter 2.8.0 #2 ✗ (1) shape ✗ «خوليا هي آخر همي الآن» «خوليا هي آخر همي الآن»
  - openrouter 2.8.0 #3 ✓ shape ✓ «هوليا هي آخر همي الآن»
  - openrouter 2.9.0 one reading #1 ✓ shape ✓ «هوليا هي آخر همي الآن»
  - openrouter 2.9.0 one reading #2 ✓ shape ✓ «هوليا هي آخر همي الآن»
  - openrouter 2.9.0 one reading #3 ✓ shape ✓ «هوليا هي آخر همي الآن»
  - openrouter 2.9.0 cross-check #1 ✓ shape ✓ «هوليا هي آخر همي الآن»
  - openrouter 2.9.0 cross-check #2 ✗ (1) shape ✗ «خوليا هي آخر همي الآن» «خوليا هي آخر همي الآن»
  - openrouter 2.9.0 cross-check #3 ✓ shape ✓ «هوليا هي آخر همي الآن»

## 28_roi_dialogue_red.jpg — video mode area 1920×432, outlined white subtitle on a bright scene (red_skin)
truth: «- هوليا ⏎ - ابتعدي يا جوليزار»

- openrouter pictures: V26 1920×432, V26_FRAMED 984×340, V26_FRAMED_NO_UPSCALE 656×227, COLOR 1920×432, COLOR_FRAMED 656×227
  - openrouter 2.8.0 #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.8.0 #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.8.0 #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 one reading #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #1 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #2 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»
  - openrouter 2.9.0 cross-check #3 ✓ shape ✓ «- هوليا ⏎ - ابتعدي يا جوليزار»

## 29_roi_help_garden.jpg — video mode area 1920×432, outlined white subtitle on a bright scene (garden)
truth: «هوليا. ساعدوني ⏎ ماذا حدث؟»

- openrouter pictures: V26 1920×432, V26_FRAMED 871×336, V26_FRAMED_NO_UPSCALE 581×224, COLOR 1920×432, COLOR_FRAMED 581×224
  - openrouter 2.8.0 #1 ✗ (1) shape ✗ «هوليا، ساعدوني ماذا حدث؟» «هوليا، ساعدوني ماذا حدث؟»
  - openrouter 2.8.0 #2 ✗ (1) shape ✗ «هوليا، ساعدوني ماذا حدث؟» «هوليا، ساعدوني ماذا حدث؟»
  - openrouter 2.8.0 #3 ✗ (1) shape ✗ «هوليا، ساعدوني ماذا حدث؟» «هوليا، ساعدوني ماذا حدث؟»
  - openrouter 2.9.0 one reading #1 ✗ (2) shape ✗ «هوليا، ساعدوني! ماذا حدث؟» «هوليا، ساعدوني! ماذا حدث؟»
  - openrouter 2.9.0 one reading #2 ✓ shape ✓ «هوليا. ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 one reading #3 ✗ (2) shape ✗ «هوليا، ساعدوني! ماذا حدث؟» «هوليا، ساعدوني! ماذا حدث؟»
  - openrouter 2.9.0 cross-check #1 ✓ shape ✓ «هوليا. ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 cross-check #2 ✓ shape ✓ «هوليا. ساعدوني ⏎ ماذا حدث؟»
  - openrouter 2.9.0 cross-check #3 ✓ shape ✓ «هوليا. ساعدوني ⏎ ماذا حدث؟»

## 30_roi_fine_tiles.jpg — video mode area 1920×432, outlined white subtitle on a bright scene (tiles)
truth: «بخير بخير أجل أنا بخير»

- openrouter pictures: V26 1920×432, V26_FRAMED 1532×346, V26_FRAMED_NO_UPSCALE 766×173, COLOR 1920×432, COLOR_FRAMED 766×173
  - openrouter 2.8.0 #1 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.8.0 #2 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.8.0 #3 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.9.0 one reading #1 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.9.0 one reading #2 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.9.0 one reading #3 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.9.0 cross-check #1 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.9.0 cross-check #2 ✓ shape ✓ «بخير بخير أجل أنا بخير»
  - openrouter 2.9.0 cross-check #3 ✓ shape ✓ «بخير بخير أجل أنا بخير»

## 31_roi_mom_lace.jpg — video mode area 1920×432, outlined white subtitle on a bright scene (lace)
truth: «أريد أمي»

- openrouter pictures: V26 1920×432, V26_FRAMED 866×366, V26_FRAMED_NO_UPSCALE 433×183, COLOR 1920×432, COLOR_FRAMED 433×183
  - openrouter 2.8.0 #1 ✓ shape ✓ «أريد أمي»
  - openrouter 2.8.0 #2 ✓ shape ✓ «أريد أمي»
  - openrouter 2.8.0 #3 ✓ shape ✓ «أريد أمي»
  - openrouter 2.9.0 one reading #1 ✓ shape ✓ «أريد أمي»
  - openrouter 2.9.0 one reading #2 ✓ shape ✓ «أريد أمي»
  - openrouter 2.9.0 one reading #3 ✓ shape ✓ «أريد أمي»
  - openrouter 2.9.0 cross-check #1 ✓ shape ✓ «أريد أمي»
  - openrouter 2.9.0 cross-check #2 ✓ shape ✓ «أريد أمي»
  - openrouter 2.9.0 cross-check #3 ✓ shape ✓ «أريد أمي»

AI usage of this run: 116 صورة • لكل صورة في المتوسط: إدخال 1351 وحدة، إخراج 9، تفكير 0 • التكلفة 0.0499$

Cross-check decisions:

- 08_dialogue_dashes.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 08_dialogue_dashes.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 08_dialogue_dashes.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 09_two_lines_period.jpg 2 readings: هوليا، ساعدوني ⏎ ماذا حدث؟ | هوليا، ساعدوني ⏎ ماذا حدث؟ → هوليا، ساعدوني ⏎ ماذا حدث؟
- 09_two_lines_period.jpg 3 readings: هوليا، ساعدوني ⏎ ماذا حدث؟ | هوليا. ساعدوني ⏎ ماذا حدث؟ | هوليا. ساعدوني ⏎ ماذا حدث؟ → هوليا. ساعدوني ⏎ ماذا حدث؟
- 09_two_lines_period.jpg 3 readings: هوليا. ساعدوني ⏎ ماذا حدث؟ | هوليا، ساعدوني ماذا حدث؟ | هوليا، ساعدوني ⏎ ماذا حدث؟ → هوليا، ساعدوني ⏎ ماذا حدث؟
- 25_frame_dialogue.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 25_frame_dialogue.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 25_frame_dialogue.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 26_roi_wake_tiles.jpg 3 readings: هوليا، استيقظي | هوليام، استيقظي. | هوليا، استيقظي → هوليا، استيقظي
- 26_roi_wake_tiles.jpg 3 readings: هوليا، استيقظي | هوليام، استيقظي | هوليا. استيقظي → هوليا، استيقظي
- 26_roi_wake_tiles.jpg 3 readings: هوليا، استيقظي | أهوليام، استيقظي! | خوليا، استيقظي → هوليا، استيقظي
- 27_roi_last_worry_lace.jpg 2 readings: هوليا هي آخر همي الآن | هوليا هي آخر همي الآن → هوليا هي آخر همي الآن
- 27_roi_last_worry_lace.jpg 2 readings: خوليا هي آخر همي الآن | خوليا هي آخر همي الآن → خوليا هي آخر همي الآن
- 27_roi_last_worry_lace.jpg 3 readings: خوليا هي آخر همي الآن | هوليا هي آخر همي الآن | هوليا هي آخر همي الآن → هوليا هي آخر همي الآن
- 28_roi_dialogue_red.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 28_roi_dialogue_red.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 28_roi_dialogue_red.jpg 2 readings: - هوليا ⏎ - ابتعدي يا جوليزار | - هوليا ⏎ - ابتعدي يا جوليزار → - هوليا ⏎ - ابتعدي يا جوليزار
- 29_roi_help_garden.jpg 3 readings: هوليا. ساعدوني ⏎ ماذا حدث؟ | هوليا، ساعدوني ماذا حدث؟ | هوليا. ساعدوني ⏎ ماذا حدث؟ → هوليا. ساعدوني ⏎ ماذا حدث؟
- 29_roi_help_garden.jpg 3 readings: هوليا. ساعدوني ⏎ ماذا حدث؟ | هوليا، ساعدوني ماذا حدث؟ | هوليا. ساعدوني ⏎ ماذا حدث؟ → هوليا. ساعدوني ⏎ ماذا حدث؟
- 29_roi_help_garden.jpg 2 readings: هوليا. ساعدوني ⏎ ماذا حدث؟ | هوليا. ساعدوني ⏎ ماذا حدث؟ → هوليا. ساعدوني ⏎ ماذا حدث؟
- 30_roi_fine_tiles.jpg 2 readings: بخير بخير أجل أنا بخير | بخير بخير أجل أنا بخير → بخير بخير أجل أنا بخير
- 30_roi_fine_tiles.jpg 2 readings: بخير بخير أجل أنا بخير | بخير بخير أجل أنا بخير → بخير بخير أجل أنا بخير
- 30_roi_fine_tiles.jpg 2 readings: بخير بخير أجل أنا بخير | بخير بخير أجل أنا بخير → بخير بخير أجل أنا بخير
- 31_roi_mom_lace.jpg 2 readings: أريد أمي | أريد أمي → أريد أمي
- 31_roi_mom_lace.jpg 2 readings: أريد أمي | أريد أمي → أريد أمي
- 31_roi_mom_lace.jpg 2 readings: أريد أمي | أريد أمي → أريد أمي

## AI faults (runs)

| variant | line left out | «خوليا» | dot at a line start | letters wrong | failed |
|---|---|---|---|---|---|
| openrouter 2.8.0 | 4 | 2 | 0 | 11 | 0 |
| openrouter 2.9.0 one reading | 2 | 0 | 0 | 8 | 0 |
| openrouter 2.9.0 cross-check | 0 | 1 | 0 | 6 | 0 |

## Summary (after the app's classic cleaning; errors = edit distance to the truth)

| engine | exact | char errors | CER |
|---|---|---|---|
| openrouter 2.8.0 | 16/27 | 16 | 2.9% |
| openrouter 2.9.0 one reading | 19/27 | 10 | 1.8% |
| openrouter 2.9.0 cross-check | 21/27 | 6 | 1.1% |

## Shape kept (lines, dialogue dashes, punctuation — after the app's Lens/Vision cleaning)

| engine | exact shape |
|---|---|
| openrouter 2.8.0 (with tashkeel) | 16/27 |
| openrouter 2.9.0 one reading (with tashkeel) | 19/27 |
| openrouter 2.9.0 cross-check (with tashkeel) | 21/27 |
