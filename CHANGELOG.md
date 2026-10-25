# Changelog

## [Unreleased]

### Faza 3 — Domena (2026-10-24)

- Moduł `:domain` to czysty Kotlin/JVM, bez zależności od Androida. Zakaz `android.*` w domenie
  pilnuje więc kompilator. **Odstępstwo od SPEC 2**: osobny moduł Gradle zamiast pakietu
  `domain/` w `app`.
- `geo/`: dekoder i enkoder Google Encoded Polyline z precyzją 5/6, `detectPrecision` (dobór
  precyzji po `routeDistance`), Douglas-Peucker 20 m (iteracyjnie), siatka przestrzenna 0,02°,
  `RouteAnalyzer`. Ten ostatni wyznacza kandydatów na metę, stacje po drodze z pozycją wzdłuż
  trasy (wszystkie przejścia pętli) i najbliższą stację. Projekcja jest liczona lokalnie wokół
  każdej stacji, a dystans skumulowany z haversine, więc na trasie 200 km nie narasta błąd.
- `time/ServiceTime`: czasy względem „południa minus 12 h", okna zapytań obejmują 2 dni
  kursowania wstecz (feed ma czasy do ~42 h).
- `plan/`: `EtaEstimator` (Karoo → ostatnia wartość przez 2 min → fallback ze średniej lub
  25 km/h), `TrainPlanner` (t_ready, PEWNY / DO ZŁAPANIA / ODPADA, v_req, tryby
  NAJSZYBCIEJ / ŚCISŁA, OSTATNI, `LastTrainMissed` z najdalszą osiągalną stacją po drodze,
  „plan bezpieczny"), `TileStabilizer` (histereza −1 min / 2 min / +2 min, średnia krocząca
  v_req 60 s). Wszystkie stany z SPEC 6.9.
- `route/`: `RouteTracker` (debounce 500 ms, `flatMapLatest`, LRU 4, Idle natychmiast,
  rejoin poza kluczem) i `NavResubscribeWatchdog` (obejście P1).
- `text/NameShortener`: skróty nazw na kafelek.
- `timetable/`: interfejs `Timetable` i `InMemoryTimetable` (testy, później symulacja).
- 62 testy JVM, w tym wszystkie sekwencje z SPEC 5.2 i przypadki czasu z SPEC 6.7.
- „Koniec dnia" dla OSTATNIEGO pociągu to najbliższa 03:00 czasu lokalnego.

### Faza 0 — Spike na urządzeniu (2026-10-24, kod gotowy, pomiary czekają na Karoo)

- Projekt Gradle: AGP 8.6.1, Kotlin 2.0.21, Gradle 8.7, compileSdk 35 / targetSdk 34 /
  minSdk 23, pakiet `pl.kolejka.karoo`.
- karoo-ext 1.1.9 jest rozwiązywany najpierw z `mavenLocal` (zbudowany ze źródeł tagu `1.1.9`),
  potem z GitHub Packages (token w `~/.gradle/gradle.properties`). CI (`android.yml`) buduje
  bibliotekę ze źródeł, więc nie potrzebuje sekretu.
- `PociagExtension` (id `pociag`) z `DeviceProbe`: loguje nawigację (polyline w precyzji 5 i 6,
  `reversed`, rejoin, cel pinezki), strumienie ETA/dystansu/prędkości, `RideState`, wykrywa P1.
  Pole graficzne „Pociąg: diagnostyka" pokazuje surowe wartości i loguje `ViewConfig`. Ekran
  aplikacji ma przyciski do prób: powiadomienie, pinezka, HTTP przez Karoo, pobieranie
  bezpośrednie, pozycja.
- `FileLog`: plik z rotacją 2 × 256 KB w `Android/data/pl.kolejka.karoo/files/logs/`, zapisuje
  też crashe.
- **Ustalenie ze źródeł karoo-ext:** `OnHttpResponse` ma limit 100 KB na treść odpowiedzi.
  Plik danych (~1,7 MB) będzie pobierany wyłącznie przez `HttpURLConnection`, a manifest może iść
  przez Karoo. To zmienia SPEC 1.4, gdzie pytanie było otwarte. Szczegóły w
  `docs/device-findings.md`.
- Release (R8) ma 2,3 MB i jest podpisany kluczem debug do czasu fazy 6.

### Faza 1 — Pipeline danych (2026-10-24)

- `pipeline/build_timetable.py`: filtruje KM + SKM Warszawa (z `polish_trains.zip`) i WKD
  (z `wkd.zip`), rozwija kalendarz na okno dziś−1…dziś+14 (Europe/Warsaw) i zapisuje SQLite
  według SPEC 3.4. Tylko biblioteka standardowa Pythona; zależność testowa: `pytest`.
- `.github/workflows/timetable.yml`: cron `30 1 * * *`, testy → budowa → orphan force-push
  na `gh-pages` (`manifest.json`, `timetable-<version>.sqlite.gz`, `.nojekyll`, zachowany
  `app-manifest.json`).
- Pobieranie warunkowe (ETag/Last-Modified zapisane w `manifest.sources`). Bez publikacji tylko
  wtedy, gdy oba źródła są niezmienione **i** opublikowane dane sięgają ≥ dziś+12. W przeciwnym
  razie okno dat przesuwa się codziennie.
- Pierwszy przebieg na prawdziwych danych (24.10.2026): 359 stacji, 5136 kursów, 96 400
  stop_times, baza 4,58 MB, plik `.gz` 1,66 MB, czas budowy ~3 s.

**Pomiary i decyzje**

- **Perony/tory**: `polish_trains` ma kolumny `platform` i `track` w `stop_times`. Pokrycie
  (bez ZKA): KM 100%, SKM 100%, WKD 0% (feed WKD ich nie zawiera). Gdy `stop_times` nie ma
  peronu, używamy `platform_code` przystanku („2 t. 3" → peron 2, tor 3).
  **Nie dociągamy `warsaw.zip`**, bo nie jest potrzebny.
- Feed `polish_trains` nie ma `calendar.txt`, tylko `calendar_dates.txt`. Obsługujemy oba pliki.
- **Odstępstwa od schematu z SPEC 3.4** (`schema_version = 1`):
  - `station.id` jest stabilne między wersjami, bo ustawienia przechowują ID stacji
    docelowych: dla PLK to numeryczne ID stacji nadrzędnej, dla WKD 10 000 000 + crc32.
  - Nowa kolumna `stop_time.flags`: bit 0 = brak wsiadania, bit 1 = brak wysiadania (z
    `pickup_type`/`drop_off_type` = 1).
  - `seq` jest przenumerowane od 0, kolejno w obrębie kursu.
  - W `manifest.url` jest nazwa pliku **względna** wobec URL manifestu, a manifest ma
    dodatkowe pole `sources`.
- **Grupy stacji**: promień 600 m zamiast 400 m. „Warszawa Śródmieście" i „Warszawa Śródmieście
  WKD" dzieli ~525 m. Nazwa pokrewna = ta sama nazwa po usunięciu sufiksu „WKD" i nawiasów.
  Grupy z prawdziwych danych: Śródmieście↔Śródmieście WKD, Zachodnia↔Zachodnia (Peron 9)↔Zachodnia
  WKD, Ochota↔Ochota WKD, Aleje Jerozolimskie (PKP)↔Aleje Jerozolimskie (WKD).
- Przystanki ZKA WKD (`wocho_bus_d0` „Plac Zawiszy") są mapowane na stację kolejową po prefiksie
  ID. Bez odpowiednika (np. „PKP Pruszków") zostają osobną stacją.
- Dwie stacje „Warszawa Aleje Jerozolimskie" (PKP i WKD) mają identyczną nazwę, więc UI powinno
  pokazywać przewoźnika obok nazwy.
- Feed WKD ma `feed_version` z 22.08.2026, ale jego `calendar_dates` sięga 12.12.2026.
