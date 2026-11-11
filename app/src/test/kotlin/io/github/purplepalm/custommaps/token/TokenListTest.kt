package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TokenListTest {

    /** Shaped like a Mapbox public token and clearly fake: "test" and "sig" in base64, and a number. */
    private fun fake(n: Int) = "pk.dGVzdA.c2ln$n"

    /** A list of [count] tokens added one after the other, with the ids t1, t2... and default names. */
    private fun saved(count: Int): TokenList =
        (1..count).fold(TokenList.EMPTY) { list, n -> list.add(null, fake(n), "t$n") }

    private fun TokenList.names() = entries.map { it.name }

    @Test
    fun `the first token added is Main and active`() {
        val list = TokenList.EMPTY.add(null, fake(1), "t1")

        assertEquals(listOf("Main"), list.names())
        assertEquals("t1", list.activeId)
        assertEquals(fake(1), list.active?.token)
    }

    @Test
    fun `later tokens are named Token 2 and so on and leave the active one`() {
        val list = saved(3)

        assertEquals(listOf("Main", "Token 2", "Token 3"), list.names())
        assertEquals("t1", list.activeId)
    }

    @Test
    fun `the default name is the lowest free one`() {
        val list = saved(3).remove("t2")
        assertEquals("Token 2", list.defaultName())

        // Main renamed: the next one is still a Token, not Main again.
        val renamed = saved(1).rename("t1", "Home")
        assertEquals("Token 2", renamed.defaultName())
        assertEquals("Token 3", saved(1).rename("t1", "token 2").defaultName())
        assertEquals("Main", TokenList.EMPTY.defaultName())
    }

    @Test
    fun `a blank name gets the default name, and names are trimmed`() {
        val list = saved(1).add("   ", fake(2), "t2").add("  Spare \n", fake(3), "t3")

        assertEquals(listOf("Main", "Token 2", "Spare"), list.names())
    }

    @Test
    fun `control characters in a name become spaces`() {
        assertEquals("Oak  Street", TokenList.cleanName("\tOak\n\rStreet\u0000"))
        assertEquals("Oak  Street", saved(1).rename("t1", "Oak\n\tStreet").active?.name)
    }

    @Test
    fun `at most five tokens`() {
        val five = saved(TokenList.MAX_ENTRIES)
        assertEquals(5, TokenList.MAX_ENTRIES)
        assertTrue(five.full)
        assertFalse(saved(4).full)

        assertEquals(TokenProblem.Full, five.problemAdding("Spare", fake(6)))
        // Full comes first, whatever the token.
        assertEquals(TokenProblem.Full, five.problemAdding(null, "sk.dGVzdA.c2Vj"))
        assertThrows<IllegalArgumentException> { five.add(null, fake(6), "t6") }
        assertFalse(five.mayAdd(tripRunning = false))
        assertTrue(saved(4).mayAdd(tripRunning = false))
        assertThrows<IllegalArgumentException> {
            TokenList((1..6).map { TokenEntry("t$it", "Token $it", fake(it)) }, "t1")
        }
    }

    @Test
    fun `the same token cannot be saved twice`() {
        val list = saved(2).rename("t2", "Test")

        assertEquals(TokenProblem.Duplicate("Test"), list.problemAdding("Spare", fake(2)))
        assertEquals(TokenProblem.Duplicate("Main"), list.problemAdding(null, "  ${fake(1)}\n"))
        assertNull(list.problemAdding(null, fake(3)))
    }

    @Test
    fun `tokens are checked as before, and a secret token is refused`() {
        val list = saved(1)

        assertEquals(TokenProblem.BadToken(AccessTokenCheck.SECRET_TOKEN), list.problemAdding(null, "sk.dGVzdA.c2Vj"))
        assertEquals(TokenProblem.BadToken(AccessTokenCheck.EMPTY), list.problemAdding(null, "  "))
        assertEquals(TokenProblem.BadToken(AccessTokenCheck.NOT_PUBLIC_TOKEN), list.problemAdding(null, "hello"))
        assertEquals(TokenProblem.BadToken(AccessTokenCheck.MALFORMED), list.problemAdding(null, "pk.onlyonepart"))
        assertThrows<IllegalArgumentException> { list.add(null, "sk.dGVzdA.c2Vj", "t2") }
    }

    @Test
    fun `a token is stored trimmed`() {
        val list = TokenList.EMPTY.add("Main", "  ${fake(1)}\n", "t1")

        assertEquals(fake(1), list.active?.token)
    }

    @Test
    fun `names have 1 to 24 characters`() {
        val list = saved(1)
        val longest = "A".repeat(TokenList.MAX_NAME_LENGTH)

        assertNull(list.problemRenaming("t1", longest))
        assertEquals(TokenProblem.BadName(NameProblem.TOO_LONG), list.problemRenaming("t1", longest + "B"))
        assertEquals(TokenProblem.BadName(NameProblem.TOO_LONG), list.problemAdding(longest + "B", fake(2)))
        assertEquals(TokenProblem.BadName(NameProblem.EMPTY), list.problemRenaming("t1", " \t "))
        // Characters, not UTF-16 units: 24 two-unit symbols fit.
        assertNull(list.problemRenaming("t1", "🚗".repeat(TokenList.MAX_NAME_LENGTH)))
        assertThrows<IllegalArgumentException> { list.rename("t1", "") }
    }

    @Test
    fun `names are unique ignoring case`() {
        val list = saved(2)

        assertEquals(TokenProblem.BadName(NameProblem.TAKEN), list.problemAdding("main", fake(3)))
        assertEquals(TokenProblem.BadName(NameProblem.TAKEN), list.problemRenaming("t2", " MAIN "))
        // An entry's own name in another case is fine.
        assertEquals(listOf("MAIN", "Token 2"), list.rename("t1", "MAIN").names())
    }

    @Test
    fun `a name that starts like a token is refused`() {
        val list = saved(1)

        for (name in listOf("pk.dGVzdA", "SK.test", "tk.x", " Pk.abc")) {
            assertEquals(TokenProblem.BadName(NameProblem.LOOKS_LIKE_TOKEN), list.problemRenaming("t1", name), name)
        }
        assertEquals(TokenProblem.BadName(NameProblem.LOOKS_LIKE_TOKEN), list.problemAdding("pk.abc", fake(2)))
        assertNull(list.problemRenaming("t1", "pk spare"))
    }

    @Test
    fun `renaming keeps the active entry`() {
        val list = saved(3).activate("t2")

        // Another entry renamed, before or after the active one: the active one stays.
        assertEquals("t2", list.rename("t3", "Test").activeId)
        assertEquals("t2", list.rename("t1", "Office").activeId)

        val renamed = list.rename("t3", "Test").rename("t2", "Spare")

        assertEquals("t2", renamed.activeId)
        assertEquals(listOf("Main", "Spare", "Test"), renamed.names())
        assertEquals(TokenProblem.NotFound, list.problemRenaming("t9", "Gym"))
    }

    @Test
    fun `activating picks the entry, and an unknown one changes nothing`() {
        val list = saved(3)

        assertEquals("t3", list.activate("t3").activeId)
        assertSame(list, list.activate("t9"))
    }

    @Test
    fun `deleting the active entry makes the first one left active`() {
        assertEquals("t2", saved(3).remove("t1").activeId)
        assertEquals("t1", saved(3).activate("t2").remove("t2").activeId)
        assertEquals("t1", saved(3).activate("t3").remove("t3").activeId)
    }

    @Test
    fun `deleting another entry keeps the active one`() {
        val list = saved(3).activate("t2").remove("t1")

        assertEquals("t2", list.activeId)
        assertEquals(listOf("Token 2", "Token 3"), list.names())
        // Also when the active one is not the first entry left.
        assertEquals("t3", saved(3).activate("t3").remove("t1").activeId)
        assertEquals("t3", saved(3).activate("t3").remove("t2").activeId)
    }

    @Test
    fun `deleting the last entry leaves none, and none active`() {
        val list = saved(1).remove("t1")

        assertEquals(TokenList.EMPTY, list)
        assertNull(list.active)
        assertSame(list, list.remove("t1"))
    }

    @Test
    fun `while a trip runs only renaming and deleting the tokens not in use are allowed`() {
        val list = saved(3).activate("t2")

        assertFalse(list.mayActivate("t1", tripRunning = true))
        assertFalse(list.mayRemove("t2", tripRunning = true))
        assertTrue(list.mayRemove("t1", tripRunning = true))
        assertFalse(list.mayAdd(tripRunning = true))
        assertNull(list.problemRenaming("t2", "Test"))
    }

    @Test
    fun `without a trip every change is allowed`() {
        val list = saved(3).activate("t2")

        assertTrue(list.mayActivate("t1", tripRunning = false))
        assertFalse(list.mayActivate("t2", tripRunning = false), "the active one is in use already")
        assertFalse(list.mayActivate("t9", tripRunning = false))
        assertTrue(list.mayRemove("t2", tripRunning = false))
        assertFalse(list.mayRemove("t9", tripRunning = false))
        assertTrue(list.mayAdd(tripRunning = false))
    }

    @Test
    fun `a list never has two active entries or none`() {
        val main = TokenEntry("t1", "Main", fake(1))
        val test = TokenEntry("t2", "Test", fake(2))

        assertThrows<IllegalArgumentException> { TokenList(listOf(main, test), null) }
        assertThrows<IllegalArgumentException> { TokenList(listOf(main, test), "t9") }
        assertThrows<IllegalArgumentException> { TokenList(emptyList(), "t1") }
        assertThrows<IllegalArgumentException> { TokenList(listOf(main, main.copy(id = "t3")), "t1") }
        assertThrows<IllegalArgumentException> { TokenList(listOf(main, test.copy(id = "t1")), "t1") }
        assertThrows<IllegalArgumentException> { TokenList(listOf(main, test.copy(name = "MAIN")), "t1") }
        assertThrows<IllegalArgumentException> { TokenList(listOf(main.copy(token = "sk.dGVzdA.c2Vj")), "t1") }
        assertThrows<IllegalArgumentException> { TokenList(listOf(main.copy(id = "t 1")), "t 1") }
        assertEquals(main, TokenList(listOf(main, test), "t1").active)
    }

    @Test
    fun `a new entry needs an unused id`() {
        val list = saved(1)

        assertThrows<IllegalArgumentException> { list.add(null, fake(2), "t1") }
        assertThrows<IllegalArgumentException> { list.add(null, fake(2), "t,2") }
        assertTrue(TokenList.isId("a1b2-c3_d4"))
        assertFalse(TokenList.isId(""))
        assertFalse(TokenList.isId("a".repeat(33)))
    }

    @Test
    fun `slots count from one`() {
        val list = saved(3)

        assertEquals(1, list.slotOf("t1"))
        assertEquals(3, list.slotOf("t3"))
        assertNull(list.slotOf("t9"))
    }

    @Test
    fun `the old single token becomes Main and active`() {
        assertEquals(TokenMigration.ADD_ENTRY, TokenMigration.of(TokenList.EMPTY, fake(1)))

        val migrated = TokenList.EMPTY.add(null, fake(1), "m1")
        assertEquals(TokenList(listOf(TokenEntry("m1", "Main", fake(1))), "m1"), migrated)
    }

    @Test
    fun `an old token that is in the list already only removes the old key`() {
        val list = saved(2)

        assertEquals(TokenMigration.REMOVE_OLD_KEY, TokenMigration.of(list, fake(2)))
        assertEquals(TokenMigration.REMOVE_OLD_KEY, TokenMigration.of(list, " ${fake(1)} "))
    }

    @Test
    fun `an old key without a valid public token is only removed`() {
        assertEquals(TokenMigration.REMOVE_OLD_KEY, TokenMigration.of(TokenList.EMPTY, "sk.dGVzdA.c2Vj"))
        assertEquals(TokenMigration.REMOVE_OLD_KEY, TokenMigration.of(TokenList.EMPTY, ""))
    }

    @Test
    fun `an old token joins a list it is not in without becoming active, unless the list is full`() {
        val list = saved(2).activate("t2")
        assertEquals(TokenMigration.ADD_ENTRY, TokenMigration.of(list, fake(7)))
        val joined = list.add(null, fake(7), "m1")
        assertEquals("t2", joined.activeId)
        assertEquals("Token 3", joined.entries.last().name)

        assertEquals(TokenMigration.REMOVE_OLD_KEY, TokenMigration.of(saved(5), fake(7)))
    }

    @Test
    fun `no old key, nothing to migrate`() {
        assertEquals(TokenMigration.NONE, TokenMigration.of(TokenList.EMPTY, null))
        assertEquals(TokenMigration.NONE, TokenMigration.of(saved(2), null))
    }

    @Test
    fun `the list is written and read back as it was`() {
        val list = saved(3).rename("t2", "Test drive").activate("t3")

        val text = TokenList.encode(list)

        assertEquals(list, TokenList.decode(text, "t3"))
        assertEquals(3, text.lines().size)
        assertEquals(TokenList.EMPTY, TokenList.decode(TokenList.encode(TokenList.EMPTY), null))
    }

    @Test
    fun `a missing or empty list is empty with none active`() {
        assertEquals(TokenList.EMPTY, TokenList.decode(null, null))
        assertEquals(TokenList.EMPTY, TokenList.decode("", "t1"))
    }

    @Test
    fun `an active id that is gone makes the first entry active`() {
        val text = TokenList.encode(saved(2))

        assertEquals("t1", TokenList.decode(text, "t9").activeId)
        assertEquals("t1", TokenList.decode(text, null).activeId)
    }

    @Test
    fun `a damaged list keeps every entry that can still be read`() {
        val text = listOf(
            "t1\t${fake(1)}\tMain",
            // Cut short, with no name field.
            "t2\t${fake(2)}",
            "garbage",
            // Not a public token, a secret one, a bad id.
            "t3\tnot-a-token\tGym",
            "t4\tsk.dGVzdA.c2Vj\tSecret",
            "t 5\t${fake(5)}\tOffice",
            // An id and a token that came before.
            "t1\t${fake(6)}\tAgain",
            "t7\t${fake(1)}\tCopy",
            // Names that break the rules get the lowest free default name.
            "t8\t${fake(8)}\t",
            "t9\t${fake(9)}\tmain",
            "t10\t${fake(10)}\t${"N".repeat(30)}",
            "t11\t${fake(11)}\tpk.dGVzdA",
            // Past five entries nothing more is read.
            "t12\t${fake(12)}\tLast",
        ).joinToString("\n")

        val list = TokenList.decode(text, "t9")

        assertEquals(listOf("t1", "t8", "t9", "t10", "t11"), list.entries.map { it.id })
        assertEquals(listOf("Main", "Token 2", "Token 3", "Token 4", "Token 5"), list.names())
        assertEquals("t9", list.activeId)
    }

    @Test
    fun `a tab inside the name field stays part of the name`() {
        val text = "t1\t${fake(1)}\tOak\tStreet"

        assertEquals("Oak\tStreet".let(TokenList::cleanName), TokenList.decode(text, "t1").active?.name)
    }

    @Test
    fun `the token never shows in what an entry or a list prints`() {
        val list = saved(2)

        for (text in listOf(list.toString(), list.entries.first().toString(), TokenProblem.Duplicate("Main").toString())) {
            assertFalse(text.contains(fake(1)), text)
            assertFalse(text.contains(fake(2)), text)
        }
        assertTrue(list.entries.first().toString().contains("Main"))
    }
}
