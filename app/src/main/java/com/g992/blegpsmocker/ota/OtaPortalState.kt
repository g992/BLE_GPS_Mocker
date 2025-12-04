package com.g992.blegpsmocker.ota

import com.g992.blegpsmocker.GNSSClientService

data class OtaPortalState(
    val enabled: Boolean = false,
    val wifiStatus: String = "unknown",
    val ip: String? = null,
    val message: String? = null
) {
    val portalUrl: String?
        get() = ip?.takeIf { it.isNotBlank() }
            ?.let { "http://$it${GNSSClientService.OTA_PORTAL_PATH}" }
}
