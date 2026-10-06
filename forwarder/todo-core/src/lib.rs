pub mod migration;

pub const DEFAULT_DATABASE: &str = "/home/agent/.local/share/remote-codex/todo.sqlite3";
pub const RETIRED_DATABASE: &str = "/home/agent/.local/share/todo/tasks.sqlite3";

pub fn reject_retired_path(path: &std::path::Path) -> Result<()> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    let normalized: std::path::PathBuf =
        absolute
            .components()
            .fold(std::path::PathBuf::new(), |mut result, part| {
                match part {
                    std::path::Component::ParentDir => {
                        result.pop();
                    }
                    std::path::Component::CurDir => {}
                    _ => result.push(part.as_os_str()),
                };
                result
            });
    if normalized == std::path::Path::new(RETIRED_DATABASE)
        || std::fs::canonicalize(path).ok().as_deref()
            == Some(std::path::Path::new(RETIRED_DATABASE))
    {
        return Err(Error::Invalid("The old Todo database path is retired. Update Android or use the repo-owned todo CLI with its new default path.".into()));
    }
    Ok(())
}

use std::{fs, path::Path, time::Duration};

use rusqlite::{Connection, OpenFlags, OptionalExtension, TransactionBehavior, params};
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("{0}")]
    Invalid(String),
    #[error("Task {0} does not exist; use `todo list --all` to find a task.")]
    NotFound(i64),
    #[error(
        "Task {id} changed: expected revision {expected}, current revision {actual}. Read it again before editing."
    )]
    Conflict { id: i64, expected: i64, actual: i64 },
    #[error(
        "Database error: {0}. Check the database path and permissions; if busy, inspect the task before retrying a write."
    )]
    Database(#[from] rusqlite::Error),
    #[error("File error: {0}")]
    Io(#[from] std::io::Error),
}

impl Error {
    pub fn code(&self) -> &'static str {
        match self {
            Self::Invalid(_) => "invalid_input",
            Self::NotFound(_) => "not_found",
            Self::Conflict { .. } => "revision_conflict",
            Self::Database(_) => "database_error",
            Self::Io(_) => "io_error",
        }
    }

    pub fn exit_code(&self) -> u8 {
        match self {
            Self::Invalid(_) => 2,
            Self::NotFound(_) => 3,
            Self::Conflict { .. } => 4,
            Self::Database(_) => 5,
            Self::Io(_) => 6,
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, clap::ValueEnum)]
pub enum Status {
    #[serde(rename = "To Do")]
    #[value(name = "To Do", alias = "todo")]
    Todo,
    #[serde(rename = "In Progress")]
    #[value(name = "In Progress", alias = "in-progress")]
    InProgress,
    #[serde(rename = "Done")]
    #[value(name = "Done", alias = "done")]
    Done,
}

impl Status {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Todo => "To Do",
            Self::InProgress => "In Progress",
            Self::Done => "Done",
        }
    }
}

#[derive(Debug, Serialize)]
pub struct TaskSummary {
    pub id: i64,
    pub title: String,
    pub status: String,
    pub archived: bool,
    pub revision: i64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Serialize)]
pub struct Note {
    pub id: i64,
    pub text: String,
    pub created_at: String,
}

#[derive(Debug, Serialize)]
pub struct Task {
    #[serde(flatten)]
    pub summary: TaskSummary,
    pub description: String,
    pub notes: Vec<Note>,
}

#[derive(Clone, Copy, Default)]
pub enum ArchiveFilter {
    #[default]
    Active,
    Archived,
    All,
}

pub enum Change {
    Edit {
        title: Option<String>,
        description: Option<String>,
    },
    Move(Status),
    Note(String),
    Archive(bool),
}

impl Change {
    pub fn validate(&self) -> Result<()> {
        match self {
            Self::Edit { title, description } => {
                if title.is_none() && description.is_none() {
                    return Err(Error::Invalid(
                        "Supply --title, --description, or --description-file to edit a task."
                            .into(),
                    ));
                }
                if let Some(title) = title {
                    validate_title(title)?;
                }
            }
            Self::Note(text) if text.trim().is_empty() => {
                return Err(Error::Invalid("A note cannot be empty.".into()));
            }
            _ => {}
        }
        Ok(())
    }
}

pub fn validate_title(title: &str) -> Result<()> {
    if title.trim().is_empty() || title.chars().any(char::is_control) {
        return Err(Error::Invalid(
            "A title must contain text on one line, without control characters.".into(),
        ));
    }
    Ok(())
}

pub struct Store {
    connection: Connection,
}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        reject_retired_path(path)?;
        if path == Path::new(DEFAULT_DATABASE)
            && !path.exists()
            && Path::new(RETIRED_DATABASE).exists()
        {
            return Err(Error::Invalid(
                "Existing Todo data requires an explicit migration before opening the new database"
                    .into(),
            ));
        }
        if path.as_os_str().is_empty() || path.as_os_str() == ":memory:" {
            return Err(Error::Invalid(
                "Use a filesystem database path, not an empty path or :memory:.".into(),
            ));
        }
        // Only create missing directories/files. Never chmod an existing user directory.
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
            let mut builder = fs::DirBuilder::new();
            builder.recursive(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::DirBuilderExt;
                builder.mode(0o700);
            }
            builder.create(parent)?;
        }
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        match options.open(path) {
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error.into()),
        }
        // Paths are literal filenames, never SQLite URI options or in-memory stores.
        let mut connection = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        connection.busy_timeout(Duration::from_secs(5))?;
        connection.pragma_update(None, "foreign_keys", true)?;
        let version: i64 = connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
        if version == 0 {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            // Another process may have initialized the database while we waited.
            let version: i64 =
                transaction.pragma_query_value(None, "user_version", |row| row.get(0))?;
            if version == 0 {
                transaction.execute_batch(
                    "CREATE TABLE tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL CHECK(length(trim(title)) > 0),
                        description TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'To Do' CHECK(status IN ('To Do', 'In Progress', 'Done')),
                        archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0, 1)),
                        revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
                        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                        updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
                    );
                    CREATE TABLE notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id INTEGER NOT NULL REFERENCES tasks(id),
                        text TEXT NOT NULL CHECK(length(trim(text)) > 0),
                        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
                    );
                    CREATE INDEX notes_by_task ON notes(task_id, id);
                    PRAGMA user_version = 1;"
                )?;
            } else if version != 1 {
                return Err(Error::Invalid(format!(
                    "Unsupported database version {version}; this CLI supports version 1."
                )));
            }
            transaction.commit()?;
        } else if version != 1 {
            return Err(Error::Invalid(format!(
                "Unsupported database version {version}; this CLI supports version 1."
            )));
        }
        Ok(Self { connection })
    }

    pub fn add(&mut self, title: &str, description: &str) -> Result<Task> {
        self.add_in_status(title, description, Status::Todo)
    }

    pub fn add_in_status(
        &mut self,
        title: &str,
        description: &str,
        status: Status,
    ) -> Result<Task> {
        validate_title(title)?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
            "INSERT INTO tasks (title, description, status) VALUES (?1, ?2, ?3)",
            params![title.trim(), description, status.as_str()],
        )?;
        let task = read_task(&transaction, transaction.last_insert_rowid())?;
        transaction.commit()?;
        Ok(task)
    }

    pub fn show(&mut self, id: i64) -> Result<Task> {
        // Read the task and its notes from the same snapshot.
        let transaction = self.connection.transaction()?;
        let task = read_task(&transaction, id)?;
        transaction.commit()?;
        Ok(task)
    }

    pub fn list(
        &self,
        status: Option<Status>,
        archive: ArchiveFilter,
        search: Option<&str>,
    ) -> Result<Vec<TaskSummary>> {
        let archived = match archive {
            ArchiveFilter::Active => Some(false),
            ArchiveFilter::Archived => Some(true),
            ArchiveFilter::All => None,
        };
        let mut query = self.connection.prepare(
            "SELECT id, title, status, archived, revision, created_at, updated_at FROM tasks t
             WHERE (?1 IS NULL OR status = ?1) AND (?2 IS NULL OR archived = ?2)
             AND (?3 IS NULL OR instr(lower(title), lower(?3)) > 0
                  OR instr(lower(description), lower(?3)) > 0
                  OR EXISTS (SELECT 1 FROM notes n WHERE n.task_id = t.id AND instr(lower(n.text), lower(?3)) > 0))
             ORDER BY CASE status WHEN 'To Do' THEN 0 WHEN 'In Progress' THEN 1 ELSE 2 END, created_at, id"
        )?;
        let tasks = query.query_map(
            params![status.map(Status::as_str), archived, search],
            summary_from_row,
        )?;
        Ok(tasks.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub fn change(
        &mut self,
        id: i64,
        expected_revision: Option<i64>,
        change: Change,
    ) -> Result<Task> {
        change.validate()?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let current: i64 = transaction
            .query_row("SELECT revision FROM tasks WHERE id = ?1", [id], |row| {
                row.get(0)
            })
            .optional()?
            .ok_or(Error::NotFound(id))?;
        if let Some(expected) = expected_revision
            && expected != current
        {
            return Err(Error::Conflict {
                id,
                expected,
                actual: current,
            });
        }
        match change {
            Change::Edit { title, description } => {
                transaction.execute("UPDATE tasks SET title = COALESCE(?1, title), description = COALESCE(?2, description) WHERE id = ?3",
                    params![title.as_deref().map(str::trim), description, id])?;
            }
            Change::Move(status) => {
                transaction.execute(
                    "UPDATE tasks SET status = ?1 WHERE id = ?2",
                    params![status.as_str(), id],
                )?;
            }
            Change::Archive(archived) => {
                transaction.execute(
                    "UPDATE tasks SET archived = ?1 WHERE id = ?2",
                    params![archived, id],
                )?;
            }
            Change::Note(text) => {
                transaction.execute(
                    "INSERT INTO notes (task_id, text) VALUES (?1, ?2)",
                    params![id, text],
                )?;
            }
        }
        transaction.execute("UPDATE tasks SET revision = revision + 1, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?1", [id])?;
        let task = read_task(&transaction, id)?;
        transaction.commit()?;
        Ok(task)
    }
}

fn summary_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<TaskSummary> {
    Ok(TaskSummary {
        id: row.get(0)?,
        title: row.get(1)?,
        status: row.get(2)?,
        archived: row.get(3)?,
        revision: row.get(4)?,
        created_at: row.get(5)?,
        updated_at: row.get(6)?,
    })
}

fn read_task(connection: &Connection, id: i64) -> Result<Task> {
    let (summary, description) = connection.query_row(
        "SELECT id, title, status, archived, revision, created_at, updated_at, description FROM tasks WHERE id = ?1",
        [id], |row| Ok((summary_from_row(row)?, row.get::<_, String>(7)?))
    ).optional()?.ok_or(Error::NotFound(id))?;
    let mut query = connection
        .prepare("SELECT id, text, created_at FROM notes WHERE task_id = ?1 ORDER BY id")?;
    let notes = query
        .query_map([id], |row| {
            Ok(Note {
                id: row.get(0)?,
                text: row.get(1)?,
                created_at: row.get(2)?,
            })
        })?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(Task {
        summary,
        description,
        notes,
    })
}
