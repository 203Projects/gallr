package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import com.gallr.shared.repository.AuthAwareFollowedGalleryRepository
import com.gallr.shared.repository.AuthAwareVisitRepository
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MyGallrAccountStore
import com.gallr.shared.repository.MyGallrAccountSyncCoordinator
import com.gallr.shared.repository.SyncBookmarkRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Owns the signed-in account's sync for as long as the host's ViewModelStore lives, as [AuthSessionViewModel] owns
 * the auth flow: the account's bookmarks, the My Gallr sync coordinator and the repositories that read through it.
 *
 * Retained ViewModels take these as dependencies. Built in the composition, a recreated Android Activity would sync
 * and sign in through new instances while those ViewModels kept watching the first ones. Held here, the composition
 * and every ViewModel share one set, created from the first host's dependencies (whose network clients live exactly
 * as long as this store).
 */
class AccountSyncViewModel(
    localBookmarkRepository: BookmarkRepositoryImpl,
    val cloudBookmarks: CloudBookmarkRepository,
    authState: StateFlow<AuthState>,
    visitRepository: VisitRepository,
    followedGalleryRepository: FollowedGalleryRepository,
    accountStore: MyGallrAccountStore,
    accountSource: MyGallrAccountCommandSource,
) : ViewModel() {
    val bookmarks = SyncBookmarkRepository(localBookmarkRepository, cloudBookmarks, authState)

    val coordinator =
        MyGallrAccountSyncCoordinator(
            guestVisitRepository = visitRepository,
            guestFollowedGalleryRepository = followedGalleryRepository,
            accountStore = accountStore,
            source = accountSource,
        )

    val visits = AuthAwareVisitRepository(visitRepository, coordinator)

    val followedGalleries = AuthAwareFollowedGalleryRepository(followedGalleryRepository, coordinator)

    /** Whose history the device holds, whether it has settled and what the account brought (spec 101). */
    val accountHistory: Flow<AccountHistory> =
        combine(
            authState,
            coordinator.observeStatus(),
            cloudBookmarks.hasSettled,
            cloudBookmarks.readBookmarkedIds,
            coordinator.arrivedArchive,
            ::accountHistory,
        )

    companion object {
        fun factory(
            localBookmarkRepository: BookmarkRepositoryImpl,
            cloudBookmarkRepository: CloudBookmarkRepository,
            authState: StateFlow<AuthState>,
            visitRepository: VisitRepository,
            followedGalleryRepository: FollowedGalleryRepository,
            accountStore: MyGallrAccountStore,
            accountSource: MyGallrAccountCommandSource,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AccountSyncViewModel(
                        localBookmarkRepository = localBookmarkRepository,
                        cloudBookmarks = cloudBookmarkRepository,
                        authState = authState,
                        visitRepository = visitRepository,
                        followedGalleryRepository = followedGalleryRepository,
                        accountStore = accountStore,
                        accountSource = accountSource,
                    )
                }
            }
    }
}
