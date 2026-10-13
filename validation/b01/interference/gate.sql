\set ON_ERROR_STOP on
CREATE FUNCTION b01_interference_pause() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.fingerprint LIKE 'contract:block:%' AND NEW.result<>'pending' THEN
        PERFORM pg_sleep(2);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER b01_interference_gate BEFORE UPDATE ON operations
FOR EACH ROW EXECUTE FUNCTION b01_interference_pause();
