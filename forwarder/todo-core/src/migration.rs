//! Explicit, quiesced transfer. The source is retained solely for cutover recovery.
use crate::{Error, Result};
use rusqlite::{Connection, OpenFlags, backup::Backup, types::Value};
use std::{fs, os::unix::fs::OpenOptionsExt, path::Path, time::Duration};

fn contents(connection: &Connection) -> Result<Vec<Vec<Value>>> {
    let mut rows = Vec::new();
    for query in [
        "SELECT type, name, tbl_name, sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",
        "SELECT * FROM tasks ORDER BY id",
        "SELECT * FROM notes ORDER BY id",
        "SELECT * FROM sqlite_sequence ORDER BY name",
    ] {
        let mut statement = connection.prepare(query)?;
        let width = statement.column_count();
        rows.extend(
            statement
                .query_map([], |row| {
                    (0..width)
                        .map(|i| row.get::<_, Value>(i))
                        .collect::<rusqlite::Result<Vec<_>>>()
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?,
        );
    }
    Ok(rows)
}

pub fn verify(connection: &Connection) -> Result<()> {
    let integrity: String = connection.query_row("PRAGMA integrity_check", [], |row| row.get(0))?;
    if integrity != "ok"
        || connection.query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))? != 1
    {
        return Err(Error::Invalid(
            "Todo database integrity or schema verification failed".into(),
        ));
    }
    let mut check = connection.prepare("PRAGMA foreign_key_check")?;
    if check.query([])?.next()?.is_some() {
        return Err(Error::Invalid(
            "Todo database foreign key verification failed".into(),
        ));
    }
    Ok(())
}

pub fn transfer(source: &Path, destination: &Path, quiesced: bool) -> Result<()> {
    if !quiesced || !source.is_absolute() || !destination.is_absolute() || source == destination {
        return Err(Error::Invalid(
            "Supply distinct absolute paths and --writers-quiesced after stopping Todo writers"
                .into(),
        ));
    }
    let staging = destination.with_extension("sqlite3.migrating");
    if destination.exists() || staging.exists() {
        return Err(Error::Invalid(
            "Destination or migration staging exists; inspect before recovery".into(),
        ));
    }
    // Lock the source against writes throughout the snapshot and verification. Do not
    // create or initialize a missing source. Operators must also stop future writers.
    let lock = Connection::open_with_flags(source, OpenFlags::SQLITE_OPEN_READ_WRITE)?;
    lock.busy_timeout(Duration::from_secs(5))?;
    lock.execute_batch("BEGIN IMMEDIATE")?;
    verify(&lock)?;
    let reader = Connection::open_with_flags(source, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let parent = destination
        .parent()
        .ok_or_else(|| Error::Invalid("Missing destination directory".into()))?;
    let mut builder = fs::DirBuilder::new();
    use std::os::unix::fs::DirBuilderExt;
    builder.recursive(true).mode(0o700).create(parent)?;
    let file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&staging)?;
    let result = (|| {
        let mut target = Connection::open(&staging)?;
        {
            let backup = Backup::new(&reader, &mut target)?;
            backup.run_to_completion(256, Duration::from_millis(10), None)?;
        }
        verify(&target)?;
        if contents(&reader)? != contents(&target)? {
            return Err(Error::Invalid(
                "Transferred Todo records do not match source".into(),
            ));
        }
        target
            .close()
            .map_err(|(_, error)| Error::Database(error))?;
        file.sync_all()?;
        // Publish without overwriting a destination that appeared during transfer.
        fs::hard_link(&staging, destination)?;
        fs::remove_file(&staging)?;
        fs::File::open(parent)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() && !destination.exists() && staging.exists() {
        fs::remove_file(&staging).map_err(|_| {
            Error::Invalid(format!(
                "Migration failed; staging remains at {}. Inspect it before recovery.",
                staging.display()
            ))
        })?;
    }
    if result.is_err() && staging.exists() {
        return Err(Error::Invalid(format!(
            "Migration publication needs inspection: destination {}, staging {}. No transfer was replayed.",
            destination.display(),
            staging.display()
        )));
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ArchiveFilter, Change, Status, Store};
    #[test]
    fn populated_transfer_preserves_all_records_and_rejects_overwrite() {
        let dir = tempfile::tempdir().unwrap();
        let source = dir.path().join("old.sqlite3");
        let dest = dir.path().join("new.sqlite3");
        let mut store = Store::open(&source).unwrap();
        let task = store
            .add_in_status("An idea", "- [ ] Think", Status::InProgress)
            .unwrap();
        store
            .change(task.summary.id, Some(1), Change::Note("A note".into()))
            .unwrap();
        store
            .change(task.summary.id, Some(2), Change::Archive(true))
            .unwrap();
        drop(store);
        assert!(transfer(&source, &dest, false).is_err());
        transfer(&source, &dest, true).unwrap();
        assert!(source.exists());
        let mut copied = Store::open(&dest).unwrap();
        assert_eq!(copied.show(1).unwrap().notes[0].text, "A note");
        assert_eq!(copied.show(1).unwrap().summary.revision, 3);
        assert_eq!(
            copied.list(None, ArchiveFilter::All, None).unwrap().len(),
            1
        );
        assert!(transfer(&source, &dest, true).is_err());
    }
    #[test]
    fn refuses_missing_source_and_interrupted_staging() {
        let dir = tempfile::tempdir().unwrap();
        let source = dir.path().join("old.sqlite3");
        let dest = dir.path().join("new.sqlite3");
        assert!(transfer(&source, &dest, true).is_err());
        assert!(!source.exists());
        Store::open(&source).unwrap();
        fs::write(dest.with_extension("sqlite3.migrating"), "interrupted").unwrap();
        assert!(transfer(&source, &dest, true).is_err());
        assert!(!dest.exists());
    }

    #[test]
    fn reverse_cutover_transfers_current_writes_instead_of_stale_source() {
        let dir = tempfile::tempdir().unwrap();
        let original = dir.path().join("original.sqlite3");
        let canonical = dir.path().join("canonical.sqlite3");
        Store::open(&original)
            .unwrap()
            .add("Before cutover", "")
            .unwrap();
        transfer(&original, &canonical, true).unwrap();
        let mut current = Store::open(&canonical).unwrap();
        current
            .change(1, Some(1), Change::Note("Written after cutover".into()))
            .unwrap();
        current
            .add_in_status("New idea", "Keep this too", Status::Done)
            .unwrap();
        drop(current);
        fs::remove_file(&original).unwrap();
        transfer(&canonical, &original, true).unwrap();
        let mut recovered = Store::open(&original).unwrap();
        assert_eq!(
            recovered.show(1).unwrap().notes[0].text,
            "Written after cutover"
        );
        assert_eq!(recovered.show(2).unwrap().summary.status, "Done");
        assert!(canonical.exists());
        assert!(!original.with_extension("sqlite3.migrating").exists());
    }
}
