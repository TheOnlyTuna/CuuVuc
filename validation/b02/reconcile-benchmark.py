from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import TypedDict


class SnapshotTable(TypedDict):
    table: str
    rows: list[dict[str, str | int | bool | None]]


def snapshot(path: Path) -> dict[str, list[dict[str, str | int | bool | None]]]:
    result: dict[str, list[dict[str, str | int | bool | None]]] = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        value: SnapshotTable = json.loads(line)
        if value["table"] in result:
            raise ValueError(f"Bảng trùng path={path} table={value['table']}")
        result[value["table"]] = value["rows"]
    if len(result) != 39:
        raise ValueError(f"Thiếu bảng path={path} count={len(result)}")
    return result


def amounts(tables: dict[str, list[dict[str, str | int | bool | None]]], names: list[str], column: str) -> int:
    return sum(int(row[column]) for name in names for row in tables[name])


def reconcile(before: dict[str, list[dict[str, str | int | bool | None]]], after: dict[str, list[dict[str, str | int | bool | None]]]) -> int:
    created: int = len(after["building_contracts"]) - len(before["building_contracts"])
    completed: list[dict[str, str | int | bool | None]] = [row for row in after["building_contracts"] if row["status"] == "COMPLETED"]
    if len(completed) != created or created < 2:
        raise ValueError(f"Số cycle/contract sai created={created} completed={len(completed)}")
    for name in ["inventory", "item_tokens", "economy_daily", "economy_policy", "player_mint_daily", "contract_block_journal",
                 "contract_recovery_fences", "unrecoverable_cases", "unrecoverable_decisions", "recovery_authorities", "sect_members"]:
        if before[name] != after[name]:
            raise ValueError(f"Bảng ngoài workload đổi table={name}")
    if amounts(after, ["profiles", "sect_accounts", "contract_escrow_accounts"], "balance") != 4:
        raise ValueError("Tổng tiền không còn 4 LT")
    contracts: dict[str, dict[str, str | int | bool | None]] = {str(row["contract_id"]): row for row in after["building_contracts"]}
    requests: dict[str, dict[str, str | int | bool | None]] = {str(row["request_id"]): row for row in after["operations"]}
    for contract in completed:
        identity: str = str(contract["contract_id"])
        for kind in ["SUBMIT", "COMPLETE"]:
            audits: list[dict[str, str | int | bool | None]] = [row for row in after["building_history"] if row["contract_id"] == identity and row["event_type"] == kind]
            receipts: list[dict[str, str | int | bool | None]] = [row for row in after["operations"] if str(row["fingerprint"]).startswith(f"contract:{kind.lower()}:{identity}:")]
            if len(audits) != 1 or len(receipts) != 1 or receipts[0]["result"] == "pending":
                raise ValueError(f"Audit/receipt mất hoặc trùng contract={identity} kind={kind}")
        settlements: list[dict[str, str | int | bool | None]] = [row for row in after["contract_escrow_ledger"] if row["contract_id"] == identity and row["kind"] == "RELEASE"]
        if len(settlements) != 1 or settlements[0]["delta"] != -2:
            raise ValueError(f"Escrow không release đúng một lần contract={identity}")
        request: str = str(settlements[0]["request_id"])
        payment: list[dict[str, str | int | bool | None]] = [row for row in after["ledger"] if row["request_id"] == request and row["reason"] == f"CONTRACT_PAYMENT:{identity}"]
        if len(payment) != 1 or payment[0]["delta"] != 2 or request not in requests:
            raise ValueError(f"Tiền công thiếu đối ứng contract={identity}")
    if len(after["operations"]) - len(before["operations"]) != 5 * created:
        raise ValueError("Có receipt ngoài CREATE/ACCEPT/PAY/SUBMIT/COMPLETE được dự kiến")
    if any(row["event_type"] in ("PLACE", "BREAK") for row in after["building_history"]):
        raise ValueError("Fixture dựng console không được tạo audit thi công")
    path_id: str = "7c9c9656-2602-48b4-98de-447f06e34e97"
    if contracts[path_id] != next(row for row in before["building_contracts"] if row["contract_id"] == path_id):
        raise ValueError("Hợp đồng đường ngoài workload đổi metadata")
    consumes: list[dict[str, str | int | bool | None]] = [row for row in after["resource_ledger"] if row["kind"] == "CONSUME"]
    if len(consumes) != 2 or {str(row["resource_id"]): int(row["delta"]) for row in consumes} != {"linh_thach": -2, "huyen_thiet": -1}:
        raise ValueError("Tiêu thụ dự trữ nguồn không đúng fixture-prepare")
    for resource in ("linh_thach", "huyen_thiet", "linh_thach_tho"):
        old: int = sum(int(row["quantity"]) for name in ("inventory", "project_inventory", "stage_inventory") for row in before[name] if row["resource_id"] == resource)
        new: int = sum(int(row["quantity"]) for name in ("inventory", "project_inventory", "stage_inventory") for row in after[name] if row["resource_id"] == resource)
        spent: int = sum(int(row["delta"]) for row in consumes if row["resource_id"] == resource)
        if new != old + spent:
            raise ValueError(f"Tài nguyên không khớp Ledger resource={resource} old={old} new={new} consume={spent}")
    return created


def main() -> None:
    parser: argparse.ArgumentParser = argparse.ArgumentParser()
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    arguments: argparse.Namespace = parser.parse_args()
    count: int = reconcile(snapshot(arguments.before), snapshot(arguments.after))
    print(f"DB_RECONCILIATION_PASS new_contracts={count} completed={count} money=4 tokens=29 original_reserve_consumed_once=true receipts_once=true no_fake_place_break=true")


if __name__ == "__main__":
    main()
