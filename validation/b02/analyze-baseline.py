from __future__ import annotations

import argparse
import csv
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import TypedDict


@dataclass(frozen=True)
class Row:
    kind: str
    ns: int
    duration: int
    identity: str
    contract: str
    actor: str
    action: str
    inflight: int
    saves: int
    phase: str
    detail: str


class Distribution(TypedDict):
    n: int
    p50: float | None
    p95: float | None
    p99: float | None
    maximum: float | None
    minimum: float | None


class Window(TypedDict):
    phase: str
    seconds: float
    mspt: Distribution
    tps: Distribution
    save_ms: Distribution
    submit_ms: Distribution
    approve_ms: Distribution
    request_to_save_ms: Distribution
    save_to_db_ms: Distribution
    db_to_response_ms: Distribution
    overhead_ms: Distribution
    peak_inflight: int
    peak_saves: int
    active_rows_metadata: int
    requests: int
    throughput_per_second: float
    duty_cycle: float


class Pooled(TypedDict):
    mspt: Distribution
    tps: Distribution
    save_ms: Distribution
    submit_ms: Distribution
    approve_ms: Distribution


class Result(TypedDict):
    mode: str
    rows: int
    windows: list[Window]
    calibration_delay_ms: float
    calibration_api_ms: float
    calibration_zero_windows: int
    calibration_outer_ms: Distribution
    calibration_delegate_ms: Distribution
    tick_boundary_delta_ms: Distribution
    verified_requests: int
    verified_saves: int
    probe_overhead_ms: Distribution
    pooled: dict[str, Pooled]


def read_rows(path: Path) -> list[Row]:
    with path.open(encoding="utf-8", newline="") as stream:
        values: list[Row] = []
        for line, fields in enumerate(csv.DictReader(stream, delimiter="\t"), 2):
            try:
                values.append(Row(fields["type"], int(fields["ns"]), int(fields["duration_ns"]), fields["id"],
                                  fields["contract"], fields["actor"], fields["action"], int(fields["inflight"]),
                                  int(fields["saves"]), fields["phase"], fields["detail"]))
            except (ValueError, TypeError, KeyError) as problem:
                raise ValueError(f"Dòng raw không hợp lệ path={path} line={line} fields={fields}") from problem
        return values


def distribution(values: list[float]) -> Distribution:
    ordered: list[float] = sorted(values)
    return Distribution(n=len(ordered), p50=rank(ordered, 0.5), p95=rank(ordered, 0.95), p99=rank(ordered, 0.99),
                        maximum=ordered[-1] if ordered else None, minimum=ordered[0] if ordered else None)


def rank(ordered: list[float], percentile: float) -> float | None:
    return ordered[math.ceil(percentile * len(ordered)) - 1] if ordered else None


def selected(rows: list[Row], kind: str) -> list[Row]:
    return [row for row in rows if row.kind == kind]


def unique(rows: list[Row], kind: str, identity: str) -> Row:
    found: list[Row] = [row for row in rows if row.kind == kind and row.identity == identity]
    if len(found) != 1:
        raise ValueError(f"Mất/trùng cặp kind={kind} id={identity} count={len(found)}")
    return found[0]


def tps(rows: list[Row], start: int, end: int) -> list[float]:
    ticks: list[Row] = selected(rows, "TICK_API")
    buckets: list[float] = [0.0] * ((end - start) // 1_000_000_000)
    for tick in ticks:
        offset: int = (tick.ns - start) // 1_000_000_000
        if 0 <= offset < len(buckets):
            buckets[offset] += 1
    return buckets


def verify(rows: list[Row], mode: str) -> tuple[int, int]:
    starts: list[Row] = selected(rows, "REQUEST_START")
    verified_saves: int = 0
    for start in starts:
        response: Row = unique(rows, "RESPONSE", start.identity)
        end: Row = unique(rows, "REQUEST_END", start.identity)
        if not start.ns <= response.ns <= end.ns or response.duration != response.ns - start.ns:
            raise ValueError(f"Timeline request sai id={start.identity}")
        if start.contract != response.contract or start.actor != response.actor or start.action != response.action:
            raise ValueError(f"Request correlation sai id={start.identity}")
        if mode == "full":
            save: Row = unique(rows, "SAVE", start.identity)
            delegate: Row = unique(rows, "SAVE_DELEGATE", start.identity)
            database: Row = unique(rows, "DB_RETURN", start.identity)
            if not start.ns <= save.ns - save.duration <= delegate.ns - delegate.duration <= delegate.ns <= save.ns <= database.ns <= response.ns:
                raise ValueError(f"Timeline save/delegate/commit sai id={start.identity}")
            verified_saves += 1
    if len(starts) != len(selected(rows, "RESPONSE")) or len(starts) != len(selected(rows, "REQUEST_END")):
        raise ValueError("Có response/end không có start")
    if mode == "full" and verified_saves != len(selected(rows, "SAVE")):
        raise ValueError("Có save không thuộc request đã nhận")
    return len(starts), verified_saves


def window(rows: list[Row], phase: str, mode: str) -> Window:
    subset: list[Row] = [row for row in rows if row.phase == phase]
    starts: list[Row] = selected(subset, "WINDOW_START")
    ends: list[Row] = selected(subset, "WINDOW_END")
    if len(starts) != 1 or len(ends) != 1:
        raise ValueError(f"Window chưa đầy đủ phase={phase}")
    start: int = starts[0].ns
    end: int = ends[0].ns
    samples: list[Row] = [row for row in subset if start <= row.ns <= end]
    tick_ids: list[int] = [int(row.identity) for row in sorted(selected(samples, "TICK_API"), key=lambda row: row.ns)]
    if not tick_ids or any(right != left + 1 for left, right in zip(tick_ids, tick_ids[1:])):
        raise ValueError(f"Mất/trùng mẫu tick trong window phase={phase}")
    requests: list[Row] = selected(samples, "REQUEST_START")
    phases_a: list[float] = []
    phases_b: list[float] = []
    phases_c: list[float] = []
    duty: int = 0
    for request in requests:
        response: Row = unique(samples, "RESPONSE", request.identity)
        finish: Row = unique(samples, "REQUEST_END", request.identity)
        duty += finish.ns - request.ns
        if mode == "full":
            save: Row = unique(samples, "SAVE", request.identity)
            database: Row = unique(samples, "DB_RETURN", request.identity)
            phases_a.append((save.ns - save.duration - request.ns) / 1_000_000)
            phases_b.append((database.ns - save.ns) / 1_000_000)
            phases_c.append((response.ns - database.ns) / 1_000_000)
    return Window(phase=phase, seconds=(end-start)/1_000_000_000,
                  mspt=distribution([row.duration/1_000_000 for row in selected(samples, "TICK_API")]),
                  tps=distribution(tps(samples, start, end)),
                  save_ms=distribution([row.duration/1_000_000 for row in selected(samples, "SAVE")]),
                  submit_ms=distribution([row.duration/1_000_000 for row in selected(samples, "RESPONSE") if row.action == "SUBMIT"]),
                  approve_ms=distribution([row.duration/1_000_000 for row in selected(samples, "RESPONSE") if row.action == "APPROVE"]),
                  request_to_save_ms=distribution(phases_a), save_to_db_ms=distribution(phases_b), db_to_response_ms=distribution(phases_c),
                  overhead_ms=distribution([row.duration/1_000_000 for row in selected(samples, "OVERHEAD")]),
                  peak_inflight=max(row.inflight for row in samples), peak_saves=max(row.saves for row in samples),
                  active_rows_metadata=int(starts[0].detail.split("active_rows=")[1]), requests=len(requests),
                  throughput_per_second=len(requests)/((end-start)/1_000_000_000), duty_cycle=duty/(end-start))


def pool(rows: list[Row]) -> dict[str, Pooled]:
    result: dict[str, Pooled] = {}
    for workload in ("idle", "clean", "dirty"):
        samples: list[Row] = [row for row in rows if row.phase.startswith(workload + "-") and row.phase.endswith("-measure")]
        buckets: list[float] = []
        for start in selected(samples, "WINDOW_START"):
            subset: list[Row] = [row for row in samples if row.phase == start.phase]
            end: Row = selected(subset, "WINDOW_END")[0]
            buckets.extend(tps(subset, start.ns, end.ns))
        result[workload] = Pooled(
            mspt=distribution([row.duration / 1_000_000 for row in selected(samples, "TICK_API")]),
            tps=distribution(buckets),
            save_ms=distribution([row.duration / 1_000_000 for row in selected(samples, "SAVE")]),
            submit_ms=distribution([row.duration / 1_000_000 for row in selected(samples, "RESPONSE") if row.action == "SUBMIT"]),
            approve_ms=distribution([row.duration / 1_000_000 for row in selected(samples, "RESPONSE") if row.action == "APPROVE"]))
    return result


def analyze(rows: list[Row], mode: str) -> Result:
    if not selected(rows, "RUN_COMPLETE"):
        raise ValueError("Thiếu RUN_COMPLETE; không xuất kết luận phiên chưa xong")
    requests, saves = verify(rows, mode)
    calibration: list[Row] = [row for row in rows if row.phase == "calibration"]
    delays: list[Row] = selected(calibration, "CAL_TICK")
    if len(delays) != 1 or delays[0].duration < 2_000_000_000:
        raise ValueError("Thiếu delay tick đã biết")
    api: list[Row] = [row for row in selected(calibration, "TICK_API") if row.duration >= delays[0].duration]
    if len(api) != 1:
        raise ValueError("API MSPT không phát hiện đúng delay calibration")
    phase_start: int = selected(calibration, "PHASE")[0].ns
    zero_windows: int = tps(calibration, phase_start, api[0].ns + 100_000_000).count(0.0)
    if zero_windows < 1:
        raise ValueError("TPS calibration không giữ cửa sổ không tick")
    delta: list[float] = []
    if mode == "full":
        api_index: dict[str, Row] = {row.identity: row for row in selected(rows, "TICK_API")}
        for tick in selected(rows, "TICK_METHOD"):
            if tick.identity in api_index:
                delta.append((tick.duration-api_index[tick.identity].duration)/1_000_000)
        delegates: list[Row] = [row for row in selected(calibration, "SAVE_DELEGATE") if not row.identity]
        outers: list[Row] = selected(calibration, "CAL_SAVE_OUTER")
        if len(delegates) != 5 or len(outers) != 5 or any(inner.duration > outer.duration for inner, outer in zip(delegates, outers, strict=True)):
            raise ValueError("Calibration save không khớp thời gian độc lập gọi bên ngoài")
    metrics: list[Window] = [window(rows, row.phase, mode) for row in selected(rows, "WINDOW_START") if row.phase.endswith("-measure")]
    if len(metrics) != 9 or any(value["peak_inflight"] != (0 if value["phase"].startswith("idle") else 1) for value in metrics):
        raise ValueError("Ma trận idle/đơn hoặc số request đồng thời không đúng")
    measured_overhead: Distribution = distribution([row.duration/1_000_000 for row in rows if row.kind == "OVERHEAD" and row.phase.endswith("-measure")])
    if measured_overhead["p99"] is None or measured_overhead["p99"] > 0.25:
        raise ValueError(f"Probe vượt ngân sách độ nhiễu p99={measured_overhead['p99']} ms")
    return Result(mode=mode, rows=len(rows), windows=metrics, pooled=pool(rows), calibration_delay_ms=delays[0].duration/1_000_000,
                  calibration_api_ms=api[0].duration/1_000_000, calibration_zero_windows=zero_windows,
                  calibration_outer_ms=distribution([row.duration/1_000_000 for row in selected(calibration, "CAL_SAVE_OUTER")]),
                  calibration_delegate_ms=distribution([row.duration/1_000_000 for row in selected(calibration, "SAVE_DELEGATE")]),
                  tick_boundary_delta_ms=distribution(delta), verified_requests=requests, verified_saves=saves,
                  probe_overhead_ms=measured_overhead)


def main() -> None:
    parser: argparse.ArgumentParser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--mode", choices=("control", "full"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments: argparse.Namespace = parser.parse_args()
    result: Result = analyze(read_rows(arguments.raw), arguments.mode)
    arguments.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"MEASUREMENT_QUALIFIED mode={result['mode']} windows={len(result['windows'])} requests={result['verified_requests']} saves={result['verified_saves']} overhead_p99_ms={result['probe_overhead_ms']['p99']}")


if __name__ == "__main__":
    main()
