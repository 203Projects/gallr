package com.gallr.shared.util

/**
 * The particle 와 or 과 after [word]: 과 when the word's last spoken sound ends in a consonant (받침), 와 otherwise.
 * Closing punctuation, brackets and spaces are skipped to the last letter or digit. Digits follow their Sino-Korean
 * reading (1 일, 3 삼, 6 육, 7 칠, 8 팔, 0 영 end in a consonant); a Latin word ending in L, M or N reads with a final
 * ㄹ, ㅁ or ㄴ, any other letter as a vowel. A word with nothing to read takes 와.
 */
fun koreanWaGwa(word: String): String = if (endsInConsonant(word)) "과" else "와"

private fun endsInConsonant(word: String): Boolean {
    val last = word.lastOrNull { it.isLetterOrDigit() } ?: return false
    return when {
        last in HANGUL_SYLLABLES -> (last - HANGUL_SYLLABLES.first) % FINALS_PER_SYLLABLE != 0
        last.isDigit() -> last in CONSONANT_DIGITS
        else -> last.lowercaseChar() in CONSONANT_LATIN_ENDINGS
    }
}

private val HANGUL_SYLLABLES = '가'..'힣'
private const val FINALS_PER_SYLLABLE = 28
private const val CONSONANT_DIGITS = "013678"
private const val CONSONANT_LATIN_ENDINGS = "lmn"
