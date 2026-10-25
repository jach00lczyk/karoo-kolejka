# Faza 0 — ustalenia z urządzenia

> Dalsze fazy opierają się na tym pliku, nie na zgadywaniu (SPEC 4.1, 12).
> Status: **spike gotowy, pomiary na Karoo jeszcze niewykonane.** Sekcja A to fakty z kodu
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
| Jednostka i format `TIME_OF_ARRIVAL` (epoch ms? s? sekundy od północy?) | | `PROBE TIME_OF_ARRIVAL @<now>` |
| Jednostka `TIME_TO_DESTINATION` (s? ms?) | | |
| Jednostka `DISTANCE_TO_DESTINATION` (m?) i wartości `NAVIGATION_STATE` / `ON_ROUTE` | | |
| Precyzja `routePolyline` (5 czy 6) | | `detectPrecision=` |
| Czy przy `reversed=true` polyline jest już odwrócona? (porównaj `first`/`last` z trasą normalną) | | |
| Kiedy ETA ma wartość (przed nagrywaniem? tylko w nawigacji?) | | |
| ETA i dystans przy zjechaniu z trasy / rejoin | | |
| Czy P1 występuje (`PROBE P1:`) i czy ponowna subskrypcja pomaga | | |
| Czy `SystemNotification.actionIntent` otwiera aktywność | | `MainActivity.onNewIntent action=pl.kolejka.karoo.MAIN` |
| `LaunchPinDrop` — co pokazuje Karoo | | |
| `OnHttpResponse` dla 0,5 MB (oczekiwany błąd > 100 KB) | | |
| Przepustowość `HttpURLConnection`: Wi-Fi / mostek BT | | `kbit/s` |
| Czy serwis startuje po bootcie (bez `BOOT_COMPLETED`)? | | `Proces start … uptime` |
| `ViewConfig` dla używanych rozmiarów pól | | `PROBE startView config=` |
| Czy pole renderuje się z tytułem mimo `showHeader=false` (P2) | | |
| Long-press na polu w edytorze profilu przy `preview=true` (P3) | | |
