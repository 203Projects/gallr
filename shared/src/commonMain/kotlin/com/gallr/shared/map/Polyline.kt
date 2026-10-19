package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoPoint

/**
 * Decodes a precision-5 encoded polyline, latitude first (the `walking-route` contract). Input that is truncated,
 * malformed or decodes outside the globe yields an empty list, so the leg falls back to an estimate rather than
 * drawing part of a path.
 */
fun decodePolyline(encoded: String): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    var index = 0
    var latitude = 0L
    var longitude = 0L
    while (index < encoded.length) {
        val latitudeStep = readValue(encoded, index) ?: return emptyList()
        val longitudeStep = readValue(encoded, latitudeStep.next) ?: return emptyList()
        latitude += latitudeStep.value
        longitude += longitudeStep.value
        index = longitudeStep.next
        val point = runCatching { GeoPoint(latitude / PRECISION, longitude / PRECISION) }.getOrNull()
        points += point ?: return emptyList()
    }
    return points
}

private class Step(
    val value: Long,
    val next: Int,
)

private fun readValue(
    encoded: String,
    start: Int,
): Step? {
    var result = 0L
    var shift = 0
    var index = start
    while (index < encoded.length) {
        val chunk = encoded[index].code - CHUNK_OFFSET
        if (chunk < 0 || chunk > MAX_CHUNK || shift > MAX_SHIFT) return null
        result = result or ((chunk and VALUE_MASK).toLong() shl shift)
        shift += BITS_PER_CHUNK
        index += 1
        if (chunk and CONTINUATION_BIT == 0) {
            val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            return Step(value, index)
        }
    }
    return null
}

private const val PRECISION = 1e5
private const val CHUNK_OFFSET = 63
private const val MAX_CHUNK = 63
private const val VALUE_MASK = 0x1f
private const val CONTINUATION_BIT = 0x20
private const val BITS_PER_CHUNK = 5
private const val MAX_SHIFT = 60
