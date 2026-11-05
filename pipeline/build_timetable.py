#!/usr/bin/env python3
"""Buduje mały plik SQLite z rozkładem KM + SKM Warszawa + ŁKA + WKD dla rozszerzenia Karoo.

Wejście: rozkład planowy z API Otwarte Dane Kolejowe PKP PLK (https://pdp-api.plk-sa.pl,
`/api/v1/schedules/shortened`) — jedno zapytanie na całe okno dat. Klucz API w zmiennej
środowiskowej `PDP_API_KEY` (nigdy w repo ani w logach). Zamiast API można podać plik
`--schedules` z zapisaną odpowiedzią (testy, praca offline).
Wyjście w katalogu `--out`: `manifest.json` oraz `timetable-<version>.sqlite.gz`. Szczegóły: SPEC.md, sekcja 3.

Schemat SQLite (schema_version = 1) — jak w SPEC 3.4, z odstępstwami:
- `station.id` = ID stacji PLK z API (stabilne między wersjami; ustawienia na urządzeniu
  przechowują ID stacji docelowych). Do 3.11.2026 (GTFS) stacje WKD miały ID 10 000 000+.
- `trip_date.pdp_sid`, `trip_date.pdp_oid` — `scheduleId` i `orderId` kursu w API PDP dla danej
  daty, potrzebne do pobrania opóźnień (`/operations/train/{sid}/{oid}/{data}`). Zamówienia
  o identycznym przebiegu (WKD ma osobne na każdy dzień) są scalane w jeden `trip`, więc ID
  zamówienia jest per data. `trip.train_no` — numer krajowy. Kolumny dopisane na końcu,
  więc aplikacje czytające starszy schemat działają bez zmian.
- `stop_time.flags`: bit 0 = brak wsiadania, bit 1 = brak wysiadania (typ postoju z API),
  bit 2 = odjazd z tej stacji autobusem komunikacji zastępczej (peron „BUS" w danych PLK;
  KM oznacza tak częściową ZKA, np. linia „R61/Z61"). Odjazdy szukamy z `flags & 1 = 0`,
  przyjazdy do celu z `flags & 2 = 0`. `trip.is_bus` = cały kurs autobusem.
- Czasy jak w GTFS (SPEC 6.7): sekundy od „południa minus 12 h" dnia kursowania. API podaje
  zegar ścienny + przesunięcie dnia (`ady`/`ddy`), więc w dni zmiany czasu przeliczamy
  (`service_seconds`) i taki dzień dostaje osobny `trip`.
- `station_group` łączy stacje o tej samej nazwie bazowej (bez sufiksu „WKD" i nawiasów)
  w promieniu `GROUP_RADIUS_M` (700 m — „Warszawa Śródmieście" i „Warszawa Śródmieście WKD"
  dzieli wg współrzędnych PLK ~620 m, więc 400 m z SPEC nie wystarcza).

Kod korzysta wyłącznie z biblioteki standardowej.
"""
from __future__ import annotations

import argparse
import datetime as dt
import functools
import gzip
import hashlib
import json
import logging
import math
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from zoneinfo import ZoneInfo

log = logging.getLogger("build_timetable")

SCHEMA_VERSION = 1
TZ = ZoneInfo("Europe/Warsaw")

PDP_BASE = "https://pdp-api.plk-sa.pl"
API_KEY_ENV = "PDP_API_KEY"

# Kod przewoźnika w API PDP → kod w bazie (bez polskich znaków, jak dotychczas).
CARRIERS = {
    "KM": "KM",
    "SKM": "SKM",
    # ŁKA (m.in. ŁS Sprinter Łódź – Warszawa Zachodnia/Centralna/Wschodnia), dodana 26.10.2026.
    "ŁKA": "LKA",
    "WKD": "WKD",
}
# Kategoria handlowa nic nie mówi o linii (WKD: „Os") → bez nazwy linii, jak w GTFS.
ROUTE_NAME_OVERRIDE = {"WKD": "WKD"}
BUS_PLATFORM = "BUS"

WINDOW_BEFORE_DAYS = 1
WINDOW_AFTER_DAYS = 14
MIN_VALID_AHEAD_DAYS = 7
MAX_DB_BYTES = 10 * 1024 * 1024
GROUP_RADIUS_M = 700.0
# Dane opublikowane nie dawniej niż tyle dni temu i niezmienione źródło → bez publikacji.
REBUILD_WHEN_VALID_AHEAD_BELOW_DAYS = 12

DEFAULT_TARGETS = [
    ["Warszawa Gdańska"],
    ["Warszawa Wileńska"],
    ["Warszawa Śródmieście", "Warszawa Śródmieście WKD"],
    # Cele ŁS Sprintera i dalekobieżnych KM (dopisane 3.11.2026 na prośbę właściciela).
    ["Warszawa Zachodnia"],
    ["Warszawa Centralna"],
]

FLAG_NO_PICKUP = 1
FLAG_NO_DROP_OFF = 2
FLAG_BUS = 4

# Typy postoju z `/dictionaries/stop-types`.
STOP_TYPE_FLAGS = {1: FLAG_NO_DROP_OFF, 2: FLAG_NO_PICKUP}  # 1 = tylko wsiadanie, 2 = tylko wysiadanie

ATTRIBUTION = "Dane: PKP Polskie Linie Kolejowe S.A. — Otwarte Dane Kolejowe (https://pdp-api.plk-sa.pl)"


class BuildError(Exception):
    """Błąd danych — nic nie jest publikowane."""


# --------------------------------------------------------------------------- pomocnicze


def parse_hms(value: str | None) -> int | None:
    if not value:
        return None
    h, m, s = value.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def parse_date(value: str) -> dt.date:
    return dt.date(int(value[0:4]), int(value[4:6]), int(value[6:8]))


def date_int(d: dt.date) -> int:
    return d.year * 10000 + d.month * 100 + d.day


def normalize_name(name: str) -> str:
    # „ł" nie rozkłada się w NFKD, więc zamieniamy ręcznie.
    s = name.replace("ł", "l").replace("Ł", "L")
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return " ".join(s.lower().split())


def base_name(name_norm: str) -> str:
    s = re.sub(r"\s*\(.*?\)\s*", " ", name_norm)
    s = re.sub(r"\s+wkd$", "", s.strip())
    return " ".join(s.split())


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


@functools.lru_cache(maxsize=4096)
def _utc_offset(local: dt.datetime) -> int:
    return int(local.replace(tzinfo=TZ).utcoffset().total_seconds())


def service_seconds(service_date: int, wall: int) -> int:
    """Zegar ścienny (s od północy dnia kursowania, może być > 86 400) → s od „południa − 12 h"."""
    d = parse_date(str(service_date))
    midnight = dt.datetime(d.year, d.month, d.day)
    # Zmiany czasu wypadają o pełnej godzinie, więc offset wystarczy liczyć z dokładnością do godziny.
    local = midnight + dt.timedelta(seconds=wall - wall % 3600)
    return wall + _utc_offset(midnight + dt.timedelta(hours=12)) - _utc_offset(local)


def rail_line_name(ccs: str) -> str:
    """„R61/Z61" (kurs częściowo z ZKA) → „R61"; odcinek autobusowy oznacza `FLAG_BUS`."""
    parts = [p for p in ccs.split("/") if p]
    rail = [p for p in parts if not re.match(r"^Z(E)?\d", p)]
    return (rail or parts or [""])[0]


# --------------------------------------------------------------------------- model


@dataclass
class Trip:
    agency: str
    route_short_name: str
    headsign: str
    is_bus: bool
    train_no: str
    stop_times: tuple[tuple, ...]  # (seq, station_id, arr, dep, platform, track, flags)
    dates: dict[int, tuple[int, int]] = field(default_factory=dict)  # data → (sid, oid) w API PDP

    @property
    def content_key(self) -> tuple:
        return (self.agency, self.route_short_name, self.headsign, self.is_bus, self.train_no, self.stop_times)


@dataclass
class Station:
    id: int
    name: str
    lat: float
    lon: float
    agencies: set[str] = field(default_factory=set)
    group_id: int | None = None


@dataclass
class Feed:
    trips: list[Trip]
    stations: dict[int, Station]
    feed_version: str


# --------------------------------------------------------------------------- wczytywanie


def stop_platform(st: dict, date: str | None) -> tuple[str | None, str | None]:
    """Peron i tor odjazdu (przyjazdu na stacji końcowej), z wariantem dla daty, jeśli jest."""
    src = st
    if date is not None:
        for variant in st.get("pv") or ():
            if date in variant.get("od", ()):
                src = variant
                break
    platform = src.get("dpl") or src.get("apl")
    track = src.get("dtr") or src.get("atr")
    return platform or None, track or None


def load_feed(data: dict, window: tuple[dt.date, dt.date]) -> Feed:
    """Zamienia odpowiedź `/schedules/shortened` (z `fullRoute=true`) na kursy i stacje."""
    start, end = date_int(window[0]), date_int(window[1])
    dict_stations = (data.get("dc") or {}).get("st") or {}
    if isinstance(dict_stations, list):
        dict_stations = {str(s["id"]): s for s in dict_stations}

    trips: dict[tuple, Trip] = {}
    stations: dict[int, Station] = {}
    skipped_carrier = 0
    skipped_stops = 0
    for route in data.get("rt") or ():
        agency = CARRIERS.get(route.get("cc", ""))
        if agency is None:
            skipped_carrier += 1
            continue
        all_dates = sorted({int(d.replace("-", "")) for d in route.get("od") or ()})
        dates = [d for d in all_dates if start <= d <= end]
        if not dates:
            continue

        stops = []
        for st in sorted(route.get("st") or (), key=lambda s: s["ord"]):
            arr = parse_hms(st.get("atm"))
            dep = parse_hms(st.get("dtm"))
            if arr is None and dep is None:
                skipped_stops += 1  # przejazd bez postoju
                continue
            if arr is not None:
                arr += 86400 * (st.get("ady") or 0)
            if dep is not None:
                dep += 86400 * (st.get("ddy") or 0)
            arr = dep if arr is None else arr
            dep = arr if dep is None else dep
            sid = int(st["id"])
            if sid not in stations:
                info = dict_stations.get(str(sid))
                if not info or info.get("lat") is None or info.get("lon") is None:
                    raise BuildError(f"Stacja {sid} bez współrzędnych w słowniku")
                stations[sid] = Station(id=sid, name=info["nm"], lat=float(info["lat"]), lon=float(info["lon"]))
            flags = STOP_TYPE_FLAGS.get(st.get("sti") or 0, 0)
            stops.append((st, sid, arr, dep, flags))
        if len(stops) < 2:
            continue
        for i, (_, _, arr, dep, _) in enumerate(stops):
            if dep < arr or (i and arr < stops[i - 1][3]):
                raise BuildError(f"Czasy nie rosną w kursie {route['sid']}/{route['oid']}")

        # Perony (`pv`) i — w dni zmiany czasu — czasy mogą się różnić między datami
        # → osobny kurs na każdy wariant.
        by_variant: dict[tuple, list[int]] = defaultdict(list)
        for d in dates:
            iso = f"{d // 10000:04d}-{d // 100 % 100:02d}-{d % 100:02d}"
            platforms = tuple(stop_platform(st, iso) for st, *_ in stops)
            times = tuple((service_seconds(d, arr), service_seconds(d, dep)) for _, _, arr, dep, _ in stops)
            by_variant[(platforms, times)].append(d)

        ccs = route.get("ccs") or ""
        name = ROUTE_NAME_OVERRIDE.get(agency, rail_line_name(ccs))
        headsign = stations[stops[-1][1]].name
        for (platforms, times), variant_dates in by_variant.items():
            stop_times = []
            for seq, ((_, sid, _, _, flags), (platform, track), (arr, dep)) in enumerate(zip(stops, platforms, times)):
                if platform == BUS_PLATFORM:
                    flags |= FLAG_BUS
                    platform = track = None
                stop_times.append((seq, sid, arr, dep, platform, track, flags))
            # Ostatni przystanek nie ma odjazdu — o „autobusowości" kursu decydują odjazdy.
            is_bus = all(st[6] & FLAG_BUS for st in stop_times[:-1])
            trip = Trip(agency, name, headsign, is_bus, str(route.get("nn") or ""), tuple(stop_times))
            trip = trips.setdefault(trip.content_key, trip)
            for d in variant_dates:
                if d in trip.dates:
                    raise BuildError(f"Dwa zamówienia tego samego kursu w dniu {d}: {trip.dates[d]}, {route['oid']}")
                trip.dates[d] = (int(route["sid"]), int(route["oid"]))
            for _, sid, *_ in stops:
                stations[sid].agencies.add(agency)

    if skipped_carrier:
        log.info("Pominięto %d kursów innych przewoźników", skipped_carrier)
    if skipped_stops:
        log.info("Pominięto %d przejazdów bez postoju", skipped_stops)
    log.info("Kursy w oknie: %d (po scaleniu identycznych), stacje: %d", len(trips), len(stations))
    return Feed(list(trips.values()), stations, str(data.get("ts", "")))


# --------------------------------------------------------------------------- scalanie


def assign_groups(stations: list[Station]) -> list[tuple[str, str, float]]:
    """Ustawia group_id dla stacji-bliźniaków; zwraca listę par do zalogowania."""
    by_base: dict[str, list[Station]] = defaultdict(list)
    for s in stations:
        by_base[base_name(normalize_name(s.name))].append(s)
    parent: dict[int, int] = {}

    def find(x: int) -> int:
        while parent.get(x, x) != x:
            x = parent[x]
        return x

    pairs = []
    for group in by_base.values():
        for i, a in enumerate(group):
            for b in group[i + 1:]:
                d = haversine_m(a.lat, a.lon, b.lat, b.lon)
                if d <= GROUP_RADIUS_M:
                    ra, rb = find(a.id), find(b.id)
                    parent[max(ra, rb)] = min(ra, rb)
                    pairs.append((a.name, b.name, d))
    members: dict[int, list[Station]] = defaultdict(list)
    for s in stations:
        members[find(s.id)].append(s)
    for root, ms in members.items():
        if len(ms) > 1:
            for s in ms:
                s.group_id = root
    return pairs


def resolve_default_targets(stations: list[Station]) -> list[list[int]]:
    by_name: dict[str, list[Station]] = defaultdict(list)
    for s in stations:
        by_name[s.name].append(s)
    result = []
    for group in DEFAULT_TARGETS:
        ids = []
        for name in group:
            found = by_name.get(name)
            if not found:
                raise BuildError(f"Nie znaleziono domyślnej stacji docelowej „{name}\"")
            if len(found) > 1:
                raise BuildError(f"Niejednoznaczna stacja docelowa „{name}\": {[s.id for s in found]}")
            ids.append(found[0].id)
        result.append(ids)
    return result


# --------------------------------------------------------------------------- SQLite

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE station(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  name_norm TEXT NOT NULL,
  lat REAL NOT NULL, lon REAL NOT NULL,
  agencies TEXT NOT NULL,
  group_id INTEGER
);
CREATE TABLE trip(
  id INTEGER PRIMARY KEY,
  agency TEXT NOT NULL,
  route_short_name TEXT,
  headsign TEXT,
  is_bus INTEGER NOT NULL DEFAULT 0,
  train_no TEXT
);
CREATE TABLE trip_date(trip_id INTEGER, service_date INTEGER, pdp_sid INTEGER, pdp_oid INTEGER,
  PRIMARY KEY(trip_id, service_date)) WITHOUT ROWID;
CREATE TABLE stop_time(
  trip_id INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  station_id INTEGER NOT NULL,
  arr_s INTEGER NOT NULL,
  dep_s INTEGER NOT NULL,
  platform TEXT,
  track TEXT,
  flags INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(trip_id, seq)
) WITHOUT ROWID;
CREATE INDEX stop_time_station_dep ON stop_time(station_id, dep_s);
"""

AGENCY_ORDER = {"KM": 0, "SKM": 1, "LKA": 2, "WKD": 3}


@dataclass
class BuildResult:
    db_path: Path
    meta: dict[str, str]
    platform_coverage: dict[str, float]


def build_database(feed: Feed, today: dt.date, out_path: Path) -> BuildResult:
    stations = sorted(feed.stations.values(), key=lambda s: s.id)
    for a, b, d in assign_groups(stations):
        log.info("Grupa stacji: %s ↔ %s (%.0f m)", a, b, d)
    targets = resolve_default_targets(stations)

    trips = sorted(feed.trips, key=lambda t: (min(t.dates), t.agency, t.stop_times[0][3], t.train_no))
    valid_from = today - dt.timedelta(days=WINDOW_BEFORE_DAYS)
    last_date: dict[str, int] = {}
    with_platform: dict[str, int] = defaultdict(int)
    total: dict[str, int] = defaultdict(int)

    if out_path.exists():
        out_path.unlink()
    con = sqlite3.connect(out_path)
    try:
        con.execute("PRAGMA journal_mode=OFF")
        con.executescript(SCHEMA)
        con.executemany(
            "INSERT INTO station VALUES (?,?,?,?,?,?,?)",
            [
                (s.id, s.name, normalize_name(s.name), round(s.lat, 6), round(s.lon, 6),
                 ",".join(sorted(s.agencies, key=AGENCY_ORDER.get)), s.group_id)
                for s in stations
            ],
        )
        for trip_id, t in enumerate(trips, start=1):
            con.execute("INSERT INTO trip VALUES (?,?,?,?,?,?)",
                        (trip_id, t.agency, t.route_short_name or None, t.headsign or None, int(t.is_bus),
                         t.train_no or None))
            con.executemany("INSERT INTO trip_date VALUES (?,?,?,?)",
                            [(trip_id, d, sid, oid) for d, (sid, oid) in sorted(t.dates.items())])
            con.executemany("INSERT INTO stop_time VALUES (?,?,?,?,?,?,?,?)",
                            [(trip_id, *st) for st in t.stop_times])
            last_date[t.agency] = max(last_date.get(t.agency, 0), max(t.dates))
            rail = [st for st in t.stop_times if not st[6] & FLAG_BUS]
            total[t.agency] += len(rail)
            with_platform[t.agency] += sum(1 for st in rail if st[4])

        for agency in CARRIERS.values():
            if agency not in last_date:
                raise BuildError(f"Brak kursów przewoźnika {agency} w oknie dat")
        valid_to = parse_date(str(min(last_date.values())))
        meta = {
            "schema_version": str(SCHEMA_VERSION),
            "feed_version": f"pdp:{feed.feed_version}",
            "generated_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
            "valid_from": valid_from.isoformat(),
            "valid_to": valid_to.isoformat(),
            "source_attribution": ATTRIBUTION,
            "default_targets": json.dumps(targets),
        }
        con.executemany("INSERT INTO meta VALUES (?,?)", sorted(meta.items()))
        con.commit()
        con.execute("VACUUM")
        con.execute("ANALYZE")
        con.commit()
    finally:
        con.close()

    coverage = {a: (with_platform[a] / total[a] if total[a] else 0.0) for a in sorted(total)}
    for a, c in coverage.items():
        log.info("Pokrycie peronów %s: %.1f%% (%d postojów, bez ZKA)", a, c * 100, total[a])
    return BuildResult(out_path, meta, coverage)


def validate(result: BuildResult, today: dt.date) -> None:
    con = sqlite3.connect(f"file:{result.db_path}?mode=ro", uri=True)
    try:
        if con.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise BuildError("integrity_check nie przeszedł")
        for table in ("station", "trip", "trip_date", "stop_time"):
            n = con.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
            log.info("Tabela %s: %d wierszy", table, n)
            if n == 0:
                raise BuildError(f"Pusta tabela {table}")
        targets = json.loads(result.meta["default_targets"])
        for group in targets:
            for sid in group:
                if not con.execute("SELECT 1 FROM station WHERE id=?", (sid,)).fetchone():
                    raise BuildError(f"Stacja docelowa {sid} nie istnieje w bazie")
    finally:
        con.close()
    valid_to = dt.date.fromisoformat(result.meta["valid_to"])
    if valid_to < today + dt.timedelta(days=MIN_VALID_AHEAD_DAYS):
        raise BuildError(f"valid_to {valid_to} < dziś+{MIN_VALID_AHEAD_DAYS}")
    size = result.db_path.stat().st_size
    log.info("Rozmiar bazy: %.2f MB", size / 1e6)
    if size > MAX_DB_BYTES:
        raise BuildError(f"Baza za duża: {size} B")


# --------------------------------------------------------------------------- publikacja


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_outputs(result: BuildResult, out_dir: Path, sources: dict, today: dt.date) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    db_sha = sha256_file(result.db_path)
    version = f"{today:%Y%m%d}-{db_sha[:8]}"
    gz_name = f"timetable-{version}.sqlite.gz"
    gz_path = out_dir / gz_name
    # mtime=0 i brak nazwy pliku w nagłówku → ten sam plik daje ten sam .gz.
    with open(result.db_path, "rb") as src, open(gz_path, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as gz:
            shutil.copyfileobj(src, gz)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "version": version,
        "sha256": sha256_file(gz_path),
        "size_bytes": gz_path.stat().st_size,
        "url": gz_name,  # względem URL manifestu
        "generated_at": result.meta["generated_at"],
        "valid_from": result.meta["valid_from"],
        "valid_to": result.meta["valid_to"],
        "sources": sources,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log.info("Zapisano %s (%.2f MB) i manifest.json", gz_name, manifest["size_bytes"] / 1e6)
    return manifest


# --------------------------------------------------------------------------- API PDP


def api_get(path: str, params: dict[str, str], api_key: str, attempts: int = 3) -> dict:
    """GET z kluczem w nagłówku (nigdy w URL ani w logach), gzip, 2 ponowienia z backoffem."""
    url = f"{PDP_BASE}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = {"X-API-Key": api_key, "Accept-Encoding": "gzip", "User-Agent": "karoo-pociag-pipeline"}
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=180) as resp:
                body = resp.read()
                if resp.headers.get("Content-Encoding") == "gzip":
                    body = gzip.decompress(body)
            log.info("GET %s → %.1f MB", url, len(body) / 1e6)
            return json.loads(body)
        except urllib.error.HTTPError as e:
            # 4xx (zły klucz, limit) nie minie od ponowienia.
            if e.code < 500 or attempt == attempts:
                raise BuildError(f"API PDP: HTTP {e.code} dla {path}") from None
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt == attempts:
                raise BuildError(f"API PDP niedostępne ({path}): {e}") from None
        log.warning("Ponawiam %s (próba %d)", path, attempt + 1)
        time.sleep(10 * attempt)
    raise AssertionError("nieosiągalne")


def fetch_schedules(api_key: str, window: tuple[dt.date, dt.date]) -> dict:
    return api_get("/api/v1/schedules/shortened", {
        "dateFrom": window[0].isoformat(),
        "dateTo": window[1].isoformat(),
        "carriersInclude": ",".join(CARRIERS),
        "fullRoute": "true",
        "dictionaries": "true",
    }, api_key)


# --------------------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--schedules", type=Path, default=None,
                   help="zapisana odpowiedź /schedules/shortened zamiast zapytania do API")
    p.add_argument("--out", type=Path, required=True, help="katalog wyjściowy")
    p.add_argument("--today", type=dt.date.fromisoformat, default=None, help="data „dziś\" (Europe/Warsaw)")
    p.add_argument("--previous-manifest", type=Path, default=None, help="poprzednio opublikowany manifest.json")
    p.add_argument("--force", action="store_true", help="buduj nawet bez zmian w źródle")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    today = args.today or dt.datetime.now(TZ).date()
    window = (today - dt.timedelta(days=WINDOW_BEFORE_DAYS), today + dt.timedelta(days=WINDOW_AFTER_DAYS))
    previous = None
    if args.previous_manifest and args.previous_manifest.exists():
        previous = json.loads(args.previous_manifest.read_text(encoding="utf-8"))
    stale = True
    if previous and previous.get("schema_version") == SCHEMA_VERSION:
        prev_valid_to = dt.date.fromisoformat(previous["valid_to"])
        stale = prev_valid_to < today + dt.timedelta(days=REBUILD_WHEN_VALID_AHEAD_BELOW_DAYS)

    tmp = Path(tempfile.mkdtemp(prefix="timetable-"))
    try:
        if args.schedules:
            data = json.loads(args.schedules.read_text(encoding="utf-8"))
            sources = {"pdp": {"file": args.schedules.name}}
        else:
            api_key = os.environ.get(API_KEY_ENV, "").strip()
            if not api_key:
                raise BuildError(f"Brak klucza API w zmiennej {API_KEY_ENV}")
            version = api_get("/api/v1/data-version", {}, api_key).get("schedulesVersion", "")
            sources = {"pdp": {"schedules_version": version}}
            if not args.force and not stale and (previous or {}).get("sources") == sources:
                log.info("Rozkład PDP bez zmian (%s), poprzednie dane aktualne — brak publikacji", version)
                set_output("publish", "false")
                return 0
            data = fetch_schedules(api_key, window)

        feed = load_feed(data, window)
        result = build_database(feed, today, tmp / "timetable.sqlite")
        validate(result, today)
        write_outputs(result, args.out, sources, today)
        set_output("publish", "true")
        return 0
    except BuildError as e:
        log.error("Błąd danych, brak publikacji: %s", e)
        set_output("publish", "false")
        return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def set_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"{name}={value}\n")


if __name__ == "__main__":
    sys.exit(main())
