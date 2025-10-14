# BLEGPSMocker

BLEGPSMocker — Android-приложение, подключающееся к BLE-приёмнику, считывающее поток координат и подменяющее системный провайдер геопозиции. Интерфейс отображает состояние соединения, качество сигнала спутников и последние полученные точки.

## Заметки по доработке
- `app/src/main/java/com/g992/blegpsmocker/MainActivity.kt` — Compose-экран, взаимодействующий с `AutoBleService`; внутри сосредоточены проверки разрешений и запуск BLE.
- `app/src/main/java/com/g992/blegpsmocker/AutoBleService.kt` — foreground-service, управляющий сканом BLE-устройств, разбором пакетов и подменой координат через `LocationManager`.
- `app/src/main/java/com/g992/blegpsmocker/BleDataSource.kt` — слой работы с BLE-стеком Android; UUID характерного сервиса описаны во вложенном объекте `BleUuids`.
- `app/src/main/java/com/g992/blegpsmocker/AppPrefs.kt` — обёртка над `SharedPreferences` с флагом автозапуска мок-локации и базовой конфигурацией.
- `app/src/main/java/com/g992/blegpsmocker/NotificationUtils.kt` — сборка foreground-уведомлений и подсказок для пользователя, включая создание каналов.
- `app/src/main/java/com/g992/blegpsmocker/AccessibilityAutoStartService.kt` и `app/src/main/java/com/g992/blegpsmocker/BootCompletedReceiver.kt` — инфраструктура автозапуска сервиса после активации Accessibility-модуля и событий загрузки устройства.
- `app/src/main/res/xml/accessibility_service_config.xml` — конфигурация accessibility-сервиса с описанием жестов и требуемых параметров.
