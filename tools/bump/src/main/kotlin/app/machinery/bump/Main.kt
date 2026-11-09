package app.machinery.bump

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Bumps tool versions to the latest upstream releases and prints a Markdown report. Writes `changed` and
 * `sdk_changed` to `$GITHUB_OUTPUT` when running in GitHub Actions.
 */
fun main() {
    val latest =
        Latest(
            jdkLts = fetch("https://api.adoptium.net/v3/info/available_releases")?.let(Machinery::latestJdkLts),
            androidApi =
                fetch(
                    "https://dl.google.com/android/repository/repository2-3.xml",
                )?.let(Machinery::latestAndroidApi),
            copilotCli = fetch(COPILOT_CLI_RELEASE)?.let(Machinery::releaseVersion),
            claudeCode = fetch(CLAUDE_CODE_PACKAGE)?.let(Machinery::npmVersion),
            actionlint = fetch(ACTIONLINT_RELEASE)?.let(Machinery::releaseVersion),
        )
    val bumps = Machinery(File(".")).apply(latest)
    val report =
        buildString {
            appendLine("## Tool versions")
            appendLine()
            appendLine("| Tool | Was | Now |")
            appendLine("|---|---|---|")
            bumps.forEach { appendLine("| ${it.name} | ${it.from} | ${if (it.changed) "**${it.to}**" else it.to} |") }
        }
    File("build").mkdirs()
    File("build/machinery-report.md").writeText(report)
    println(report)
    System.getenv("GITHUB_OUTPUT")?.let { output ->
        val sdkChanged = bumps.any { it.changed && it.name.endsWith("Sdk") }
        File(output).appendText("changed=${bumps.any { it.changed }}\nsdk_changed=$sdkChanged\n")
    }
}

private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()

/** The body of [url], or `null` if it cannot be fetched (the version then stays as it is). */
private fun fetch(url: String): String? =
    runCatching {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("User-Agent", "app-machinery/1.0")
                .apply {
                    System
                        .getenv("GITHUB_TOKEN")
                        ?.takeIf {
                            url.contains("api.github.com")
                        }?.let { header("Authorization", "Bearer $it") }
                }.timeout(Duration.ofSeconds(TIMEOUT_S))
                .build()
        client.send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() in OK }?.body()
    }.getOrNull()

private const val TIMEOUT_S = 60L
private val OK = 200..299

private const val COPILOT_CLI_RELEASE = "https://api.github.com/repos/github/copilot-cli/releases/latest"
private const val CLAUDE_CODE_PACKAGE = "https://registry.npmjs.org/@anthropic-ai/claude-code/latest"
private const val ACTIONLINT_RELEASE = "https://api.github.com/repos/rhysd/actionlint/releases/latest"
