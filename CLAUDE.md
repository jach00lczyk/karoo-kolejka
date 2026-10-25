# CLAUDE.md

Pełna specyfikacja: [SPEC.md](SPEC.md). Przeczytaj w całości przed pierwszą zmianą.
Priorytet: **stabilność i energooszczędność ponad liczbę funkcji.**

## Reguły

- Nie zgaduj API karoo-ext — dokumentacja (hammerheadnav.github.io/karoo-ext) lub `docs/device-findings.md`.
- Logika w `domain/` bez `android.*`; każda zmiana tam = test. Czas zawsze przez wstrzykiwany `Clock`.
- Dostęp do Karoo tylko przez `KarooSource`.
- Każda zmiana w obsłudze nawigacji = uruchomienie testów sekwencji z SPEC 5.2.
- Bez nowych zależności bez uzasadnienia w commit message; żadnych GMS/Firebase.
- Bez nowego pollingu/timerów bez wpisu w SPEC 7.3/11.
- Teksty UI po polsku w `strings.xml`.
- Repo jest publiczne: żadnych sekretów w plikach; token GitHub Packages tylko w `~/.gradle/gradle.properties` i sekretach CI.
- Instalacja: `adb install -r` lub aktualizacja przez manifest (SPEC 4.3); po aktualizacji zrestartuj Karoo (P6).
- Każda faza kończy się zielonym `./gradlew test` i `pytest` oraz wpisem w `CHANGELOG.md`.

## Android

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew test :app:assembleDebug
```

karoo-ext 1.1.9 z `mavenLocal` (zbudowany ze źródeł, patrz README) lub GitHub Packages.
API karoo-ext sprawdzaj w źródłach tagu `1.1.9`, nie z pamięci.

## Pipeline

```
cd pipeline
uv run --python 3.12 --with-requirements requirements.txt pytest
uv run --python 3.12 python build_timetable.py --out dist
```

Schemat SQLite i jego odstępstwa od SPEC 3.4 są opisane w docstringu `build_timetable.py` i w `CHANGELOG.md`.
