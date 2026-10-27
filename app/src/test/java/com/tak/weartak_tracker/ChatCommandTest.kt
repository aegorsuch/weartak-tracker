package com.tak.weartak_tracker.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandTest {
    private fun parsed(text: String) = (ChatCommandParser.parse(text) as ChatCommandParse.Parsed).command
    private fun malformed(text: String) = ChatCommandParser.parse(text) is ChatCommandParse.Malformed

    @Test
    fun ordinaryChatIsNeverACommand() {
        assertEquals(ChatCommandParse.NotACommand, ChatCommandParser.parse("hello there"))
        assertEquals(ChatCommandParse.NotACommand, ChatCommandParser.parse("see #weartaktrackercommandsetcallsign callsign=X"))
    }

    @Test
    fun parsesNameCaseInsensitivelyAndArguments() {
        val c = parsed("  #WearTAKTrackerCommandSetCallsign Callsign=ALPHA-1 ")
        assertEquals("setcallsign", c.name)
        assertEquals(mapOf("callsign" to "ALPHA-1"), c.args)
    }

    @Test
    fun positionalValuesForListedCommands() {
        val keys = mapOf("setusertype" to listOf("usertype"), "enrollserver" to listOf("name", "url", "port", "username", "password"))
        fun p(text: String) = ChatCommandParser.parse(text, keys)
        assertEquals(
            mapOf("usertype" to "Coalition Civilian"),
            (p("#weartaktrackercommandsetusertype  Coalition Civilian ") as ChatCommandParse.Parsed).command.args,
        )
        assertEquals(
            mapOf("usertype" to "Coalition Civilian"),
            (p("#weartaktrackercommandsetusertype usertype=\"Coalition Civilian\"") as ChatCommandParse.Parsed).command.args,
        )
        assertEquals(
            listOf("OPS", "tak.example.org", "8089", "u", "p;w"),
            (p("#weartaktrackercommandenrollserver OPS; tak.example.org ;8089; u; p;w") as ChatCommandParse.Parsed)
                .command.args.values.toList(),
        )
        assertTrue(p("#weartaktrackercommandenrollserver OPS; tak.example.org; 8089") is ChatCommandParse.Malformed)
        assertTrue(p("#weartaktrackercommandenrollserver OPS;;8089;u;p") is ChatCommandParse.Malformed)
        assertTrue(malformed("#weartaktrackercommandsetusertype Coalition Civilian"))
    }

    @Test
    fun enrollserverCommaSyntaxKeepsCommasInPasswordAndSupportsPrefixSpacing() {
        val parsed = ChatCommandParser.parse(
            "#weartakcommand enrollserver OPS, tak.example.org, 8089, alice, secret,with,commas",
            mapOf("enrollserver" to listOf("name", "url", "port", "username", "password")),
        ) as ChatCommandParse.Parsed
        assertEquals(
            mapOf(
                "name" to "OPS",
                "url" to "tak.example.org",
                "port" to "8089",
                "username" to "alice",
                "password" to "secret,with,commas",
            ),
            parsed.command.args,
        )
        assertTrue(ChatCommandParser.parse(
            "#weartakcommand enrollserver OPS, tak.example.org, 8089, alice",
            mapOf("enrollserver" to listOf("name", "url", "port", "username", "password")),
        ) is ChatCommandParse.Malformed)
    }

    @Test
    fun parsesQuotedValuesWithSpacesAndEscapes() {
        val c = parsed("#weartaktrackercommandenrollserver name=\"TAK \\\"Ops\\\"\" url=cloud.tak-ops.com port=8089")
        assertEquals("TAK \"Ops\"", c.args["name"])
        assertEquals("cloud.tak-ops.com", c.args["url"])
        assertEquals("8089", c.args["port"])
    }

    @Test
    fun commandWithoutArgumentsParses() {
        assertEquals(emptyMap<String, String>(), parsed("#weartaktrackercommandping").args)
    }

    @Test
    fun acceptsJoinedAndWhitespaceSeparatedCommandsAfterEitherPrefix() {
        val expected = setOf("enabletakservers", "disabletakservers", "enablemulticast", "disablemulticast")
        for (name in expected) {
            assertEquals(name, parsed("#weartakcommand$name").name)
            assertEquals(name, parsed("#weartakcommand $name").name)
            assertEquals(name, parsed("#weartaktrackercommand$name").name)
            assertEquals(name, parsed("#weartaktrackercommand $name").name)
        }
    }

    @Test
    fun rejectsMalformedInput() {
        assertTrue(malformed("#weartaktrackercommand"))
        assertTrue(malformed("#weartaktrackercommand9 a=b"))
        assertTrue(malformed("#weartaktrackercommandx novalue"))
        assertTrue(malformed("#weartaktrackercommandx a="))
        assertTrue(malformed("#weartaktrackercommandx a=1 a=2"))
        assertTrue(malformed("#weartaktrackercommandx a=\"open"))
        assertTrue(malformed("#weartaktrackercommandx a=\"q\"junk"))
        assertTrue(malformed("#weartaktrackercommandx 1a=b"))
        assertTrue(malformed("#weartaktrackercommandx a=${"v".repeat(129)}"))
        assertTrue(malformed("#weartaktrackercommandx " + (1..13).joinToString(" ") { "k$it=v" }))
        assertTrue(malformed("#weartaktrackercommandx " + "a=b ".repeat(200)))
        assertTrue(malformed("#weartaktrackercommandx a=b\u0007c"))
    }

    @Test
    fun authorizerRequiresAllowedFreshUniqueSender() {
        val auth = ChatCommandAuthorizer(maxAgeMs = 1000)
        val allowed = listOf("Alpha-1", " BRAVO ")
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("Eve", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("alpha-1", emptyList(), "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "old", 1_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "future", 20_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "zero", 0, 10_000))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("ALPHA-1", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.DUPLICATE, auth.authorize("alpha-1", allowed, "m1", 10_000, 10_100))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("bravo", allowed, "m1", 10_000, 10_100))
    }

    @Test
    fun rejectedMessagesAreNotRememberedAsDuplicates() {
        val auth = ChatCommandAuthorizer(maxAgeMs = 1000)
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("eve", listOf("a"), "m", 10_000, 10_000))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("a", listOf("a"), "m", 10_000, 10_000))
    }

    @Test
    fun allowListParsingTrimsDeduplicatesAndSortsForDisplay() {
        assertEquals(listOf("A", "B"), ChatCommandAuthorizer.parseAllowList(" A, b ,a,, "))
    }

    @Test
    fun allowListParsingMigratesCommaAndNewlineSeparatedValues() {
        assertEquals(
            listOf("ALPHA", "BRAVO", "ZULU"),
            ChatCommandAuthorizer.parseAllowList("Zulu, alpha\nBRAVO\r\nalpha"),
        )
    }

    @Test
    fun allowedCallsignsAreValidatedAndAddedInUppercase() {
        assertEquals(null, ChatCommandAuthorizer.validateNewCallsign(" alpha-1 ", emptyList()))
        assertEquals(listOf("ALPHA-1"), ChatCommandAuthorizer.addAllowedCallsign(emptyList(), " alpha-1 "))

        assertTrue(ChatCommandAuthorizer.validateNewCallsign("bad/callsign", emptyList())!!.contains("valid callsign"))
        assertEquals(null, ChatCommandAuthorizer.addAllowedCallsign(emptyList(), "bad/callsign"))
        assertEquals(
            "This callsign is already allowed",
            ChatCommandAuthorizer.validateNewCallsign("alpha-1", listOf("ALPHA-1")),
        )
        assertEquals(null, ChatCommandAuthorizer.addAllowedCallsign(listOf("ALPHA-1"), "alpha-1"))
    }

    @Test
    fun removingCallsignIsCaseInsensitiveAndKeepsListSorted() {
        assertEquals(
            listOf("BRAVO"),
            ChatCommandAuthorizer.removeAllowedCallsign(listOf("ALPHA", "bravo"), "alpha"),
        )
    }
}