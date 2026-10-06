use std::{
    env, fs,
    io::{self, Read, Write},
    path::PathBuf,
    process::ExitCode,
};

use clap::{Args, Parser, Subcommand, error::ErrorKind};
use remote_codex_todo::{
    ArchiveFilter, Change, Error, Result, Status, Store, Task, TaskSummary, validate_title,
};
use serde_json::{Value, json};

#[derive(Parser)]
#[command(
    name = "todo",
    version,
    about = "Local tasks, with no running service",
    after_help = "Writes are never automatically retried. If a response is lost, inspect the task before repeating a write."
)]
struct Cli {
    /// Database file (default: $XDG_DATA_HOME/remote-codex/todo.sqlite3 or ~/.local/share/remote-codex/todo.sqlite3)
    #[arg(long, global = true, env = "TODO_DB", value_name = "PATH")]
    db: Option<PathBuf>,
    /// Emit versioned JSON (errors go to stderr)
    #[arg(long, global = true)]
    json: bool,
    #[command(subcommand)]
    command: Command,
}

#[derive(Args)]
struct Description {
    /// Markdown description; an empty string clears it on edit
    #[arg(long, conflicts_with = "description_file", allow_hyphen_values = true)]
    description: Option<String>,
    /// Read a UTF-8 Markdown description from FILE, or - for stdin
    #[arg(long, value_name = "FILE")]
    description_file: Option<PathBuf>,
}

impl Description {
    fn read(self) -> Result<Option<String>> {
        match self.description_file {
            Some(path) => Ok(Some(read_text(path)?)),
            None => Ok(self.description),
        }
    }
}

#[derive(Args)]
struct Target {
    #[arg(value_parser = clap::value_parser!(i64).range(1..))]
    id: i64,
    /// Reject this write if the task has changed since this revision
    #[arg(long, value_parser = clap::value_parser!(i64).range(1..))]
    expect_revision: Option<i64>,
}

#[derive(Subcommand)]
enum Command {
    /// Transfer a database while writers are stopped; retain source for recovery.
    Migrate {
        #[arg(long)]
        from: PathBuf,
        #[arg(long)]
        to: PathBuf,
        #[arg(long)]
        writers_quiesced: bool,
    },
    /// Create a task in To Do
    Add {
        title: String,
        #[command(flatten)]
        description: Description,
    },
    /// List compact tasks, grouped by status and oldest first within each status
    List {
        #[arg(long, value_enum, ignore_case = true)]
        status: Option<Status>,
        /// Literal substring search across title, description, and notes (ASCII case-insensitive)
        #[arg(long)]
        search: Option<String>,
        /// Only show archived tasks
        #[arg(long, conflicts_with = "all")]
        archived: bool,
        /// Include both archived and unarchived tasks
        #[arg(long)]
        all: bool,
    },
    /// Read a complete task, including its notes (also works for archived tasks)
    Show {
        #[arg(value_parser = clap::value_parser!(i64).range(1..))]
        id: i64,
    },
    /// Change only supplied fields
    Edit {
        #[command(flatten)]
        target: Target,
        #[arg(long)]
        title: Option<String>,
        #[command(flatten)]
        description: Description,
    },
    /// Change status; accepts quoted names or todo/in-progress/done
    Move {
        #[command(flatten)]
        target: Target,
        #[arg(value_enum, ignore_case = true)]
        status: Status,
    },
    /// Append a note, preserving existing notes
    Note {
        #[command(flatten)]
        target: Target,
        #[arg(required_unless_present = "file", conflicts_with = "file")]
        text: Option<String>,
        /// Read a UTF-8 note from FILE, or - for stdin
        #[arg(long, value_name = "FILE")]
        file: Option<PathBuf>,
    },
    /// Hide a task from the normal list without deleting it
    Archive {
        #[command(flatten)]
        target: Target,
    },
    /// Return an archived task to the normal list, preserving its status
    Restore {
        #[command(flatten)]
        target: Target,
    },
}

enum Output {
    Task(Task),
    List(Vec<TaskSummary>),
    Migrated,
}

impl Output {
    fn json(&self) -> Value {
        match self {
            Self::Migrated => json!({"schema_version":1, "ok":true, "kind":"migration"}),
            Self::Task(task) => {
                json!({ "schema_version": 1, "ok": true, "kind": "task", "task": task })
            }
            Self::List(tasks) => {
                json!({ "schema_version": 1, "ok": true, "kind": "task-list", "tasks": tasks })
            }
        }
    }

    fn print(&self, writer: &mut impl Write) -> io::Result<()> {
        match self {
            Self::Migrated => writeln!(
                writer,
                "Transferred and verified Todo database; source retained for cutover recovery."
            )?,
            Self::List(tasks) => {
                if tasks.is_empty() {
                    writeln!(writer, "No tasks.")?;
                }
                let mut previous_status = "";
                for task in tasks {
                    if previous_status != task.status {
                        if !previous_status.is_empty() {
                            writeln!(writer)?;
                        }
                        writeln!(writer, "{}", task.status)?;
                        previous_status = &task.status;
                    }
                    writeln!(
                        writer,
                        "  #{}  {}{}  (r{})",
                        task.id,
                        safe_text(&task.title),
                        if task.archived { " [archived]" } else { "" },
                        task.revision
                    )?;
                }
            }
            Self::Task(task) => {
                let t = &task.summary;
                writeln!(writer, "#{}  {}", t.id, safe_text(&t.title))?;
                writeln!(
                    writer,
                    "{}{} | revision {}",
                    t.status,
                    if t.archived { " [archived]" } else { "" },
                    t.revision
                )?;
                writeln!(
                    writer,
                    "Created: {} | Updated: {}",
                    t.created_at, t.updated_at
                )?;
                if !task.description.is_empty() {
                    writeln!(writer, "\n{}", safe_text(&task.description))?;
                }
                if !task.notes.is_empty() {
                    writeln!(writer, "\nNotes")?;
                    for note in &task.notes {
                        writeln!(
                            writer,
                            "[{}] {}\n{}",
                            note.id,
                            note.created_at,
                            safe_text(&note.text)
                        )?;
                    }
                }
            }
        }
        Ok(())
    }
}

// Keep stored Markdown intact, but never execute terminal control sequences when printing it.
fn safe_text(text: &str) -> String {
    text.chars()
        .filter(|c| !c.is_control() || matches!(c, '\n' | '\t'))
        .collect()
}

fn read_text(path: PathBuf) -> Result<String> {
    if path.as_os_str() == "-" {
        let mut text = String::new();
        io::stdin().read_to_string(&mut text)?;
        Ok(text)
    } else {
        fs::read_to_string(&path).map_err(|error| {
            Error::Io(io::Error::new(
                error.kind(),
                format!("{}: {error}", path.display()),
            ))
        })
    }
}

fn database_path(explicit: Option<PathBuf>) -> Result<PathBuf> {
    if let Some(path) = explicit {
        if path.as_os_str().is_empty() {
            return Err(Error::Invalid("The database path cannot be empty.".into()));
        }
        remote_codex_todo::reject_retired_path(&path)?;
        return Ok(path);
    }
    let data_home = env::var_os("XDG_DATA_HOME").filter(|v| !v.is_empty());
    let root = match data_home {
        Some(value) => {
            let path = PathBuf::from(value);
            if !path.is_absolute() {
                return Err(Error::Invalid(
                    "XDG_DATA_HOME must be absolute, or use --db PATH.".into(),
                ));
            }
            path
        }
        None => PathBuf::from(
            env::var_os("HOME")
                .filter(|v| !v.is_empty())
                .ok_or_else(|| Error::Invalid("HOME is unset; supply --db PATH.".into()))?,
        )
        .join(".local/share"),
    };
    Ok(root.join("remote-codex/todo.sqlite3"))
}

fn run(cli: Cli) -> Result<Output> {
    // Transfer paths are explicit; ambient legacy TODO_DB/HOME must not select
    // or initialize another database while performing a cutover.
    let command = match cli.command {
        Command::Migrate {
            from,
            to,
            writers_quiesced,
        } => {
            remote_codex_todo::migration::transfer(&from, &to, writers_quiesced)?;
            return Ok(Output::Migrated);
        }
        command => command,
    };
    let path = database_path(cli.db)?;
    match command {
        Command::Add { title, description } => {
            validate_title(&title)?;
            let description = description.read()?.unwrap_or_default();
            Ok(Output::Task(Store::open(&path)?.add(&title, &description)?))
        }
        Command::List {
            status,
            search,
            archived,
            all,
        } => {
            let archive = if all {
                ArchiveFilter::All
            } else if archived {
                ArchiveFilter::Archived
            } else {
                ArchiveFilter::Active
            };
            Ok(Output::List(Store::open(&path)?.list(
                status,
                archive,
                search.as_deref(),
            )?))
        }
        Command::Show { id } => Ok(Output::Task(Store::open(&path)?.show(id)?)),
        command => {
            let (target, change) = match command {
                Command::Edit {
                    target,
                    title,
                    description,
                } => (
                    target,
                    Change::Edit {
                        title,
                        description: description.read()?,
                    },
                ),
                Command::Move { target, status } => (target, Change::Move(status)),
                Command::Note { target, text, file } => {
                    let text = match (text, file) {
                        (Some(text), None) => text,
                        (None, Some(path)) => read_text(path)?,
                        _ => return Err(Error::Invalid("Supply note text or --file FILE.".into())),
                    };
                    (target, Change::Note(text))
                }
                Command::Archive { target } => (target, Change::Archive(true)),
                Command::Restore { target } => (target, Change::Archive(false)),
                _ => unreachable!(),
            };
            change.validate()?;
            Ok(Output::Task(Store::open(&path)?.change(
                target.id,
                target.expect_revision,
                change,
            )?))
        }
    }
}

fn report_error(error: &Error, json_output: bool) -> ExitCode {
    if json_output {
        let mut value = json!({ "schema_version": 1, "ok": false, "error": { "code": error.code(), "message": error.to_string() } });
        if let Error::Conflict {
            id,
            expected,
            actual,
        } = error
        {
            value["error"]["task_id"] = json!(id);
            value["error"]["expected_revision"] = json!(expected);
            value["error"]["actual_revision"] = json!(actual);
        }
        let _ = writeln!(io::stderr(), "{value}");
    } else {
        let _ = writeln!(io::stderr(), "todo: {error}");
    }
    ExitCode::from(error.exit_code())
}

fn main() -> ExitCode {
    let cli = match Cli::try_parse() {
        Ok(cli) => cli,
        Err(error) => {
            if matches!(
                error.kind(),
                ErrorKind::DisplayHelp | ErrorKind::DisplayVersion
            ) {
                let _ = error.print();
                return ExitCode::SUCCESS;
            }
            return report_error(
                &Error::Invalid(error.to_string()),
                env::args_os().any(|arg| arg == "--json"),
            );
        }
    };
    let json_output = cli.json;
    match run(cli) {
        Ok(output) => {
            let mut stdout = io::stdout().lock();
            let result = if json_output {
                writeln!(stdout, "{}", output.json())
            } else {
                output.print(&mut stdout)
            };
            match result {
                Ok(()) => ExitCode::SUCCESS,
                Err(error) => report_error(
                    &Error::Io(io::Error::new(
                        error.kind(),
                        format!(
                            "Could not deliver output: {error}. A write may have committed; inspect the task before retrying."
                        ),
                    )),
                    json_output,
                ),
            }
        }
        Err(error) => report_error(&error, json_output),
    }
}
