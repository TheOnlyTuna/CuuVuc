\set ON_ERROR_STOP on
BEGIN READ ONLY;
-- Chỉ áp dụng fixture hai hợp đồng, trong đó sàn đã trả công còn đường ACTIVE.
DO $check$
BEGIN
    IF (SELECT count(*) FROM contract_escrow_ledger WHERE kind='RELEASE') IS DISTINCT FROM 1
       OR (SELECT sum(delta) FROM contract_escrow_ledger WHERE kind='RELEASE') IS DISTINCT FROM -2
       OR (SELECT count(*) FROM operations WHERE fingerprint LIKE 'contract:complete:%' AND result<>'pending') IS DISTINCT FROM 1
       OR (SELECT balance FROM profiles WHERE player_name='TyninWorld') IS DISTINCT FROM 2
       OR (SELECT sum(balance) FROM contract_escrow_accounts) IS DISTINCT FROM 2
       OR (SELECT sum(delta) FROM ledger)+(SELECT sum(delta) FROM contract_escrow_ledger) IS DISTINCT FROM 4
       OR (SELECT sum(minted-burned) FROM economy_daily) IS DISTINCT FROM 4
       OR (SELECT count(DISTINCT request_id) FROM resource_ledger WHERE kind='CONSUME') IS DISTINCT FROM 1
       OR (SELECT sum(delta) FROM resource_ledger WHERE kind='CONSUME' AND resource_id='linh_thach') IS DISTINCT FROM -2
       OR (SELECT sum(delta) FROM resource_ledger WHERE kind='CONSUME' AND resource_id='huyen_thiet') IS DISTINCT FROM -1
       OR (SELECT sum(quantity) FROM stage_inventory) IS DISTINCT FROM 1
       OR (SELECT sum(quantity) FROM project_inventory) IS DISTINCT FROM 0
       OR (SELECT count(*) FROM item_tokens) IS DISTINCT FROM 29
       OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='b01_pause') THEN
        RAISE EXCEPTION 'B01 settlement: receipt/ledger/escrow/nguyên liệu hoặc hook thử không khớp';
    END IF;
END
$check$;
SELECT 'B01_SETTLEMENT_ASSERTIONS_PASS' AS result;
SELECT request_id,kind,delta,balance_after FROM contract_escrow_ledger ORDER BY entry_id;
SELECT request_id,resource_id,delta FROM resource_ledger WHERE kind='CONSUME' ORDER BY resource_id;
COMMIT;
