# Android-Nanocul-wmbus · NanoCUL GPS Logger

**[Pobierz APK z GitHub Releases](https://github.com/1337-44/Android-Nanocul-wmbus/releases/latest)** · Android 8+ · USB Host/OTG · CH340

Skaner radiowy wM-Bus na telefon z Androidem. Odbiera telegramy z
**nanoCUL868 (ATmega328P, CC1101, CH340, culfw r571)** przez USB OTG,
dekoduje pomiary, loguje RSSI i GPS telefonu i szacuje położenie
nadajników z odczytów z wielu miejsc. Flipper Zero nie jest potrzebny.

## Wersja 0.4.0: trwałe pozycje i namiary anteną kierunkową

### Stały rejestr nadajników

Po wyznaczeniu pozycji aplikacja przechowuje osobno:

- **`transmitter_positions.csv`** — jedna linia na nadajnik:
  numer seryjny (`serial`), producent, szerokość i długość geograficzna,
  `quality_score`, orientacyjna `uncertainty_m`, metoda i liczba
  stanowisk. Pozycja **zastępuje starszą tylko wtedy**, gdy nowy zestaw
  pomiarów ma lepszą ocenę modelu i nie pogarsza znacząco niepewności,
  albo istotnie zmniejsza jej promień bez pogorszenia oceny.
  Skala 0–100 to ranking **nie jest procentowym prawdopodobieństwem**.
- **`radio-survey.json`** — pełna baza pomiarów GPS/RSSI oraz ręcznych
  namiarów, zachowana między sesjami i ponownie wykorzystywana przez
  estymator przy kolejnym telegramie.

CSV powstaje automatycznie w prywatnym katalogu aplikacji
`Android/data/<pakiet>/files/Documents/nanoCUL/transmitter_positions.csv`.
Aby mieć **własną kopię pliku** widoczną w systemowym folderze,
kliknij w aplikacji **Eksportuj najlepsze pozycje (CSV)**.

**Bardzo ważne:** przed odinstalowaniem aplikacji (np. z powodu
niezgodnego podpisu APK) wybierz także
**Eksportuj bazę poprzednich sesji (JSON)**. Dopiero ten plik zawiera
surowe punkty GPS, RSSI i zapisane kierunki anteny. Po instalacji nowej
wersji użyj **Przywróć bazę poprzednich sesji (JSON)**. Import bazy
zastępuje historię w aplikacji — nie wykonuj tego podczas skanowania.

### Algorytm RSSI + GPS

Współrzędne telefonu i **względne** RSSI liczników są dopasowywane do
modelu logarytmicznej zależności tłumienia od odległości. Algorytm
w 0.4 dopasowuje także nieznany współczynnik propagacji (1,8–4,0)
i poziom nadawania, a do strat używa odpornej funkcji Hubera.
Powtórzenia z jednego stanowiska są agregowane (ok. 12 m), dlatego
250 telegramów z jednego miejsca nie udaje 250 niezależnych punktów.
W bazie zachowujemy do 400 punktów z różnych lokalizacji na nadajnik.

Szacunki RSSI mają **dużą, orientacyjną niepewność**, szczególnie w
otoczeniu zabudowy, metalowych przeszkód i odbić. Nie można ich
traktować jako GPS licznika ani 95% przedziału ufności.

### ARDF / antena kierunkowa (manualne namiary)

1. Podłącz antenę kierunkową do NanoCUL. Do kalibracji kompasu odsuń
   telefon od metalu, przewodów prądowych, magnesów i innych urządzeń
   zakłócających wskazanie.
2. Uruchom odbiór USB, wybierz **Antena kierunkowa — zapisz namiary**.
3. Wybierz **konkretny licznik** (ID). Każdy namiar jest przypisany
   wyłącznie do tego samego licznika, a nie do wszystkich telegramów.
4. Ustaw telefon **poziomo ekranem w górę**, z górną krawędzią
   skierowaną równolegle do osi głównej wiązki anteny. Jeśli mocowanie
   jest przekręcone, zastosuj regulator korekty azymutu.
5. Obracaj antenę i obserwuj RSSI z kolejnych telegramów tego licznika.
   Na maksimum kierunkowego sygnału naciśnij **ZAPISZ AZYMUT**.
   Nie przechowujemy automatycznie przypadkowych kierunków telefonu.
6. Przejdź do 2–3 kolejnych stanowisk, oddalonych od poprzednich.
   Dla oszacowania żądamy **min. 3 różnych stanowisk oddalonych
   łącznie o co najmniej 30 m**. Najlepsze wyniki daje przecinanie
   namiarów pod wyraźnie różnymi kątami, a nie trzy niemal równoległe
   kierunki. Na mapie zielone linie wskazują ręczne namiary.
7. Aplikacja wyznacza punkt metodą odpornych najmniejszych kwadratów
   (IRLS/Huber) z odrzuceniem kierunków odstających i sprawdzeniem,
   czy przecięcie leży przed anteną (nie na przedłużeniu wstecznym).
   Wynik wraz z orientacyjnym błędem może poprawić zapisany CSV.

Wykorzystujemy `TYPE_ROTATION_VECTOR` Androida i `GeomagneticField`
do poprawki deklinacji (północ magnetyczna → geograficzna).
Kompas wymaga kalibracji i może wariować w pobliżu metalu.
Odbicia radiowe mogą wskazywać pozorny kierunek. Przy antenach z
symetrycznymi listkami (np. niektóre konstrukcje pętlowe) trzeba
rozstrzygnąć **niejednoznaczność przód/tył** przed zapisaniem azymutu.

**Założenie modelu:** nadajnik stoi nieruchomo, a telefon/odbiornik
przemieszcza się. Jeżeli faktycznie nadajnik również się porusza,
potrzebny jest odrębny filtr śledzący z modelem ruchu; ta wersja
nie próbuje z wielu czasów obliczać jednej pozycji ruchomego nadajnika.

### Podpis APK i aktualizacje

Mechanizmu aktualizacji nie da się poprawić tylko przyciskiem:
Android porównuje podpis starego i nowego APK. Wersje debugowe z różnych
biegów GitHub Actions są podpisane różnymi kluczami. W repozytorium
pozostaje **opcjonalny** proces podpisywania stałym, PRYWATNYM kluczem
z GitHub Secrets — wymaga jednorazowej konfiguracji po stronie właściciela.
Nie publikuj prywatnego klucza ani haseł w repozytorium. Przy przejściu
z obecnego debug APK do stabilnie podpisanego wydania konieczna jest
jedna reinstalacja, więc najpierw wyeksportuj bazę JSON i CSV.

---

## Poprawka mapy w 0.3.1

**Odebrane ramki to nie szacunki pozycji nadajnika.** We wcześniejszej
wersji mapa rysowała tylko lokalizacje nadajników obliczone przez model.
W efekcie po 250 telegramach odebranych w jednym miejscu mogła nie
pokazać niczego. Dodatkowo nie zapisywała danych o licznikach, jeśli
GPS/RSSI odrzucił wszystkie pomiary.

Od 0.3.1:
- **Pomarańczowe znaczniki = pozycje TELEFONU podczas odbioru**
  (punkty GPS, niezależnie od tego, czy da się wyliczyć pozycję licznika).
- **Niebieskie znaczniki + kręgi = niepewne szacunki nadajników**;
  wymagane co najmniej 4 różne punkty tego SAMEGO licznika, minimum
  60 m rozpiętości i trasa nieomal niekolinearna.
- W nagłówku mapy jest liczba wykrytych liczników, zapisanych
  odczytów GPS, danych RSSI oraz powód niewyświetlania szacunku.
  Metadane liczników zapisują się nawet wtedy, gdy nie ma GPS.
- Miejsca odbioru zapisują się także bez RSSI, natomiast takie
  próbki **nie są używane do lokalizowania** nadajnika.
- Na ekranie skanowania wyświetla się przyczyna odrzucenia GPS:
  brak aktualnej pozycji, GPS starszy niż 20 s lub dokładność gorsza
  niż 35 m. Na Androidzie sprawdź uprawnienie aplikacji do
  **dokładnej lokalizacji**, włącz GPS, najlepiej wyjdź na zewnątrz.
- **Przywróć mapę z logu JSONL:** po wyeksportowaniu starej sesji
  możesz odtworzyć pomiary przez wybór pliku w nowej aplikacji.
  Najpierw STOP, potem import. Nie importuj tego samego pliku
  wielokrotnie. Jeśli w starym logu wszystkie `location` mają
  wartość `null`, nie da się odzyskać brakujących współrzędnych.

**Ważne przy instalacji:** pliki APK z różnych biegów CI mogą mieć
różne klucze debug. Przed reinstalacją wyeksportuj JSONL ze starej
wersji. Odtworzysz z niego wszystkie dane GPS/RSSI faktycznie
zapisane w logu; pomiarów, których w logu nie było, nie odzyskamy.

## Funkcje wersji 0.3.x

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
