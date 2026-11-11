package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens

/**
 * One saved Mapbox public token. [id] is the app's own, random and never derived from the token;
 * the trip session log keeps it for the token, never the token itself. [name] is the driver's,
 * such as Main or Test. [toString] masks the token, so an entry written anywhere by mistake never
 * shows it.
 */
data class TokenEntry(val id: String, val name: String, val token: String) {
    override fun toString(): String = "TokenEntry(id=$id, name=$name, token=${MapboxAccessTokens.mask(token)})"
}

/** Why a name cannot be an entry's name. */
enum class NameProblem {
    EMPTY,

    /** Longer than [TokenList.MAX_NAME_LENGTH] characters. */
    TOO_LONG,

    /** Another entry has it, in any case. */
    TAKEN,

    /** It starts like a token. The debug log may name the active entry, so a token pasted here would end up in it. */
    LOOKS_LIKE_TOKEN,
}

/** Why the list refused a token, a name or a change. */
sealed interface TokenProblem {

    /** The token itself: empty, secret, not public or malformed, as [MapboxAccessTokens.check] says. */
    data class BadToken(val check: AccessTokenCheck) : TokenProblem

    data class BadName(val problem: NameProblem) : TokenProblem

    /** The same token is saved already, under [name]. */
    data class Duplicate(val name: String) : TokenProblem

    /** [TokenList.MAX_ENTRIES] tokens are saved. */
    data object Full : TokenProblem

    /** No entry has the id any more. */
    data object NotFound : TokenProblem

    /** Guidance runs: the active token and the number of tokens stay as they are until the trip ends. */
    data object DuringTrip : TokenProblem
}

/**
 * The Mapbox public tokens saved on this phone, at most [MAX_ENTRIES], in the order they were
 * added, and the one the SDKs use, [activeId]. Exactly one entry is active whenever the list is not
 * empty, and the constructor refuses anything else.
 *
 * Nothing here ever picks another token by itself: the active entry changes only when the driver
 * picks one ([activate]) or deletes the active one ([remove]), and then the first entry left takes
 * over. No rule looks at errors, quotas or counts. While guidance runs the driver may only rename
 * entries and delete the ones that are not active ([mayActivate], [mayRemove], [mayAdd]): a trip
 * session always ends on the token it began with.
 */
data class TokenList(val entries: List<TokenEntry> = emptyList(), val activeId: String? = null) {

    init {
        require(entries.size <= MAX_ENTRIES) { "At most $MAX_ENTRIES tokens" }
        require(entries.all { isId(it.id) }) { "An entry id is not an id" }
        require(entries.all { it.token == it.token.trim() && MapboxAccessTokens.check(it.token) == AccessTokenCheck.VALID }) {
            "An entry holds no public token"
        }
        require(entries.distinctBy { it.id }.size == entries.size) { "Two entries have the same id" }
        require(entries.distinctBy { it.token }.size == entries.size) { "A token is saved twice" }
        require(entries.distinctBy { foldCase(it.name) }.size == entries.size) { "Two entries have the same name" }
        require(entries.all { it.name == cleanName(it.name) && nameShapeProblem(it.name) == null }) { "An entry's name breaks the name rules" }
        require(if (entries.isEmpty()) activeId == null else entries.any { it.id == activeId }) { "Not exactly one active entry" }
    }

    /** The entry the SDKs use; null only when no token is saved. */
    val active: TokenEntry? get() = entries.firstOrNull { it.id == activeId }

    /** No more tokens can be added. */
    val full: Boolean get() = entries.size >= MAX_ENTRIES

    /** The entry's place in the list, counted from 1, as the debug log names it; null when there is none with [id]. */
    fun slotOf(id: String): Int? = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)

    /** The name a new entry gets unless the driver gives one: Main for the first, then the lowest of Token 2, Token 3 and so on that is free. */
    fun defaultName(): String {
        if (entries.isEmpty()) return FIRST_NAME
        return (2..MAX_ENTRIES + 2).map { "Token $it" }.first { candidate -> entries.none { sameName(it.name, candidate) } }
    }

    /**
     * Why [token] cannot be added under [name], or null when it can. A blank or missing name stands
     * for [defaultName]. The token is trimmed and checked as [MapboxAccessTokens.check] checks it.
     */
    fun problemAdding(name: String?, token: String): TokenProblem? {
        if (full) return TokenProblem.Full
        val trimmed = token.trim()
        val check = MapboxAccessTokens.check(trimmed)
        if (check != AccessTokenCheck.VALID) return TokenProblem.BadToken(check)
        entries.firstOrNull { it.token == trimmed }?.let { return TokenProblem.Duplicate(it.name) }
        return nameProblem(nameOrDefault(name), renaming = null)?.let(TokenProblem::BadName)
    }

    /**
     * The list with [token] added at the end under [name] (see [problemAdding], which must have no
     * objection) and the unused [id]. The first entry of an empty list becomes the active one; any
     * other leaves the active entry as it is.
     */
    fun add(name: String?, token: String, id: String): TokenList {
        require(problemAdding(name, token) == null) { "This token cannot be added" }
        require(isId(id) && entries.none { it.id == id }) { "A new entry needs an unused id" }
        return TokenList(entries + TokenEntry(id, nameOrDefault(name), token.trim()), activeId ?: id)
    }

    /** Why the entry [id] cannot be called [name], or null when it can. Its own name in another case is fine. */
    fun problemRenaming(id: String, name: String): TokenProblem? {
        if (entries.none { it.id == id }) return TokenProblem.NotFound
        return nameProblem(cleanName(name), renaming = id)?.let(TokenProblem::BadName)
    }

    /** The list with the entry [id] called [name] (see [problemRenaming]). The active entry stays the active one. */
    fun rename(id: String, name: String): TokenList {
        require(problemRenaming(id, name) == null) { "This name cannot be used" }
        val clean = cleanName(name)
        return copy(entries = entries.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /**
     * The list without the entry [id]. When it was the active one, the first entry left becomes
     * active, and an empty list has none. An unknown id changes nothing.
     */
    fun remove(id: String): TokenList {
        val left = entries.filterNot { it.id == id }
        if (left.size == entries.size) return this
        return TokenList(left, if (id == activeId) left.firstOrNull()?.id else activeId)
    }

    /** The list with the entry [id] active. An unknown id changes nothing. */
    fun activate(id: String): TokenList = if (entries.any { it.id == id }) copy(activeId = id) else this

    /** Whether the driver may make [id] the active entry: another saved entry, and no trip running. */
    fun mayActivate(id: String, tripRunning: Boolean): Boolean = !tripRunning && id != activeId && entries.any { it.id == id }

    /** Whether the driver may delete [id]: any saved entry, except the active one while a trip runs. */
    fun mayRemove(id: String, tripRunning: Boolean): Boolean = entries.any { it.id == id } && !(tripRunning && id == activeId)

    /** Whether the driver may add a token: fewer than [MAX_ENTRIES] saved, and no trip running. */
    fun mayAdd(tripRunning: Boolean): Boolean = !tripRunning && !full

    private fun nameOrDefault(name: String?): String = cleanName(name.orEmpty()).ifEmpty { defaultName() }

    /** Why the cleaned [name] cannot be used; [renaming] is the entry that would get it, whose own name does not count. */
    private fun nameProblem(name: String, renaming: String?): NameProblem? =
        nameShapeProblem(name) ?: NameProblem.TAKEN.takeIf { entries.any { it.id != renaming && sameName(it.name, name) } }

    companion object {
        /** The most tokens the phone keeps. */
        const val MAX_ENTRIES = 5

        /** The longest name, in characters. */
        const val MAX_NAME_LENGTH = 24

        /** The name of the first token: the one an older version kept, or the first one entered. */
        const val FIRST_NAME = "Main"

        val EMPTY = TokenList()

        private val ID = Regex("[A-Za-z0-9_-]{1,32}")
        private val TOKEN_PREFIXES = listOf("pk.", "sk.", "tk.")

        /** Whether [text] can be an entry's id: up to 32 letters, digits, '_' and '-', so it fits in a line of the trip session log. */
        fun isId(text: String): Boolean = ID.matches(text)

        /** [input] as a name: every control character, such as a line break or a tab, becomes a space, and the ends are trimmed. */
        fun cleanName(input: String): String = buildString(input.length) {
            for (char in input) append(if (char.isISOControl()) ' ' else char)
        }.trim()

        /**
         * The list in the token file: one line for each entry, in the list's order, with its id, its
         * token and its name between tabs. Ids and tokens hold no tab and no line break, and names
         * have none after [cleanName].
         */
        fun encode(list: TokenList): String = list.entries.joinToString("\n") { "${it.id}\t${it.token}\t${it.name}" }

        /**
         * The list that [text], as [encode] wrote it, and [activeId] describe. Damage never costs the
         * entries that can still be read: a line without an id and a valid public token is skipped,
         * and so is an id or a token that came before; a name that is missing, too long, like a
         * token or taken by an entry before it becomes the lowest free default name; only the first
         * [MAX_ENTRIES] count. When [activeId] names none of them, the first entry is the active one.
         */
        fun decode(text: String?, activeId: String?): TokenList {
            val read = mutableListOf<TokenEntry>()
            for (line in text.orEmpty().lineSequence()) {
                if (read.size == MAX_ENTRIES) break
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) continue
                val id = parts[0].trim()
                val token = parts[1].trim()
                if (!isId(id) || MapboxAccessTokens.check(token) != AccessTokenCheck.VALID) continue
                if (read.any { it.id == id || it.token == token }) continue
                read += TokenEntry(id, cleanName(parts[2]), token)
            }
            var list = EMPTY
            for (entry in read) {
                val name = if (list.nameProblem(entry.name, renaming = null) == null) entry.name else list.defaultName()
                list = TokenList(list.entries + entry.copy(name = name), list.activeId ?: entry.id)
            }
            return if (activeId != null) list.activate(activeId) else list
        }

        private fun nameShapeProblem(name: String): NameProblem? = when {
            name.isEmpty() -> NameProblem.EMPTY
            name.codePointCount(0, name.length) > MAX_NAME_LENGTH -> NameProblem.TOO_LONG
            TOKEN_PREFIXES.any { name.startsWith(it, ignoreCase = true) } -> NameProblem.LOOKS_LIKE_TOKEN
            else -> null
        }

        private fun sameName(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

        /** The same text for names that are equal ignoring case, as [sameName] compares them. */
        private fun foldCase(name: String): String = buildString(name.length) {
            for (char in name) append(char.uppercaseChar().lowercaseChar())
        }
    }
}

/**
 * What a read of the token file still has to do with the single token that versions before the
 * list kept under the old key. Two writes, never one in place of the other: first the entry, while
 * the old key stays, then the old key goes. A process that dies between them leaves both, and the
 * next read sees the token in the list and only removes the old key, so nothing is lost and nothing
 * is added twice.
 */
enum class TokenMigration {
    /** There is no old key. */
    NONE,

    /**
     * The old key's token becomes an entry ([TokenList.add] with the default name): Main and active
     * in an empty list, as after an update. A list that has entries already gets it as the next
     * name and keeps its active entry; that happens only when a version before the list wrote the
     * key again after the update, as an older build installed over a newer one would.
     */
    ADD_ENTRY,

    /**
     * Only the old key goes: its token is in the list already, or it is no valid public token, or
     * the list is full and has no room for it.
     */
    REMOVE_OLD_KEY,
    ;

    companion object {
        /** The next step for [list], as read, and the old key's [oldToken], null when there is no old key. */
        fun of(list: TokenList, oldToken: String?): TokenMigration = when {
            oldToken == null -> NONE
            list.problemAdding(null, oldToken) == null -> ADD_ENTRY
            else -> REMOVE_OLD_KEY
        }
    }
}
