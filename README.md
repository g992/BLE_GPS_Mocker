# BLEGPSMocker

BLEGPSMocker — Android-приложение, подключающееся к BLE-приёмнику, считывающее поток координат и подменяющее системный провайдер геопозиции. Интерфейс отображает состояние соединения, качество сигнала спутников и последние полученные точки.

## Заметки по доработке
- `app/src/main/java/com/g992/blegpsmocker/MainActivity.kt` — Compose-экран, взаимодействующий с `GNSSClientService`; внутри сосредоточены проверки разрешений, запуск BLE и хранение предпочтений (`AppPrefs`).
- `app/src/main/java/com/g992/blegpsmocker/GNSSClientService.kt` — foreground-сервис, управляющий сканом BLE-устройств, разбором пакетов и подменой координат через `LocationManager`; внутри вынесены вспомогательные уведомления (`NotificationUtils`).
- `app/src/main/java/com/g992/blegpsmocker/ConnectionManager.kt` — слой работы с BLE-стеком Android; UUID характерного сервиса описаны во вложенном объекте `BleUuids`.
- `app/src/main/java/com/g992/blegpsmocker/BootReceiver.kt` — обработчик событий загрузки, который стартует сервис при необходимости.
