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
uv run --python 3.12 python build_timetable.py --out dist
```

## Instalacja na Karoo

`adb install -r app-release.apk`. **Po każdej aktualizacji zrestartuj Karoo**, bo serwis
rozszerzenia nie zawsze sam się restartuje.

## Dane

Rozkład: Koleje Mazowieckie, SKM Warszawa, Łódzka Kolej Aglomeracyjna (PKP PLK, warunki ponownego wykorzystania informacji
sektora publicznego), WKD (CC0). Feed GTFS przygotowuje [Mikołaj Kuranowski](https://mkuran.pl/gtfs/).
