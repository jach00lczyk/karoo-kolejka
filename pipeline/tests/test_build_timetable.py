import datetime as dt
import gzip
import hashlib
import io
import json
import shutil
import sqlite3
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_timetable as bt  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "pdp_schedules.json"
TODAY = dt.date(2026, 10, 26)  # poniedziałek


@pytest.fixture
def schedules():
    """Kopia fixture jako słownik, żeby testy mogły ją modyfikować."""
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def run(data: dict, tmp_path: Path, out: Path, *extra: str) -> int:
    path = tmp_path / "schedules.json"
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return bt.main(["--schedules", str(path), "--out", str(out), "--today", TODAY.isoformat(), *extra])


@pytest.fixture
def built(schedules, tmp_path):
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 0
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    db = tmp_path / "timetable.sqlite"
    with gzip.open(out / manifest["url"], "rb") as src, open(db, "wb") as dst:
        shutil.copyfileobj(src, dst)
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    yield con, manifest, out
    con.close()


def route(data: dict, oid: int) -> dict:
    return next(r for r in data["rt"] if r["oid"] == oid)


def trip_id(con, oid, date=None):
    """ID kursu po numerze zamówienia PDP (i dacie, gdy zamówienie ma kilka wariantów)."""
    sql = "SELECT trip_id FROM trip_date WHERE pdp_oid=?" + (" AND service_date=?" if date else "")
    return con.execute(sql, (oid, date) if date else (oid,)).fetchone()["trip_id"]


def hms(h, m, s=0):
    return h * 3600 + m * 60 + s


def station(con, name):
    return con.execute("SELECT * FROM station WHERE name=?", (name,)).fetchone()


def stop_times(con, tid):
    return con.execute("SELECT * FROM stop_time WHERE trip_id=? ORDER BY seq", (tid,)).fetchall()


def dates(con, tid):
    return [r[0] for r in con.execute("SELECT service_date FROM trip_date WHERE trip_id=? ORDER BY 1", (tid,))]


def trip(con, tid):
    return con.execute("SELECT * FROM trip WHERE id=?", (tid,)).fetchone()


# --------------------------------------------------------------------------- filtrowanie


def test_only_selected_carriers(built):
    con, _, _ = built
    assert {r[0] for r in con.execute("SELECT DISTINCT agency FROM trip")} == {"KM", "SKM", "LKA", "WKD"}
    assert station(con, "Kraków Główny") is None


def test_trip_outside_window_and_single_stop_trip_dropped(built):
    con, _, _ = built
    assert con.execute("SELECT count(*) FROM trip_date WHERE pdp_oid IN (1009, 1010)").fetchone()[0] == 0


def test_station_ids_from_api_and_agencies(built):
    con, _, _ = built
    assert station(con, "Mińsk Mazowiecki")["id"] == 39008
    assert station(con, "Mińsk Mazowiecki")["agencies"] == "KM"
    assert station(con, "Warszawa Śródmieście")["agencies"] == "KM,SKM"
    assert station(con, "Warszawa Zachodnia")["agencies"] == "KM,LKA"
    assert station(con, "Warszawa Śródmieście WKD")["id"] == 35006
    assert station(con, "Warszawa Śródmieście WKD")["agencies"] == "WKD"


def test_name_norm_without_diacritics(built):
    con, _, _ = built
    assert station(con, "Mińsk Mazowiecki")["name_norm"] == "minsk mazowiecki"
    assert bt.normalize_name("Łódź Fabryczna") == "lodz fabryczna"


def test_missing_station_coordinates_fail(schedules, tmp_path):
    del schedules["dc"]["st"]["39008"]["lat"]
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 1
    assert not (out / "manifest.json").exists()


# --------------------------------------------------------------------------- daty i czas


def test_operating_dates_clipped_to_window(built):
    con, _, _ = built
    assert dates(con, trip_id(con, 1001)) == [20261026, 20261028, 20261105, 20261109]


def test_trip_after_midnight(built):
    con, _, _ = built
    t = trip_id(con, 1002)
    assert dates(con, t) == [20261031]
    st = stop_times(con, t)
    assert st[0]["dep_s"] == hms(23, 50)
    assert (st[1]["arr_s"], st[1]["dep_s"]) == (hms(24, 20), hms(24, 21))
    assert st[2]["arr_s"] == st[2]["dep_s"] == hms(24, 24)


def test_pass_through_without_times_skipped(built):
    con, _, _ = built
    st = stop_times(con, trip_id(con, 1001))
    assert [r["seq"] for r in st] == [0, 1, 2]
    assert [r["station_id"] for r in st] == [39008, 33571, 33506]
    assert station(con, "Sulejówek Miłosna") is None
    # pierwszy i ostatni przystanek mają tylko odjazd / przyjazd
    assert st[0]["arr_s"] == st[0]["dep_s"] == hms(17, 12)
    assert st[2]["arr_s"] == st[2]["dep_s"] == hms(18, 5)


def test_decreasing_times_fail(schedules, tmp_path):
    route(schedules, 1001)["st"][2]["atm"] = "17:00:00"
    assert run(schedules, tmp_path, tmp_path / "out") == 1


def test_stop_type_flags(built):
    con, _, _ = built
    st = stop_times(con, trip_id(con, 3001))
    assert [r["flags"] for r in st] == [bt.FLAG_NO_DROP_OFF, 0, bt.FLAG_NO_PICKUP]


# --------------------------------------------------------------------------- perony, ZKA


def test_platform_and_track(built):
    con, _, _ = built
    st = stop_times(con, trip_id(con, 1001))
    assert (st[0]["platform"], st[0]["track"]) == ("3", "1")
    assert (st[1]["platform"], st[1]["track"]) == ("1", "2")  # odjazdowy
    assert (st[2]["platform"], st[2]["track"]) == ("6", None)  # stacja końcowa: przyjazdowy


def test_platform_variants_split_trip(built):
    con, _, _ = built
    a, b = trip_id(con, 1005, 20261026), trip_id(con, 1005, 20261027)
    assert a != b
    assert (stop_times(con, a)[0]["platform"], stop_times(con, a)[0]["track"]) == ("2", None)
    assert (stop_times(con, b)[0]["platform"], stop_times(con, b)[0]["track"]) == ("4", "7")
    assert trip(con, a)["route_short_name"] == "ŁS"


def test_partial_bus_replacement(built):
    con, _, _ = built
    t = trip_id(con, 1003)
    assert trip(con, t)["is_bus"] == 0
    assert trip(con, t)["route_short_name"] == "R61"
    st = stop_times(con, t)
    assert [r["flags"] & bt.FLAG_BUS for r in st] == [bt.FLAG_BUS, 0, 0]  # z Gdańskiej już pociągiem
    assert st[0]["platform"] is None
    assert (st[1]["platform"], st[1]["track"]) == ("2", "4")


def test_full_bus_replacement(built):
    con, _, _ = built
    t = trip_id(con, 1004)
    assert trip(con, t)["is_bus"] == 1
    assert all(r["platform"] is None for r in stop_times(con, t))


def test_rail_line_name():
    assert bt.rail_line_name("R61/Z61") == "R61"
    assert bt.rail_line_name("RE61/ZE61") == "RE61"
    assert bt.rail_line_name("Z61") == "Z61"
    assert bt.rail_line_name("S1") == "S1"
    assert bt.rail_line_name("") == ""


# --------------------------------------------------------------------------- scalanie i ID zamówień


def test_identical_orders_merged_with_order_per_date(built):
    con, _, _ = built
    t = trip_id(con, 2001)
    assert trip_id(con, 2002) == t
    rows = con.execute("SELECT service_date, pdp_sid, pdp_oid FROM trip_date WHERE trip_id=? ORDER BY 1", (t,)).fetchall()
    assert [tuple(r) for r in rows] == [(20261026, 2026, 2001), (20261027, 2026, 2002), (20261104, 2026, 2002)]
    assert (trip(con, t)["agency"], trip(con, t)["route_short_name"], trip(con, t)["train_no"]) == ("WKD", "WKD", "100")
    assert trip(con, t)["headsign"] == "Warszawa Śródmieście WKD"


def test_order_ids_for_delays(built):
    con, _, _ = built
    row = con.execute("SELECT pdp_sid, pdp_oid FROM trip_date WHERE trip_id=? AND service_date=20261031",
                      (trip_id(con, 1002),)).fetchone()
    assert tuple(row) == (2026, 1002)


def test_duplicate_order_on_same_date_fails(schedules, tmp_path):
    route(schedules, 2002)["od"].append("2026-10-26")
    assert run(schedules, tmp_path, tmp_path / "out") == 1


# --------------------------------------------------------------------------- grupy i cele


def test_twin_stations_grouped_not_merged(built):
    con, _, _ = built
    srod, srod_wkd = station(con, "Warszawa Śródmieście"), station(con, "Warszawa Śródmieście WKD")
    assert srod["id"] != srod_wkd["id"]
    assert srod["group_id"] is not None and srod["group_id"] == srod_wkd["group_id"]
    assert station(con, "Warszawa Centralna")["group_id"] is None
    assert station(con, "Mińsk Mazowiecki")["group_id"] is None


def test_default_targets(built):
    con, _, _ = built
    meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
    assert json.loads(meta["default_targets"]) == [[36103], [37440], [33571, 35006], [33506], [33605]]


def test_missing_default_target_fails_without_output(schedules, tmp_path):
    schedules["dc"]["st"]["36103"]["nm"] = "Gdańsk"
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 1
    assert not (out / "manifest.json").exists()


def test_missing_carrier_fails(schedules, tmp_path):
    schedules["rt"] = [r for r in schedules["rt"] if r["cc"] != "SKM"]
    assert run(schedules, tmp_path, tmp_path / "out") == 1


# --------------------------------------------------------------------------- walidacja i manifest


def test_meta_and_manifest(built):
    con, manifest, out = built
    meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
    assert meta["schema_version"] == "1"
    assert meta["valid_from"] == "2026-10-25"
    assert meta["valid_to"] == "2026-11-03"  # najwcześniejsza ostatnia data przewoźnika (ŁKA)
    assert "PKP Polskie Linie Kolejowe" in meta["source_attribution"]
    assert meta["feed_version"] == "pdp:2026-10-26T01:30:00Z"
    gz = out / manifest["url"]
    assert manifest["sha256"] == hashlib.sha256(gz.read_bytes()).hexdigest()
    assert manifest["size_bytes"] == gz.stat().st_size
    assert manifest["version"].startswith("20261026-")
    assert manifest["valid_to"] == meta["valid_to"]
    assert manifest["carriers_valid_to"] == {"KM": "2026-11-09", "SKM": "2026-11-05", "LKA": "2026-11-03", "WKD": "2026-11-04"}
    assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def lka_until(schedules, last: str) -> None:
    route(schedules, 1004)["od"] = [last]
    route(schedules, 1005)["od"] = ["2026-10-25"]


def prev_manifest(tmp_path, valid_to: str) -> str:
    prev = tmp_path / "prev.json"
    prev.write_text(json.dumps({"schema_version": 1, "valid_to": valid_to}), encoding="utf-8")
    return str(prev)


def test_short_required_carrier_publishes_truncated(schedules, tmp_path, gh_output):
    lka_until(schedules, "2026-10-30")  # ŁKA tylko do dziś+4
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 0
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["valid_to"] == "2026-10-30"
    o = outputs(gh_output)
    assert o["publish"] == "true"
    assert o["warnings"] == "LKA: kursy tylko do 2026-10-30 < dziś+7 — rozkład okrojony"


def test_truncated_today_only_still_publishes(schedules, tmp_path, gh_output):
    lka_until(schedules, "2026-10-26")  # tylko dziś
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 0
    assert json.loads((out / "manifest.json").read_text(encoding="utf-8"))["valid_to"] == "2026-10-26"


def test_required_carrier_without_today_fails(schedules, tmp_path):
    lka_until(schedules, "2026-10-25")  # tylko wczoraj
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 1
    assert not (out / "manifest.json").exists()


def test_truncated_keeps_longer_previous(schedules, tmp_path, gh_output):
    lka_until(schedules, "2026-10-30")
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out, "--previous-manifest", prev_manifest(tmp_path, "2026-11-01")) == 0
    assert not (out / "manifest.json").exists()
    o = outputs(gh_output)
    assert o["publish"] == "false"
    assert "Zostaje poprzedni rozkład (do 2026-11-01), nowy tylko do 2026-10-30" in o["warnings"]


def test_truncated_replaces_shorter_previous(schedules, tmp_path, gh_output):
    lka_until(schedules, "2026-10-30")
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out, "--previous-manifest", prev_manifest(tmp_path, "2026-10-28")) == 0
    assert (out / "manifest.json").exists()
    assert outputs(gh_output)["publish"] == "true"


def wkd_until(schedules, last: str) -> None:
    for oid in (2001, 2002):
        route(schedules, oid)["od"] = [d for d in route(schedules, oid)["od"] if d <= last]
    route(schedules, 2002)["od"].append(last)


def outputs(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines())


@pytest.fixture
def gh_output(monkeypatch, tmp_path):
    path = tmp_path / "github_output"
    path.write_text("", encoding="utf-8")
    monkeypatch.setenv("GITHUB_OUTPUT", str(path))
    return path


def test_wkd_week_ahead_publishes_without_warning(schedules, tmp_path, caplog, gh_output):
    # 24.11.2026: WKD w API tylko do dziś+6, reszta do dziś+14 — rozkład ma się opublikować.
    caplog.set_level("INFO", logger="build_timetable")
    wkd_until(schedules, "2026-11-01")
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 0
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["valid_to"] == "2026-11-03"  # wg KM/SKM/ŁKA — WKD nie postarza danych
    assert "WKD 2026-11-01" in caplog.text
    assert outputs(gh_output) == {"publish": "true", "warnings": ""}


def test_wkd_short_publishes_with_warning(schedules, tmp_path, gh_output):
    wkd_until(schedules, "2026-10-28")  # dziś+2
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 0
    assert (out / "manifest.json").exists()
    o = outputs(gh_output)
    assert o["publish"] == "true"
    assert o["warnings"] == "WKD: kursy tylko do 2026-10-28 < dziś+3"


def test_missing_wkd_publishes_with_warning(schedules, tmp_path, gh_output):
    schedules["rt"] = [r for r in schedules["rt"] if r["cc"] != "WKD"]
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 0
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    db = tmp_path / "db.sqlite"
    db.write_bytes(gzip.decompress((out / manifest["url"]).read_bytes()))
    con = sqlite3.connect(db)
    meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
    assert {r[0] for r in con.execute("SELECT DISTINCT agency FROM trip")} == {"KM", "SKM", "LKA"}
    con.close()
    assert json.loads(meta["default_targets"]) == [[36103], [37440], [33571], [33506], [33605]]
    warnings = outputs(gh_output)["warnings"].split(" | ")
    assert "Brak kursów WKD w oknie dat — rozkład bez WKD" in warnings
    assert any("Warszawa Śródmieście WKD" in w for w in warnings)


def test_required_target_still_mandatory(schedules, tmp_path):
    schedules["dc"]["st"]["33571"]["nm"] = "Gdańsk Główny"  # Warszawa Śródmieście
    assert run(schedules, tmp_path, tmp_path / "out") == 1


# --------------------------------------------------------------------------- API


class FakeResponse(io.BytesIO):
    def __init__(self, payload: dict, gzipped: bool = True):
        body = json.dumps(payload).encode()
        super().__init__(gzip.compress(body) if gzipped else body)
        self.headers = {"Content-Encoding": "gzip"} if gzipped else {}

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


@pytest.fixture
def fake_api(monkeypatch, schedules):
    """Podmienia urlopen; zwraca listę wysłanych żądań."""
    calls = []

    def urlopen(req, timeout):
        calls.append(req)
        if req.full_url.startswith(bt.PDP_BASE + "/api/v1/data-version"):
            return FakeResponse({"schedulesVersion": "v-1"}, gzipped=False)
        return FakeResponse(schedules)

    monkeypatch.setattr(bt.urllib.request, "urlopen", urlopen)
    monkeypatch.setenv(bt.API_KEY_ENV, "tajny-klucz")
    return calls


def test_api_build_sends_key_only_in_header(fake_api, tmp_path, caplog):
    out = tmp_path / "out"
    assert bt.main(["--out", str(out), "--today", TODAY.isoformat()]) == 0
    sched = fake_api[-1]
    assert sched.get_header("X-api-key") == "tajny-klucz"
    assert "tajny-klucz" not in sched.full_url and "tajny-klucz" not in caplog.text
    assert "dateFrom=2026-10-25" in sched.full_url and "dateTo=2026-11-09" in sched.full_url
    assert "carriersInclude=KM%2CSKM%2C%C5%81KA%2CWKD" in sched.full_url
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["sources"] == {"pdp": {"schedules_version": "v-1"}}


FRESH = {"KM": "2026-11-09", "SKM": "2026-11-09", "LKA": "2026-11-09", "WKD": "2026-11-09"}


def test_api_unchanged_version_skips_build(fake_api, tmp_path):
    prev = tmp_path / "prev.json"
    prev.write_text(json.dumps({"schema_version": 1, "valid_to": "2026-11-09", "carriers_valid_to": FRESH,
                                "sources": {"pdp": {"schedules_version": "v-1"}}}), encoding="utf-8")
    out = tmp_path / "out"
    assert bt.main(["--out", str(out), "--today", TODAY.isoformat(), "--previous-manifest", str(prev)]) == 0
    assert len(fake_api) == 1  # tylko data-version
    assert not (out / "manifest.json").exists()


def test_api_unchanged_but_stale_rebuilds(fake_api, tmp_path):
    prev = tmp_path / "prev.json"
    prev.write_text(json.dumps({"schema_version": 1, "valid_to": "2026-11-03",
                                "sources": {"pdp": {"schedules_version": "v-1"}}}), encoding="utf-8")
    out = tmp_path / "out"
    assert bt.main(["--out", str(out), "--today", TODAY.isoformat(), "--previous-manifest", str(prev)]) == 0
    assert (out / "manifest.json").exists()


@pytest.mark.parametrize("carriers", [
    {**FRESH, "WKD": "2026-11-02"},                   # dane WKD się kończą (dziś+7 < dziś+12)
    {k: v for k, v in FRESH.items() if k != "WKD"},  # poprzednio bez WKD
    None,                                            # manifest sprzed tego pola
])
def test_api_unchanged_but_wkd_ending_rebuilds(fake_api, tmp_path, carriers):
    prev = tmp_path / "prev.json"
    m = {"schema_version": 1, "valid_to": "2026-11-09", "sources": {"pdp": {"schedules_version": "v-1"}}}
    if carriers is not None:
        m["carriers_valid_to"] = carriers
    prev.write_text(json.dumps(m), encoding="utf-8")
    out = tmp_path / "out"
    assert bt.main(["--out", str(out), "--today", TODAY.isoformat(), "--previous-manifest", str(prev)]) == 0
    assert (out / "manifest.json").exists()


def test_api_without_key_fails(monkeypatch, tmp_path):
    monkeypatch.delenv(bt.API_KEY_ENV, raising=False)
    assert bt.main(["--out", str(tmp_path / "out"), "--today", TODAY.isoformat()]) == 1


def test_api_client_error_not_retried(monkeypatch, tmp_path):
    calls = []

    def urlopen(req, timeout):
        calls.append(req)
        raise bt.urllib.error.HTTPError(req.full_url, 401, "Unauthorized", {}, None)

    monkeypatch.setattr(bt.urllib.request, "urlopen", urlopen)
    monkeypatch.setenv(bt.API_KEY_ENV, "zly-klucz")
    assert bt.main(["--out", str(tmp_path / "out"), "--today", TODAY.isoformat()]) == 1
    assert len(calls) == 1


def test_service_seconds_across_dst():
    assert bt.service_seconds(20261103, hms(17, 12)) == hms(17, 12)  # zwykły dzień
    assert bt.service_seconds(20261103, hms(24, 20)) == hms(24, 20)
    # 25.10.2026: zegar cofany o 03:00 CEST na 02:00 CET; „południe − 12 h" = 01:00 CEST
    assert bt.service_seconds(20261025, hms(1, 0)) == 0
    assert bt.service_seconds(20261025, hms(0, 30)) == -1800
    assert bt.service_seconds(20261025, hms(4, 0)) == hms(4, 0)
    assert bt.service_seconds(20261024, hms(28, 0)) == hms(29, 0)  # kurs z 24.10 po zmianie czasu
    # 29.03.2026: 02:00 CET → 03:00 CEST; „południe − 12 h" = 23:00 CET poprzedniego dnia
    assert bt.service_seconds(20260329, hms(1, 0)) == hms(2, 0)
    assert bt.service_seconds(20260329, hms(5, 0)) == hms(5, 0)


def test_dst_day_gets_own_trip(schedules, tmp_path):
    r = route(schedules, 1002)
    r["od"] = ["2026-10-25", "2026-10-31"]
    r["st"][0]["dtm"] = "00:30:00"
    out = tmp_path / "out"
    assert run(schedules, tmp_path, out) == 0
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    db = tmp_path / "db.sqlite"
    db.write_bytes(gzip.decompress((out / manifest["url"]).read_bytes()))
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    dst, normal = trip_id(con, 1002, 20261025), trip_id(con, 1002, 20261031)
    assert dst != normal
    assert stop_times(con, dst)[0]["dep_s"] == -1800
    assert stop_times(con, normal)[0]["dep_s"] == hms(0, 30)
    assert stop_times(con, dst)[1]["arr_s"] == stop_times(con, normal)[1]["arr_s"] == hms(24, 20)
    con.close()
