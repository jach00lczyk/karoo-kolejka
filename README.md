# Pociąg do Warszawy — rozszerzenie Hammerhead Karoo

Rozszerzenie [karoo-ext](https://github.com/hammerheadnav/karoo-ext). Gdy meta trasy leży przy
stacji KM / SKM Warszawa / ŁKA / WKD, pokazuje, którym pociągiem wrócisz do Warszawy i czy na niego
zdążysz. Projekt na własny użytek (sideload). Pełna specyfikacja: [SPEC.md](SPEC.md).

## Stan

- [x] Faza 1 — pipeline danych (`pipeline/`, codzienny workflow → `gh-pages`)
- [~] Faza 0 — spike na urządzeniu: kod gotowy, pomiary na Karoo do zrobienia ([procedura](docs/device-findings.md))
- [x] Faza 3 — domena (`domain/`, czysty Kotlin, 62 testy)
- [ ] Fazy 2, 4–7 — dane na Androidzie, symulacja, kafelek, aplikacja, testy na rowerze

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

## Pipeline

```bash
cd pipeline
uv run --python 3.12 --with-requirements requirements.txt pytest
PDP_API_KEY=$(security find-generic-password -a "$USER" -s karoo-kolejka-pdp-api-key -w) \
  uv run --python 3.12 python build_timetable.py --out dist
```

Klucz API PDP PLK leży wyłącznie w Keychain (lokalnie) i w sekrecie repo `PDP_API_KEY` (workflow).
Regulamin API zabrania udostępniania klucza, a repo i APK są publiczne — nigdy nie wpisuj go do
plików. Gdy Python nie ufa certyfikatowi (proxy z własnym CA), dodaj
`SSL_CERT_FILE=<eksport certyfikatów systemowych>`.

## Instalacja na Karoo

`adb install -r app-release.apk`. **Po każdej aktualizacji zrestartuj Karoo**, bo serwis
rozszerzenia nie zawsze sam się restartuje.

## Dane

Rozkład Kolei Mazowieckich, SKM Warszawa, Łódzkiej Kolei Aglomeracyjnej i WKD: PKP Polskie Linie
Kolejowe S.A., [Otwarte Dane Kolejowe](https://pdp-api.plk-sa.pl) (do 3.11.2026: GTFS
[Mikołaja Kuranowskiego](https://mkuran.pl/gtfs/)).
