SET search_path TO cv_client_v051;
SELECT 'completed_once',
    (SELECT count(*) FROM building_contracts WHERE contract_id='5aa2208b-9d12-4861-b5f8-b165e422a27e' AND status='COMPLETED' AND reward=2)=1
    AND (SELECT count(*) FROM ledger WHERE reason='CONTRACT_PAYMENT:5aa2208b-9d12-4861-b5f8-b165e422a27e')=1
    AND (SELECT count(*) FROM building_history WHERE event_type='COMPLETE')=1;
SELECT 'escrow_settled_once',
    (SELECT sum(balance) FROM contract_escrow_accounts)=0
    AND (SELECT count(*) FROM contract_escrow_ledger WHERE kind='RELEASE')=1
    AND (SELECT count(*) FROM contract_escrow_ledger WHERE kind='REFUND')=0
    AND (SELECT sum(delta) FROM contract_escrow_ledger)=0;
SELECT 'wallets',
    (SELECT balance FROM profiles WHERE player_id='3bb5ddbc-5b75-377f-8647-6f25c51f252a')=0
    AND (SELECT balance FROM profiles WHERE player_id='1194cfbc-1195-338c-9eb1-0c49dd118ae3')=2;
SELECT 'currency_conserved',
    (SELECT sum(balance) FROM profiles)+(SELECT sum(balance) FROM contract_escrow_accounts)
    =(SELECT sum(minted-burned) FROM economy_daily)
    AND (SELECT sum(minted) FROM economy_daily)=30 AND (SELECT sum(burned) FROM economy_daily)=20;
SELECT 'ownership_and_no_permanent_grant',
    (SELECT count(*) FROM islands WHERE player_id='3bb5ddbc-5b75-377f-8647-6f25c51f252a' AND slot=3 AND ready)=1
    AND (SELECT count(*) FROM island_builders)=0;
SELECT 'builder_placed_all_nine',
    (SELECT count(DISTINCT detail) FROM building_history WHERE event_type='PLACE' AND actor_id='1194cfbc-1195-338c-9eb1-0c49dd118ae3')=9
    AND (SELECT count(*) FROM building_history WHERE event_type='BREAK')=0;
