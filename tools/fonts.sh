#!/usr/bin/env bash
# The Thmanyah fonts for the build (Build APK and UI screenshots workflows).
#
# Thmanyah's license forbids uploading or hosting its font files, so the repository holds only an
# encrypted copy (fonts/thmanyah.enc, AES-256) whose key is the repository secret THMANYAH_FONT_KEY.
# The fonts are decrypted here, packed scrambled into the APK's assets (tools/pack_fonts.py) and never
# committed (app/src/main/assets/type is ignored by git). Without the key or the encrypted copy the app
# is built with the system font, as before.
set -euo pipefail
if [ -z "${THMANYAH_FONT_KEY:-}" ] || [ ! -f fonts/thmanyah.enc ]; then
  echo "Thmanyah font: no key or no encrypted copy — the app uses the system font"
  exit 0
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in fonts/thmanyah.enc -pass env:THMANYAH_FONT_KEY | tar -xz -C "$work"
python3 tools/pack_fonts.py "$work" app/src/main/assets/type
echo "Thmanyah font packed: $(ls app/src/main/assets/type | tr '\n' ' ')"
