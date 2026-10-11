# Third-party notices

Hushutter itself is released under the MIT License (see `LICENSE`).
The APK also contains the following third-party software and fonts.

| Component | License | Source |
| --- | --- | --- |
| LibADB Android 3.1.1 | Apache-2.0 (dual-licensed GPL-3.0-or-later OR Apache-2.0; used under Apache-2.0) | https://github.com/MuntashirAkon/libadb-android |
| Spake2-Java (used by LibADB Android for wireless debugging pairing) | LGPL-3.0 | https://github.com/MuntashirAkon/spake2-java |
| Conscrypt 2.5.3 | Apache-2.0 | https://github.com/google/conscrypt |
| Bouncy Castle 1.81 (bcprov, bcpkix, bcutil) | MIT (Bouncy Castle License) | https://github.com/bcgit/bc-java |
| AndroidX Annotation 1.9.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/annotation |
| BIZ UDPGothic (subset) | SIL Open Font License 1.1 | https://github.com/googlefonts/morisawa-biz-ud-gothic |

## LGPL-3.0 (Spake2-Java)

Spake2-Java is used unmodified as a separate library. Its complete source code is available at the URL above.
Because the source code of Hushutter is public, you can rebuild the app with a modified version of the library.

## SIL Open Font License 1.1 (BIZ UDPGothic)

The app bundles a subset of BIZ UDPGothic (Copyright 2022 The BIZ UDGothic Project Authors) that contains only the
characters the app displays. The full license text is in `app/src/main/assets/licenses/OFL-BIZUDPGothic.txt` and is
also included in the APK.
