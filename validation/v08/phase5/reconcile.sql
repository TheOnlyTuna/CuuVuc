\set ON_ERROR_STOP on
BEGIN READ ONLY;
-- Đối soát riêng phiên ba người; không tạo hoặc sửa dữ liệu nghiệp vụ.
DO $check$
BEGIN
    IF (SELECT count(*) FROM profiles) IS DISTINCT FROM 3
       OR (SELECT count(*) FROM sect_members) IS DISTINCT FROM 3
       OR (SELECT count(*) FROM projects) IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'Phiên nghiệm thu phải có ba hồ sơ/thành viên và một dự án';
    END IF;
    IF (SELECT count(*) FROM building_contracts WHERE status='COMPLETED') IS DISTINCT FROM 2
       OR (SELECT count(*) FROM building_contracts) IS DISTINCT FROM 2
       OR (SELECT count(*) FROM project_stages WHERE status='COMPLETED') IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION 'Hai hợp đồng và hạng mục phải hoàn tất';
    END IF;
    IF (SELECT balance FROM profiles WHERE player_name='TyninWorld') IS DISTINCT FROM 4
       OR (SELECT sum(balance) FROM profiles) IS DISTINCT FROM 4
       OR (SELECT sum(balance) FROM sect_accounts) IS DISTINCT FROM 0
       OR (SELECT sum(balance) FROM contract_escrow_accounts) IS DISTINCT FROM 0
       OR (SELECT sum(minted) FROM economy_daily) IS DISTINCT FROM 4
       OR (SELECT sum(burned) FROM economy_daily) IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'Tiền công, tổng tiền hoặc mint/burn không khớp 4 Linh Tệ';
    END IF;
    IF (SELECT sum(quantity) FROM project_inventory) IS DISTINCT FROM 0
       OR (SELECT sum(quantity) FROM stage_inventory) IS DISTINCT FROM 0
       OR (SELECT count(*) FROM resource_ledger WHERE kind='CONSUME') IS DISTINCT FROM 3
       OR (SELECT count(DISTINCT request_id) FROM resource_ledger WHERE kind='CONSUME') IS DISTINCT FROM 2
       OR (SELECT sum(delta) FROM resource_ledger WHERE kind='CONSUME' AND resource_id='linh_thach') IS DISTINCT FROM -3
       OR (SELECT sum(delta) FROM resource_ledger WHERE kind='CONSUME' AND resource_id='huyen_thiet') IS DISTINCT FROM -1
       OR EXISTS (SELECT 1 FROM resource_ledger WHERE kind='TRANSFER' GROUP BY request_id,resource_id HAVING sum(delta)<>0 OR count(*)<>2) THEN
        RAISE EXCEPTION 'Dự trữ, tiêu thụ hoặc cặp chuyển tài nguyên không khớp';
    END IF;
    IF (SELECT sum(quantity) FROM inventory WHERE resource_id='linh_thach_tho') IS DISTINCT FROM 22
       OR (SELECT sum(quantity) FROM inventory WHERE resource_id='huyen_thiet') IS DISTINCT FROM 7
       OR (SELECT sum(quantity) FROM inventory WHERE resource_id='linh_thach') IS DISTINCT FROM 0
       OR EXISTS (SELECT 1 FROM inventory i WHERE i.quantity<>(SELECT count(*) FROM item_tokens t WHERE t.player_id=i.player_id AND t.resource_id=i.resource_id))
       OR EXISTS (SELECT 1 FROM item_tokens t WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.player_id=t.player_id AND i.resource_id=t.resource_id AND i.quantity>0)) THEN
        RAISE EXCEPTION 'Kho và token sau góp/restore không khớp 22 raw + 7 iron';
    END IF;
    IF (SELECT treasury_spend FROM sect_members m JOIN profiles p USING(player_id) WHERE p.player_name='CuuVucNPC1') IS DISTINCT FROM false
       OR (SELECT count(*) FROM contract_escrow_ledger WHERE kind='RELEASE') IS DISTINCT FROM 2
       OR EXISTS (SELECT 1 FROM contract_escrow_ledger WHERE kind='REFUND')
       OR (SELECT count(*) FROM project_history WHERE event_type='CONSUME') IS DISTINCT FROM 2
       OR EXISTS (SELECT 1 FROM building_history WHERE event_type IN ('PLACE','BREAK')) THEN
        RAISE EXCEPTION 'Quyền, giải ngân hoặc lịch sử không khớp phạm vi dựng console';
    END IF;
END
$check$;
SELECT p.player_name,p.balance,p.inventory_revision,m.role,m.treasury_spend
FROM profiles p JOIN sect_members m USING(player_id) ORDER BY p.player_name;
SELECT p.player_name,i.resource_id,i.quantity,
       (SELECT count(*) FROM item_tokens t WHERE t.player_id=i.player_id AND t.resource_id=i.resource_id) AS tokens
FROM inventory i JOIN profiles p USING(player_id) ORDER BY p.player_name,i.resource_id;
TABLE sect_accounts;
SELECT contract_id,template_id,status,builder_id,reward FROM building_contracts ORDER BY template_id;
TABLE contract_escrow_accounts;
SELECT kind,count(*),sum(delta) FROM contract_escrow_ledger GROUP BY kind ORDER BY kind;
TABLE project_inventory;
TABLE stage_inventory;
SELECT kind,resource_id,count(*),sum(delta) FROM resource_ledger GROUP BY kind,resource_id ORDER BY kind,resource_id;
SELECT event_type,count(*) FROM project_history GROUP BY event_type ORDER BY event_type;
SELECT event_type,count(*) FROM sect_history GROUP BY event_type ORDER BY event_type;
SELECT event_type,count(*) FROM building_history GROUP BY event_type ORDER BY event_type;
SELECT request_id,player_id,sect_id,delta,balance_after,reason FROM ledger ORDER BY entry_id;
TABLE economy_daily;
SELECT request_id,kind,delta,balance_after FROM contract_escrow_ledger ORDER BY entry_id;
SELECT 'PHASE5_DB_ASSERTIONS_PASS' AS result;
COMMIT;
