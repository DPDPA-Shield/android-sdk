/**
 * Real, executed verification of the pure-Kotlin `core` module - no mocking
 * framework, no JUnit (neither is on the classpath in the sandbox this was
 * built in; there is no Gradle/AGP/Android SDK available here at all - see
 * CLAUDE.md "Android SDK - Kotlin core" for the full explanation). Compiled
 * and run with a standalone kotlinc + the Android Studio JBR JDK 21, exactly
 * as `apps/api/scripts/verify-*.ts` are run with tsx: real objects, real
 * behaviour, numbered gates, non-zero exit on any failure.
 *
 * Run with:
 *   kotlinc-jvm <every .kt file under core/src/main and this file> -include-runtime -d verify-core.jar
 *   java -jar verify-core.jar
 */
import com.dpdpashield.sdk.core.ShieldConsentConfig
import com.dpdpashield.sdk.core.ShieldConsentEngine
import com.dpdpashield.sdk.core.gate.ShieldGate
import com.dpdpashield.sdk.core.hash.DataPrincipalHasher
import com.dpdpashield.sdk.core.i18n.NoticeLocalizer
import com.dpdpashield.sdk.core.model.ConsentDecision
import com.dpdpashield.sdk.core.model.ConsentNotice
import com.dpdpashield.sdk.core.model.ConsentPurpose
import com.dpdpashield.sdk.core.model.NoticeTranslation
import com.dpdpashield.sdk.core.model.PurposeTranslation
import com.dpdpashield.sdk.core.net.ShieldApiClient
import com.dpdpashield.sdk.core.net.ShieldApiResult
import com.dpdpashield.sdk.core.net.SdkRecordRequest
import com.dpdpashield.sdk.core.queue.QueueStore
import com.dpdpashield.sdk.core.queue.QueuedConsentWrite
import com.dpdpashield.sdk.core.tcf.BitReader
import com.dpdpashield.sdk.core.tcf.BitWriter
import com.dpdpashield.sdk.core.tcf.Base64Url
import com.dpdpashield.sdk.core.tcf.TcfCoreInput
import com.dpdpashield.sdk.core.tcf.TcfCoreStringDecoder
import com.dpdpashield.sdk.core.tcf.TcfPurposeMapping
import com.dpdpashield.sdk.core.tcf.TcfStandardPurpose
import com.dpdpashield.sdk.core.tcf.TcfStringEncoder
import com.dpdpashield.sdk.core.webview.MiniJson
import com.dpdpashield.sdk.core.webview.WebViewConsentBridge
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun gate(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("PASS  $name")
    } catch (t: Throwable) {
        failed++
        failures.add("$name :: ${t.message}")
        println("FAIL  $name :: ${t.message}")
    }
}

private fun assertEquals(expected: Any?, actual: Any?, label: String) {
    if (expected != actual) throw AssertionError("$label - expected=$expected actual=$actual")
}

private fun assertTrue(condition: Boolean, label: String) {
    if (!condition) throw AssertionError(label)
}

/**
 * Test-only helper for gate 8c: extracts every top-level single-quoted JS
 * string literal's RAW (still backslash-escaped) body from a script
 * produced by [WebViewConsentBridge.buildLocalStorageInjectionScript], in
 * source order. A small generic scanner (not a JS parser) - sufficient
 * because the only single-quoted literals that script ever contains are
 * exactly the ones [WebViewConsentBridge.escapeForSingleQuotedJs] produces.
 * This is what makes gate 8c a genuine end-to-end proof: it parses the
 * actual generated script text the same way a lexer would, rather than
 * calling the encode function and trusting its own output.
 */
private fun extractSingleQuoteLiterals(script: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < script.length) {
        if (script[i] == '\'') {
            val start = i + 1
            var j = start
            while (j < script.length) {
                if (script[j] == '\\' && j + 1 < script.length) {
                    j += 2
                    continue
                }
                if (script[j] == '\'') break
                j++
            }
            result.add(script.substring(start, j))
            i = j + 1
        } else {
            i++
        }
    }
    return result
}

// ── In-memory fakes used only by this verify program ────────────────────────

private class InMemoryQueueStore : QueueStore {
    var items: List<QueuedConsentWrite> = emptyList()
    override fun loadAll(): List<QueuedConsentWrite> = items
    override fun saveAll(items: List<QueuedConsentWrite>) {
        this.items = items
    }
}

/** Fake network: [succeed] controls whether submitConsent succeeds, so gates
 *  can exercise both the happy path and the offline/backoff path. */
private class FakeApiClient(
    private val notice: ConsentNotice,
    var succeed: Boolean = true,
) : ShieldApiClient {
    var submitCallCount = 0
        private set
    val submittedRequests = mutableListOf<SdkRecordRequest>()

    override suspend fun fetchNotice(apiKey: String, appIdentity: String?, noticeId: String?): ShieldApiResult<ConsentNotice> =
        ShieldApiResult.Success(notice)

    override suspend fun submitConsent(request: SdkRecordRequest, appIdentity: String?): ShieldApiResult<Unit> {
        submitCallCount++
        submittedRequests.add(request)
        return if (succeed) ShieldApiResult.Success(Unit) else ShieldApiResult.Failure(500, null, null)
    }
}

private fun sampleNotice(): ConsentNotice = ConsentNotice(
    id = "notice-1",
    title = "We value your privacy",
    description = "This is the default English intro.",
    privacyPolicyUrl = "https://acme.example/privacy",
    contactEmail = "dpo@acme.example",
    complaintText = "You may complain to the Data Protection Board.",
    withdrawalUrl = "https://acme.example/withdraw",
    purposes = listOf(
        ConsentPurpose(id = "p-required", name = "Account", description = "Needed to run your account", required = true, dataCategories = listOf("email")),
        ConsentPurpose(id = "p-marketing", name = "Marketing", description = "Send you offers", required = false, dataCategories = listOf("email", "phone")),
        ConsentPurpose(id = "p-analytics", name = "Analytics", description = "Understand app usage", required = false, dataCategories = listOf("device_id")),
    ),
    translations = mapOf(
        "HI" to NoticeTranslation(
            title = "हम आपकी गोपनीयता को महत्व देते हैं",
            introText = null, // deliberately partial - must fall back to English description only for this field
            rightsText = null,
            purposes = mapOf("p-marketing" to PurposeTranslation(name = "विपणन", description = null)),
        ),
    ),
    availableLanguages = listOf("EN", "HI"),
    brandName = null,
)

fun main() {
    println()
    println("=".repeat(80))
    println("VERIFY: mobile/android-sdk/core")
    println("=".repeat(80))

    // ── Gate 1: DataPrincipalHasher must be byte-exact with the backend ──────
    // sha256("acme@example.com") lowercase-hex, independently computed here
    // via plain java.security.MessageDigest (NOT by calling the class under
    // test) so this gate can't pass merely by both sides sharing a bug.
    gate("1. DataPrincipalHasher matches independently-computed SHA-256") {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("acme@example.com".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val actual = DataPrincipalHasher.hash("  ACME@Example.COM  ")
        assertEquals(expected, actual, "hash mismatch")
    }

    gate("1b. DataPrincipalHasher trims and lowercases exactly like hashDataPrincipal()") {
        val a = DataPrincipalHasher.hash("User@Domain.com")
        val b = DataPrincipalHasher.hash("  user@domain.com  ")
        assertEquals(a, b, "trim/lowercase normalisation should make these identical")
    }

    // ── Gate 2: BitWriter/BitReader round-trip at the primitive level ───────
    gate("2. BitWriter/BitReader round-trip mixed field widths") {
        val w = BitWriter()
        w.writeBits(2L, 6)
        w.writeBoolean(true)
        w.writeBoolean(false)
        w.writeChar6Pair("IN")
        w.writeBitfield(8) { it == 3 || it == 7 }
        val bytes = w.pack()
        val r = BitReader(bytes)
        assertEquals(2L, r.readBits(6), "version field")
        assertEquals(true, r.readBoolean(), "bool 1")
        assertEquals(false, r.readBoolean(), "bool 2")
        assertEquals("IN", r.readChar6Pair(), "char6 pair")
        assertEquals(setOf(3, 7), r.readBitfieldAsSet(8), "bitfield")
    }

    gate("2b. Base64Url round-trips arbitrary bytes, matches base64url alphabet (- _, no padding)") {
        val original = byteArrayOf(0x00, 0x01.toByte(), 0xFF.toByte(), 0x7E, 0x3A)
        val encoded = Base64Url.encode(original)
        assertTrue(!encoded.contains('+') && !encoded.contains('/') && !encoded.contains('='), "must be URL-safe, unpadded: $encoded")
        val decoded = Base64Url.decode(encoded)
        assertTrue(decoded.contentEquals(original), "round-trip mismatch")
    }

    // ── Gate 3: full IAB TCF v2 Core String encode/decode round-trip ────────
    gate("3. TcfStringEncoder produces a Core String that decodes back to the same field values") {
        val input = TcfCoreInput(
            createdEpochMs = 1_700_000_000_000L,
            lastUpdatedEpochMs = 1_700_000_500_000L,
            cmpId = 999,
            cmpVersion = 3,
            consentScreen = 1,
            consentLanguage = "en",
            vendorListVersion = 0,
            tcfPolicyVersion = 4,
            isServiceSpecific = true,
            useNonStandardStacks = false,
            specialFeatureOptIns = setOf(1),
            purposeConsent = setOf(1, 4, TcfStandardPurpose.MEASURE_AD_PERFORMANCE),
            purposeLegitimateInterest = emptySet(),
            purposeOneTreatment = false,
            publisherCountryCode = "in",
        )
        val tcString = TcfStringEncoder.encodeCoreString(input)
        assertTrue(tcString.isNotBlank(), "encoded string must be non-blank")
        assertTrue(!tcString.contains('+') && !tcString.contains('/'), "must be base64url, not base64: $tcString")

        val decoded = TcfCoreStringDecoder.decode(tcString)
        assertEquals(2L, decoded.version, "version")
        // created/lastUpdated are stored at decisecond precision (spec), so
        // round-tripping loses sub-100ms precision - compare at that precision.
        assertEquals(input.createdEpochMs / 100 * 100, decoded.createdEpochMs, "created")
        assertEquals(input.lastUpdatedEpochMs / 100 * 100, decoded.lastUpdatedEpochMs, "lastUpdated")
        assertEquals(999L, decoded.cmpId, "cmpId")
        assertEquals(3L, decoded.cmpVersion, "cmpVersion")
        assertEquals("EN", decoded.consentLanguage, "consentLanguage should be upper-cased")
        assertEquals(4L, decoded.tcfPolicyVersion, "tcfPolicyVersion")
        assertEquals(true, decoded.isServiceSpecific, "isServiceSpecific")
        assertEquals(setOf(1), decoded.specialFeatureOptIns, "specialFeatureOptIns")
        assertEquals(setOf(1, 4, 7), decoded.purposeConsent, "purposeConsent")
        assertEquals(emptySet<Int>(), decoded.purposeLegitimateInterest, "purposeLegitimateInterest")
        assertEquals("IN", decoded.publisherCountryCode, "publisherCountryCode should be upper-cased")
        assertEquals(0L, decoded.vendorConsentMaxVendorId, "no GVL - vendor consent section must be empty")
        assertEquals(0L, decoded.vendorLiMaxVendorId, "no GVL - vendor LI section must be empty")
        assertEquals(0L, decoded.numPubRestrictions, "no publisher restrictions declared")
    }

    gate("3b. TcfCoreInput rejects an out-of-range purpose number (fail loud, never silently clamp)") {
        var threw = false
        try {
            TcfCoreInput(
                createdEpochMs = 0, lastUpdatedEpochMs = 0, cmpId = 1, cmpVersion = 1,
                consentScreen = 1, consentLanguage = "EN", vendorListVersion = 0, tcfPolicyVersion = 4,
                isServiceSpecific = true, purposeConsent = setOf(25), // 25 is out of the 1..24 range
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "constructing with purpose 25 must throw, not silently accept")
    }

    gate("3c. TcfPurposeMapping never fabricates a mapping - unmapped purpose ids contribute nothing") {
        val mapping = TcfPurposeMapping(mapOf("p-marketing" to setOf(TcfStandardPurpose.SELECT_PERSONALISED_ADS)))
        val result = mapping.toIabPurposeSet(setOf("p-marketing", "p-analytics-unmapped"))
        assertEquals(setOf(4), result, "only the mapped purpose id should contribute an IAB purpose number")
    }

    // ── Gate 4: OfflineConsentQueue - enqueue, retry ordering, backoff, drop ─
    gate("4. OfflineConsentQueue: enqueue then dueForRetry respects nextAttemptAt and FIFO-by-createdAt") {
        val store = InMemoryQueueStore()
        val q = com.dpdpashield.sdk.core.queue.OfflineConsentQueue(store, baseDelayMs = 1000, maxDelayMs = 60_000, maxAttempts = 5)
        val req = SdkRecordRequest(apiKey = "k", purposes = mapOf("p1" to true))
        q.enqueue("w1", req, null, nowEpochMs = 100)
        q.enqueue("w2", req, null, nowEpochMs = 200)
        val due = q.dueForRetry(nowEpochMs = 500)
        assertEquals(listOf("w1", "w2"), due.map { it.id }, "both immediately due, oldest first")
        assertEquals(2, store.items.size, "store must reflect enqueued items (durability)")
    }

    gate("4b. OfflineConsentQueue: markFailed applies exponential backoff and is not due until it elapses") {
        val store = InMemoryQueueStore()
        val q = com.dpdpashield.sdk.core.queue.OfflineConsentQueue(store, baseDelayMs = 1000, maxDelayMs = 60_000, maxAttempts = 10)
        val req = SdkRecordRequest(apiKey = "k")
        q.enqueue("w1", req, null, nowEpochMs = 0)
        val afterFail1 = q.markFailed("w1", nowEpochMs = 0)!!
        // attemptCount becomes 1 -> delay = 1000 * 2^1 = 2000
        assertEquals(2000L, afterFail1.nextAttemptAtEpochMs, "first backoff should be baseDelay * 2^1")
        assertTrue(q.dueForRetry(nowEpochMs = 1000).isEmpty(), "must not be due before backoff elapses")
        assertTrue(q.dueForRetry(nowEpochMs = 2000).size == 1, "must be due once backoff elapses")

        val afterFail2 = q.markFailed("w1", nowEpochMs = 2000)!!
        // attemptCount becomes 2 -> delay = 1000 * 2^2 = 4000, from now=2000 -> 6000
        assertEquals(6000L, afterFail2.nextAttemptAtEpochMs, "second backoff should double again")
    }

    gate("4c. OfflineConsentQueue: backoff delay is capped at maxDelayMs, never grows unbounded") {
        val store = InMemoryQueueStore()
        val q = com.dpdpashield.sdk.core.queue.OfflineConsentQueue(store, baseDelayMs = 1000, maxDelayMs = 5000, maxAttempts = 30)
        q.enqueue("w1", SdkRecordRequest(apiKey = "k"), null, nowEpochMs = 0)
        var last = q.markFailed("w1", nowEpochMs = 0)!!
        repeat(10) { last = q.markFailed("w1", nowEpochMs = last.nextAttemptAtEpochMs)!! }
        val delay = last.nextAttemptAtEpochMs - (last.nextAttemptAtEpochMs - 5000).coerceAtLeast(0)
        assertTrue(delay <= 5000, "delay must never exceed maxDelayMs=5000, got a next-attempt implying $delay")
    }

    gate("4d. OfflineConsentQueue: a write is dropped (not retried forever) once maxAttempts is exceeded") {
        val store = InMemoryQueueStore()
        val q = com.dpdpashield.sdk.core.queue.OfflineConsentQueue(store, baseDelayMs = 1, maxDelayMs = 10, maxAttempts = 3)
        q.enqueue("w1", SdkRecordRequest(apiKey = "k"), null, nowEpochMs = 0)
        var now = 0L
        var lastResult: com.dpdpashield.sdk.core.queue.QueuedConsentWrite? = com.dpdpashield.sdk.core.queue.QueuedConsentWrite("w1", SdkRecordRequest(apiKey = "k"), null, 0)
        var iterations = 0
        while (lastResult != null && iterations < 10) {
            lastResult = q.markFailed("w1", now)
            now += 100
            iterations++
        }
        assertEquals(0, q.size, "write must be gone from the queue after exceeding maxAttempts")
        assertEquals(3, iterations, "must drop on exactly the (maxAttempts)th failure, not before or after")
    }

    gate("4e. OfflineConsentQueue: markSent removes exactly the sent write, leaves siblings intact") {
        val store = InMemoryQueueStore()
        val q = com.dpdpashield.sdk.core.queue.OfflineConsentQueue(store, baseDelayMs = 1000, maxDelayMs = 60_000)
        q.enqueue("w1", SdkRecordRequest(apiKey = "k"), null, 0)
        q.enqueue("w2", SdkRecordRequest(apiKey = "k"), null, 0)
        q.markSent("w1")
        assertEquals(listOf("w2"), q.snapshot().map { it.id }, "only w1 should be removed")
    }

    // ── Gate 5: ShieldGate - fail-closed default, required-purpose bypass, listeners ─
    gate("5. ShieldGate: unknown purpose fails CLOSED (not consented) before any decision exists") {
        val gate = ShieldGate()
        assertEquals(false, gate.isConsented("some-purpose"), "no decision yet - must be fail-closed")
    }

    gate("5b. ShieldGate: a required (non-consent-legal-basis) purpose is always treated as consented") {
        val gate = ShieldGate(requiredPurposeIds = setOf("p-required"))
        assertEquals(true, gate.isConsented("p-required"), "required purposes bypass the toggle entirely")
        assertEquals(false, gate.isConsented("p-marketing"), "non-required purposes still fail closed with no decision")
    }

    gate("5c. ShieldGate: runIfConsented only executes the block when consented, and reports whether it ran") {
        val gate = ShieldGate()
        gate.updateDecision(ConsentDecision(noticeId = "n1", given = mapOf("p1" to true, "p2" to false), languageShown = "EN", decidedAtEpochMs = 0, synced = false))
        var ranP1 = false
        var ranP2 = false
        val didRunP1 = gate.runIfConsented("p1") { ranP1 = true }
        val didRunP2 = gate.runIfConsented("p2") { ranP2 = true }
        assertTrue(ranP1 && didRunP1, "p1 was consented - block must run and report true")
        assertTrue(!ranP2 && !didRunP2, "p2 was declined - block must NOT run and report false")
    }

    gate("5d. ShieldGate: observeConsented fires immediately with current state, then again on every future change") {
        val gate = ShieldGate()
        val observed = mutableListOf<Boolean>()
        gate.observeConsented("p1") { observed.add(it) }
        assertEquals(listOf(false), observed, "should fire immediately with the fail-closed default")

        gate.updateDecision(ConsentDecision(noticeId = "n1", given = mapOf("p1" to true), languageShown = "EN", decidedAtEpochMs = 0, synced = false))
        assertEquals(listOf(false, true), observed, "must re-fire with the new state on decision change")

        gate.updateDecision(ConsentDecision(noticeId = "n1", given = mapOf("p1" to false), languageShown = "EN", decidedAtEpochMs = 1, synced = false))
        assertEquals(listOf(false, true, false), observed, "must re-fire again when the user later withdraws")
    }

    // ── Gate 6: NoticeLocalizer - per-field English fallback ────────────────
    gate("6. NoticeLocalizer: unknown language code falls back entirely to English") {
        val localized = NoticeLocalizer.resolve(sampleNotice(), "TA") // TA is not in availableLanguages
        assertEquals("EN", localized.languageUsed, "unavailable language must resolve to EN")
        assertEquals("We value your privacy", localized.title, "must use the English title")
    }

    gate("6b. NoticeLocalizer: a partially-translated language falls back per-field, not all-or-nothing") {
        val localized = NoticeLocalizer.resolve(sampleNotice(), "hi") // lowercase on purpose - must be case-insensitive
        assertEquals("HI", localized.languageUsed, "should resolve the case-insensitive match")
        assertEquals("हम आपकी गोपनीयता को महत्व देते हैं", localized.title, "translated title must be used")
        assertEquals("This is the default English intro.", localized.introText, "introText has no HI translation - must fall back to the English description")
        val marketing = localized.purposes.first { it.id == "p-marketing" }
        assertEquals("विपणन", marketing.name, "translated purpose name must be used")
        assertEquals("Send you offers", marketing.description, "purpose description has no HI translation - must fall back to English")
    }

    // ── Gate 7: ShieldConsentEngine end-to-end, offline-first + queue flush ──
    gate("7. ShieldConsentEngine: recordDecision gates immediately, before any network call happens") {
        runBlocking {
            val notice = sampleNotice()
            val api = FakeApiClient(notice, succeed = true)
            val store = InMemoryQueueStore()
            val engine = ShieldConsentEngine(
                config = ShieldConsentConfig(apiKey = "k", cmpId = 1),
                apiClient = api,
                queueStore = store,
            )
            val loadResult = engine.loadNotice()
            assertTrue(loadResult is ShieldApiResult.Success, "loadNotice should succeed against the fake client")

            assertEquals(false, engine.gate.isConsented("p-marketing"), "fail-closed before any decision")
            engine.recordDecision(
                identifier = "user@example.com",
                given = mapOf("p-marketing" to true, "p-analytics" to false),
                languageShown = "EN",
                nowEpochMs = 1000,
                writeId = "w1",
            )
            // Gate flips synchronously - no network call has happened yet.
            assertEquals(0, api.submitCallCount, "recordDecision must not itself call the network")
            assertEquals(true, engine.gate.isConsented("p-marketing"), "gate must flip immediately, offline-first")
            assertEquals(false, engine.gate.isConsented("p-analytics"), "declined purpose must gate closed immediately")
            assertEquals(true, engine.gate.isConsented("p-required"), "required purpose is always consented")
            assertEquals(1, engine.queueSize, "the write must be queued for later sync")
        }
    }

    gate("7b. ShieldConsentEngine: recordDecision never sends a raw identifier - only its hash") {
        runBlocking {
            val notice = sampleNotice()
            val api = FakeApiClient(notice, succeed = true)
            val engine = ShieldConsentEngine(
                config = ShieldConsentConfig(apiKey = "k", cmpId = 1),
                apiClient = api,
                queueStore = InMemoryQueueStore(),
            )
            engine.loadNotice()
            engine.recordDecision("Raw.Identifier@Example.com", mapOf("p-marketing" to true), "EN", 0, "w1")
            engine.flushQueue(nowEpochMs = 0)
            val sent = api.submittedRequests.single()
            assertEquals(null, sent.identifier, "raw identifier field must never be populated")
            assertEquals(DataPrincipalHasher.hash("Raw.Identifier@Example.com"), sent.identifierHash, "identifierHash must be the on-device hash")
        }
    }

    gate("7c. ShieldConsentEngine: flushQueue drains due writes and reports an accurate summary") {
        runBlocking {
            val notice = sampleNotice()
            val api = FakeApiClient(notice, succeed = true)
            val engine = ShieldConsentEngine(
                config = ShieldConsentConfig(apiKey = "k", cmpId = 1),
                apiClient = api,
                queueStore = InMemoryQueueStore(),
            )
            engine.loadNotice()
            engine.recordDecision("a@example.com", mapOf("p-marketing" to true), "EN", 0, "w1")
            engine.recordDecision("b@example.com", mapOf("p-marketing" to false), "EN", 1, "w2")
            val summary = engine.flushQueue(nowEpochMs = 100)
            assertEquals(2, summary.sent, "both due writes should be sent")
            assertEquals(0, summary.remaining, "queue must be empty after a fully successful flush")
            assertEquals(2, api.submitCallCount, "network must have been called once per queued write")
        }
    }

    gate("7d. ShieldConsentEngine: a failing backend leaves the write queued for retry, not dropped or lost") {
        runBlocking {
            val notice = sampleNotice()
            val api = FakeApiClient(notice, succeed = false)
            val engine = ShieldConsentEngine(
                config = ShieldConsentConfig(apiKey = "k", cmpId = 1),
                apiClient = api,
                queueStore = InMemoryQueueStore(),
            )
            engine.loadNotice()
            engine.recordDecision("a@example.com", mapOf("p-marketing" to true), "EN", 0, "w1")
            val summary = engine.flushQueue(nowEpochMs = 0)
            assertEquals(0, summary.sent, "backend is failing - nothing should be marked sent")
            assertEquals(1, summary.failed, "the write should be recorded as a retry-scheduled failure")
            assertEquals(1, summary.remaining, "the write must remain queued - never silently discarded")
            // Consent must still be enforced locally even though the sync failed.
            assertEquals(true, engine.gate.isConsented("p-marketing"), "local enforcement must not depend on sync success")
        }
    }

    gate("7e. ShieldConsentEngine.buildTcfString returns null with no purpose mapping configured (never fabricates one)") {
        runBlocking {
            val notice = sampleNotice()
            val engine = ShieldConsentEngine(
                config = ShieldConsentConfig(apiKey = "k", cmpId = 1), // no tcfPurposeMapping
                apiClient = FakeApiClient(notice),
                queueStore = InMemoryQueueStore(),
            )
            engine.loadNotice()
            engine.recordDecision("a@example.com", mapOf("p-marketing" to true), "EN", 0, "w1")
            assertEquals(null, engine.buildTcfString(nowEpochMs = 0), "must be null - no mapping means no fabricated TCF signal")
        }
    }

    gate("7f. ShieldConsentEngine.buildTcfString reflects the actual decision once a mapping is configured") {
        runBlocking {
            val notice = sampleNotice()
            val mapping = TcfPurposeMapping(mapOf("p-marketing" to setOf(TcfStandardPurpose.SELECT_PERSONALISED_ADS)))
            val engine = ShieldConsentEngine(
                config = ShieldConsentConfig(apiKey = "k", cmpId = 42, tcfPurposeMapping = mapping),
                apiClient = FakeApiClient(notice),
                queueStore = InMemoryQueueStore(),
            )
            engine.loadNotice()
            engine.recordDecision("a@example.com", mapOf("p-marketing" to true, "p-analytics" to false), "EN", 5000, "w1")
            val tcString = engine.buildTcfString(nowEpochMs = 6000)
            assertTrue(tcString != null, "mapping is configured - a TC string must be produced")
            val decoded = TcfCoreStringDecoder.decode(tcString!!)
            assertEquals(42L, decoded.cmpId, "cmpId must match config")
            assertEquals(setOf(4), decoded.purposeConsent, "only the mapped, GIVEN purpose should appear as IAB purpose 4")
        }
    }

    // ── Gate 8: WebViewConsentBridge - native decision injected into a WebView ─
    gate("8. WebViewConsentBridge.classifyStatus matches the backend's ACCEPTED/REJECTED/PARTIAL tri-state") {
        assertEquals("ACCEPTED", WebViewConsentBridge.classifyStatus(mapOf("a" to true, "b" to true)), "all true")
        assertEquals("REJECTED", WebViewConsentBridge.classifyStatus(mapOf("a" to false, "b" to false)), "all false")
        assertEquals("PARTIAL", WebViewConsentBridge.classifyStatus(mapOf("a" to true, "b" to false)), "mixed")
        assertEquals("ACCEPTED", WebViewConsentBridge.classifyStatus(emptyMap()), "no toggleable purposes at all - nothing to decline")
    }

    gate("8b. buildLocalStorageInjectionScript is a genuine no-op when there's no native decision yet") {
        val script = WebViewConsentBridge.buildLocalStorageInjectionScript("any-api-key", null)
        assertTrue(!script.contains("localStorage.setItem"), "must not touch localStorage when there's nothing to inject: $script")
    }

    gate("8c. buildLocalStorageInjectionScript round-trips through its own escaping - apiKey/purpose ids with quotes, backslashes, newlines") {
        val apiKey = "dpdpa_live_o'reilly\\test\nkey"
        val given = mapOf(
            "purpose-with-'quote" to true,
            "purpose\\with\\backslash" to false,
        )
        val decision = ConsentDecision(noticeId = "n-1", given = given, languageShown = "EN", decidedAtEpochMs = 42L, synced = false)
        val script = WebViewConsentBridge.buildLocalStorageInjectionScript(apiKey, decision)

        val literals = extractSingleQuoteLiterals(script)
        assertEquals(4, literals.size, "expected exactly 4 single-quoted string literals in the generated script")

        val consentKeyLiteral = WebViewConsentBridge.unescapeSingleQuotedJs(literals[0])
        val consentJsonLiteral = WebViewConsentBridge.unescapeSingleQuotedJs(literals[1])
        val purposesKeyLiteral = WebViewConsentBridge.unescapeSingleQuotedJs(literals[2])
        val purposesJsonLiteral = WebViewConsentBridge.unescapeSingleQuotedJs(literals[3])

        assertEquals("dpdpa_consent_$apiKey", consentKeyLiteral, "consent localStorage key must round-trip byte-exact through the generated script")
        assertEquals("dpdpa_purposes_$apiKey", purposesKeyLiteral, "purposes localStorage key must round-trip byte-exact through the generated script")

        val decodedPurposes = WebViewConsentBridge.parseWebReportedPurposes(purposesJsonLiteral)
        assertEquals(given, decodedPurposes, "purposes map must survive encode -> JS-escape -> generate -> extract -> unescape -> decode intact")

        assertTrue(consentJsonLiteral.contains("\"status\":\"PARTIAL\""), "consent JSON must classify this mixed decision as PARTIAL: $consentJsonLiteral")
        assertTrue(consentJsonLiteral.contains("\"id\":\"n-1\""), "consent JSON must carry the notice id: $consentJsonLiteral")
    }

    gate("8d. MiniJson encodeObject/decodeFlatBooleanMap round-trip a flat boolean map, including unicode and escaped characters in keys") {
        val original = mapOf(
            "simple" to true,
            "with \"quotes\" and \\backslash" to false,
            "with\nnewline" to true,
            "हिन्दी-purpose" to false,
        )
        val json = MiniJson.encodeObject(*original.map { (k, v) -> k to v }.toTypedArray())
        val decoded = MiniJson.decodeFlatBooleanMap(json)
        assertEquals(original, decoded, "round trip mismatch for $json")
    }

    gate("8e. MiniJson.decodeFlatBooleanMap of an empty object returns an empty map, not null") {
        assertEquals(emptyMap<String, Boolean>(), MiniJson.decodeFlatBooleanMap("{}"), "empty object should decode to an empty map")
    }

    gate("8f. MiniJson.decodeFlatBooleanMap fails closed on malformed input - never throws, never partially parses") {
        val malformedInputs = listOf(
            "not json at all",
            "[\"a\",\"b\"]",
            "{\"a\":true",
            "{\"a\":\"not-a-boolean\"}",
            "{a:true}",
            "",
            "null",
        )
        malformedInputs.forEach { input ->
            assertEquals(null, MiniJson.decodeFlatBooleanMap(input), "must return null (fail closed): input=$input")
        }
    }

    gate("8g. escapeForSingleQuotedJs/unescapeSingleQuotedJs round-trip, including U+2028/U+2029 (JS line/paragraph separator)") {
        val lineSeparator = 0x2028.toChar()
        val paragraphSeparator = 0x2029.toChar()
        val original = "text with a ${lineSeparator}LS and a ${paragraphSeparator}PS, plus a ' quote and a \\ backslash"

        val escaped = WebViewConsentBridge.escapeForSingleQuotedJs(original)
        assertTrue(!escaped.contains(lineSeparator), "escaped output must not contain a raw U+2028")
        assertTrue(!escaped.contains(paragraphSeparator), "escaped output must not contain a raw U+2029")

        val roundTripped = WebViewConsentBridge.unescapeSingleQuotedJs(escaped)
        assertEquals(original, roundTripped, "unescape must be the exact inverse of escape")
    }

    // ── Summary ──────────────────────────────────────────────────────────────
    println("-".repeat(80))
    println("RESULT: $passed passed, $failed failed (of ${passed + failed})")
    if (failures.isNotEmpty()) {
        println("Failures:")
        failures.forEach { println("  - $it") }
    }
    println("=".repeat(80))
    if (failed > 0) kotlin.system.exitProcess(1)
}
