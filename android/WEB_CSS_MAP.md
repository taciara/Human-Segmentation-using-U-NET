# Mapeamento CSS web → Android

Referência: `templates/index.html` (photobooth Fanta).

| CSS | Android |
|-----|---------|
| `:root` cores | `res/values/colors.xml` |
| `.screen` | `@style/BoothScreen` |
| `.top-logo` (#capture) | `@style/BoothTopLogo` + `@dimen/top_logo_capture` |
| `.drip-wrap` | `DripBackgroundView` |
| `.polaroid` | `PolaroidPaperDrawable` + `PolaroidFrameLayout` |
| `.viewfinder` 4/5 | `AspectRatioFrameLayout` |
| `.how` | `@layout/include_how_panel` + `@style/BoothHow*` |
| `.btn-shot`, `.btn-share` | `@style/BoothButtonFanta` |
| `.btn-again` | `@style/BoothButtonAgain` |
| `#ready .headline` / `.lead` | `@style/BoothReadyHeadline` / `BoothReadyLead` |
| `#qrBox` | `@dimen/qr_box_max` FrameLayout branco |

Dimensões `clamp()`/`vh` aproximadas em `res/values/dimens.xml`.
