\set ON_ERROR_STOP on
BEGIN READ ONLY;
-- Đối soát riêng ca xây tay; không suy diễn audit từ block ngoài world.
DO $check$
BEGIN
    IF (SELECT count(*) FROM building_contracts WHERE status='COMPLETED') IS DISTINCT FROM 2
       OR (SELECT balance FROM profiles WHERE player_name='TyninWorld') IS DISTINCT FROM 4
       OR (SELECT sum(balance) FROM contract_escrow_accounts) IS DISTINCT FROM 0
       OR (SELECT count(*) FROM island_builders) IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'Hợp đồng, tiền công hoặc quyền xây vĩnh viễn không khớp';
    END IF;
    IF (SELECT count(*) FROM building_history WHERE event_type='PLACE') IS DISTINCT FROM 15
       OR (SELECT count(*) FROM building_history WHERE event_type='BREAK') IS DISTINCT FROM 3
       OR EXISTS (SELECT 1 FROM building_history h JOIN building_contracts c USING(contract_id)
                  WHERE h.event_type IN ('PLACE','BREAK') AND h.actor_id IS DISTINCT FROM c.builder_id) THEN
        RAISE EXCEPTION 'Audit phải có 15 PLACE/3 BREAK của đúng Builder';
    END IF;
    IF (SELECT count(DISTINCT (contract_id,detail)) FROM building_history WHERE event_type IN ('PLACE','BREAK')) IS DISTINCT FROM 12
       OR EXISTS (SELECT 1 FROM building_history WHERE event_type IN ('PLACE','BREAK')
                  GROUP BY contract_id,detail HAVING sum(CASE WHEN event_type='PLACE' THEN 1 ELSE -1 END)<>1)
       OR EXISTS (SELECT 1 FROM building_history h JOIN building_contracts c USING(contract_id)
                  WHERE h.event_type IN ('PLACE','BREAK')
                  AND NOT ((c.template_id='STONE_PAD_V1' AND h.detail ~ '^(67|68|69):100:(3|4|5)$')
                           OR (c.template_id='STONE_PATH_V1' AND h.detail ~ '^(74|75|76):100:11$'))) THEN
        RAISE EXCEPTION 'Audit tọa độ hoặc tổng PLACE trừ BREAK không khớp 12 ô';
    END IF;
    IF EXISTS (SELECT 1 FROM building_history h WHERE h.event_type IN ('PLACE','BREAK')
               AND h.event_id > (SELECT min(s.event_id) FROM building_history s WHERE s.contract_id=h.contract_id AND s.event_type='SUBMIT'))
       OR EXISTS (SELECT 1 FROM building_history h WHERE h.event_type IN ('PLACE','BREAK')
                  AND h.event_id < (SELECT min(a.event_id) FROM building_history a WHERE a.contract_id=h.contract_id AND a.event_type='ACCEPT')) THEN
        RAISE EXCEPTION 'Audit block nằm ngoài giai đoạn ACTIVE';
    END IF;
END
$check$;
SELECT event_id,contract_id,actor_id,event_type,detail,created_at FROM building_history ORDER BY event_id;
SELECT c.template_id,h.detail,
       count(*) FILTER (WHERE event_type='PLACE') AS placements,
       count(*) FILTER (WHERE event_type='BREAK') AS breaks,
       sum(CASE WHEN event_type='PLACE' THEN 1 ELSE -1 END) AS remaining
FROM building_history h JOIN building_contracts c USING(contract_id)
WHERE event_type IN ('PLACE','BREAK') GROUP BY c.template_id,h.detail ORDER BY c.template_id,h.detail;
SELECT 'SURVIVAL_AUDIT_ASSERTIONS_PASS' AS result;
COMMIT;
