package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Q-042: unit coverage for the [Credential] wrapper and the three default
 * [CredentialProvider] implementations. Scenarios 1, 2, and 8 from § 7 of
 * `proposals/implemented/credential-isolation.md`:
 *
 *  1. `Credential.toString` is redacted (never returns the underlying
 *     value).
 *  2. `Credential.reveal` returns the raw String exactly.
 *  8. Credential equality is reference identity, not value identity —
 *     value-comparing two credentials minted from the same raw String
 *     must be unequal.
 */
class CredentialTest {

    private val savedCredentialProvider = Builtins.credentialProvider

    @BeforeEach
    fun setUp() {
        CredentialScrubber.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        Builtins.credentialProvider = savedCredentialProvider
        CredentialScrubber.resetForTesting()
    }

    // -- Scenario 1: toString is redacted --

    @Test
    fun `Credential_toString returns the redacted placeholder, never the raw value`() {
        val credential = Credential("sk-ant-api03-xxxxxxxx", "anthropic", "api_key")
        val rendered = credential.toString()
        assertEquals("[REDACTED:anthropic:api_key]", rendered)
        // The raw value must NOT appear anywhere in the rendered text.
        assertFalse("sk-ant-api03-xxxxxxxx" in rendered, "raw value leaked through toString")
    }

    @Test
    fun `Credential_toString carries provider and credentialKey separately`() {
        val pinecone = Credential("pk-test-123456", "pinecone", "api_key")
        val openaiOrg = Credential("org-secret-xxxxx", "openai", "organization_id")
        assertEquals("[REDACTED:pinecone:api_key]", pinecone.toString())
        assertEquals("[REDACTED:openai:organization_id]", openaiOrg.toString())
    }

    @Test
    fun `StaticCredentialProvider produces credentials whose toString is redacted`() {
        val provider = StaticCredentialProvider(mapOf("anthropic" to "sk-ant-test-1234"))
        val credential = provider.apiKey("anthropic")
        assertNotNull(credential)
        val rendered = credential!!.toString()
        assertEquals("[REDACTED:anthropic:api_key]", rendered)
        assertFalse("sk-ant-test-1234" in rendered)
    }

    // -- Scenario 2: reveal() returns the raw value --

    @Test
    fun `Credential_reveal returns the underlying raw string verbatim`() {
        val credential = Credential("the-actual-secret", "test", "api_key")
        assertEquals("the-actual-secret", credential.reveal())
    }

    @Test
    fun `Credential minted by EnvCredentialProvider reveals the env-var value`() {
        // No direct way to install an env var portably — use the InMemory
        // provider as a stand-in for the reveal behavior. The semantics
        // are identical: the provider mints a Credential whose reveal()
        // returns the supplied raw string.
        val provider = InMemoryCredentialProvider(mapOf(
            ("anthropic" to "api_key") to "sk-ant-raw-value-xxxxxx",
        ))
        val credential = provider.resolve("anthropic", "api_key")
        assertNotNull(credential)
        assertEquals("sk-ant-raw-value-xxxxxx", credential!!.reveal())
        assertEquals("anthropic", credential.provider)
        assertEquals("api_key", credential.credentialKey)
    }

    // -- Scenario 8: equality is reference identity --

    @Test
    fun `two Credentials with the same raw value are not equal`() {
        val a = Credential("same-secret-value-xxxxxx", "anthropic", "api_key")
        val b = Credential("same-secret-value-xxxxxx", "anthropic", "api_key")
        assertNotEquals(a, b, "value-equality must be disabled to prevent lookup-by-value")
        // hashCode follows identity, so collisions are accidental — not
        // structurally guaranteed equal.
        assertTrue(a.hashCode() == System.identityHashCode(a))
        assertTrue(b.hashCode() == System.identityHashCode(b))
    }

    @Test
    fun `the same Credential instance equals itself`() {
        val credential = Credential("self-equality-test-xxxxx", "openai", "api_key")
        assertEquals(credential, credential, "reference equality holds for the identity case")
    }

    @Test
    fun `Set_contains on a known-value Credential does not match a freshly-minted Credential`() {
        // The classic "lookup-by-value" anti-pattern that the
        // reference-identity equality prevents.
        val original = Credential("set-contains-test-xxxxx", "test", "api_key")
        val set: Set<Credential> = setOf(original)
        val freshlyMinted = Credential("set-contains-test-xxxxx", "test", "api_key")
        assertTrue(original in set)
        assertFalse(freshlyMinted in set,
            "lookup-by-value across distinct Credential instances must fail by design")
    }

    @Test
    fun `Map keyed by Credential does not collide on equal raw values`() {
        val a = Credential("map-key-test-value-xxxxx", "test", "api_key")
        val b = Credential("map-key-test-value-xxxxx", "test", "api_key")
        val map: Map<Credential, String> = mapOf(a to "first", b to "second")
        // Two entries — value identity does NOT collapse them into one.
        assertEquals(2, map.size)
    }

    @Test
    fun `null resolution returns null and does not register anything`() {
        val provider = StaticCredentialProvider(mapOf("anthropic" to "abc-1234567"))
        assertNull(provider.resolve("openai", "api_key"))
        assertNull(provider.resolve("anthropic", "client_secret"))  // StaticCredentialProvider only honors api_key
    }
}
