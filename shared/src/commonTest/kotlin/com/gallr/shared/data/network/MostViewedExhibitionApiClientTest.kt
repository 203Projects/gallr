package com.gallr.shared.data.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 091 FR-019: the most viewed read is an ordered list of exhibition ids and nothing else. */
class MostViewedExhibitionApiClientTest {
    @Test
    fun theRankingKeepsTheServersOrder() {
        assertEquals(listOf("b", "a", "c"), decodeMostViewedExhibitionIds("""["b","a","c"]"""))
    }

    @Test
    fun blankRepeatedAndNonTextRowsAreDropped() {
        assertEquals(
            listOf("b", "a"),
            decodeMostViewedExhibitionIds("""["b"," ","a",7,null,{"id":"x"},"b",""]"""),
        )
    }

    @Test
    fun anythingButAListIsAnEmptyRanking() {
        assertEquals(emptyList(), decodeMostViewedExhibitionIds("""{"code":"PGRST202","message":"missing"}"""))
        assertEquals(emptyList(), decodeMostViewedExhibitionIds("not json"))
        assertEquals(emptyList(), decodeMostViewedExhibitionIds("[]"))
    }

    @Test
    fun theRequestNamesTheWindowAndTheLimit() {
        assertEquals("""{"p_days":7,"p_limit":12}""", mostViewedRequestBody(days = 7, limit = 12))
    }
}
