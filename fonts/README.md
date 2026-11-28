# The app's font (encrypted)

`thmanyah.enc` is Thmanyah Sans (ثمانية سانس, © Thmanyah Publishing & Distribution, version 1.003),
five weights (Light, Regular, Medium, Bold, Black), the typeface of every text in the app, encrypted
with AES-256 (`openssl enc -aes-256-cbc -pbkdf2 -iter 200000`). The Thmanyah font license forbids
uploading or hosting the font files, so this repository holds only this encrypted copy. The key is the
repository secret `THMANYAH_FONT_KEY`; `tools/fonts.sh` decrypts it during the build and packs each
weight scrambled into the APK, where the app unscrambles it in memory (`ui/theme/AppFonts.kt`). No
plain font file is ever committed or put in the APK.

The font itself is available only from its official page: https://font.thmanyah.com
