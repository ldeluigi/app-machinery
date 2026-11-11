package app.machinery.release

import app.machinery.release.AppStoreDocuments.create
import app.machinery.release.AppStoreDocuments.link
import app.machinery.release.AppStoreDocuments.links
import app.machinery.release.AppStoreDocuments.resource
import app.machinery.release.AppStoreDocuments.update
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The app's price and the countries and regions it is sold in, from the listing's `price`, `price.territory`,
 * `availability.newTerritories` and `availability.exclude` settings.
 */
class PricingSync(
    private val api: AppStoreApi,
    private val listing: AppStoreListing,
    private val report: AppStoreReport,
    private val today: () -> LocalDate = LocalDate::now,
) {
    /** Sets the price in the base territory, where every other one follows it, unless it already is that price. */
    fun price(app: AppRecord) {
        val wanted = listing.setting("price")?.toBigDecimal() ?: return
        val territory = listing.setting("price.territory") ?: "USA"
        if (currentPrices(app, territory).any { it.compareTo(wanted) == 0 }) {
            report.fact("Price: ${wanted.toPlainString()} in $territory")
            return
        }
        val point =
            api
                .getAll("/v1/apps/${app.id}/appPricePoints?filter[territory]=$territory&limit=200")
                .firstOrNull {
                    it.attributes
                        .string("customerPrice")
                        ?.toBigDecimalOrNull()
                        ?.compareTo(wanted) == 0
                }
                ?: error("App Store Connect has no price of ${wanted.toPlainString()} in $territory")
        val price = "\${price}"
        api.post(
            "/v1/appPriceSchedules",
            create(
                "appPriceSchedules",
                relationships =
                    mapOf(
                        "app" to link("apps", app.id),
                        "baseTerritory" to link("territories", territory),
                        "manualPrices" to links("appPrices", listOf(price)),
                    ),
                included =
                    listOf(
                        resource(
                            "appPrices",
                            price,
                            mapOf("startDate" to JsonNull),
                            mapOf("appPricePoint" to link("appPricePoints", point.id)),
                        ),
                    ),
            ),
        )
        report.done("Price: ${wanted.toPlainString()} in $territory, and the same tier everywhere else")
    }

    /** The prices the app has today in [territory]. */
    private fun currentPrices(
        app: AppRecord,
        territory: String,
    ): List<BigDecimal> {
        val schedule = api.get("/v1/apps/${app.id}/appPriceSchedule")?.resource
        val prices =
            schedule?.let {
                api.get("/v1/appPriceSchedules/${it.id}/manualPrices?filter[territory]=$territory&$PRICE_POINTS")
            } ?: return emptyList()
        val points = prices.included("appPricePoints").associate { it.id to it.attributes.string("customerPrice") }
        val now = today().toString()
        return prices.resources
            .filter { price ->
                val start = price.attributes.string("startDate")
                val end = price.attributes.string("endDate")
                (start == null || start <= now) && (end == null || end > now)
            }.mapNotNull { points[it.related("appPricePoint")]?.toBigDecimalOrNull() }
    }

    /**
     * Makes the app available in every territory but the excluded ones. App Store Connect answers with a reason for
     * each territory where the app still can't be sold (the EU's trader status, China's ICP number...): those go to
     * the owner.
     */
    fun availability(app: AppRecord) {
        val newTerritories = listing.setting("availability.newTerritories")?.toBooleanStrict() ?: return
        val excluded = listing.list("availability.exclude").toSet()
        val existing = api.get("/v1/apps/${app.id}/appAvailabilityV2")?.resource
        if (existing == null) {
            create(app, newTerritories, excluded)
            return
        }
        val territories =
            api.getAll("/v2/appAvailabilities/${existing.id}/territoryAvailabilities?include=territory&limit=200")
        var changed = 0
        territories.forEach { availability ->
            val wanted = availability.related("territory") !in excluded
            if (availability.attributes["available"]?.jsonPrimitive?.booleanOrNull != wanted) {
                api.patch(
                    "/v1/territoryAvailabilities/${availability.id}",
                    update("territoryAvailabilities", availability.id, mapOf("available" to JsonPrimitive(wanted))),
                )
                changed++
            }
        }
        if (changed > 0) report.done("Availability: changed in $changed countries and regions")
        if (existing.attributes["availableInNewTerritories"]?.jsonPrimitive?.booleanOrNull != newTerritories) {
            report.owner(
                "Pricing and Availability → Availability: set \"Make your app available in new countries or " +
                    "regions\" to $newTerritories (the API sets it only on the first availability)",
            )
        }
        blocked(territories)
    }

    private fun create(
        app: AppRecord,
        newTerritories: Boolean,
        excluded: Set<String>,
    ) {
        val territories = api.getAll("/v1/territories?limit=200").map { it.id }
        val included =
            territories.mapIndexed { index, territory ->
                resource(
                    "territoryAvailabilities",
                    "\${territory-$index}",
                    mapOf("available" to JsonPrimitive(territory !in excluded)),
                    mapOf("territory" to link("territories", territory)),
                )
            }
        api.post(
            "/v2/appAvailabilities",
            create(
                "appAvailabilities",
                mapOf("availableInNewTerritories" to JsonPrimitive(newTerritories)),
                mapOf(
                    "app" to link("apps", app.id),
                    "territoryAvailabilities" to
                        links("territoryAvailabilities", included.map { it.id }),
                ),
                included,
            ),
        )
        val available = territories.count { it !in excluded }
        val but = if (excluded.isEmpty()) "" else ", all but ${excluded.joinToString()}"
        report.done("Availability: $available countries and regions$but")
    }

    /** Tells the owner where the app can't be sold yet, and why, grouped by App Store Connect's reasons. */
    private fun blocked(territories: List<JsonObject>) {
        val reasons =
            territories
                .filter { it.attributes["available"]?.jsonPrimitive?.booleanOrNull == true }
                .flatMap { availability ->
                    availability.attributes["contentStatuses"]
                        ?.jsonArray
                        .orEmpty()
                        .map { it.jsonPrimitive.content }
                        .filter { it !in SELLABLE }
                        .map { it to availability.related("territory").orEmpty() }
                }.groupBy({ it.first }, { it.second })
        reasons.forEach { (reason, where) ->
            report.owner("Can't be sold in ${where.sorted().joinToString()}: $reason")
        }
    }

    private companion object {
        const val PRICE_POINTS = "include=appPricePoint&limit=50"

        /** The statuses of a territory where the app is, or is about to be, on sale. */
        val SELLABLE =
            setOf(
                "AVAILABLE",
                "AVAILABLE_FOR_SALE_UNRELEASED_APP",
                "PROCESSING_TO_AVAILABLE",
                "AVAILABLE_FOR_PREORDER",
                "AVAILABLE_FOR_PREORDER_ON_DATE",
                "PREORDER_ON_UNRELEASED_APP",
                "PROCESSING_TO_PRE_ORDER",
            )
    }
}
