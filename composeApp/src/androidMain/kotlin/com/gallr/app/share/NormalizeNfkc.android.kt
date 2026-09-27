package com.gallr.app.share

import java.text.Normalizer

internal actual fun normalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
