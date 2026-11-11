package com.gallr.shared.diary

import com.gallr.shared.data.model.ExhibitionVisit

/**
 * The sign-in merge of a guest entry into the account's entry for the same exhibition (research P5), the same rule
 * the server's `merge_visit` applies. The account's record is kept; the device fills only the fields the account left
 * empty, and two differing notes are joined account first so no text is lost (up to
 * [DIARY_MERGED_NOTE_MAX_LENGTH]). The later update time is kept.
 */
fun mergeEntry(
    account: ExhibitionVisit,
    device: ExhibitionVisit,
): ExhibitionVisit =
    account.copy(
        visitDate = account.visitDate ?: device.visitDate,
        feelingWire = account.feelingWire ?: device.feelingWire,
        tagWires = account.tagWires.ifEmpty { device.tagWires },
        note = joinNotes(account.note, device.note),
        entryUpdatedAt = listOfNotNull(account.entryUpdatedAt, device.entryUpdatedAt).maxOrNull(),
    )

private fun joinNotes(
    account: String?,
    device: String?,
): String? =
    when {
        account == null -> device
        device == null || device == account -> account
        else -> "$account\n\n$device"
    }
