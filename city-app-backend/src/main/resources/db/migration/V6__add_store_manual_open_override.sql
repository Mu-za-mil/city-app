-- Nullable means the store follows its configured operating hours.
-- TRUE/FALSE records an explicit seller override until the schedule reaches that state.
ALTER TABLE stores
    ADD COLUMN manual_open_override BOOLEAN;
