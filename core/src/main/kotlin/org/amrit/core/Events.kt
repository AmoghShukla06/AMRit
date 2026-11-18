package org.amrit.core

import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The only thing that leaves the phone about a scan. No user id, no device id, no photo, no brand name.
 * event_id is a fresh random UUID per event so retried uploads are idempotent; it links to nothing.
 * Schema shared with the dashboard: backend/schema.sql (table scan_events).
 */
@Serializable
data class ScanEvent(
    val event_id: String,
    /** canonical ingredient names, sorted */
    val ingredients: List<String>,
    /** "Access" | "Watch" | "Reserve" | null (not a systemic antibiotic) */
    val aware_group: String?,
    val is_antibiotic: Boolean,
    val banned: Boolean,
    /** 6-digit Indian pincode typed by the user, or null */
    val pincode: String?,
    /** ISO-8601 UTC, truncated to the hour */
    val scanned_at: String,
    /** answer to "was this antibiotic sold with a prescription?" (true/false); null = not asked or skipped */
    val prescribed: Boolean? = null,
) {
    companion object {
        fun from(r: RuleEngine.StripReport, pincode: String?, now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)) = ScanEvent(
            event_id = UUID.randomUUID().toString(),
            ingredients = r.items.map { it.ingredient.name }.distinct().sorted(),
            aware_group = r.antibiotic?.aware?.name,
            is_antibiotic = r.antibiotic != null,
            banned = r.banned,
            pincode = pincode?.takeIf { Regex("^[1-9][0-9]{5}$").matches(it) },
            scanned_at = now.withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).format(DateTimeFormatter.ISO_INSTANT),
        )
    }
}
