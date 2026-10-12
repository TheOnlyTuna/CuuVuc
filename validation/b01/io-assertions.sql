DO $$
BEGIN
    IF (SELECT count(*) FROM contract_block_journal)<>1
       OR (SELECT count(*) FROM contract_block_journal WHERE state='APPLIED')<>1
       OR (SELECT count(*) FROM building_history WHERE event_type='PLACE')<>1
       OR EXISTS(SELECT 1 FROM building_history WHERE event_type='BREAK')
       OR (SELECT count(*) FROM operations WHERE fingerprint LIKE 'contract:block:%')<>1 THEN
        RAISE EXCEPTION 'Sai số lượng journal/audit/receipt tại ranh giới I/O';
    END IF;
    IF (SELECT count(*) FROM contract_block_journal j JOIN operations o ON o.request_id=j.event_id
        JOIN building_history h ON h.event_id=j.audit_event_id
        WHERE j.state='APPLIED' AND o.actor_id=j.actor_id AND o.result<>'pending'
        AND o.fingerprint='contract:block:'||j.contract_id||':'||j.actor_id||':'||j.event_type||':'||j.position
        AND h.actor_id=j.actor_id AND h.contract_id=j.contract_id AND h.event_type=j.event_type AND h.detail=j.position)<>1 THEN
        RAISE EXCEPTION 'Proof journal/audit/receipt I/O không khớp';
    END IF;
    IF (SELECT count(*) FROM building_contracts WHERE status='ACTIVE')<>2
       OR (SELECT sum(balance) FROM profiles)<>0 OR (SELECT sum(balance) FROM sect_accounts)<>0
       OR (SELECT sum(balance) FROM contract_escrow_accounts)<>4
       OR (SELECT sum(minted) FROM economy_daily)<>4 OR (SELECT sum(burned) FROM economy_daily)<>0
       OR EXISTS(SELECT 1 FROM contract_escrow_ledger WHERE kind IN ('RELEASE','REFUND'))
       OR EXISTS(SELECT 1 FROM resource_ledger WHERE kind='CONSUME')
       OR EXISTS(SELECT 1 FROM operations WHERE fingerprint LIKE 'contract:complete:%')
       OR (SELECT count(*) FROM item_tokens)<>29
       OR (SELECT sum(quantity) FROM stage_inventory)<>4
       OR (SELECT coalesce(sum(quantity),0) FROM project_inventory)<>0 THEN
        RAISE EXCEPTION 'Tài sản hoặc settlement thay đổi tại ranh giới I/O';
    END IF;
    IF EXISTS(SELECT 1 FROM resource_ledger WHERE kind='TRANSFER' GROUP BY request_id,resource_id HAVING sum(delta)<>0) THEN
        RAISE EXCEPTION 'Resource Ledger không cân bằng';
    END IF;
END $$;
SELECT 'IO_JOURNAL_AUDIT_RECEIPT_ASSETS_PASS';
