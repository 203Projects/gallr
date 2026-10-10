package com.gallr.app.ui.components

import com.gallr.shared.data.model.AppLanguage

/**
 * What a screen reader says for a back control drawn as the "←" glyph. Every back button sets this as its
 * content description and clears the glyph's own semantics, so TalkBack and VoiceOver announce "뒤로" / "Back"
 * instead of "Leftwards arrow".
 */
internal fun backButtonLabel(lang: AppLanguage): String =
    when (lang) {
        AppLanguage.KO -> "뒤로"
        AppLanguage.EN -> "Back"
    }
