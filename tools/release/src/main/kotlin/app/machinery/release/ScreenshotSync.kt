package app.machinery.release

import app.machinery.release.AppStoreDocuments.create
import app.machinery.release.AppStoreDocuments.link
import app.machinery.release.AppStoreDocuments.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * The screenshots of the version being prepared, in its primary language (the others show the same ones): one set per
 * display type, replaced whole when any of its pictures changed, as Google Play's are.
 */
class ScreenshotSync(
    private val api: AppStoreApi,
    private val report: AppStoreReport,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    /** Makes the sets of the version localization [localization] show [screenshots] (files by display type). */
    fun sync(
        localization: String,
        screenshots: Map<String, List<File>>,
    ) {
        screenshots.forEach { (type, files) ->
            report.step("Screenshots ($type)") { replaceIfChanged(localization, type, files) }
        }
    }

    private fun replaceIfChanged(
        localization: String,
        type: String,
        files: List<File>,
    ) {
        val sets = "/v1/appStoreVersionLocalizations/$localization/appScreenshotSets"
        val set = api.getAll("$sets?filter[screenshotDisplayType]=$type").firstOrNull()
        val current = set?.let { api.getAll("/v1/appScreenshotSets/${it.id}/appScreenshots?limit=50") }.orEmpty()
        val delivered = current.all { state(it) == COMPLETE }
        if (delivered && current.map { it.attributes.string("sourceFileChecksum")?.lowercase() } == files.map(::md5)) {
            report.fact("Screenshots ($type): ${files.size}, up to date")
            return
        }
        val setId =
            set?.id ?: checkNotNull(
                api
                    .post(
                        "/v1/appScreenshotSets",
                        create(
                            "appScreenshotSets",
                            mapOf("screenshotDisplayType" to JsonPrimitive(type)),
                            mapOf("appStoreVersionLocalization" to link("appStoreVersionLocalizations", localization)),
                        ),
                    ).resource,
            ).id
        current.forEach { api.delete("/v1/appScreenshots/${it.id}") }
        val uploaded = files.map { upload(setId, it) }
        waitForDelivery(uploaded)
        report.done("Screenshots ($type): ${files.size}")
    }

    /** Uploads [file] into the set [setId], as App Store Connect's upload operations ask, and returns its id. */
    private fun upload(
        setId: String,
        file: File,
    ): String {
        val screenshot =
            checkNotNull(
                api
                    .post(
                        "/v1/appScreenshots",
                        create(
                            "appScreenshots",
                            mapOf("fileName" to JsonPrimitive(file.name), "fileSize" to JsonPrimitive(file.length())),
                            mapOf("appScreenshotSet" to link("appScreenshotSets", setId)),
                        ),
                    ).resource,
            ) { "App Store Connect made no screenshot for ${file.name}" }
        RandomAccessFile(file, "r").use { input ->
            UploadOperation.of(screenshot.attributes["uploadOperations"]).forEach { operation ->
                val part = ByteArray(operation.length)
                input.seek(operation.offset.toLong())
                input.readFully(part)
                api.upload(operation, part)
            }
        }
        api.patch(
            "/v1/appScreenshots/${screenshot.id}",
            update(
                "appScreenshots",
                screenshot.id,
                mapOf("uploaded" to JsonPrimitive(true), "sourceFileChecksum" to JsonPrimitive(md5(file))),
            ),
        )
        return screenshot.id
    }

    /** Waits until Apple has checked every screenshot of [ids], and fails with its reasons for any it refused. */
    private fun waitForDelivery(ids: List<String>) {
        var pending = ids
        repeat(DELIVERY_CHECKS) {
            val states = pending.associateWith { id -> api.get("/v1/appScreenshots/$id")?.resource }
            val refused = states.values.filterNotNull().filter { shot -> state(shot) == FAILED }
            check(refused.isEmpty()) {
                "Apple refused ${refused.joinToString { shot -> shot.attributes.string("fileName").orEmpty() }}: " +
                    refused.joinToString { shot -> errors(shot) }
            }
            pending = states.filterValues { shot -> shot == null || state(shot) != COMPLETE }.keys.toList()
            if (pending.isEmpty()) return
            sleep(DELIVERY_POLL)
        }
        report.fact("${pending.size} screenshots were still being processed by Apple when the run ended")
    }

    companion object {
        private const val COMPLETE = "COMPLETE"
        private const val FAILED = "FAILED"
        private const val DELIVERY_CHECKS = 20
        private val DELIVERY_POLL: Duration = Duration.ofSeconds(10)

        /** The display types of App Store screenshots and the sizes Apple takes for each, as `width×height`. */
        val SIZES =
            mapOf(
                "APP_IPHONE_67" to setOf("1320x2868", "1290x2796", "1260x2736"),
                "APP_IPHONE_65" to setOf("1284x2778", "1242x2688"),
                "APP_IPAD_PRO_3GEN_129" to setOf("2064x2752", "2048x2732"),
            )

        /** The screenshots drawn in [dir], one folder per display type, each in the order of the files' names. */
        fun from(dir: File): Map<String, List<File>> {
            val sets =
                dir
                    .listFiles(File::isDirectory)
                    .orEmpty()
                    .sortedBy { it.name }
                    .associate { set ->
                        set.name to set.listFiles { file -> file.extension == "png" }.orEmpty().sortedBy { it.name }
                    }.filterValues { it.isNotEmpty() }
            val problems =
                sets.flatMap { (type, files) ->
                    val sizes = SIZES[type]
                    files.mapNotNull { file ->
                        val size = pngSize(file)
                        if (sizes != null &&
                            size !in sizes
                        ) {
                            "$type/${file.name} is $size, Apple takes ${sizes.joinToString()}"
                        } else {
                            null
                        }
                    } +
                        listOfNotNull(
                            if (files.size >
                                MAX_PER_SET
                            ) {
                                "$type has ${files.size} screenshots, Apple takes $MAX_PER_SET"
                            } else {
                                null
                            },
                        )
                }
            check(problems.isEmpty()) {
                "The App Store screenshots in ${dir.path} can't go up:\n" +
                    problems.joinToString("\n")
            }
            return sets
        }

        /** The MD5 of [file] in lowercase hex, the checksum App Store Connect keeps of an upload. */
        fun md5(file: File): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(file.readBytes()))

        /** The `width×height` of a PNG file, from its header. */
        fun pngSize(file: File): String {
            val header = file.inputStream().use { it.readNBytes(PNG_HEADER) }
            check(header.size == PNG_HEADER) { "${file.name} isn't a PNG file" }

            fun int(at: Int) =
                (0 until Int.SIZE_BYTES).fold(0) { value, i ->
                    (value shl Byte.SIZE_BITS) or
                        (header[at + i].toInt() and BYTE)
                }
            return "${int(WIDTH_AT)}x${int(HEIGHT_AT)}"
        }

        private fun state(screenshot: JsonObject): String? =
            (screenshot.attributes["assetDeliveryState"] as? JsonObject)?.string("state")

        private fun errors(screenshot: JsonObject): String =
            (screenshot.attributes["assetDeliveryState"] as? JsonObject)?.get("errors").toString()

        private const val MAX_PER_SET = 10
        private const val PNG_HEADER = 24
        private const val WIDTH_AT = 16
        private const val HEIGHT_AT = 20
        private const val BYTE = 0xFF
    }
}
