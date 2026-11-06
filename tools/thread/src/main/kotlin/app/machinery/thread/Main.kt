package app.machinery.thread

import java.io.File
import kotlin.system.exitProcess

/**
 * Writes what an AI agent may read of issue or pull request `<number>` to `<file>`, in Markdown ([ReadableThread]):
 * `thread <number> <file> [--caller <login>]`. With `--caller`, it first checks that whoever called the agent (the
 * event's sender) is a maintainer or the automation's App, and otherwise fails with exit code 3, reading nothing.
 *
 * From the environment: `GITHUB_REPOSITORY`, `GITHUB_API_URL` and `GITHUB_GRAPHQL_URL` (GitHub's own by default),
 * `GH_TOKEN`, which reads the repository's issues, pull requests and collaborators, and `BOT_LOGIN`, the App's login.
 */
fun main(args: Array<String>) {
    val number = args.getOrNull(0)?.toIntOrNull()
    val output = args.getOrNull(1)
    val caller = args.getOrNull(3).takeIf { args.getOrNull(2) == "--caller" }
    if (number == null || output == null || (args.size > 2 && caller == null)) {
        System.err.println("Usage: thread <number> <file> [--caller <login>]")
        exitProcess(USAGE)
    }
    val api = env("GITHUB_API_URL") ?: "https://api.github.com"
    val github =
        GitHub(api, env("GITHUB_GRAPHQL_URL") ?: "$api/graphql", required("GITHUB_REPOSITORY"), required("GH_TOKEN"))
    val people = People(env("BOT_LOGIN").orEmpty(), github::hasWriteAccess)
    if (caller != null && !people.mayCall(caller)) {
        println(
            "::error title=Not a maintainer::@$caller can't call the agents: only the maintainers, who have write " +
                "access to the repository, and the automation's App can.",
        )
        exitProcess(REFUSED)
    }
    val thread = ReadableThread(github.conversation(number), people)
    File(output).writeText(thread.markdown())
    val call = thread.lastCall?.let { ", as it stood when a maintainer last called an agent ($it)" }.orEmpty()
    println("What an agent may read of #$number$call is in $output.")
}

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

private fun required(name: String): String = checkNotNull(env(name)) { "$name is not set" }

private const val USAGE = 2
private const val REFUSED = 3
