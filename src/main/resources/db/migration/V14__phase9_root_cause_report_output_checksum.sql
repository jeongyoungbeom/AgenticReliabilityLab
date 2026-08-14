-- Keep V13 immutable for databases that have already migrated it.
alter table root_cause_report_run add column output_checksum varchar(128);
