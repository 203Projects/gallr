package com.gallr.shared.repository

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * How [AuthRepositoryImpl.observeAuthState] reads supabase-kt's session status ([authStatesFrom]).
 *
 * A refresh that fails for a network or server error keeps the stored session and is retried by the SDK, so it must not
 * read as a sign-out; only `NotAuthenticated(isSignOut = true)` signs out. A signed-in state never carries an empty id.
 */
class AuthRepositoryImplTest {
    private val hana = userInfo(id = "user-1", fullName = "하나", avatarUrl = "https://example.test/a.png")
    private val hanaUser = GallrUser(id = "user-1", displayName = "하나", avatarUrl = "https://example.test/a.png")

    @Test
    fun aRefreshFailureAfterSignInKeepsTheUserSignedIn() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Initializing,
                            SessionStatus.Authenticated(session(hana), SessionSource.Storage),
                            refreshFailure(),
                            refreshFailure(),
                        ),
                    fetchCurrentUser = { hana },
                    loadStoredSession = { fail("the last signed-in user is already known") },
                ).toList()

            assertEquals(
                listOf(
                    AuthState.Loading,
                    AuthState.Authenticated(hanaUser),
                    AuthState.Authenticated(hanaUser, isSessionRefreshPending = true),
                    AuthState.Authenticated(hanaUser, isSessionRefreshPending = true),
                ),
                states,
            )
        }

    @Test
    fun theRefreshThatLandsAfterAFailureIsANewStateSoTheAccountSyncsAgain() =
        runTest {
            val stored = session(hana)
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Authenticated(stored, SessionSource.Storage),
                            refreshFailure(),
                            SessionStatus.Authenticated(session(hana), SessionSource.Refresh(stored)),
                        ),
                    fetchCurrentUser = { hana },
                    loadStoredSession = { stored },
                ).toList()

            assertEquals(AuthState.Authenticated(hanaUser), states.last())
            assertNotEquals(states[1], states[2])
        }

    @Test
    fun aRefreshFailureOnAColdStartRestoresTheStoredUser() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus = flowOf(SessionStatus.Initializing, refreshFailure()),
                    fetchCurrentUser = { fail("the network user is not read while the session is unusable") },
                    loadStoredSession = { session(hana) },
                ).toList()

            assertEquals(
                listOf(AuthState.Loading, AuthState.Authenticated(hanaUser, isSessionRefreshPending = true)),
                states,
            )
        }

    @Test
    fun aStartUpRefreshThatFailsBeforeExpiryIsNotASignOut() =
        runTest {
            // The SDK ends its start-up with NotAuthenticated(isSignOut = false) when a session in the last fifth of
            // its lifetime cannot be refreshed; the session stays stored and the SDK keeps retrying.
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Initializing,
                            SessionStatus.NotAuthenticated(isSignOut = false),
                        ),
                    fetchCurrentUser = { fail("the network user is not read while the session is unusable") },
                    loadStoredSession = { session(hana) },
                ).toList()

            assertEquals(AuthState.Authenticated(hanaUser, isSessionRefreshPending = true), states.last())
        }

    @Test
    fun aStartWithNoStoredSessionIsAnonymous() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Initializing,
                            SessionStatus.NotAuthenticated(isSignOut = false),
                        ),
                    fetchCurrentUser = { fail("no session to read a user for") },
                    loadStoredSession = { null },
                ).toList()

            assertEquals(listOf(AuthState.Loading, AuthState.Anonymous), states)
        }

    @Test
    fun aRefreshFailureWithNoKnownUserNeverClaimsAnEmptyId() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus = flowOf(SessionStatus.Initializing, refreshFailure()),
                    fetchCurrentUser = { fail("the network user is not read while the session is unusable") },
                    loadStoredSession = { session(user = null) },
                ).toList()

            assertEquals(listOf(AuthState.Loading, AuthState.Loading), states)
        }

    @Test
    fun aSignOutSignsOutEvenWithAStoredSession() =
        runTest {
            // A sign-out, a revoked refresh token and a missing server session all clear the session and arrive as
            // NotAuthenticated(isSignOut = true).
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Authenticated(session(hana), SessionSource.Storage),
                            refreshFailure(),
                            SessionStatus.NotAuthenticated(isSignOut = true),
                        ),
                    fetchCurrentUser = { hana },
                    loadStoredSession = { session(hana) },
                ).toList()

            assertEquals(AuthState.Anonymous, states.last())
        }

    @Test
    fun aSignOutForgetsTheUserForTheNextSession() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Authenticated(session(hana), SessionSource.Storage),
                            SessionStatus.NotAuthenticated(isSignOut = true),
                            SessionStatus.Authenticated(session(user = null), SessionSource.SignIn(Email)),
                        ),
                    fetchCurrentUser = lookUps(hana, null),
                    loadStoredSession = { null },
                ).toList()

            assertEquals(listOf(AuthState.Authenticated(hanaUser), AuthState.Anonymous, AuthState.Loading), states)
        }

    @Test
    fun aFailedUserLookupFallsBackToTheSessionUser() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus = flowOf(SessionStatus.Authenticated(session(hana), SessionSource.SignIn(Email))),
                    fetchCurrentUser = { throw IllegalStateException("offline") },
                    loadStoredSession = { null },
                ).toList()

            assertEquals(listOf(AuthState.Authenticated(hanaUser)), states)
        }

    @Test
    fun aFailedUserLookupOnANewSessionWithoutAUserIsNotSignedInWithAnEmptyId() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(SessionStatus.Authenticated(session(user = null), SessionSource.SignIn(Email))),
                    fetchCurrentUser = { throw IllegalStateException("offline") },
                    loadStoredSession = { null },
                ).toList()

            assertEquals(listOf(AuthState.Loading), states)
            assertTrue(states.none { it is AuthState.Authenticated && it.user.id.isBlank() })
        }

    @Test
    fun aFailedUserLookupOnARefreshedSessionKeepsTheKnownUser() =
        runTest {
            val stored = session(hana)
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Authenticated(stored, SessionSource.Storage),
                            SessionStatus.Authenticated(session(user = null), SessionSource.Refresh(stored)),
                        ),
                    fetchCurrentUser = lookUps(hana, null),
                    loadStoredSession = { stored },
                ).toList()

            assertEquals(listOf(AuthState.Authenticated(hanaUser), AuthState.Authenticated(hanaUser)), states)
        }

    @Test
    fun aNewSessionWhoseUserIsUnknownDoesNotInheritThePreviousUser() =
        runTest {
            val dana = userInfo(id = "user-2", fullName = "Dana")
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(
                            SessionStatus.Authenticated(session(hana), SessionSource.Storage),
                            SessionStatus.Authenticated(session(user = null), SessionSource.SignIn(Email)),
                            refreshFailure(),
                        ),
                    fetchCurrentUser = lookUps(hana, null),
                    loadStoredSession = { session(dana) },
                ).toList()

            assertEquals(
                listOf(
                    AuthState.Authenticated(hanaUser),
                    AuthState.Loading,
                    AuthState.Authenticated(GallrUser("user-2", "Dana", null), isSessionRefreshPending = true),
                ),
                states,
            )
        }

    @Test
    fun aUserWithABlankIdIsNotSignedIn() =
        runTest {
            val states =
                authStatesFrom(
                    sessionStatus =
                        flowOf(SessionStatus.Authenticated(session(user = null), SessionSource.SignIn(Email))),
                    fetchCurrentUser = { userInfo(id = " ") },
                    loadStoredSession = { null },
                ).toList()

            assertEquals(listOf(AuthState.Loading), states)
        }

    @Test
    fun theNameAndPictureComeFromTheProviderMetadata() =
        runTest {
            val google = userInfo(id = "user-2", name = "Dana", picture = "https://example.test/d.png")
            val states =
                authStatesFrom(
                    sessionStatus = flowOf(SessionStatus.Authenticated(session(google), SessionSource.Storage)),
                    fetchCurrentUser = { google },
                    loadStoredSession = { null },
                ).toList()

            assertEquals(
                listOf(AuthState.Authenticated(GallrUser("user-2", "Dana", "https://example.test/d.png"))),
                states,
            )
        }

    /** Answers each user lookup with the next user; null stands for a failed lookup. */
    private fun lookUps(vararg users: UserInfo?): suspend () -> UserInfo {
        val answers = ArrayDeque(users.toList())
        return { answers.removeFirst() ?: throw IllegalStateException("offline") }
    }

    @Suppress("DEPRECATION")
    private fun refreshFailure(): SessionStatus =
        SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(IllegalStateException("offline")))

    private fun session(user: UserInfo?): UserSession =
        UserSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresIn = 3_600,
            tokenType = "bearer",
            user = user,
        )

    private fun userInfo(
        id: String,
        fullName: String? = null,
        avatarUrl: String? = null,
        name: String? = null,
        picture: String? = null,
    ): UserInfo =
        UserInfo(
            aud = "authenticated",
            id = id,
            userMetadata =
                buildJsonObject {
                    fullName?.let { put("full_name", it) }
                    avatarUrl?.let { put("avatar_url", it) }
                    name?.let { put("name", it) }
                    picture?.let { put("picture", it) }
                },
        )
}
