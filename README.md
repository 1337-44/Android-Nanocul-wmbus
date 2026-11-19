# Android-Nanocul-wmbus · NanoCUL GPS Logger

**[Pobierz APK z GitHub Releases](https://github.com/1337-44/Android-Nanocul-wmbus/releases/latest)** · Android 8+ · USB Host/OTG · CH340

Skaner radiowy wM-Bus na telefon z Androidem. Odbiera telegramy z
**nanoCUL868 (ATmega328P, CC1101, CH340, culfw r571)** przez USB OTG,
dekoduje pomiary, loguje RSSI i GPS telefonu i szacuje położenie
nadajników z odczytów z wielu miejsc. Flipper Zero nie jest potrzebny.

## Funkcje wersji 0.3.0

- **USB-OTG/CH340:** USB Serial 38400 8N1 domyślnie, wybór 57600/115200
  i T1/C1/T+C/S1 (`brt`, `brc`, `brs`, `X21`).
- **Dekodowanie:** pełny silnik sterowników wmbusmeters **WebAssembly**
  (wersja binarna z przypiętego publicznego źródła) uruchamiany lokalnie
  w Android WebView, plus dotychczasowy natywny parser podstawowych OMS
  DIF/VIF. Wszystkie telegramy przechodzą kontrolę CRC. Sterowniki
  producentów obecne w tej kompilacji wspierają m.in. część liczników
  wymagających danych niestandardowych; **brak gwarancji dla wszystkich
  odmian**. Kod źródłowy i licencje: [THIRD_PARTY_WMBUSMETERS.md](THIRD_PARTY_WMBUSMETERS.md).
- **Szyfrowanie:** silnik w tej wersji pracuje z `NOKEY`. Nie ma jeszcze
  interfejsu do kluczy AES. Zaszyfrowany telegram bez klucza nie może
  zwrócić rzeczywistego wskazania. Surowe dane są zachowywane.
- **Log JSONL:** kompletne telegramy RAW z UTC, RSSI i aktualnym GPS
  telefonu lub `location:null` (pozycja starsza niż 20 s jest
  odrzucana). Do każdego pierwotnego zdarzenia jest `telegram_id`;
  gdy asynchroniczny wmbusmeters zwróci wynik, zapisuje *drugi* rekord
  `event: "decode_update"` z tym samym `telegram_id` i odczytem JSON.
  Dzięki temu zapisy nie blokują odbioru USB.
- **Mapa OpenStreetMap:** szacowane lokalizacje liczników wyliczane z
  GPS i RSSI. Model korzysta z względnej siły sygnału i dopasowuje
  nieznaną moc nadajnika; min. 4 różne lokalizacje, span >=60 m,
  dokładność GPS <=35 m, trasa nie może być niemal linią prostą.
  Odczyt CRC-valid aktualizuje model licznika i log.
  **To orientacyjna lokalizacja z heurystyczną strefą niepewności,
  NIE lokalizacja gwarantowana.** Ściany, metal, odbicia i budynki
  mogą przesunąć wynik o setki metrów. Przy braku danych nie pokazuje
  fałszywej pinezki. Próby pozycji przechowywane są lokalnie.
- **Aktualizacje:** przycisk sprawdzania GitHub Releases; pobieranie
  APK przez Android DownloadManager, kontrola sumy SHA-256, jeśli
  GitHub udostępni digest, instalację potwierdza użytkownik
  w systemie Android (bez cichych instalacji).
- **Bez zewnętrznego serwera dekodowania:** CUL/OMS/wmbusmeters i GPS
  działają lokalnie; internet jest używany dla kafelków OSM oraz
  sprawdzania/pobierania aktualizacji GitHub.

## Pierwsze użycie

1. Pobierz najnowszy APK z [Releases](https://github.com/1337-44/Android-Nanocul-wmbus/releases).
2. Podłącz nanoCUL do telefonu przez USB-C OTG (czasem wymagany hub z
   zasilaniem, jeśli telefon nie zasili płytki).
3. Włącz lokalizację telefonu i udziel zgody na GPS i CH340.
4. W aplikacji wybierz T+C oraz 38400 baud, naciśnij START.
5. Zbieraj telegramy w kilku różnych miejscach.
6. Otwórz **Mapa nadajników (RSSI + GPS)**, aby podejrzeć szacunki.
7. Po sesji STOP → **Eksportuj log JSONL**. Mapa i historia pomiarów
   pozostają na telefonie między sesjami.

### Wskazówka do pomiarów terenowych

Obchodź szukany obiekt z kilku stron, z odstępami między punktami.
Kilkadziesiąt powtórzeń z jednego miejsca to **jeden punkt pomiarowy**,
a nie triangulacja. Jeśli trasa idzie tylko jedną ulicą i nie obejmuje
obiektu, model świadomie nie pokazuje szacunku.

## Aktualizacje APK i podpis Androida

Android pozwala zainstalować aktualizację **na istniejącą aplikację
tylko wtedy, gdy obie wersje są podpisane tym samym kluczem**.

**Istotne:** wcześniejsze wydanie v0.1.0 to testowy APK z kluczem
debugowym, który mógł być inny na każdej maszynie CI. Także obecne
debugowe APK mogą być niezgodne podpisem. Jeśli instalacja nowszej
wersji zostanie odrzucona, **wyeksportuj swoje logi**, następnie
odinstaluj starą apkę i zainstaluj nową. Nie da się tego legalnie
obejść w kodzie. Po odinstalowaniu prywatne dane i historia pomiarów
mogą zniknąć.

Aby **kolejne** aktualizacje instalowały się bez odinstalowywania,
skonfiguruj trwały prywatny klucz podpisu jako **GitHub Actions Secrets**
w repozytorium:

- `ANDROID_KEYSTORE_BASE64` — base64 pliku .jks/.keystore
- `ANDROID_SIGNING_STORE_PASSWORD` — hasło magazynu
- `ANDROID_SIGNING_KEY_ALIAS` — alias klucza
- `ANDROID_SIGNING_KEY_PASSWORD` — hasło klucza

Workflow `.github/workflows/android.yml` używa wtedy
`assembleRelease` i publikuje `NanoCUL_GPS_Logger.apk` podpisany
tym samym kluczem. Nigdy nie publikuj pliku keystore ani haseł w Git
lub logach. Migracja ze starych losowo podpisanych debug APK wciąż
wymaga jednorazowej reinstalacji.

## Budowa z kodu

W Android Studio z JDK 17: otwórz repo i zbuduj aplikację. Ponieważ
wmbusmeters jest dołączany z przypiętych plików WASM przy budowaniu CI,
dla budowy **lokalnej** uruchom wcześniej:

```sh
bash scripts/install-wmbusmeters-wasm.sh
```

Następnie:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

CI uruchamia również `tests/wmbusmeters-wasm-smoke.mjs` na
skompilowanym silniku C++ oraz testy estymatora GPS/RSSI.

## Prywatność i ograniczenia

- Każdy log zawiera identyfikator licznika i może zawierać dokładną
  pozycję użytkownika — usuń współrzędne przed publikacją.
- Kafelki OpenStreetMap są pobierane z sieci; dostawca widzi obszar
  przeglądanej mapy. Odbierane telegramy i logi nie są wysyłane.
- GPS może mieć błędy, a RSSI nie jest pomiarem odległości.
  Pin i promień niepewności są **heurystycznym przybliżeniem**.
- NanoCUL może obcinać zbyt długie telegramy (często >148 bajtów),
  czego aplikacja Android nie naprawi.
- WebView i dekoder WASM mogą być niedostępne na starszych telefonach;
  logger nadal zapisuje RAW i podstawowe OMS.
- Testy programu i kompilacji w CI nie zastępują testów w rzeczywistym
  terenie i na konkretnym Androidzie.

Licencja źródeł: [GPL-3.0](LICENSE), informacje o silniku i binariach:
[THIRD_PARTY_WMBUSMETERS.md](THIRD_PARTY_WMBUSMETERS.md).
