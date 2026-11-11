# Pociąg do Warszawy — rozszerzenie Hammerhead Karoo

Rozszerzenie [karoo-ext](https://github.com/hammerheadnav/karoo-ext). Gdy meta trasy leży przy
stacji KM / SKM Warszawa / ŁKA / WKD, pokazuje, którym pociągiem wrócisz do Warszawy i czy na niego
zdążysz. Projekt na własny użytek (sideload). Pełna specyfikacja: [SPEC.md](SPEC.md),
historia zmian: [CHANGELOG.md](CHANGELOG.md).

## Co robi

**Kafelek „Pociąg do Warszawy”** (pole danych na stronie profilu jazdy):

- pociągi ze stacji przy mecie do stacji docelowych: odjazd, przyjazd, peron; który złapiesz
  na pewno, na który trzeba przycisnąć (wymagana prędkość), a który już odjechał;
- ETA mety z Karoo (albo szacunek „≈”, gdy Karoo jej nie podaje) i czas na dojazd z mety
  na peron;
- ostatni pociąg dnia; gdy go nie złapiesz — stacja po drodze, z której jeszcze coś jedzie;
- pierwsze pociągi z 2 najbliższych stacji po drodze;
- opóźnienia i odwołania na żywo (z kluczem API, niżej) — „⏱ N'” w rogu mówi, ile minut temu
  je sprawdzono.

**Aplikacja** (dotknięcie kafelka lub ikona):

- *Finisz* — wszystkie pociągi ze stacji przy mecie, przycisk „Sprawdź opóźnienia”;
- *Po drodze* — stacje przy trasie z ETA i pociągami, „Prowadź do stacji”;
- *Stacje* — rozkład dowolnej stacji na dziś i jutro;
- *Opcje* — stacje docelowe, promienie, zapas na peronie, prędkości, sposób wyboru pociągu.

## Instalacja na Karoo

1. APK: `./gradlew :app:assembleRelease` (→ `app/build/outputs/apk/release/`) albo artefakt
   `apk` z ostatniego przebiegu workflow *Android*.
2. `adb install -r app-release.apk`. **Po każdej aktualizacji zrestartuj Karoo**, bo serwis
   rozszerzenia nie zawsze sam się restartuje.
3. Dodaj pole „Pociąg do Warszawy” do strony profilu jazdy (najlepiej duże — mieści więcej
   pociągów i stacji po drodze).

Rozkład aplikacja pobiera sama przy starcie Karoo (codziennie nowy na `gh-pages`); w trakcie
jazdy aktualizację odkłada na później.

### Opóźnienia na żywo (klucz API)

Klucza nie ma w APK (repo jest publiczne). Wgraj go raz, z Keychain prosto na Karoo:

```bash
security find-generic-password -a "$USER" -s karoo-kolejka-pdp-api-key -w | tr -d '\n' > /tmp/pdp_api_key.txt
adb push /tmp/pdp_api_key.txt /sdcard/Android/data/pl.kolejka.karoo/files/ && rm /tmp/pdp_api_key.txt
```

Aplikacja przy najbliższym sprawdzeniu przenosi klucz do pamięci prywatnej i usuwa plik
z katalogu zewnętrznego (w logu: „Zaimportowano klucz API PDP”). Bez klucza wszystko działa
na rozkładzie planowym, a zakładka Finisz mówi, że opóźnienia są wyłączone.

Pociągi z kafelka sprawdzane są tym częściej, im bliżej odjazdu (co 30 / 10 / 5 min), i od razu
po wyborze innej trasy — szczegóły w SPEC 7.3.

### Log

```bash
adb pull /sdcard/Android/data/pl.kolejka.karoo/files/logs/
```

Ostatnie wpisy widać też w aplikacji, w ekranie „O danych”.

## Budowanie

Wymaga JDK 17 i Android SDK (`local.properties` → `sdk.dir=…`). karoo-ext 1.1.9 bierze się z
`mavenLocal`, gdzie trafia po zbudowaniu ze źródeł:

```bash
git clone --depth 1 --branch 1.1.9 https://github.com/hammerheadnav/karoo-ext.git /tmp/karoo-ext
(cd /tmp/karoo-ext && ./gradlew :lib:publishToMavenLocal -Pgpr.user=x -Pgpr.key=x)
./gradlew test :app:assembleDebug
```

Drugie źródło to GitHub Packages z tokenem `read:packages`. Token wpisz w
`~/.gradle/gradle.properties` jako `gpr.user` / `gpr.key`, nigdy w repo.

Kod: `domain/` — czysty Kotlin (planer, geometria trasy, ETA, opóźnienia) z testami;
`app/` — rozszerzenie Karoo, kafelek (Glance), aplikacja (Compose); `pipeline/` — rozkład.

## Pipeline

```bash
cd pipeline
uv run --python 3.12 --with-requirements requirements.txt pytest
PDP_API_KEY=$(security find-generic-password -a "$USER" -s karoo-kolejka-pdp-api-key -w) \
  uv run --python 3.12 python build_timetable.py --out dist
```

Workflow *timetable* robi to codziennie i publikuje SQLite z manifestem na `gh-pages`.

Klucz API PDP PLK leży wyłącznie w Keychain (lokalnie) i w sekrecie repo `PDP_API_KEY` (workflow).
Regulamin API zabrania udostępniania klucza, a repo i APK są publiczne — nigdy nie wpisuj go do
plików. Gdy Python nie ufa certyfikatowi (proxy z własnym CA), dodaj
`SSL_CERT_FILE=<eksport certyfikatów systemowych>`.

## Dane

Rozkład i opóźnienia Kolei Mazowieckich, SKM Warszawa, Łódzkiej Kolei Aglomeracyjnej i WKD:
PKP Polskie Linie Kolejowe S.A., [Otwarte Dane Kolejowe](https://pdp-api.plk-sa.pl)
(do 3.11.2026: GTFS [Mikołaja Kuranowskiego](https://mkuran.pl/gtfs/)).
