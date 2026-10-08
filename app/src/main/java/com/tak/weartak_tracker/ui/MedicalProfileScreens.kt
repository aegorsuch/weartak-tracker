package com.tak.weartak_tracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.wear.compose.navigation.composable
import com.tak.weartak_tracker.data.ALLERGY_OPTIONS
import com.tak.weartak_tracker.data.BLOOD_TYPE_OPTIONS
import com.tak.weartak_tracker.data.MEDICAL_SEX_OPTIONS
import com.tak.weartak_tracker.data.MEDICAL_USER_TYPE_OPTIONS
import com.tak.weartak_tracker.data.MedicalProfile
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TrackerConfig
import java.time.Year

private enum class MedicalNumber(
    val route: String,
    val title: String,
    val minimum: Int,
    val maximum: Int,
    val get: (MedicalProfile) -> Int,
    val set: (MedicalProfile, Int) -> MedicalProfile,
) {
    HEIGHT_FEET("height_feet", "Height (feet)", 1, 8, { it.heightFeet }, { p, v -> p.copy(heightFeet = v) }),
    HEIGHT_INCHES("height_inches", "Height (inches)", 0, 11, { it.heightInches }, { p, v -> p.copy(heightInches = v) }),
    WEIGHT("weight", "Weight (lbs)", 0, 1000, { it.weightLbs }, { p, v -> p.copy(weightLbs = v) }),
    RESTING_HR("resting_heart_rate", "Resting Heart Rate (bpm)", 1, 200, { it.restingHeartRateBpm }, { p, v -> p.copy(restingHeartRateBpm = v) }),
}

private enum class MedicalChoice(
    val route: String,
    val title: String,
    val options: List<String>,
    val get: (MedicalProfile) -> String,
    val set: (MedicalProfile, String) -> MedicalProfile,
) {
    SEX("sex", "Sex", MEDICAL_SEX_OPTIONS, { it.sex }, { p, v -> p.copy(sex = v) }),
    BLOOD("blood_type", "Blood Type", BLOOD_TYPE_OPTIONS, { it.bloodType }, { p, v -> p.copy(bloodType = v) }),
    USER("user_type", "User Type", MEDICAL_USER_TYPE_OPTIONS, { it.userType }, { p, v -> p.copy(userType = v) }),
}

fun NavGraphBuilder.medicalProfileGraph(repo: SettingsRepository, configState: State<TrackerConfig>, go: Navigate, back: () -> Unit) {
    val profile by derivedStateOf { configState.value.medicalProfile }
    fun update(transform: (MedicalProfile) -> MedicalProfile) =
        repo.updateAsync { it.copy(medicalProfile = transform(it.medicalProfile)) }

    composable("my_user_metrics") {
        WearTAKPageWithBackArrow("My User Metrics", back) {
            item { WearTAKTitleChip("Medical Profile (BATDOK)") { go("medical_profile_batdok") } }
        }
    }
    composable("medical_profile_batdok") {
        WearTAKPageWithBackArrow("Medical Profile (BATDOK)", back) {
            item { WearTAKTitleChipWithState("Birth Year", profile.birthYear?.toString() ?: "N/A") { go("birth_year") } }
            item {
                WearTAKTitleChipWithState(
                    "Height", if (profile.heightFeet == 0) "N/A" else "${profile.heightFeet}' ${profile.heightInches}\"",
                ) { go("height") }
            }
            item { WearTAKTitleChipWithState("Weight", "${profile.weightLbs} lbs") { go("weight") } }
            MedicalChoice.entries.take(2).forEach { choice ->
                item { WearTAKTitleChipWithState(choice.title, choice.get(profile)) { go(choice.route) } }
            }
            item {
                WearTAKTitleChipWithState("Allergies", profile.allergies.joinToString(", ").ifEmpty { "N/A" }) { go("allergies") }
            }
            item { WearTAKTitleChipWithState("User Type", profile.userType) { go("user_type") } }
            item {
                WearTAKTitleChipWithState("Resting Heart Rate", "${profile.restingHeartRateBpm} bpm") { go("resting_heart_rate") }
            }
        }
    }
    composable("birth_year") {
        val currentYear = Year.now().value
        WearTAKIntEntryPage(profile.birthYear ?: currentYear, "Birth Year", 1900, currentYear, back) { value ->
            update { it.copy(birthYear = value) }
        }
    }
    composable("height") {
        WearTAKPageWithBackArrow("Height", back) {
            MedicalNumber.entries.take(2).forEach { number ->
                item { WearTAKTitleChipWithState(number.title, number.get(profile).toString()) { go(number.route) } }
            }
        }
    }
    MedicalNumber.entries.forEach { number ->
        composable(number.route) {
            WearTAKIntEntryPage(number.get(profile), number.title, number.minimum, number.maximum, back) { value ->
                update { number.set(it, value) }
            }
        }
    }
    MedicalChoice.entries.forEach { choice ->
        composable(choice.route) {
            WearTAKSelectionPage(choice.title, choice.options, choice.get(profile), onBack = back) { value ->
                update { choice.set(it, value) }
                back()
            }
        }
    }
    composable("allergies") {
        var selected by rememberSaveable { mutableStateOf(ArrayList(profile.allergies)) }
        WearTAKPageWithConfirmCancel(
            title = "Allergies",
            onClickConfirm = {
                update { it.copy(allergies = selected.toSet()) }
                back()
            },
            onClickCancel = back,
        ) {
            ALLERGY_OPTIONS.forEach { option ->
                item {
                    WearTAKToggleChip(
                        checked = if (option == "N/A") selected.isEmpty() else option in selected,
                        onCheckedChange = { on ->
                            selected = when {
                                option == "N/A" -> arrayListOf()
                                on -> ArrayList((selected + option).distinct())
                                else -> ArrayList(selected - option)
                            }
                        },
                        title = option,
                        description = "",
                    )
                }
            }
        }
    }
}
