SET search_path TO cv_client_v051;
SELECT format('SELECT jsonb_build_object(%L, coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), ''[]''::jsonb)) FROM %I t;', tablename, tablename)
FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename COLLATE "C"
\gexec
