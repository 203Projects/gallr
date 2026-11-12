package com.gallr.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

// Value: protects=reasons such as “봄의 정원”과 read as Korean after a final consonant (spec 103 e2e defect 12);
// fails_when=koreanWaGwa ignores the 받침, reads the closing quote or bracket as the word's end, or misreads a digit;
// why_new=no particle helper existed; seam=the pure function; no_claim=other particles (이/가, 을/를)

class KoreanParticlesTest {
    @Test
    fun aSyllableWithAFinalConsonantTakesGwa() {
        assertEquals("과", koreanWaGwa("봄의 정원"))
        assertEquals("과", koreanWaGwa("빛"))
        assertEquals("과", koreanWaGwa("서울"))
    }

    @Test
    fun aSyllableWithoutAFinalConsonantTakesWa() {
        assertEquals("와", koreanWaGwa("바다"))
        assertEquals("와", koreanWaGwa("전시 회화"))
    }

    @Test
    fun closingPunctuationIsSkippedToTheLastSpokenCharacter() {
        assertEquals("과", koreanWaGwa("정원 (서울)"))
        assertEquals("와", koreanWaGwa("바다!"))
        assertEquals("과", koreanWaGwa("정원 "))
    }

    @Test
    fun digitsAndLatinLettersFollowHowTheyAreReadInKorean() {
        // 1 일, 3 삼, 6 육, 7 칠, 8 팔, 0 영 end in a consonant; 2 이, 4 사, 5 오, 9 구 do not.
        assertEquals("과", koreanWaGwa("Room 1"))
        assertEquals("과", koreanWaGwa("2026"))
        assertEquals("와", koreanWaGwa("Part 2"))
        assertEquals("와", koreanWaGwa("No. 9"))
        // Letters read with a final ㄹ, ㅁ or ㄴ (L, M, N) take 과; the rest read as vowels.
        assertEquals("과", koreanWaGwa("Film"))
        assertEquals("과", koreanWaGwa("Seoul"))
        assertEquals("와", koreanWaGwa("Studio"))
    }

    @Test
    fun aWordWithNothingToReadTakesWa() {
        assertEquals("와", koreanWaGwa(""))
        assertEquals("와", koreanWaGwa("…"))
    }
}
