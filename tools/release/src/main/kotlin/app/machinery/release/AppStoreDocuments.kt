package app.machinery.release

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The JSON:API documents App Store Connect's API takes, to create and change its resources. */
object AppStoreDocuments {
    /** A new resource of [type] with its [attributes] and [relationships] ([link], [links]), and [included] ones. */
    fun create(
        type: String,
        attributes: Map<String, JsonElement> = emptyMap(),
        relationships: Map<String, JsonObject> = emptyMap(),
        included: List<JsonObject> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("data", resource(type, null, attributes, relationships))
            if (included.isNotEmpty()) put("included", JsonArray(included))
        }

    /** A change of the resource [id] of [type]: its new [attributes], and the [relationships] it points to. */
    fun update(
        type: String,
        id: String,
        attributes: Map<String, JsonElement> = emptyMap(),
        relationships: Map<String, JsonObject> = emptyMap(),
    ): JsonObject = buildJsonObject { put("data", resource(type, id, attributes, relationships)) }

    /** A resource object, without the members it has nothing for. */
    fun resource(
        type: String,
        id: String?,
        attributes: Map<String, JsonElement> = emptyMap(),
        relationships: Map<String, JsonObject> = emptyMap(),
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            if (id != null) put("id", id)
            if (attributes.isNotEmpty()) put(ATTRIBUTES, JsonObject(attributes))
            if (relationships.isNotEmpty()) put(RELATIONSHIPS, JsonObject(relationships))
        }

    /** A to-one relationship's linkage, to the resource [id] of [type], or to none when they're null. */
    fun link(
        type: String?,
        id: String?,
    ): JsonObject =
        buildJsonObject {
            if (type == null || id == null) {
                put("data", JsonNull)
            } else {
                put("data", identifier(type, id))
            }
        }

    /** A to-many relationship's linkage, to the resources [ids] of [type]. */
    fun links(
        type: String,
        ids: List<String>,
    ): JsonObject = buildJsonObject { put("data", buildJsonArray { ids.forEach { add(identifier(type, it)) } }) }

    /** The resource [id] of [type], as a linkage names it. */
    fun identifier(
        type: String,
        id: String,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            put("id", id)
        }

    /** [value] as an attribute's JSON: `true` and `false` as booleans, everything else as text. */
    fun value(value: String): JsonElement =
        when (value) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> JsonPrimitive(value)
        }

    /** The attributes of [wanted] that [current] doesn't have yet, or has with another value. */
    fun changes(
        current: JsonObject,
        wanted: Map<String, JsonElement>,
    ): Map<String, JsonElement> = wanted.filter { (name, value) -> !same(current[name], value) }

    private fun same(
        current: JsonElement?,
        wanted: JsonElement,
    ): Boolean {
        val have = (current as? JsonPrimitive)?.takeUnless { it is JsonNull }
        val want = wanted as? JsonPrimitive
        return when {
            have == null || want == null -> current == wanted
            else -> have.content == want.content
        }
    }

    private const val ATTRIBUTES = "attributes"
    private const val RELATIONSHIPS = "relationships"
}
