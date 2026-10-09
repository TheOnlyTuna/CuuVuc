CREATE FUNCTION cv_client_v051.test_shutdown_delay() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(3); RETURN NEW; END; $$;
CREATE TRIGGER test_shutdown_delay BEFORE UPDATE OF balance ON cv_client_v051.profiles FOR EACH ROW EXECUTE FUNCTION cv_client_v051.test_shutdown_delay();
