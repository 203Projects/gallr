package com.gallr.app.ui.mygallr

import com.gallr.app.ui.route.composer.myRoutesSectionLabel
import com.gallr.app.viewmodel.MyGallrSection
import com.gallr.shared.data.model.AppLanguage

/** A MY section's name, in its tab and in the counts row; the diary replaced 방문 (spec 103 US2). */
internal fun myGallrSectionLabel(
    section: MyGallrSection,
    language: AppLanguage,
): String =
    when (section) {
        MyGallrSection.DIARY -> if (language == AppLanguage.KO) "일기" else "DIARY"
        MyGallrSection.FOLLOWING -> if (language == AppLanguage.KO) "팔로잉" else "FOLLOWING"
        MyGallrSection.ROUTES -> myRoutesSectionLabel(language)
    }
