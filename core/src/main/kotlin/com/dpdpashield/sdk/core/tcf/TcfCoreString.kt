package com.dpdpashield.sdk.core.tcf

/**
 * Inputs for one IAB TCF v2 Core String. This SDK is a first-party consent
 * manager, not an ad-exchange CMP with a Global Vendor List - so
 * [maxVendorId] is always 0 for both the vendor-consent and vendor-legitimate-
 * interest sections (a CMP legitimately declares zero vendors when it has
 * none to declare; a decoder reading `IABTCF_TCString` sees a spec-valid
 * string with an empty vendor section, never a fabricated vendor list).
 *
 * [purposeConsent] and [purposeLegitimateInterest] are keyed on the IAB's
 * fixed purpose numbers 1..24 (https://iabeurope.eu/tcf-2-0-purposes/), NOT
 * on our own tenant-defined ProcessingPurpose UUIDs - those are two different
 * vocabularies. Mapping "this tenant's Marketing Communications purpose" onto
 * "IAB purpose 4 (advertising personalisation)" is a decision only the tenant
 * can make (see [TcfPurposeMapping]), which is why this class takes the
 * already-mapped IAB-purpose-number sets rather than our ConsentDecision
 * directly - encoding must never guess a legal-basis mapping on the tenant's
 * behalf.
 */
data class TcfCoreInput(
    val createdEpochMs: Long,
    val lastUpdatedEpochMs: Long,
    val cmpId: Int,
    val cmpVersion: Int,
    val consentScreen: Int,
    val consentLanguage: String,
    val vendorListVersion: Int,
    val tcfPolicyVersion: Int,
    val isServiceSpecific: Boolean,
    val useNonStandardStacks: Boolean = false,
    val specialFeatureOptIns: Set<Int> = emptySet(),
    val purposeConsent: Set<Int>,
    val purposeLegitimateInterest: Set<Int> = emptySet(),
    val purposeOneTreatment: Boolean = false,
    val publisherCountryCode: String = "IN",
) {
    init {
        require(cmpId in 0..4095) { "cmpId must fit in 12 bits" }
        require(cmpVersion in 0..4095) { "cmpVersion must fit in 12 bits" }
        require(consentScreen in 0..63) { "consentScreen must fit in 6 bits" }
        require(vendorListVersion in 0..4095) { "vendorListVersion must fit in 12 bits" }
        require(tcfPolicyVersion in 0..63) { "tcfPolicyVersion must fit in 6 bits" }
        require(specialFeatureOptIns.all { it in 1..12 }) { "special features are numbered 1..12" }
        require(purposeConsent.all { it in 1..24 }) { "TCF purposes are numbered 1..24" }
        require(purposeLegitimateInterest.all { it in 1..24 }) { "TCF purposes are numbered 1..24" }
    }
}

/** Purpose numbers per https://iabeurope.eu/tcf-2-0-purposes/ - documented
 *  here so a tenant configuring [TcfPurposeMapping] doesn't have to go
 *  spec-diving to know what "purpose 4" means. */
object TcfStandardPurpose {
    const val STORE_OR_ACCESS_INFO_ON_DEVICE = 1
    const val SELECT_BASIC_ADS = 2
    const val CREATE_PERSONALISED_ADS_PROFILE = 3
    const val SELECT_PERSONALISED_ADS = 4
    const val CREATE_PERSONALISED_CONTENT_PROFILE = 5
    const val SELECT_PERSONALISED_CONTENT = 6
    const val MEASURE_AD_PERFORMANCE = 7
    const val MEASURE_CONTENT_PERFORMANCE = 8
    const val MARKET_RESEARCH = 9
    const val DEVELOP_AND_IMPROVE_PRODUCTS = 10
}

/**
 * A tenant-supplied mapping from our own ProcessingPurpose ids to IAB
 * standard purpose numbers. Deliberately optional everywhere it's used -
 * [ShieldConsentEngine.buildTcfString] returns null rather than a string with
 * a guessed mapping when this isn't configured, because a fabricated purpose
 * mapping is a false compliance signal handed to every third-party SDK that
 * reads it, which is worse than not emitting `IABTCF_TCString` at all.
 */
class TcfPurposeMapping(private val ourPurposeIdToIabPurposeNumbers: Map<String, Set<Int>>) {
    fun toIabPurposeSet(givenPurposeIds: Set<String>): Set<Int> =
        givenPurposeIds.flatMap { ourPurposeIdToIabPurposeNumbers[it].orEmpty() }.toSet()
}

object TcfStringEncoder {
    private const val VERSION = 2L
    private const val SPECIAL_FEATURE_COUNT = 12
    private const val PURPOSE_COUNT = 24

    fun encodeCoreString(input: TcfCoreInput): String {
        val w = BitWriter()
        w.writeBits(VERSION, 6)
        w.writeBits(input.createdEpochMs / 100, 36) // deciseconds since epoch, per spec
        w.writeBits(input.lastUpdatedEpochMs / 100, 36)
        w.writeBits(input.cmpId.toLong(), 12)
        w.writeBits(input.cmpVersion.toLong(), 12)
        w.writeBits(input.consentScreen.toLong(), 6)
        w.writeChar6Pair(input.consentLanguage)
        w.writeBits(input.vendorListVersion.toLong(), 12)
        w.writeBits(input.tcfPolicyVersion.toLong(), 6)
        w.writeBoolean(input.isServiceSpecific)
        w.writeBoolean(input.useNonStandardStacks)
        w.writeBitfield(SPECIAL_FEATURE_COUNT) { it in input.specialFeatureOptIns }
        w.writeBitfield(PURPOSE_COUNT) { it in input.purposeConsent }
        w.writeBitfield(PURPOSE_COUNT) { it in input.purposeLegitimateInterest }
        w.writeBoolean(input.purposeOneTreatment)
        w.writeChar6Pair(input.publisherCountryCode)

        // Vendor Consent section - no GVL, so this is always an empty
        // bitfield-encoded (IsRangeEncoding=false) section of zero vendors.
        w.writeBits(0L, 16) // MaxVendorId
        w.writeBoolean(false) // IsRangeEncoding

        // Vendor Legitimate Interest section - same, always empty.
        w.writeBits(0L, 16)
        w.writeBoolean(false)

        // Publisher Restrictions - none declared.
        w.writeBits(0L, 12) // NumPubRestrictions

        return Base64Url.encode(w.pack())
    }
}

/** Structural fields decoded back out of a Core String, for round-trip
 *  verification only (see [TcfStringEncoder]'s doc comment - no production
 *  code path in this SDK ever needs to decode a TCF string it produced). */
data class DecodedTcfCore(
    val version: Long,
    val createdEpochMs: Long,
    val lastUpdatedEpochMs: Long,
    val cmpId: Long,
    val cmpVersion: Long,
    val consentScreen: Long,
    val consentLanguage: String,
    val vendorListVersion: Long,
    val tcfPolicyVersion: Long,
    val isServiceSpecific: Boolean,
    val useNonStandardStacks: Boolean,
    val specialFeatureOptIns: Set<Int>,
    val purposeConsent: Set<Int>,
    val purposeLegitimateInterest: Set<Int>,
    val purposeOneTreatment: Boolean,
    val publisherCountryCode: String,
    val vendorConsentMaxVendorId: Long,
    val vendorLiMaxVendorId: Long,
    val numPubRestrictions: Long,
)

object TcfCoreStringDecoder {
    fun decode(tcString: String): DecodedTcfCore {
        val r = BitReader(Base64Url.decode(tcString))
        val version = r.readBits(6)
        val created = r.readBits(36) * 100
        val lastUpdated = r.readBits(36) * 100
        val cmpId = r.readBits(12)
        val cmpVersion = r.readBits(12)
        val consentScreen = r.readBits(6)
        val consentLanguage = r.readChar6Pair()
        val vendorListVersion = r.readBits(12)
        val tcfPolicyVersion = r.readBits(6)
        val isServiceSpecific = r.readBoolean()
        val useNonStandardStacks = r.readBoolean()
        val specialFeatureOptIns = r.readBitfieldAsSet(12)
        val purposeConsent = r.readBitfieldAsSet(24)
        val purposeLi = r.readBitfieldAsSet(24)
        val purposeOneTreatment = r.readBoolean()
        val publisherCC = r.readChar6Pair()
        val vendorConsentMaxVendorId = r.readBits(16)
        val vendorConsentIsRange = r.readBoolean()
        check(!vendorConsentIsRange) { "decoder only supports bitfield-encoded (empty) vendor sections" }
        val vendorLiMaxVendorId = r.readBits(16)
        val vendorLiIsRange = r.readBoolean()
        check(!vendorLiIsRange) { "decoder only supports bitfield-encoded (empty) vendor sections" }
        val numPubRestrictions = r.readBits(12)

        return DecodedTcfCore(
            version = version,
            createdEpochMs = created,
            lastUpdatedEpochMs = lastUpdated,
            cmpId = cmpId,
            cmpVersion = cmpVersion,
            consentScreen = consentScreen,
            consentLanguage = consentLanguage,
            vendorListVersion = vendorListVersion,
            tcfPolicyVersion = tcfPolicyVersion,
            isServiceSpecific = isServiceSpecific,
            useNonStandardStacks = useNonStandardStacks,
            specialFeatureOptIns = specialFeatureOptIns,
            purposeConsent = purposeConsent,
            purposeLegitimateInterest = purposeLi,
            purposeOneTreatment = purposeOneTreatment,
            publisherCountryCode = publisherCC,
            vendorConsentMaxVendorId = vendorConsentMaxVendorId,
            vendorLiMaxVendorId = vendorLiMaxVendorId,
            numPubRestrictions = numPubRestrictions,
        )
    }
}
