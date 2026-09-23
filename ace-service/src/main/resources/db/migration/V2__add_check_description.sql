-- CheckId.java already carries a human-readable description per check id (the "single source of
-- truth" registry), but it was never persisted past import, so the UI's check-results grid always
-- showed an empty "check" column.
ALTER TABLE check_results ADD COLUMN description TEXT;
