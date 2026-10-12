SELECT event_id,event_type,position,state,audit_event_id,resolution_review_id FROM contract_block_journal ORDER BY prepared_at,event_id;
SELECT event_id,contract_id,actor_id,event_type,detail FROM building_history ORDER BY event_id;
SELECT review_id,actor_id,decision,reason FROM recovery_decisions ORDER BY created_at;
SELECT player_id,enabled,revision FROM recovery_authorities ORDER BY player_id;
SELECT contract_id,status,reward FROM building_contracts ORDER BY contract_id;
SELECT resource_id,quantity FROM inventory WHERE player_id='3bb5ddbc-5b75-377f-8647-6f25c51f252a' ORDER BY resource_id;
SELECT kind,count(*),sum(delta) FROM contract_escrow_ledger GROUP BY kind ORDER BY kind;
SELECT json_build_object('escrow',(SELECT sum(balance) FROM contract_escrow_accounts),'wallet',(SELECT sum(balance) FROM profiles),'sect',(SELECT sum(balance) FROM sect_accounts),'mint',(SELECT sum(minted) FROM economy_daily),'burn',(SELECT sum(burned) FROM economy_daily),'tokens',(SELECT count(*) FROM item_tokens),'reserve',(SELECT sum(quantity) FROM stage_inventory),'project',(SELECT coalesce(sum(quantity),0) FROM project_inventory));
DO $$ BEGIN
 IF (SELECT count(*) FROM contract_block_journal WHERE state='APPLIED')<>2
 OR (SELECT count(*) FROM contract_block_journal WHERE state='REVIEWED_UNCONFIRMED')<>2
 OR EXISTS(SELECT 1 FROM contract_block_journal WHERE state='INTENT')
 OR EXISTS(SELECT 1 FROM contract_recovery_fences)
 THEN RAISE EXCEPTION 'Journal hoặc fence không đúng sau thao tác client'; END IF;
 IF EXISTS(SELECT 1 FROM contract_block_journal j WHERE j.state='APPLIED' AND (
 NOT EXISTS(SELECT 1 FROM operations o WHERE o.request_id=j.event_id AND o.actor_id=j.actor_id AND o.result<>'pending' AND o.fingerprint='contract:block:'||j.contract_id||':'||j.actor_id||':'||j.event_type||':'||j.position)
 OR NOT EXISTS(SELECT 1 FROM building_history h WHERE h.event_id=j.audit_event_id AND h.contract_id=j.contract_id AND h.actor_id=j.actor_id AND h.event_type=j.event_type AND h.detail=j.position)))
 THEN RAISE EXCEPTION 'Thiếu audit/receipt chính xác cho APPLIED'; END IF;
 IF EXISTS(SELECT 1 FROM contract_block_journal j WHERE j.state='REVIEWED_UNCONFIRMED' AND (j.audit_event_id IS NOT NULL OR NOT EXISTS(SELECT 1 FROM recovery_decisions d JOIN recovery_reviews r USING(review_id) WHERE d.review_id=j.resolution_review_id AND d.decision='RESOLVE_INTENTS_AND_REOPEN' AND r.contract_id=j.contract_id)))
 THEN RAISE EXCEPTION 'Quyết định bị biến thành sự kiện hoặc thiếu liên kết'; END IF;
 IF (SELECT count(*) FROM recovery_decisions)<>3
 OR (SELECT count(*) FROM recovery_decisions WHERE actor_id='3bb5ddbc-5b75-377f-8647-6f25c51f252a')<>2
 OR (SELECT enabled FROM recovery_authorities WHERE player_id='3bb5ddbc-5b75-377f-8647-6f25c51f252a')
 THEN RAISE EXCEPTION 'Quyết định lặp hoặc quyền quản trị chưa thu hồi'; END IF;
 IF (SELECT sum(balance) FROM contract_escrow_accounts)<>4
 OR (SELECT sum(balance) FROM profiles)<>0 OR (SELECT sum(balance) FROM sect_accounts)<>0
 OR (SELECT sum(minted) FROM economy_daily)<>4 OR (SELECT sum(burned) FROM economy_daily)<>0
 OR (SELECT count(*) FROM building_contracts WHERE status='ACTIVE')<>2
 OR EXISTS(SELECT 1 FROM contract_escrow_ledger WHERE kind IN ('RELEASE','REFUND'))
 OR EXISTS(SELECT 1 FROM resource_ledger WHERE kind='CONSUME')
 THEN RAISE EXCEPTION 'Tài chính hoặc trạng thái hợp đồng thay đổi ngoài ý muốn'; END IF;
 IF (SELECT count(*) FROM item_tokens)<>30 OR (SELECT sum(quantity) FROM stage_inventory)<>4
 OR (SELECT quantity FROM inventory WHERE player_id='3bb5ddbc-5b75-377f-8647-6f25c51f252a' AND resource_id='linh_thach_tho')<>23
 OR (SELECT quantity FROM inventory WHERE player_id='3bb5ddbc-5b75-377f-8647-6f25c51f252a' AND resource_id='huyen_thiet')<>7
 THEN RAISE EXCEPTION 'Tài nguyên không khớp baseline sau lần đào hợp lệ'; END IF;
END $$;
SELECT 'CLIENT_RESOLUTION_ASSERTIONS_PASS';
