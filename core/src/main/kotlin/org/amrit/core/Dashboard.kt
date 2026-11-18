package org.amrit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The public, k-anonymous summary served at /v1/dashboard (see supabase/functions/api/logic.ts `aggregate`).
 * Field names match the JSON. Areas are 3-digit postal regions, present only with at least [k] scans.
 */
@Serializable
data class Dashboard(
    val total_scans: Int = 0,
    val antibiotic_scans: Int = 0,
    val by_group: Map<String, Int> = emptyMap(),
    val watch_reserve_share: Double? = null,
    val banned_scans: Int = 0,
    val rx_answered: Int = 0,
    val without_prescription_share: Double? = null,
    val regions: List<Region> = emptyList(),
    val hidden_regions: Int = 0,
    val top_antibiotics: List<Named> = emptyList(),
    val k: Int = 5,
    val updated_at: String = "",
) {
    @Serializable data class Region(val region: String, val scans: Int, val antibiotic_scans: Int, val watch_reserve_share: Double? = null)
    @Serializable data class Named(val name: String, val scans: Int)

    /** The region for a 6-digit pincode, when that region has enough scans to be shown. */
    fun regionOf(pincode: String?): Region? = pincode?.takeIf { it.length == 6 }?.let { p -> regions.firstOrNull { it.region == p.take(3) } }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Dashboard? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
