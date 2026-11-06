package app.machinery.thread

import java.time.Instant

/** Someone who writes on GitHub: a user, or an app when [isBot], whose [login] then ends in `[bot]`. */
data class Author(
    val login: String,
    val isBot: Boolean = false,
)

/** What a [Post] is. */
enum class PostKind {
    /** The description of the issue or pull request, written as it was opened. */
    OPENING,

    /** A comment on the conversation. */
    COMMENT,

    /** A review of a pull request, with its verdict. */
    REVIEW,

    /** A review's comment on lines of the pull request's code. */
    REVIEW_COMMENT,
}

/**
 * A text of a conversation: its [kind], who wrote it and when, its Markdown [body], the moment of its last edit and who
 * made it ([editor]; both `null` when never edited), and whether the page hides it ([minimized], as off-topic or spam).
 * A deleted account, as [author] or [editor], is `null`. [about] says more of what it is (a review's verdict, the file
 * and line a review comment is on), [code] holds the lines a review comment is on.
 */
data class Post(
    val kind: PostKind,
    val author: Author?,
    val createdAt: Instant,
    val body: String,
    val lastEditedAt: Instant? = null,
    val editor: Author? = null,
    val minimized: Boolean = false,
    val about: String = "",
    val code: String = "",
)

/** The label [label], which [actor] added [at] that moment. */
data class Labeling(
    val actor: Author?,
    val at: Instant,
    val label: String,
)

/** [actor]'s change of the title [from] one [to] another, [at] that moment. */
data class Renaming(
    val actor: Author?,
    val at: Instant,
    val from: String,
    val to: String,
)

/** A pull request's branches: [head], from a fork when [fromFork], into [base]. */
data class Branches(
    val head: String,
    val base: String,
    val fromFork: Boolean = false,
)

/**
 * Issue or (with [branches]) pull request [number]: its current [title], its [posts] (its description first, then the
 * others by date), the [labelings] and [renamings] of its timeline, and the number of its comments older than those
 * read ([olderComments]).
 */
data class Conversation(
    val number: Int,
    val title: String,
    val posts: List<Post>,
    val labelings: List<Labeling> = emptyList(),
    val renamings: List<Renaming> = emptyList(),
    val branches: Branches? = null,
    val olderComments: Int = 0,
)

/**
 * Who may steer the AI agents: the repository's maintainers, the users with write access to it (the write, maintain or
 * admin role, as [hasWriteAccess] tells), who can call an agent; and the automation's own GitHub App, [bot] (its login,
 * ending in `[bot]`), whose posts are the agents' answers and the automation's reports. Each login is looked up once.
 */
class People(
    private val bot: String,
    private val hasWriteAccess: (String) -> Boolean,
) {
    private val maintainers = mutableMapOf<String, Boolean>()

    /** Whether [author] is a maintainer, whose posts can call an agent. Apps never are. */
    fun isMaintainer(author: Author?): Boolean =
        author != null && !author.isBot && maintainers.getOrPut(author.login) { hasWriteAccess(author.login) }

    /** Whether [author] is a maintainer or the automation's App, whose posts the agents may read as they are. */
    fun isTrusted(author: Author?): Boolean = isMaintainer(author) || isBot(author)

    /** Whether [login], an event's sender, may call an agent: a maintainer, or the App handing an agent's work on. */
    fun mayCall(login: String): Boolean = isTrusted(Author(login, isBot = login.endsWith(BOT_SUFFIX)))

    private fun isBot(author: Author?): Boolean =
        author != null && author.isBot && bot.isNotEmpty() && author.login.equals(bot, ignoreCase = true)

    private companion object {
        const val BOT_SUFFIX = "[bot]"
    }
}
