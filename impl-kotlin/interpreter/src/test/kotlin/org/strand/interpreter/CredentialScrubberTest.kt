package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Q-042: unit coverage for [CredentialScrubber] and its integration with
 * the [IoFailure] runtime exception. Scenarios 3, 9, and 10 from § 7 of
 * `proposals/implemented/credential-isolation.md`:
 *
 *  3. `IoFailure.detail` is scrubbed at construction (every literal
 *     occurrence of a registered credential value is replaced with the
 *     placeholder).
 *  9. The scrubber skips credentials that are blank or shorter than 8
 *     characters, to avoid over-redacting common substrings ("test",
 *     "demo", short test placeholders).
 * 10. `resetForTesting` empties the registry so tests can isolate
 *     scrubber state between cases.
 *
 * The unscrubbedDetail field is verified to retain the original raw
 * value — needed by the `ErrorVerbosity.Full` opt-in path (covered by
 * [ErrorVerbosityTest] separately).
 */
class CredentialScrubberTest {

    @BeforeEach
    fun setUp() {
        CredentialScrubber.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        CredentialScrubber.resetForTesting()
    }

    // -- Scenario 3: IoFailure.detail is scrubbed at construction --

    @Test
    fun `IoFailure detail is scrubbed at construction when the credential is registered first`() {
        val credential = Credential("sk-ant-test-1234abcd", "anthropic", "api_key")
        CredentialScrubber.register(credential)

        val failure = IoFailure(
            "anthropic-http-status",
            "status 401: {\"error\":\"invalid key: sk-ant-test-1234abcd\"}",
        )

        // Detail is scrubbed.
        assertEquals(
            "status 401: {\"error\":\"invalid key: [REDACTED:anthropic:api_key]\"}",
            failure.detail,
        )
        // The raw value is preserved for ErrorVerbosity.Full reads.
        assertEquals(
            "status 401: {\"error\":\"invalid key: sk-ant-test-1234abcd\"}",
            failure.unscrubbedDetail,
        )
        // The exception message uses the scrubbed form (toString cascade safety).
        assertFalse("sk-ant-test-1234abcd" in failure.message!!)
    }

    @Test
    fun `IoFailure detail with multiple credential occurrences scrubs every one`() {
        val credential = Credential("repeated-secret-value", "test", "api_key")
        CredentialScrubber.register(credential)

        val failure = IoFailure(
            "test-leak",
            "url=https://x?key=repeated-secret-value retry=https://x?key=repeated-secret-value",
        )
        assertEquals(
            "url=https://x?key=[REDACTED:test:api_key] retry=https://x?key=[REDACTED:test:api_key]",
            failure.detail,
        )
    }

    @Test
    fun `IoFailure detail with multiple distinct credentials scrubs each`() {
        CredentialScrubber.register(Credential("first-credential-1234", "a", "api_key"))
        CredentialScrubber.register(Credential("second-credential-5678", "b", "api_key"))

        val failure = IoFailure(
            "mixed",
            "saw first-credential-1234 then second-credential-5678",
        )
        assertEquals(
            "saw [REDACTED:a:api_key] then [REDACTED:b:api_key]",
            failure.detail,
        )
    }

    @Test
    fun `IoFailure constructed before credential is registered preserves raw value`() {
        // Construction-time scrub means a credential registered after the
        // IoFailure is built does NOT retroactively scrub. This is the
        // expected behavior — IoFailures are immutable, the scrubber sees
        // the registry state at construction time.
        val failure = IoFailure("early-leak", "the secret is sk-not-yet-registered-12")
        CredentialScrubber.register(Credential("sk-not-yet-registered-12", "test", "api_key"))
        assertTrue("sk-not-yet-registered-12" in failure.detail,
            "construction-time scrub cannot see future registrations")
    }

    @Test
    fun `scrub returns text unchanged when registry is empty`() {
        val text = "no secrets in this string at all"
        assertEquals(text, CredentialScrubber.scrub(text))
    }

    @Test
    fun `scrub returns text unchanged when no registered credential appears in it`() {
        CredentialScrubber.register(Credential("unrelated-secret-value", "x", "api_key"))
        val text = "this string mentions no registered credential"
        assertEquals(text, CredentialScrubber.scrub(text))
    }

    // -- Scenario 9: scrubber skips short / blank values --

    @Test
    fun `scrubber skips registering blank credential values`() {
        // Blank values must not enter the registry — they'd match every
        // substring and over-redact every IoFailure.
        CredentialScrubber.register(Credential("", "blank", "api_key"))
        val failure = IoFailure("test", "this should be preserved verbatim")
        assertEquals("this should be preserved verbatim", failure.detail)
    }

    @Test
    fun `scrubber skips registering values shorter than 8 characters`() {
        // "abc" (3 chars) should not be registered — common substrings
        // like "test", "demo", and short placeholder values would over-
        // redact production error messages.
        CredentialScrubber.register(Credential("abc", "short", "api_key"))
        val failure = IoFailure("test", "abc is a common substring of normal text like abcd")
        assertEquals(
            "abc is a common substring of normal text like abcd",
            failure.detail,
        )
    }

    @Test
    fun `scrubber skips a 7-character credential at the boundary`() {
        // Boundary case: length 7 == below threshold (< 8). Skip.
        CredentialScrubber.register(Credential("1234567", "boundary7", "api_key"))
        val failure = IoFailure("test", "the secret 1234567 should NOT be scrubbed")
        assertEquals("the secret 1234567 should NOT be scrubbed", failure.detail)
    }

    @Test
    fun `scrubber registers an 8-character credential at the boundary`() {
        // Boundary case: length 8 == at threshold (>= 8). Register.
        CredentialScrubber.register(Credential("12345678", "boundary8", "api_key"))
        val failure = IoFailure("test", "the secret 12345678 should be scrubbed")
        assertEquals(
            "the secret [REDACTED:boundary8:api_key] should be scrubbed",
            failure.detail,
        )
    }

    @Test
    fun `scrubber skips a whitespace-only credential`() {
        CredentialScrubber.register(Credential("        ", "whitespace", "api_key"))
        val failure = IoFailure("test", "whitespace         in the middle")
        // No replacement — the credential is blank by `isBlank`.
        assertEquals("whitespace         in the middle", failure.detail)
    }

    // -- Scenario 10: resetForTesting clears the registry --

    @Test
    fun `resetForTesting empties the registry between cases`() {
        CredentialScrubber.register(Credential("registered-secret-value", "x", "api_key"))
        val before = IoFailure("test", "saw registered-secret-value")
        assertEquals("saw [REDACTED:x:api_key]", before.detail)

        CredentialScrubber.resetForTesting()

        val after = IoFailure("test", "saw registered-secret-value")
        assertEquals(
            "saw registered-secret-value",
            after.detail,
            "after reset, the value should not be scrubbed",
        )
    }

    // -- Direct CredentialScrubber.scrub coverage --

    @Test
    fun `direct scrub call replaces every registered value`() {
        CredentialScrubber.register(Credential("alpha-credential-value", "test", "api_key"))
        val scrubbed = CredentialScrubber.scrub("prefix alpha-credential-value suffix")
        assertEquals("prefix [REDACTED:test:api_key] suffix", scrubbed)
    }
}
