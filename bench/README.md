# OCR bench (live)

`make_frames.py` draws synthetic subtitle crops shaped like real hard-subbed frames (1230×201, white Arabic
in an opaque black box, Latin crew credits on the same rows). Nothing copyrighted is stored here.

`Bench.kt` reads them with the app's own Lens/Vision code on GitHub Actions (`ocr-bench.yml`) and writes the
report to the branch `ocr-bench-results` (`latest.md`). Google Cloud Vision runs only when the repository has
a `VISION_API_KEY` secret; the key is never printed.

Run: push a change under `bench/` to `android-app`.
