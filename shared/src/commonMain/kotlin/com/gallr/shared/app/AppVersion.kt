package com.gallr.shared.app

/** A release's version as three numbers; anything after them ("-debug", " (42)") is ignored (spec 098 FR-006). */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val LEADING_NUMBERS = Regex("""^\s*(\d+)\.(\d+)\.(\d+)""")

        /** The version in [text], or null when it does not start with three dot-separated numbers. */
        fun parse(text: String): AppVersion? {
            val match = LEADING_NUMBERS.find(text) ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/** What staff published for a platform: builds below [minimum] are stopped, below [recommended] nudged. */
data class UpdatePolicy(
    val minimum: AppVersion,
    val recommended: AppVersion,
)

enum class UpdateRequirement { NONE, RECOMMENDED, REQUIRED }

/** The platform a build runs on, as the policy names it. */
enum class AppPlatform(
    val wireName: String,
) {
    ANDROID("android"),
    IOS("ios"),
}

/** The rule behind the update screens: the minimum wins over the recommendation (spec 098 US3 scenario 4). */
fun updateRequirement(
    installed: AppVersion,
    policy: UpdatePolicy,
): UpdateRequirement =
    when {
        installed < policy.minimum -> UpdateRequirement.REQUIRED
        installed < policy.recommended -> UpdateRequirement.RECOMMENDED
        else -> UpdateRequirement.NONE
    }
