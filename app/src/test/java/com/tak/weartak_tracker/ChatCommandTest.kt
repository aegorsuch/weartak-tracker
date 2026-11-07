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
    fun enrollserverQuotedCredentialsSurviveAllArgumentForms() {
        val password = " !p_ass\\word\\\"x,;= "
        val encoded = password.replace("\\", "\\\\").replace("\"", "\\\"")
        val commands = listOf(
            """name="Field Ops" url=tak.example.org username=alice password="$encoded"""",
            """"Field Ops", tak.example.org, 8089, alice, "$encoded"""",
            """"Field Ops"; tak.example.org; 8089; alice; "$encoded"""",
        )
        for (prefix in listOf(ChatCommandParser.PREFIX, ChatCommandParser.COMMON_PREFIX)) {
            for (args in commands) {
                val result = ChatCommandParser.parse("$prefix enrollserver $args", ProfileHandler.POSITIONAL_KEYS)
                assertTrue(result.toString(), result is ChatCommandParse.Parsed)
                val command = (result as ChatCommandParse.Parsed).command
                assertEquals("Field Ops", command.args["name"])
                assertEquals(password, command.args["password"])
            }
        }
    }

    @Test
    fun enrollserverPreservesLiteralUnquotedBackslashesAndPunctuation() {
        val password = """!p_ass\word\\x,;="""
        for (args in listOf(
            "name=OPS url=tak.example.org username=alice password=$password",
            "OPS, tak.example.org, 8089, alice, $password",
            "OPS; tak.example.org; 8089; alice; $password",
        )) {
            val result = ChatCommandParser.parse(
                "${ChatCommandParser.PREFIX} enrollserver $args", ProfileHandler.POSITIONAL_KEYS,
            ) as ChatCommandParse.Parsed
            assertEquals(password, result.command.args["password"])
        }
    }

    @Test
    fun enrollserverSeparatorsInsideQuotesDoNotSplitCredentials() {
        for (delimiter in listOf(',', ';')) {
            val result = ChatCommandParser.parse(
                """${ChatCommandParser.PREFIX} enrollserver "OPS"$delimiter tak.example.org$delimiter 8089$delimiter "alice,;ops"$delimiter "p,;w"""",
                ProfileHandler.POSITIONAL_KEYS,
            ) as ChatCommandParse.Parsed
            assertEquals("alice,;ops", result.command.args["username"])
            assertEquals("p,;w", result.command.args["password"])
        }
    }

    @Test
    fun enrollserverQuotedPasswordLimitAppliesAfterDecodingEscapes() {
        for (delimiter in listOf(',', ';')) {
            val password = "\\".repeat(ChatCommandParser.MAX_VALUE_LENGTH)
            val encoded = password.replace("\\", "\\\\")
            val result = ChatCommandParser.parse(
                """${ChatCommandParser.PREFIX} enrollserver OPS$delimiter tak.example.org$delimiter 8089$delimiter alice$delimiter "$encoded"""",
                ProfileHandler.POSITIONAL_KEYS,
            ) as ChatCommandParse.Parsed
            assertEquals(password, result.command.args["password"])
        }
    }

    @Test
    fun enrollserverRejectsMalformedQuotedPositionalValues() {
        for (args in listOf(
            """OPS, tak.example.org, 8089, alice, "unterminated""",
            """OPS; tak.example.org; 8089; alice; "closed"junk""",
            """OPS, tak.example.org, 8089, alice, """"",
            """OPS; tak.example.org; 8089; alice; "${"x".repeat(129)}"""",
            "OPS, tak.example.org, 8089, alice, \"bad\u0007value\"",
        )) {
            assertTrue(ChatCommandParser.parse(
                "${ChatCommandParser.PREFIX} enrollserver $args", ProfileHandler.POSITIONAL_KEYS,
            ) is ChatCommandParse.Malformed)
        }
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
    fun editingCallsignReplacesEntryAndKeepsSortedUppercase() {
        assertEquals(
            listOf("ALPHA", "CHARLIE"),
            ChatCommandAuthorizer.replaceAllowedCallsign(listOf("ALPHA", "BRAVO"), "bravo", " charlie "),
        )
        // Re-saving the same value (or changing only case) is not a duplicate.
        assertEquals(
            listOf("ALPHA", "BRAVO"),
            ChatCommandAuthorizer.replaceAllowedCallsign(listOf("ALPHA", "BRAVO"), "BRAVO", "bravo"),
        )
    }

    @Test
    fun editingCallsignRejectsInvalidAndDuplicateValues() {
        val list = listOf("ALPHA", "BRAVO")
        assertEquals(null, ChatCommandAuthorizer.replaceAllowedCallsign(list, "BRAVO", "alpha"))
        assertEquals(null, ChatCommandAuthorizer.replaceAllowedCallsign(list, "BRAVO", "bad/callsign"))
        assertEquals(
            "This callsign is already allowed",
            ChatCommandAuthorizer.validateEditedCallsign("BRAVO", "alpha", list),
        )
        assertEquals(null, ChatCommandAuthorizer.validateEditedCallsign("BRAVO", "bravo", list))
    }

    @Test
    fun removingCallsignIsCaseInsensitiveAndKeepsListSorted() {
        assertEquals(
            listOf("BRAVO"),
            ChatCommandAuthorizer.removeAllowedCallsign(listOf("ALPHA", "bravo"), "alpha"),
        )
    }
}