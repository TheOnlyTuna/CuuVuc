SELECT format($snapshot$
SELECT json_build_object('table',%L,'rows',count(*),'sha256',encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text COLLATE "C"),''),'UTF8')),'hex')) FROM %I.%I t;
$snapshot$,tablename,schemaname,tablename)
FROM pg_tables WHERE schemaname='public' ORDER BY tablename
\gexec
