package com.tak.weartak_tracker.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Year

val MEDICAL_SEX_OPTIONS = listOf("N/A", "Male", "Female")
val BLOOD_TYPE_OPTIONS = listOf("N/A", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
val ALLERGY_OPTIONS = listOf(
    "N/A", "Antibiotics", "Anti-Inflammatory (Ibuprofen)", "Antiseizure",
    "Aspirin", "Insulin", "Muscle Relaxers", "Sulfa Drugs",
)
val MEDICAL_USER_TYPE_OPTIONS = listOf(
    "N/A", "Child", "Coalition Civilian", "Coalition Military",
    "Non-Coalition Civilian", "Non-Coalition Military", "Opposing Force Detainee",
)

data class MedicalProfile(
    val birthYear: Int? = null,
    val heightFeet: Int = 0,
    val heightInches: Int = 0,
    val weightLbs: Int = 0,
    val sex: String = "N/A",
    val bloodType: String = "N/A",
    val allergies: Set<String> = emptySet(),
    val userType: String = "N/A",
    val restingHeartRateBpm: Int = 60,
) {
    fun ageYears(currentYear: Int = Year.now().value): Int? =
        birthYear?.takeIf { it in 1900..currentYear }?.let { currentYear - it }
}

/** The repository encrypts this profile before storing it. */
object MedicalProfileCodec {
    fun encode(profile: MedicalProfile): String = JSONObject()
        .put("birthYear", profile.birthYear)
        .put("heightFeet", profile.heightFeet)
        .put("heightInches", profile.heightInches)
        .put("weightLbs", profile.weightLbs)
        .put("sex", profile.sex)
        .put("bloodType", profile.bloodType)
        .put("allergies", JSONArray(profile.allergies.toList()))
        .put("userType", profile.userType)
        .put("restingHeartRateBpm", profile.restingHeartRateBpm)
        .toString()

    fun decode(raw: String?): MedicalProfile {
        if (raw == null) return MedicalProfile()
        val json = JSONObject(raw)
        val allergies = json.optJSONArray("allergies") ?: JSONArray()
        return MedicalProfile(
            birthYear = if (json.isNull("birthYear")) null else json.getInt("birthYear"),
            heightFeet = json.optInt("heightFeet"),
            heightInches = json.optInt("heightInches"),
            weightLbs = json.optInt("weightLbs"),
            sex = json.optString("sex", "N/A"),
            bloodType = json.optString("bloodType", "N/A"),
            allergies = (0 until allergies.length()).map { allergies.getString(it) }.toSet(),
            userType = json.optString("userType", "N/A"),
            restingHeartRateBpm = json.optInt("restingHeartRateBpm", 60),
        )
    }
}
