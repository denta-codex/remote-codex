use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    process::{Command, Output, Stdio},
};

use serde_json::Value;
use tempfile::TempDir;

struct Fixture {
    dir: TempDir,
    db: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let db = dir.path().join("data/tasks.sqlite3");
        Self { dir, db }
    }

    fn command(&self) -> Command {
        let mut command = Command::new(env!("CARGO_BIN_EXE_todo"));
        command
            .env_remove("TODO_DB")
            .env_remove("XDG_DATA_HOME")
            .env("HOME", self.dir.path());
        command
            .current_dir(self.dir.path())
            .arg("--db")
            .arg(&self.db)
            .arg("--json");
        command
    }

    fn raw(&self, args: &[&str]) -> Output {
        self.command().args(args).output().unwrap()
    }

    fn ok(&self, args: &[&str]) -> Value {
        success(self.raw(args))
    }

    fn error(&self, args: &[&str], exit: i32, code: &str) -> Value {
        let output = self.raw(args);
        assert_eq!(output.status.code(), Some(exit), "{output:?}");
        assert!(output.stdout.is_empty());
        let value: Value = serde_json::from_slice(&output.stderr).unwrap();
        assert_eq!(value["schema_version"], 1);
        assert_eq!(value["ok"], false);
        assert_eq!(value["error"]["code"], code);
        value
    }

    fn stdin(&self, args: &[&str], input: &str) -> Value {
        let mut child = self
            .command()
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        child
            .stdin
            .take()
            .unwrap()
            .write_all(input.as_bytes())
            .unwrap();
        success(child.wait_with_output().unwrap())
    }
}

fn success(output: Output) -> Value {
    assert!(output.status.success(), "{output:?}");
    assert!(output.stderr.is_empty(), "{output:?}");
    let value: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(value["schema_version"], 1);
    assert_eq!(value["ok"], true);
    value
}

fn ids(value: &Value) -> Vec<i64> {
    value["tasks"]
        .as_array()
        .unwrap()
        .iter()
        .map(|task| task["id"].as_i64().unwrap())
        .collect()
}

#[test]
fn lifecycle_persists_fields_and_ordered_notes_across_processes() {
    let f = Fixture::new();
    assert!(ids(&f.ok(&["list"])).is_empty());
    let first = f.ok(&["add", " First ", "--description", "- [ ] One\n- [x] Two"]);
    let task = &first["task"];
    assert_eq!(task["id"], 1);
    assert_eq!(task["title"], "First");
    assert_eq!(task["status"], "To Do");
    assert_eq!(task["revision"], 1);
    assert_eq!(task["archived"], false);
    assert!(task["created_at"].as_str().unwrap().ends_with('Z'));
    f.ok(&["edit", "1", "--title", "Renamed", "--expect-revision", "1"]);
    f.ok(&["note", "1", "first note"]);
    f.ok(&["note", "1", "second note"]);
    f.ok(&["move", "1", "in-progress"]);
    let before = f.ok(&["show", "1"]);
    assert_eq!(before["task"]["description"], task["description"]);
    assert_eq!(before["task"]["title"], "Renamed");
    assert_eq!(before["task"]["created_at"], task["created_at"]);
    assert_eq!(before["task"]["revision"], 5);
    assert_eq!(before["task"]["notes"][0]["text"], "first note");
    assert_eq!(before["task"]["notes"][1]["text"], "second note");
    f.ok(&["archive", "1"]);
    assert!(ids(&f.ok(&["list"])).is_empty());
    assert_eq!(ids(&f.ok(&["list", "--archived"])), [1]);
    assert_eq!(ids(&f.ok(&["list", "--all"])), [1]);
    f.ok(&["restore", "1"]);
    let restored = f.ok(&["show", "1"]);
    assert_eq!(restored["task"]["status"], "In Progress");
    assert_eq!(restored["task"]["notes"], before["task"]["notes"]);
    assert_eq!(restored["task"]["revision"], 7);
    f.ok(&["edit", "1", "--description", ""]);
    let cleared = f.ok(&["show", "1"]);
    assert_eq!(cleared["task"]["description"], "");
    assert_eq!(cleared["task"]["title"], "Renamed");
    assert_eq!(f.ok(&["add", "Second"])["task"]["id"], 2);
}

#[test]
fn list_filters_and_search_are_literal_and_compact() {
    let f = Fixture::new();
    f.ok(&["add", "Done first"]);
    f.ok(&["add", "Doing second", "--description", "Résumé: NEEDLE"]);
    f.ok(&["add", "Todo third"]);
    f.ok(&["add", "Todo fourth"]);
    f.ok(&["move", "1", "Done"]);
    f.ok(&["move", "2", "In Progress"]);
    f.ok(&["note", "3", "50%_complete"]);
    assert_eq!(ids(&f.ok(&["list"])), [3, 4, 2, 1]);
    assert_eq!(ids(&f.ok(&["list", "--status", "to do"])), [3, 4]);
    assert_eq!(ids(&f.ok(&["list", "--search", "needle"])), [2]);
    assert_eq!(ids(&f.ok(&["list", "--search", "%_"])), [3]);
    assert_eq!(ids(&f.ok(&["list", "--search", "FOURTH"])), [4]);
    assert!(ids(&f.ok(&["list", "--search", "' OR 1=1 --"])).is_empty());
    assert!(ids(&f.ok(&["list", "--search", "needle", "--status", "Done"])).is_empty());
    let list = f.ok(&["list"]);
    assert!(list["tasks"][0].get("description").is_none());
    assert!(list["tasks"][0].get("notes").is_none());
}

#[test]
fn file_and_stdin_input_preserve_markdown_and_unicode() {
    let f = Fixture::new();
    let text = "Hello 🦀\n\n- [ ] a `quoted` task\n";
    let file = f.dir.path().join("body.md");
    fs::write(&file, text).unwrap();
    let added = f.ok(&[
        "add",
        "Unicode 🦀",
        "--description-file",
        file.to_str().unwrap(),
    ]);
    assert_eq!(added["task"]["description"], text);
    let edited = f.stdin(
        &["edit", "1", "--description-file", "-"],
        "replacement\n\nline two\n",
    );
    assert_eq!(edited["task"]["description"], "replacement\n\nline two\n");
    let noted = f.stdin(&["note", "1", "--file", "-"], text);
    assert_eq!(noted["task"]["notes"][0]["text"], text);
    f.ok(&["note", "1", "--file", file.to_str().unwrap()]);
    let added = f.stdin(&["add", "From stdin", "--description-file", "-"], text);
    assert_eq!(added["task"]["description"], text);
}

#[test]
fn invalid_input_and_missing_files_do_not_create_database() {
    let f = Fixture::new();
    for args in [
        vec!["add", "  "],
        vec!["add", "two\nlines"],
        vec!["edit", "1"],
        vec!["move", "1", "blocked"],
        vec!["show", "0"],
        vec!["show", "abc"],
        vec!["note", "1"],
        vec!["note", "1", " "],
        vec!["list", "--archived", "--all"],
        vec!["edit", "1", "--title", "x", "--expect-revision", "0"],
        vec!["add", "x", "--description", "x", "--description-file", "a"],
    ] {
        f.error(&args, 2, "invalid_input");
        assert!(!f.db.exists());
    }
    f.error(
        &["add", "Valid", "--description-file", "/no-such-todo-file"],
        6,
        "io_error",
    );
    assert!(!f.db.exists());
}

#[test]
fn unknown_ids_and_stale_revisions_leave_data_unchanged() {
    let f = Fixture::new();
    let original = f.ok(&["add", "original"]);
    for args in [
        vec!["show", "99"],
        vec!["edit", "99", "--title", "x"],
        vec!["note", "99", "x"],
        vec!["archive", "99"],
    ] {
        f.error(&args, 3, "not_found");
    }
    for args in [
        vec!["edit", "1", "--title", "stale", "--expect-revision", "2"],
        vec!["note", "1", "stale", "--expect-revision", "2"],
        vec!["move", "1", "Done", "--expect-revision", "2"],
        vec!["archive", "1", "--expect-revision", "2"],
        vec!["restore", "1", "--expect-revision", "2"],
    ] {
        let error = f.error(&args, 4, "revision_conflict");
        assert_eq!(error["error"]["actual_revision"], 1);
        assert_eq!(error["error"]["expected_revision"], 2);
    }
    assert_eq!(f.ok(&["show", "1"]), original);
}

#[test]
fn concurrent_processes_initialize_and_create_unique_tasks() {
    let f = Fixture::new();
    let children: Vec<_> = (0..12)
        .map(|i| {
            f.command()
                .args(["add", &format!("Task {i}")])
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap()
        })
        .collect();
    let mut created: Vec<_> = children
        .into_iter()
        .map(|child| {
            success(child.wait_with_output().unwrap())["task"]["id"]
                .as_i64()
                .unwrap()
        })
        .collect();
    created.sort();
    assert_eq!(created, (1..=12).collect::<Vec<_>>());
    assert_eq!(ids(&f.ok(&["list"])), created);
}

#[test]
fn concurrent_notes_are_preserved_and_competing_edits_conflict() {
    let f = Fixture::new();
    f.ok(&["add", "original"]);
    let children: Vec<_> = (0..8)
        .map(|i| {
            f.command()
                .args(["note", "1", &format!("Note {i}")])
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap()
        })
        .collect();
    for child in children {
        success(child.wait_with_output().unwrap());
    }
    let task = f.ok(&["show", "1"]);
    assert_eq!(task["task"]["revision"], 9);
    assert_eq!(task["task"]["notes"].as_array().unwrap().len(), 8);
    let children: Vec<_> = ["Edit A", "Edit B"]
        .into_iter()
        .map(|title| {
            f.command()
                .args(["edit", "1", "--title", title, "--expect-revision", "9"])
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap()
        })
        .collect();
    let mut exits: Vec<_> = children
        .into_iter()
        .map(|child| child.wait_with_output().unwrap().status.code().unwrap())
        .collect();
    exits.sort();
    assert_eq!(exits, [0, 4]);
    assert_eq!(f.ok(&["show", "1"])["task"]["revision"], 10);
}

#[test]
fn failure_between_note_insert_and_revision_update_rolls_back_both() {
    let f = Fixture::new();
    let original = f.ok(&["add", "original"]);
    let connection = rusqlite::Connection::open(&f.db).unwrap();
    connection.execute_batch("CREATE TRIGGER fail_update BEFORE UPDATE ON tasks BEGIN SELECT RAISE(ABORT, 'test failure'); END;").unwrap();
    f.error(&["note", "1", "must roll back"], 5, "database_error");
    assert_eq!(f.ok(&["show", "1"]), original);
    connection
        .execute_batch("DROP TRIGGER fail_update;")
        .unwrap();
    f.ok(&["note", "1", "works afterward"]);
}

#[test]
fn corrupt_or_newer_databases_fail_without_modifying_them() {
    let f = Fixture::new();
    fs::create_dir_all(f.db.parent().unwrap()).unwrap();
    fs::write(&f.db, "not sqlite").unwrap();
    f.error(&["list"], 5, "database_error");
    assert_eq!(fs::read(&f.db).unwrap(), b"not sqlite");
    fs::remove_file(&f.db).unwrap();
    let connection = rusqlite::Connection::open(&f.db).unwrap();
    connection.pragma_update(None, "user_version", 99).unwrap();
    drop(connection);
    let before = fs::read(&f.db).unwrap();
    f.error(&["list"], 2, "invalid_input");
    assert_eq!(fs::read(&f.db).unwrap(), before);
}

#[test]
fn default_paths_and_explicit_overrides_are_independent_of_cwd() {
    let f = Fixture::new();
    let run = |env_db: Option<&Path>, explicit: Option<&Path>, xdg: Option<&Path>| {
        let mut command = Command::new(env!("CARGO_BIN_EXE_todo"));
        command
            .current_dir(f.dir.path())
            .env("HOME", f.dir.path())
            .env_remove("TODO_DB")
            .env_remove("XDG_DATA_HOME");
        if let Some(path) = env_db {
            command.env("TODO_DB", path);
        }
        if let Some(path) = explicit {
            command.arg("--db").arg(path);
        }
        if let Some(path) = xdg {
            command.env("XDG_DATA_HOME", path);
        }
        success(command.args(["list", "--json"]).output().unwrap());
    };
    run(None, None, None);
    assert!(
        f.dir
            .path()
            .join(".local/share/remote-codex/todo.sqlite3")
            .exists()
    );
    let xdg = f.dir.path().join("xdg");
    run(None, None, Some(&xdg));
    assert!(xdg.join("remote-codex/todo.sqlite3").exists());
    let env_db = f.dir.path().join("env.sqlite3");
    run(Some(&env_db), Some(&f.db), None);
    assert!(f.db.exists());
    assert!(!env_db.exists());
    run(Some(&env_db), None, None);
    assert!(env_db.exists());
}

#[test]
fn plain_output_is_readable_and_help_is_available_without_database() {
    let f = Fixture::new();
    let plain = |args: &[&str]| {
        Command::new(env!("CARGO_BIN_EXE_todo"))
            .env_remove("TODO_DB")
            .arg("--db")
            .arg(&f.db)
            .args(args)
            .output()
            .unwrap()
    };
    let help = plain(&["--help"]);
    assert!(help.status.success());
    assert!(String::from_utf8_lossy(&help.stdout).contains("archive"));
    assert!(!f.db.exists());
    f.ok(&[
        "add",
        "Readable",
        "--description",
        "paragraph\n\n- [ ] checklist",
    ]);
    let list = plain(&["list"]);
    assert!(String::from_utf8_lossy(&list.stdout).contains("#1  Readable"));
    let show = plain(&["show", "1"]);
    assert!(String::from_utf8_lossy(&show.stdout).contains("- [ ] checklist"));
    f.ok(&["note", "1", "\u{1b}[31mnot a terminal command\u{1b}[0m"]);
    assert!(!plain(&["show", "1"]).stdout.contains(&0x1b));
}

#[cfg(unix)]
#[test]
fn newly_created_database_is_private() {
    use std::os::unix::fs::PermissionsExt;
    let f = Fixture::new();
    f.ok(&["list"]);
    assert_eq!(
        fs::metadata(&f.db).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert_eq!(
        fs::metadata(f.db.parent().unwrap())
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
}

#[test]
fn retired_path_is_rejected_without_accessing_live_data() {
    let output = Command::new(env!("CARGO_BIN_EXE_todo"))
        .args([
            "--db",
            "/home/agent/.local/share/todo/tasks.sqlite3",
            "list",
            "--json",
        ])
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&output.stderr).contains("retired"));
}

#[test]
fn migration_cli_transfers_fixture_and_retains_source() {
    let f = Fixture::new();
    f.ok(&["add", "Keep me"]);
    let destination = f.dir.path().join("moved.sqlite3");
    let output = Command::new(env!("CARGO_BIN_EXE_todo"))
        .arg("migrate")
        .env_remove("HOME")
        .env("TODO_DB", "/home/agent/.local/share/todo/tasks.sqlite3")
        .arg("--from")
        .arg(&f.db)
        .arg("--to")
        .arg(&destination)
        .args(["--writers-quiesced", "--json"])
        .output()
        .unwrap();
    success(output);
    assert!(f.db.exists());
    let output = Command::new(env!("CARGO_BIN_EXE_todo"))
        .arg("--db")
        .arg(destination)
        .args(["show", "1", "--json"])
        .output()
        .unwrap();
    assert!(String::from_utf8_lossy(&output.stdout).contains("Keep me"));
    assert!(output.status.success());
}
