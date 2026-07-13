# WG Home Auto

Android-приложение, которое **автоматически выключает VPN в домашней Wi-Fi сети и включает во всех остальных случаях** (другая Wi-Fi, мобильный интернет, отсутствие сети).

**WireGuard встроен в приложение** — оно само поднимает туннель через официальный движок (`com.wireguard.android:tunnel`, нативный `wg-go`). Отдельное приложение WireGuard **не требуется**.

---

## Как это работает

1. Foreground-сервис слушает смену Wi-Fi через `ConnectivityManager.NetworkCallback`.
2. При каждом изменении читается SSID текущей сети.
3. Дома (SSID в списке «домашних») → туннель **выключается**.
4. Вне дома (чужая Wi-Fi / мобильный интернет / нет сети) → туннель **включается**.
5. Туннель поднимает сам процесс приложения (`GoBackend.setState`), защищённый системным `VpnService`.

Раньше (v1) приложение управляло сторонним WireGuard через broadcast — это оказалось ненадёжно
(Android выгружал стороннее приложение из памяти при старте из фона). В v2 VPN встроен, и эта проблема устранена.

---

## Требования

- Android 8.0+ (проверялось на Android 16), `minSdk 26`, `targetSdk 34`, ABI arm64-v8a / armeabi-v7a.
- Файл конфигурации WireGuard (`.conf`) вашего туннеля.

---

## Настройка (первый запуск)

1. **Импортировать конфиг**: карточка «Конфигурация WireGuard» → **«Импортировать конфиг (.conf)»** → выбрать файл.
2. **Согласие на VPN**: при первом поднятии система один раз спросит разрешение — «Разрешить».
3. **Отключить оптимизацию батареи**: нажать кнопку «Отключить оптимизацию батареи» (для надёжной работы в фоне).
4. Подключиться к домашней Wi-Fi и нажать **«Добавить текущую сеть»** (можно несколько).
5. Включить **«Автоматический режим»**.
6. Проверить вручную кнопкой **«Проверить (поднять туннель)»** — строка «VPN сейчас» должна стать «активен».

---

## Защита от утечек

Тумблер **«Защита от утечек»** (по умолчанию включён) сокращает окно незащищённого трафика на переходе:
в строгом режиме VPN включается, даже пока вы дома, если **сигнал слабый** (гистерезис −70 / −80 dBm) или
**Wi-Fi без реального интернета** (`NET_CAPABILITY_VALIDATED = false`). Переключение асимметрично:
включение ~0.7 c (быстро), выключение ~4 c (без «мигания»).

> **Полная гарантия против утечек** — только kill-switch WireGuard в системе (Настройки → VPN → «Всегда включён»
> + «Блокировать соединения без VPN»), но он блокирует трафик и дома. Наш режим — компромисс, сокращающий окно.

---

## Разрешения — зачем каждое

| Разрешение | Зачем |
|---|---|
| `INTERNET` | Движок WireGuard создаёт сетевые сокеты (без него — `operation not permitted`) |
| `ACCESS_FINE_LOCATION` | Android отдаёт SSID Wi-Fi только приложениям с точной геолокацией |
| `FOREGROUND_SERVICE` + `..._LOCATION` | Постоянный мониторинг в фоне (сервис типа `location`) |
| `POST_NOTIFICATIONS` | Уведомление службы (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Возобновление мониторинга после перезагрузки |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Освобождение от Doze для надёжной работы в фоне |
| `BIND_VPN_SERVICE` | Собственный VPN-сервис (из библиотеки WireGuard) |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` | Чтение состояния сети и уровня сигнала |

---

## Сборка

### Debug (для разработки)
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd c:\www\vTools
.\gradlew.bat :app:assembleDebug
```
APK: `app\build\outputs\apk\debug\app-debug.apk`

### Release (подписанный)
Нужны локальные (не в git) файлы `keystore.jks` и `keystore.properties` в корне проекта:
```properties
storeFile=keystore.jks
storePassword=<пароль>
keyAlias=<alias>
keyPassword=<пароль>
```
Затем:
```powershell
.\gradlew.bat :app:assembleRelease
```
APK: `app\build\outputs\apk\release\app-release.apk`. **Храните keystore.jks — без него не выпустить обновление.**

Установка на устройство:
```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

---

## Структура проекта

```
app/src/main/java/com/vtools/wghome/
  MainActivity.kt        — UI на Compose, импорт .conf, согласие VPN, разрешения
  MonitorService.kt      — foreground-сервис, NetworkCallback, логика вкл/выкл, гистерезис
  TunnelController.kt     — встроенный WireGuard (GoBackend): up/down/parseConfig
  WifiUtils.kt           — SSID, уровень сигнала, признак интернета
  SettingsRepository.kt   — настройки и хранение конфига (SharedPreferences)
  AppState.kt            — живой статус для UI
  BootReceiver.kt        — автозапуск после перезагрузки
```

**Стек:** Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.9 · Jetpack Compose (BOM 2024.02.02) · Material 3 ·
`com.wireguard.android:tunnel` (встроенный движок).
