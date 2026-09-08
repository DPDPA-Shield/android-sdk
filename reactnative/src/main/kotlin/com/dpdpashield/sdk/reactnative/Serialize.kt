package com.dpdpashield.sdk.reactnative

import com.dpdpashield.sdk.core.i18n.LocalizedNotice
import com.dpdpashield.sdk.core.i18n.LocalizedPurpose
import com.dpdpashield.sdk.core.model.ConsentDecision
import com.dpdpashield.sdk.core.model.ConsentNotice
import com.dpdpashield.sdk.core.model.ConsentPurpose
import com.dpdpashield.sdk.core.net.ApiErrorEnvelope
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * Every `:core` model -> `WritableMap` conversion in one place, field names
 * copied verbatim from the Kotlin data classes they mirror (which are
 * themselves copied verbatim from the backend routes - see each model file's
 * own doc comment). Do not rename a JS-facing key without checking both ends.
 */
internal object Serialize {
    fun purpose(p: ConsentPurpose): WritableMap = Arguments.createMap().apply {
        putString("id", p.id)
        putString("name", p.name)
        putString("description", p.description)
        putBoolean("required", p.required)
        putArray("dataCategories", Arguments.createArray().apply { p.dataCategories.forEach { pushString(it) } })
    }

    fun notice(n: ConsentNotice): WritableMap = Arguments.createMap().apply {
        putString("id", n.id)
        putString("title", n.title)
        putString("description", n.description)
        putString("privacyPolicyUrl", n.privacyPolicyUrl)
        putString("contactEmail", n.contactEmail)
        putString("complaintText", n.complaintText)
        putString("withdrawalUrl", n.withdrawalUrl)
        putArray("purposes", Arguments.createArray().apply { n.purposes.forEach { pushMap(purpose(it)) } })
        putArray("availableLanguages", Arguments.createArray().apply { n.availableLanguages.forEach { pushString(it) } })
        putString("brandName", n.brandName)
    }

    fun localizedPurpose(p: LocalizedPurpose): WritableMap = Arguments.createMap().apply {
        putString("id", p.id)
        putString("name", p.name)
        putString("description", p.description)
        putBoolean("required", p.required)
        putArray("dataCategories", Arguments.createArray().apply { p.dataCategories.forEach { pushString(it) } })
    }

    fun localizedNotice(n: LocalizedNotice): WritableMap = Arguments.createMap().apply {
        putString("languageUsed", n.languageUsed)
        putString("title", n.title)
        putString("introText", n.introText)
        putString("rightsText", n.rightsText)
        putArray("purposes", Arguments.createArray().apply { n.purposes.forEach { pushMap(localizedPurpose(it)) } })
    }

    fun decision(d: ConsentDecision): WritableMap = Arguments.createMap().apply {
        putString("noticeId", d.noticeId)
        putMap("given", Arguments.createMap().apply { d.given.forEach { (k, v) -> putBoolean(k, v) } })
        putString("languageShown", d.languageShown)
        // WritableMap has no putLong - epoch-ms as a JS number loses precision
        // only above 2^53ms (~year 285,000), so double is safe here.
        putDouble("decidedAtEpochMs", d.decidedAtEpochMs.toDouble())
        putBoolean("synced", d.synced)
    }

    /** Attached as a Promise rejection's `userInfo` map - see
     *  ShieldConsentModule.loadNotice()'s ShieldApiResult.Failure branch. */
    fun error(httpStatus: Int?, err: ApiErrorEnvelope?, cause: Throwable?): WritableMap = Arguments.createMap().apply {
        if (httpStatus != null) putInt("httpStatus", httpStatus) else putNull("httpStatus")
        putString("code", err?.code)
        putString("message", err?.message ?: cause?.message ?: "Unknown error")
    }
}
