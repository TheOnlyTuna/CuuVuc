\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT event_id,actor_id,event_type,position,state,audit_event_id FROM contract_block_journal ORDER BY prepared_at,event_id;
SELECT contract_id,classification,reason FROM contract_recovery_fences ORDER BY contract_id;
SELECT event_id,event_type,detail FROM building_history WHERE event_type IN ('PLACE','BREAK') ORDER BY event_id;
SELECT c.contract_id,c.status,c.reward,e.balance FROM building_contracts c JOIN contract_escrow_accounts e USING(contract_id) ORDER BY c.template_id;
DO $$
BEGIN
    IF (SELECT count(*) FROM contract_block_journal WHERE state='APPLIED')<>8
       OR (SELECT count(*) FROM contract_block_journal WHERE state='INTENT')<>1
       OR (SELECT count(*) FROM contract_block_journal WHERE state='ABORTED')<>2 THEN
        RAISE EXCEPTION 'Journal native khác 8 APPLIED / 1 INTENT / 2 ABORTED';
    END IF;
    IF (SELECT count(*) FROM building_history WHERE event_type='PLACE')<>4
       OR (SELECT count(*) FROM building_history WHERE event_type='BREAK')<>4
       OR (SELECT count(*) FROM operations WHERE fingerprint LIKE 'contract:block:%')<>8 THEN
        RAISE EXCEPTION 'Audit/receipt native không giữ đúng 4 PLACE / 4 BREAK';
    END IF;
    IF (SELECT count(*) FROM contract_block_journal j JOIN operations o ON o.request_id=j.event_id
        JOIN building_history h ON h.event_id=j.audit_event_id
        WHERE j.state='APPLIED' AND o.actor_id=j.actor_id AND o.result<>'pending'
        AND o.fingerprint='contract:block:'||j.contract_id||':'||j.actor_id||':'||j.event_type||':'||j.position
        AND h.actor_id=j.actor_id AND h.contract_id=j.contract_id AND h.event_type=j.event_type AND h.detail=j.position)<>8 THEN
        RAISE EXCEPTION 'Thiếu proof journal/audit/receipt';
    END IF;
    IF (SELECT count(*) FROM contract_recovery_fences)<>1
       OR NOT EXISTS (SELECT 1 FROM contract_recovery_fences WHERE contract_id='279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3' AND classification='INCOMPLETE') THEN
        RAISE EXCEPTION 'Fence phải chỉ khóa hợp đồng sàn có INTENT chưa phân giải';
    END IF;
    IF (SELECT count(*) FROM building_contracts WHERE status='ACTIVE')<>2
       OR (SELECT sum(balance) FROM profiles)<>0 OR (SELECT sum(balance) FROM sect_accounts)<>0
       OR (SELECT sum(balance) FROM contract_escrow_accounts)<>4
       OR (SELECT sum(minted) FROM economy_daily)<>4 OR (SELECT sum(burned) FROM economy_daily)<>0 THEN
        RAISE EXCEPTION 'Sai trạng thái hợp đồng hoặc bảo toàn Linh Tệ';
    END IF;
    IF (SELECT count(*) FROM contract_escrow_ledger WHERE kind='DEPOSIT')<>2
       OR EXISTS(SELECT 1 FROM contract_escrow_ledger WHERE kind IN ('RELEASE','REFUND'))
       OR EXISTS(SELECT 1 FROM resource_ledger WHERE kind='CONSUME')
       OR (SELECT count(*) FROM item_tokens)<>29
       OR (SELECT sum(quantity) FROM stage_inventory)<>4
       OR (SELECT coalesce(sum(quantity),0) FROM project_inventory)<>0 THEN
        RAISE EXCEPTION 'Escrow, nguyên liệu dự trữ hoặc token thay đổi ngoài ý muốn';
    END IF;
    IF EXISTS(SELECT 1 FROM resource_ledger WHERE kind='TRANSFER' GROUP BY request_id,resource_id HAVING sum(delta)<>0) THEN
        RAISE EXCEPTION 'Resource Ledger TRANSFER không cân bằng';
    END IF;
END $$;
SELECT 'NATIVE_JOURNAL_FENCE_ASSETS_ASSERTIONS_PASS';
COMMIT;
