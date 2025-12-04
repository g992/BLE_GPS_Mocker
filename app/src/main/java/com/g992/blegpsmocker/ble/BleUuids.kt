package com.g992.blegpsmocker.ble

import java.util.UUID

/**
 * Centralized BLE UUID registry shared between scanner, service and UI code.
 */
object BleUuids {
    val GPS_SERVICE_UUID: UUID = UUID.fromString("14f0514a-e15f-4ad3-89a6-b4cb3ac86abe")
    val CHAR_COORDINATES_UUID: UUID = UUID.fromString("12c64fea-7ed9-40be-9c7e-9912a5050d23")
    val CHAR_STATUS_UUID: UUID = UUID.fromString("3e4f5d6c-7b8a-9d0e-1f2a-3b4c5d6e7f8a")
    val CHAR_AP_CONTROL_UUID: UUID = UUID.fromString("a37f8c1b-281d-4e15-8fb2-0b7e6ebd21c0")
    val CHAR_MODE_CONTROL_UUID: UUID = UUID.fromString("d047f6b3-5f7c-4e5b-9c21-4c0f2b6a8f10")
    val CHAR_GPS_BAUD_UUID: UUID = UUID.fromString("f3a1a816-28f2-4b6d-9f76-6f7aa2d06123")
    val CHAR_GNSS_PROFILE_UUID: UUID = UUID.fromString("1fd95e59-993e-4bf5-a0b7-f481508c9a94")
    val CHAR_BASE_SETTINGS_PROFILE_UUID: UUID =
        UUID.fromString("7f0c9ad9-c6e8-4d2a-b3c1-1703708c6c2d")
    val CHAR_CUSTOM_GNSS_PROFILE_UUID: UUID =
        UUID.fromString("0abf4f57-12a2-47d9-9c61-96e0d47f332b")
    val CHAR_CUSTOM_BASE_SETTINGS_UUID: UUID =
        UUID.fromString("4b88f5a8-3b35-4c64-a241-0c7fdfced0e0")
    val CHAR_KEEPALIVE_UUID: UUID = UUID.fromString("6b5d5304-4523-4db4-9a31-0f3d88c2ce11")
    val OTA_SERVICE_UUID: UUID = UUID.fromString("c7b44a0c-24c6-4af3-97ec-19ff34d45095")
    val CHAR_OTA_CONTROL_UUID: UUID = UUID.fromString("0f6f8ff7-1b61-4d44-9f31-3536c3a601a7")
    val CHAR_OTA_DATA_UUID: UUID = UUID.fromString("cb08c9fd-6c57-4b51-8bbe-20f3214bf3e9")
    val CHAR_OTA_STATUS_UUID: UUID = UUID.fromString("d19d3c86-9ba9-4a52-9244-99118bd88d08")
    val CHAR_WIFI_STATUS_UUID: UUID = UUID.fromString("9b9a3f07-3a36-4c74-a48a-4ad0d68f1d39")
    val CHAR_DEVICE_VERSION_UUID: UUID = UUID.fromString("c4e6f890-6b5e-4f1b-9d2e-7a3c8d2f1b01")
    val CHAR_INPUT_VOLTAGE_UUID: UUID = UUID.fromString("81b2c6f8-cb9e-4069-9a2e-9e5abca5d56e")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
