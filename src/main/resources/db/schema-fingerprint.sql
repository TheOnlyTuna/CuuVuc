WITH tables AS (
    SELECT c.oid, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname=current_schema() AND c.relkind IN ('r','p') AND c.relname <> 'migration_history'
), parts AS (
    SELECT 'column|' || t.relname || '|' || a.attnum || '|' || a.attname || '|' ||
           format_type(a.atttypid,a.atttypmod) || '|' || a.attnotnull || '|' || a.attidentity::text || '|' || a.attgenerated::text || '|' ||
           coalesce(pg_get_expr(d.adbin,d.adrelid),'') AS part
    FROM tables t JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum>0 AND NOT a.attisdropped
    LEFT JOIN pg_attrdef d ON d.adrelid=t.oid AND d.adnum=a.attnum
    UNION ALL
    SELECT 'constraint|' || t.relname || '|' || c.conname || '|' || pg_get_constraintdef(c.oid) || '|' || c.convalidated
    FROM tables t JOIN pg_constraint c ON c.conrelid=t.oid
    UNION ALL
    SELECT 'index|' || t.relname || '|' || pg_get_indexdef(i.indexrelid) || '|' || i.indisvalid || '|' || i.indisready
    FROM tables t JOIN pg_index i ON i.indrelid=t.oid
    UNION ALL
    SELECT 'trigger|' || t.relname || '|' || pg_get_triggerdef(g.oid) || '|' || g.tgenabled::text
    FROM tables t JOIN pg_trigger g ON g.tgrelid=t.oid WHERE NOT g.tgisinternal
    UNION ALL
    SELECT 'function|' || pg_get_functiondef(p.oid)
    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname=current_schema() AND p.proname='reject_ledger_edit'
)
SELECT encode(sha256(convert_to(string_agg(replace(part, current_schema() || '.', ''), E'\n' ORDER BY part COLLATE "C"), 'UTF8')), 'hex') FROM parts;
