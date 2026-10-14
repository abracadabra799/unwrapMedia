package com.multiviewer.ui.ai

/** VERIFIED only after the capability actually worked in this environment; never inferred from a binary existing. */
enum class CapabilityState { VERIFIED, UNSUPPORTED, UNKNOWN }

data class AiProviderCapabilities(
    val provider: AiProviderId,
    val executablePath: String? = null,
    val version: String? = null,
    /** Authenticated non-interactive text answer. */
    val text: CapabilityState = CapabilityState.UNKNOWN,
    val image: CapabilityState = CapabilityState.UNKNOWN,
    val sessionResume: CapabilityState = CapabilityState.UNKNOWN,
    val streaming: CapabilityState = CapabilityState.UNKNOWN,
    val webSearch: CapabilityState = CapabilityState.UNKNOWN,
    val toolBridge: CapabilityState = CapabilityState.UNKNOWN,
    val detail: String? = null,
) {
    val canAttachImage: Boolean get() = image == CapabilityState.VERIFIED

    fun afterAnswer() = copy(text = CapabilityState.VERIFIED, detail = null)

    fun afterFailure(reason: AiFailure) =
        if (reason == AiFailure.AUTH_REQUIRED) copy(text = CapabilityState.UNSUPPORTED, detail = "login required") else this
}
