# OCR bench (live)

`make_frames.py` draws synthetic subtitle crops shaped like real hard-subbed frames (1230×201, white Arabic
in an opaque black box, Latin crew credits on the same rows). Nothing copyrighted is stored here.

`Bench.kt` reads them with the app's own Lens/Vision code on GitHub Actions (`ocr-bench.yml`) and writes the
report to the branch `ocr-bench-results` (`latest.md`). Google Cloud Vision runs only when the repository has
a `VISION_API_KEY` secret; the key is never printed.

Gemini / OpenRouter run when the commit message contains `[bench-ai]` and the repository has a
`GEMINI_API_KEY` / `OPENROUTER_API_KEY` secret (never printed); `[ai-only]` reads only the AI engines'
hard frames (01–09, 25, the video-mode areas 26–31). The model is the one of the user's reports
(Gemini 3.5 Flash-Lite); each variant reads each frame three times, and the report ends with the faults
per variant (a line left out, «خوليا», letters wrong), the cross-check's decisions and the run's cost.

Run: push a change under `bench/` to `android-app`.
