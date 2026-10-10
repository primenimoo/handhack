# HandHack — iOS

Проверка стойкости паролей Wi-Fi по **вашим собственным** записям рукопожатия. Всё считается
на устройстве: ни сервера, ни отправки данных. Используйте только на сетях, которыми владеете
или имеете право управлять.

Это полноценный Xcode-проект. Он самодостаточен: ничего доустанавливать не нужно, кроме Xcode.

## Что умеет

- Импорт записей: `hc22000` (WPA*01 / WPA*02), `hccapx`, `hccap`, `pcap`, `pcapng` и текстовый
  экспорт HandHack.
- Разбор кадров 802.11: находит beacon/probe для имени сети, EAPOL-Key message 1 и 2, собирает
  PTK и проверяет MIC.
- Подбор по словарю: PBKDF2-HMAC-SHA1 (4096 итераций) + проверка PMK / PMKID / MIC, с
  «взрывом» слова (слово + цифра и т.п.) и нормализацией вариантов.
- Прогресс, скорость, пауза/остановка, тактильный отклик и локальные уведомления.
- История подборов, менеджер словарей, настройки.
- Стиль Apple Liquid Glass: `Material`-карточки, системная стеклянная таб-панель на iOS 26+.
  Минимальная версия — iOS 17.

## Требования

- macOS с **Xcode 16 или новее** (для эффекта Liquid Glass и таб-панели нужен iOS 26 SDK —
  это Xcode 26, 2025 год).
- Свободный или платный Apple ID для подписи.

Сборка `.ipa` компилятором возможна **только на macOS** — этого требует Xcode. Но если Mac нет,
проект собирается в облаке на бесплатном macOS-раннере GitHub (см. ниже).

## Сборка без Mac (GitHub Actions)

В репозитории уже лежит готовый workflow `.github/workflows/build-ios.yml`. Он собирает
**неподписанный** `.ipa` на macOS-раннере GitHub — Apple ID и секреты не нужны, подпись делаете
вы локально в Sideloadly/AltStore.

1. Создайте пустой репозиторий на GitHub (например, `handhack`).
2. В корне проекта привяжите remote и запушьте:

   ```sh
   git remote add origin https://github.com/ВАШ_ЛОГИН/handhack.git
   git push -u origin main
   ```

3. На GitHub откройте вкладку **Actions** → workflow **Build iOS IPA**. Сборка запустится сама
   (либо нажмите **Run workflow**).
4. Дождитесь зелёной галочки и скачайте артефакт **HandHack-unsigned-ipa** (внутри
   `HandHack-unsigned.ipa`).
5. Подпишите `.ipa` через Sideloadly/AltStore (раздел «Подпись и установка» ниже).

Для эффекта Liquid Glass раннер должен иметь Xcode 26 (iOS 26 SDK); workflow автоматически
выбирает самую новую установленную версию Xcode.

## Сборка и тесты (на Mac)

Откройте `HandHack.xcodeproj`, выберите схему **HandHack**.

Запуск тестов (крипто-векторы IEEE 802.11i и все контейнеры):

```sh
xcodebuild test -project HandHack.xcodeproj -scheme HandHack \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

Запуск на симуляторе: **Product ▸ Run**.

## Как получить `.ipa`

### Вариант A. Xcode Archive (рекомендуется)

1. В Xcode: **Product ▸ Destination ▸ Any iOS Device (arm64)**.
2. **Product ▸ Archive**.
3. В Organizer выделите архив → **Distribute App ▸ Custom ▸ Copy App** (получите папку с
   `HandHack.app` без подписи). Либо просто возьмите `HandHack.app` из
   `~/Library/Developer/Xcode/Archives/.../HandHack.xcarchive/Products/Applications/`.
4. Соберите `.ipa` вручную: создайте папку `Payload`, положите туда `HandHack.app`, и заархивируйте
   `Payload` в zip, переименовав в `HandHack.ipa`.

### Вариант B. Командная строка (без подписи)

```sh
xcodebuild -project HandHack.xcodeproj -scheme HandHack \
  -configuration Release -sdk iphoneos -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO build
```

Готовый бандл: `build/Build/Products/Release-iphoneos/HandHack.app`.
Соберите из него `Payload/HandHack.app` и упакуйте в `HandHack.ipa` как выше.

## Подпись и установка (с Windows)

Собранный `.ipa` подписывается вашим Apple ID и ставится на iPhone через **Sideloadly** или
**AltStore** (обе работают на Windows):

1. Подключите iPhone, откройте Sideloadly, перетащите `HandHack.ipa`, укажите Apple ID.
2. Введите пароль (для платного аккаунта — app-specific password).
3. На телефоне: **Настройки ▸ Основные ▸ VPN и управление устройством ▸ Доверять разработчику**.

Срок подписи:

- Бесплатный Apple ID — **7 дней**, затем нужно переподписать.
- Платный ($99/год) — **до 1 года**.

## Структура

```
ios/
  HandHack.xcodeproj/        проект Xcode (синхронизированные группы файлов)
  HandHack/
    App/                     точка входа, состояние, раннер подбора
    Models/                  крипто-ядро, EAPOL-Key, модель рукопожатия
    Engine/                  читалки контейнеров, парсер кадров, движок подбора
    Dictionary/              хранилище словарей
    UI/                      экраны (Подбор, Словари, История, Настройки, Онбординг)
  HandHackTests/             XCTest: векторы и round-trip по всем форматам
```

## Форматы записей

- **WPA*02** — проверяется MIC сообщения 2 через PTK (нужны ANonce из message 1 и SNonce из
  message 2, а также SSID).
- **WPA*01** — проверяется PMKID = HMAC-SHA1-96(PMK, «PMK Name» ‖ AP ‖ STA).
- **WPA*03 / WPA*04** — 802.11r fast-transition: сообщается как неподдерживаемое, чтобы не
  выдать неверный результат.
- `pcap`/`pcapng` должны содержать сами кадры 802.11 (link type 105 или 127, radiotap снимается).

---

@primenimooo · https://t.me/primenimooo
