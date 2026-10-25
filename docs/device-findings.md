# Faza 0 — ustalenia z urządzenia

> Dalsze fazy opierają się na tym pliku, nie na zgadywaniu (SPEC 4.1, 12).
> Status: **pomiary w toku** (Karoo 3, ext lib 1.1.9). Testy w domu 24.10, jazda z GPS-em i nawigacją 25.10 (trasy zwykła i odwrócona, zmiany trasy, objazdy).
> Repo jest publiczne: nie wpisujemy tu współrzędnych z logów, nazw tras ani numeru seryjnego. Sekcja A to fakty z kodu
> źródłowego karoo-ext 1.1.9 (tag `1.1.9`, commit `26e1d1c`). Sekcję B wypełniamy z logów.

## A. Ustalone ze źródeł karoo-ext 1.1.9

| Pytanie | Odpowiedź | Źródło |
|---|---|---|
| Czy `OnHttpResponse` przeniesie plik kilku MB? | **Nie.** `OnHttpResponse.MAX_REQUEST_SIZE = 100_000` („100KB maximum for request/response body"). Manifest (~300 B) można pobierać przez Karoo, plik danych (~1,7 MB) **musi** iść przez `HttpURLConnection`. | `models/KarooEvent.kt` |
| `MakeHttpRequest.waitForConnection` | domyślnie `true`, czyli żądanie czeka w kolejce na połączenie. | j.w. |
| Częstotliwość `updateView` | `ViewEmitter.updateView` odrzuca wywołania częstsze niż co 900 ms. | `internal/Emitter.kt` |
| `ViewConfig` | `gridSize` (siatka 60×60, np. (60,15) = pełna szerokość, ¼ wysokości), `viewSize` w px, `textSize`, `alignment`, `boundariesEnabled`, `preview` (od 1.1.2). | `models/ViewConfig.kt` |
| Nagłówek pola graficznego | `UpdateGraphicConfig(showHeader = false)`. | `models/ViewEvent.kt` |
| `NavigatingRoute` | `routePolyline`, `routeDistance`, `routeElevationPolyline?`, `rejoinPolyline?`, `rejoinDistance?`, `name`, `reversed`, `breadcrumb`, `pois`, `climbs`. | `models/KarooEvent.kt` |
| `NavigatingToDestination` | `destination: Symbol.POI`, `polyline`, `elevationPolyline?`, `climbs`. | j.w. |
| Pola nawigacji | `DISTANCE_TO_DESTINATION` niesie `FIELD_DISTANCE_TO_DESTINATION_ID`, `FIELD_NAVIGATION_STATE_ID`, `FIELD_REROUTING_ENABLED_ID`, `FIELD_ON_ROUTE_ID`. `TIME_OF_ARRIVAL` i `TIME_TO_DESTINATION` mają po jednym polu. **Jednostki nie są udokumentowane.** | `models/DataType.kt` |
| `SystemNotification` | `id, message, subText?, header?, style (EVENT/ERROR/UPDATE/EDUCATION/SETUP), action?, actionIntent?`. `actionIntent` to String; przykładowa aplikacja Hammerhead rejestruje w aktywności akcję `<pakiet>.MAIN`, więc tak robimy (`pl.kolejka.karoo.MAIN`). | `models/KarooEffect.kt`, sample app |
| `LaunchPinDrop` | `LaunchPinDrop(Symbol.POI(id, lat, lng, type, name))`. | j.w. |
| Manifest aktualizacji APK | metadana `io.hammerhead.karooext.MANIFEST_URL` → JSON `KarooAppManifest(label, packageName, latestApkUrl, latestVersion, latestVersionCode, …, tags)`. | `Constants.kt`, `models/KarooAppManifest.kt` |
| Konsumenci a reconnect | `addConsumer` można wywołać przed `connect`; konsumenci są ponownie rejestrowani po reconnect (`onServiceConnected`). | `KarooSystemService.kt` |

## B. Do zmierzenia na Karoo (spike w buildzie 0.1.0)

Procedura:

1. `./gradlew :app:assembleDebug` → `adb install -r app/build/outputs/apk/debug/app-debug.apk` → restart Karoo.
2. Dodaj pole **„Pociąg: diagnostyka"** do strony profilu (najlepiej w 2–3 rozmiarach na różnych stronach).
3. Scenariusze (każdy krok loguje się sam):
   - zaraz po starcie Karoo, bez otwierania aplikacji: czy jest linia `Proces start … uptime=…` (start serwisu po bootcie),
   - wybór trasy **przed** startem nagrywania; start jazdy; kilka minut jazdy,
   - odwrócenie tej samej trasy (`reversed`),
   - zjechanie z trasy (rejoin) i powrót,
   - zmiana trasy w trakcie jazdy; zakończenie nawigacji (Idle),
   - nawigacja do pinezki (`NavigatingToDestination`),
   - w aplikacji: przyciski *Powiadomienie* (dotknij powiadomienie!), *Pinezka*, *HTTP Karoo (mały)*, *HTTP Karoo (0,5 MB)*, *Pobierz bezpośrednio* — raz na Wi-Fi i raz tylko z telefonem przez Bluetooth.
4. `adb pull /sdcard/Android/data/pl.kolejka.karoo/files/logs/` i uzupełnij tabelę.

| Pytanie | Wynik | Linie logu |
|---|---|---|
| Jednostka i format `TIME_OF_ARRIVAL` (epoch ms? s? sekundy od północy?) | **Czas epoki w ms** (np. 1,7929355e12 = 14:38 lokalnie). Bywa chwilowo `Streaming` z pustą mapą wartości — traktujemy jak brak. | 25.10, 09:56 |
| Jednostka `TIME_TO_DESTINATION` (s? ms?) | **ms** (1,694e7 ms ≈ 4 h 42 min, zgodne z `TIME_OF_ARRIVAL` z tej samej chwili). | 25.10, 09:56 |
| Jednostka `DISTANCE_TO_DESTINATION` (m?) i wartości `NAVIGATION_STATE` / `ON_ROUTE` | **Metry** (107 066 m przy `routeDistance` 107 916 m na starcie). `NAVIGATION_STATE=0`, `ON_ROUTE=0` także w trakcie nawigacji na trasie — pola nie nadają się do wykrywania „na trasie". `AVERAGE_SPEED` i `SPEED` w **m/s**. | 25.10 |
| Precyzja `routePolyline` (5 czy 6) | **5.** Trasa 68 422 m (`routeDistance`): p5 daje 68 345 m (−0,1%, różnica po uproszczeniu), a p6 daje bzdurne współrzędne (5,2°N). 1098 punktów, 3440 znaków. | `detectPrecision=5` (24.10, 20:17) |
| Czy przy `reversed=true` polyline jest już odwrócona? (porównaj `first`/`last` z trasą normalną) | **Tak — polyline jest już w kierunku jazdy**, ostatni punkt = meta (trasa „do Mrozów" odwrócona: ostatni punkt 70 m od stacji Mrozy, `DISTANCE_TO_DESTINATION` 8 km zgodne z jazdą w tę stronę). Wersja testowa z 25.10 rano odwracała punkty drugi raz — błąd, poprawiony. | 25.10, 12:57 |
| Kiedy ETA ma wartość (przed nagrywaniem? tylko w nawigacji?) | Bez trasy: ETA i dystans `NotAvailable`. **Trasa wybrana, brak GPS-u, brak nagrywania:** `TIME_OF_ARRIVAL` i `TIME_TO_DESTINATION` w stanie `Idle`; `DISTANCE_TO_DESTINATION` w stanie `Streaming`, ale **bez pola dystansu** — tylko `NAVIGATION_STATE=0`, `ON_ROUTE=0`, `REROUTING_ENABLED=1`. Kod musi obsłużyć brak pola (jest „Optional"). Z GPS-em i w jeździe: **do zmierzenia**. |  **Z GPS-em:** ETA i dystans dostępne od razu po wyborze trasy, gdy jest fix i ruch; przy wyborze trasy na auto-pauzie na początku jazdy strumienie były puste przez ~10 min (do ruszenia). Fallback „≈" pokrywa ten czas. |
| ETA i dystans przy zjechaniu z trasy / rejoin | Przy zjechaniu Karoo emituje kolejne `NavigatingRoute` z tą samą `routePolyline` i rosnącym `rejoinPolyline`/`rejoinDistance` (pierwszy punkt = rowerzysta). `routeKey` bez rejoinu → bez przeliczania geometrii; potwierdzone w logu (jedna „Geometria" na trasę). | 25.10, 10:21 |
| Czy P1 występuje (`PROBE P1:`) i czy ponowna subskrypcja pomaga | Wybór trasy przy działającej subskrypcji: `NavigatingRoute` przyszło od razu, **P1 nie wystąpił**. Pole `NAVIGATION_STATE` zmienia się null → 0.0 ~1,5 s *po* zdarzeniu, a pierwsza wersja watchdoga liczyła tylko zdarzenia po zmianie i dała **fałszywy alarm**. Poprawione: okno ±5 s, test z tą sekwencją. Ponowna subskrypcja natychmiast (0,1 s) dostarcza bieżący stan, więc jest bezpieczna. | `PROBE P1:` 20:17:44 |
| Czy `SystemNotification.actionIntent` otwiera aktywność | **Tak.** Dotknięcie powiadomienia daje `onNewIntent action=pl.kolejka.karoo.MAIN`. Dotknięcie kafelka w jeździe (`actionStartActivity`) też otwiera aplikację (`action=null`, flags 0x30400000). | 20:23:42, 20:20:31 |
| `LaunchPinDrop` — co pokazuje Karoo | **Działa.** Karoo otwiera ekran nawigacji do pinezki z zaznaczonym POI („Warszawa Gdańska"); stamtąd jedno dotknięcie uruchamia nawigację. | relacja użytkownika |
| `OnHttpResponse` dla 0,5 MB (oczekiwany błąd > 100 KB) | **Odrzucone:** `onError("RESPONSE_TOO_LARGE")` po ~0,25 s, flow kończy się bez elementu. Mały plik (26 KB): 200 OK w 0,25–1,2 s, nagłówki dostępne (`last-modified`, …). Bez sieci od razu `Complete(status=0, error="Unable to resolve host…")` (sonda ustawia `waitForConnection=false`; z `true` żądanie czekałoby w kolejce). | 20:24:31, 20:24:26 |
| Przepustowość `HttpURLConnection`: Wi-Fi / mostek BT | Wi-Fi: 501 KB w 0,48 s (~8,4 Mbit/s). Mostek BT: **do zmierzenia**. | 20:27:47 |
| Czy serwis startuje po bootcie (bez `BOOT_COMPLETED`)? | **Tak.** Proces startuje 46–65 s po bootcie bez otwierania aplikacji, `connected=true` po ~0,2 s. `RECEIVE_BOOT_COMPLETED` niepotrzebne. | `Proces start: v0.1.0, uptime=65s` / `uptime=46s` (24.10) |
| `ViewConfig` dla używanych rozmiarów pól | Pół szerokości `(30,12)` → 240×128 px, textSize 46. Pełna szerokość `(60,12)` → 478×128 px w podglądzie, **478×144 px w jeździe** (wysokość się różni!), textSize 55. `preview` = true w edytorze, false w jeździe. | `PROBE startView config=` (24.10, 20:16) |
| Czy pole renderuje się z tytułem mimo `showHeader=false` (P2) | | |
| Long-press na polu w edytorze profilu przy `preview=true` (P3) | | |

## C. Inne obserwacje

- **`DISTANCE_TO_DESTINATION` może być większy niż `routeDistance`:** z dala od trasy Karoo
  wlicza dojazd do niej (25.10: 78 km przy trasie 46 km). Dystanse do stacji po drodze liczymy
  wtedy jako dojazd do trasy + pozycja stacji na trasie.
- Sonda z fazy 0 (`DeviceProbe`, pole „Pociąg: diagnostyka", ekran prób) została usunięta
  po zakończeniu pomiarów; jest w historii gita (commit `a714b7e`), gdyby trzeba było coś
  zmierzyć ponownie.

- **Aplikacja i rozszerzenie dzielą jeden proces.** Wyjątek w coroutine ekranu (`first()` na
  pustym flow po `RESPONSE_TOO_LARGE`) zabił też serwis rozszerzenia; Karoo uruchomiło go ponownie
  po ~6–10 s. Każdy scope UI i serwisu musi mieć `CoroutineExceptionHandler`. W sondzie poprawione.
- **Koniec jazdy kończy nawigację:** `RideState` Recording → Paused → Idle, a ~0,3 s później
  `OnNavigationState: Idle`.
- `SPEED` bez ruchu daje wartości 0,36 i 0,514 (= 0,7 i 1 węzeł w m/s), więc jednostką jest
  najpewniej **m/s**, a wartości to szum GPS. Do potwierdzenia w jeździe.
- `OnLocationChanged` bez fixu GPS nie emituje niczego (timeout 30 s → null). Po złapaniu fixu
  przychodzi co ~4 s, `orientation=null` w bezruchu.

- **Zegar ścianowy cofa się zaraz po starcie** (24.10, drugi boot): po `20:14:29.353` następny wpis
  ma `20:14:00.317`, czyli korekta wstecz o ~29 s. Logika zależna od różnic czasu (histereza,
  przytrzymanie ETA 2 min, debounce oparty na zegarze) musi to znosić. Ujemne `Duration`
  traktujemy jako 0.
