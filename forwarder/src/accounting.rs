//! Read-only, bounded accounting batches invoked through stock command/exec.
//! Continuations contain counters and checkpoints, never rollout text.
use serde::{
    Deserialize, Serialize,
    de::{IgnoredAny, MapAccess, Visitor},
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    io::{BufRead, BufReader, Read, Seek, SeekFrom},
    os::unix::fs::{MetadataExt, OpenOptionsExt},
    path::Path,
    time::{Duration, Instant},
};

const BATCH_BYTES: u64 = 8 * 1024 * 1024;
const RECORD_BYTES: usize = 1024 * 1024;
const WIRE_BYTES: usize = 64 * 1024;
const MAX_BUCKETS: usize = 128;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    version: u32,
    thread: String,
    path: String,
    continuation: Option<Checkpoint>,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Checkpoint {
    thread: String,
    device: u64,
    inode: u64,
    boundary: u64,
    modified_seconds: i64,
    modified_nanos: i64,
    head: String,
    anchor: String,
    offset: u64,
    skipping: bool,
    identity_seen: bool,
    provider: String,
    model: String,
    tier: String,
    previous: Option<Tokens>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
struct Tokens {
    input_tokens: u64,
    cached_input_tokens: u64,
    #[serde(default)]
    cache_write_input_tokens: u64,
    output_tokens: u64,
    #[serde(default, skip_serializing)]
    reasoning_output_tokens: u64,
    total_tokens: u64,
}

impl Tokens {
    fn valid(&self) -> bool {
        self.cached_input_tokens <= self.input_tokens
            && self.cache_write_input_tokens <= self.input_tokens - self.cached_input_tokens
            && self.reasoning_output_tokens <= self.output_tokens
            && self.input_tokens.checked_add(self.output_tokens) == Some(self.total_tokens)
            && self.total_tokens <= i64::MAX as u64
    }

    fn same_counts(&self, other: &Self) -> bool {
        self.input_tokens == other.input_tokens
            && self.cached_input_tokens == other.cached_input_tokens
            && self.cache_write_input_tokens == other.cache_write_input_tokens
            && self.output_tokens == other.output_tokens
            && self.total_tokens == other.total_tokens
    }

    fn follows(&self, prior: &Self, last: &Self) -> bool {
        self.input_tokens.checked_sub(prior.input_tokens) == Some(last.input_tokens)
            && self
                .cached_input_tokens
                .checked_sub(prior.cached_input_tokens)
                == Some(last.cached_input_tokens)
            && self
                .cache_write_input_tokens
                .checked_sub(prior.cache_write_input_tokens)
                == Some(last.cache_write_input_tokens)
            && self.output_tokens.checked_sub(prior.output_tokens) == Some(last.output_tokens)
    }

    fn add(&mut self, other: &Self) -> Result<(), ()> {
        self.input_tokens = self
            .input_tokens
            .checked_add(other.input_tokens)
            .ok_or(())?;
        self.cached_input_tokens = self
            .cached_input_tokens
            .checked_add(other.cached_input_tokens)
            .ok_or(())?;
        self.cache_write_input_tokens = self
            .cache_write_input_tokens
            .checked_add(other.cache_write_input_tokens)
            .ok_or(())?;
        self.output_tokens = self
            .output_tokens
            .checked_add(other.output_tokens)
            .ok_or(())?;
        self.total_tokens = self
            .total_tokens
            .checked_add(other.total_tokens)
            .ok_or(())?;
        if self.valid() { Ok(()) } else { Err(()) }
    }
}

#[derive(Deserialize)]
struct Row {
    #[serde(rename = "type")]
    kind: String,
    payload: Payload,
}

#[derive(Deserialize)]
struct EventKind {
    payload: PayloadKind,
}

#[derive(Deserialize)]
struct PayloadKind {
    #[serde(default, rename = "type")]
    kind: String,
}

#[derive(Default, Deserialize)]
struct Payload {
    #[serde(default)]
    id: String,
    #[serde(default)]
    session_id: String,
    #[serde(default)]
    model_provider: String,
    #[serde(default)]
    model: String,
    #[serde(default)]
    service_tier: Option<String>,
    #[serde(default, rename = "type")]
    kind: String,
    thread_settings: Option<Box<Payload>>,
    info: Option<Usage>,
}

#[derive(Deserialize)]
struct Usage {
    total_token_usage: Tokens,
    last_token_usage: Tokens,
}

#[derive(Serialize)]
struct Bucket {
    provider: String,
    model: String,
    tier: String,
    context: String,
    requests: u32,
    tokens: Tokens,
}

// Read only the outer type, even from an unfinished prefix. Never find envelope
// names by searching message text, which could contain fabricated token events.
fn outer_type(bytes: &[u8]) -> Result<String, ()> {
    struct TypeVisitor<'a>(&'a mut Option<String>);
    impl<'de> Visitor<'de> for TypeVisitor<'_> {
        type Value = ();
        fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
            formatter.write_str("an accounting envelope")
        }
        fn visit_map<M: MapAccess<'de>>(self, mut map: M) -> Result<(), M::Error> {
            while let Some(key) = map.next_key::<String>()? {
                if key == "type" {
                    *self.0 = Some(map.next_value()?);
                    // Stop before the remaining payload, including an unfinished
                    // giant message. serde otherwise requires the closing brace.
                    return Err(serde::de::Error::custom("type found"));
                }
                map.next_value::<IgnoredAny>()?;
            }
            Err(serde::de::Error::custom("missing type"))
        }
    }
    let mut found = None;
    let _ = serde::Deserializer::deserialize_map(
        &mut serde_json::Deserializer::from_slice(bytes),
        TypeVisitor(&mut found),
    );
    found.ok_or(())
}

fn bounded_text(value: &str) -> bool {
    value.len() <= 256 && !value.chars().any(char::is_control)
}

fn hash_window(file: &mut File, start: u64, length: u64) -> Result<String, ()> {
    file.seek(SeekFrom::Start(start)).map_err(|_| ())?;
    let mut bytes = vec![0; length as usize];
    file.read_exact(&mut bytes).map_err(|_| ())?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
}

fn anchor(file: &mut File, offset: u64) -> Result<String, ()> {
    let length = offset.min(4096);
    hash_window(file, offset - length, length)
}

pub fn run(input: &str) -> Value {
    let unavailable = || json!({"version": 1, "status": "unavailable"});
    if input.len() > WIRE_BYTES {
        return unavailable();
    }
    let result = serde_json::from_str::<Request>(input)
        .map_err(|_| ())
        .and_then(|request| scan(request, BATCH_BYTES));
    match result {
        Ok(response) if response.to_string().len() <= WIRE_BYTES => response,
        _ => unavailable(),
    }
}

fn account(
    line: &[u8],
    thread: &str,
    state: &mut Checkpoint,
    buckets: &mut BTreeMap<(String, String, String, String), Bucket>,
) -> Result<(), ()> {
    if line.iter().all(u8::is_ascii_whitespace) {
        return Ok(());
    }
    let kind = outer_type(line)?;
    if !matches!(kind.as_str(), "session_meta" | "turn_context" | "event_msg") {
        return Ok(());
    }
    if kind == "event_msg" {
        let event: EventKind = serde_json::from_slice(line).map_err(|_| ())?;
        if !matches!(
            event.payload.kind.as_str(),
            "token_count" | "thread_settings_applied"
        ) {
            return Ok(());
        }
    }
    let row: Row = serde_json::from_slice(line).map_err(|_| ())?;
    let payload = row.payload;
    let settings = match row.kind.as_str() {
        "session_meta" => {
            let id = if payload.id.is_empty() {
                &payload.session_id
            } else {
                &payload.id
            };
            if id != thread {
                return Err(());
            }
            state.identity_seen = true;
            state.provider = payload.model_provider;
            None
        }
        "turn_context" => Some(payload),
        "event_msg" if payload.kind == "thread_settings_applied" => {
            Some(*payload.thread_settings.ok_or(())?)
        }
        "event_msg" if payload.kind == "token_count" => {
            let Some(info) = payload.info else {
                return Ok(());
            };
            if !state.identity_seen
                || !info.total_token_usage.valid()
                || !info.last_token_usage.valid()
            {
                return Err(());
            }
            if state
                .previous
                .as_ref()
                .is_some_and(|previous| previous.same_counts(&info.total_token_usage))
            {
                return Ok(());
            }
            if info.last_token_usage.total_tokens == 0 {
                if match &state.previous {
                    Some(previous) => !previous.same_counts(&info.total_token_usage),
                    None => info.total_token_usage.total_tokens != 0,
                } {
                    return Err(());
                }
                state.previous = Some(info.total_token_usage);
                return Ok(());
            }
            let contiguous = match &state.previous {
                Some(previous) => info
                    .total_token_usage
                    .follows(previous, &info.last_token_usage),
                None => info.total_token_usage.same_counts(&info.last_token_usage),
            };
            if !contiguous || state.provider.is_empty() || state.model.is_empty() {
                return Err(());
            }
            state.previous = Some(info.total_token_usage);
            let context = if info.last_token_usage.input_tokens > 272_000 {
                "long"
            } else {
                "short"
            }
            .to_owned();
            let key = (
                state.provider.clone(),
                state.model.clone(),
                state.tier.clone(),
                context.clone(),
            );
            if let Some(bucket) = buckets.get_mut(&key) {
                bucket.tokens.add(&info.last_token_usage)?;
                bucket.requests = bucket.requests.checked_add(1).ok_or(())?;
            } else {
                if buckets.len() >= MAX_BUCKETS {
                    return Err(());
                }
                buckets.insert(
                    key,
                    Bucket {
                        provider: state.provider.clone(),
                        model: state.model.clone(),
                        tier: state.tier.clone(),
                        context,
                        requests: 1,
                        tokens: info.last_token_usage,
                    },
                );
            }
            None
        }
        _ => None,
    };
    if let Some(settings) = settings {
        state.model = settings.model;
        state.tier = settings.service_tier.unwrap_or_default();
        if !settings.model_provider.is_empty() {
            state.provider = settings.model_provider;
        }
    }
    if [&state.provider, &state.model, &state.tier]
        .iter()
        .any(|value| !bounded_text(value))
    {
        return Err(());
    }
    Ok(())
}

fn scan(request: Request, batch_bytes: u64) -> Result<Value, ()> {
    if request.version != 1
        || request.thread.is_empty()
        || !bounded_text(&request.thread)
        || !Path::new(&request.path).is_absolute()
    {
        return Err(());
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(&request.path)
        .map_err(|_| ())?;
    let metadata = file.metadata().map_err(|_| ())?;
    if !metadata.is_file() || metadata.uid() != unsafe { libc::geteuid() } {
        return Err(());
    }
    let mut state = if let Some(state) = request.continuation {
        if state.thread != request.thread
            || state.device != metadata.dev()
            || state.inode != metadata.ino()
            || state.boundary > metadata.len()
            || state.offset > state.boundary
            || !state.identity_seen && state.offset > 0 && !state.skipping
            || [&state.provider, &state.model, &state.tier]
                .iter()
                .any(|value| !bounded_text(value))
            || state
                .previous
                .as_ref()
                .is_some_and(|tokens| !tokens.valid())
            || metadata.len() == state.boundary
                && (metadata.mtime() != state.modified_seconds
                    || metadata.mtime_nsec() != state.modified_nanos)
            || hash_window(&mut file, 0, state.boundary.min(4096))? != state.head
            || anchor(&mut file, state.offset)? != state.anchor
        {
            return Err(());
        }
        state
    } else {
        Checkpoint {
            thread: request.thread.clone(),
            device: metadata.dev(),
            inode: metadata.ino(),
            boundary: metadata.len(),
            modified_seconds: metadata.mtime(),
            modified_nanos: metadata.mtime_nsec(),
            head: hash_window(&mut file, 0, metadata.len().min(4096))?,
            anchor: String::new(),
            offset: 0,
            skipping: false,
            identity_seen: false,
            provider: String::new(),
            model: String::new(),
            tier: String::new(),
            previous: None,
        }
    };
    let started = Instant::now();
    let starting_offset = state.offset;
    file.seek(SeekFrom::Start(state.offset)).map_err(|_| ())?;
    let mut reader = BufReader::with_capacity(64 * 1024, file);
    let mut buckets = BTreeMap::new();
    let mut line = Vec::new();
    let mut line_start = state.offset;
    while state.offset < state.boundary
        && state.offset - starting_offset < batch_bytes
        && started.elapsed() < Duration::from_millis(250)
    {
        let available = reader.fill_buf().map_err(|_| ())?;
        if available.is_empty() {
            return Err(());
        }
        let limit = available
            .len()
            .min((state.boundary - state.offset) as usize)
            .min((batch_bytes - (state.offset - starting_offset)) as usize);
        let bytes = &available[..limit];
        let count = bytes
            .iter()
            .position(|byte| *byte == b'\n')
            .map_or(bytes.len(), |index| index + 1);
        let complete = bytes[count - 1] == b'\n';
        if !state.skipping {
            if line.len() + count > RECORD_BYTES {
                // Huge tool/message records can be skipped across batches. Unknown or
                // accounting records are never silently discarded as complete usage.
                if outer_type(&line)? != "response_item" {
                    return Err(());
                }
                state.skipping = true;
                line.clear();
            } else {
                line.extend_from_slice(&bytes[..count]);
            }
        }
        reader.consume(count);
        state.offset += count as u64;
        if complete {
            if !state.skipping {
                account(&line, &request.thread, &mut state, &mut buckets)?;
            }
            state.skipping = false;
            line.clear();
            line_start = state.offset;
        }
    }
    let caught_up = state.offset == state.boundary;
    // An unfinished record is excluded from this opening snapshot. At a batch
    // boundary, reread it in the next batch instead of shipping a text buffer.
    if !state.skipping && !line.is_empty() {
        state.offset = line_start;
    }
    if !state.identity_seen || !caught_up && state.offset == starting_offset {
        return Err(());
    }
    let mut file = reader.into_inner();
    let after = file.metadata().map_err(|_| ())?;
    if after.len() < state.boundary
        || after.len() == metadata.len()
            && (after.mtime() != metadata.mtime() || after.mtime_nsec() != metadata.mtime_nsec())
        || hash_window(&mut file, 0, state.boundary.min(4096))? != state.head
    {
        return Err(());
    }
    state.anchor = anchor(&mut file, state.offset)?;
    Ok(
        json!({"version": 1, "status": if caught_up { "caughtUp" } else { "more" }, "thread": request.thread, "boundary": state.boundary, "offset": state.offset, "buckets": buckets.into_values().collect::<Vec<_>>(), "continuation": if caught_up { None } else { Some(state) }}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::NamedTempFile;

    fn counts(input: u64, output: u64) -> Value {
        json!({"input_tokens":input,"cached_input_tokens":input/5,"cache_write_input_tokens":input/10,"output_tokens":output,"reasoning_output_tokens":output/2,"total_tokens":input+output})
    }
    fn event(total: Value, last: Value) -> String {
        json!({"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":total,"last_token_usage":last}}}).to_string()+"\n"
    }
    fn header() -> String {
        concat!("{\"type\":\"session_meta\",\"payload\":{\"id\":\"chat-a\",\"model_provider\":\"openai\"}}\n",
            "{\"type\":\"turn_context\",\"payload\":{\"model\":\"model-a\",\"service_tier\":\"default\"}}\n").into()
    }
    fn request(file: &NamedTempFile, continuation: Option<Value>) -> String {
        json!({"version":1,"thread":"chat-a","path":file.path(),"continuation":continuation})
            .to_string()
    }
    fn fixture(text: &str) -> NamedTempFile {
        let mut file = NamedTempFile::new().unwrap();
        file.write_all(text.as_bytes()).unwrap();
        file
    }
    fn giant(size: u64) -> NamedTempFile {
        let mut file = fixture(&(header() + &event(counts(1000, 100), counts(1000, 100))));
        let prefix = b"{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"output\":\"";
        let suffix = b"\"}}\n";
        let tail = event(counts(2000, 200), counts(1000, 100));
        let padding = size
            - file.as_file().metadata().unwrap().len()
            - prefix.len() as u64
            - suffix.len() as u64
            - tail.len() as u64;
        file.write_all(prefix).unwrap();
        let block = vec![b'x'; 64 * 1024];
        let mut remaining = padding;
        while remaining > 0 {
            let n = remaining.min(block.len() as u64) as usize;
            file.write_all(&block[..n]).unwrap();
            remaining -= n as u64;
        }
        file.write_all(suffix).unwrap();
        file.write_all(tail.as_bytes()).unwrap();
        assert_eq!(size, file.as_file().metadata().unwrap().len());
        file
    }

    #[test]
    fn deduplicates_and_preserves_model_provider_tier_and_per_request_context() {
        let first = event(counts(1000, 100), counts(1000, 100));
        let text = header()
            + &first
            + &first
            + "{\"type\":\"event_msg\",\"payload\":{\"type\":\"thread_settings_applied\",\"thread_settings\":{\"model\":\"model-b\",\"model_provider\":\"other\",\"service_tier\":\"priority\"}}}\n"
            + &event(counts(301000, 200), counts(300000, 100));
        let file = fixture(&text);
        let response = run(&request(&file, None));
        assert_eq!("caughtUp", response["status"]);
        let buckets = response["buckets"].as_array().unwrap();
        assert_eq!(2, buckets.len());
        assert_eq!("short", buckets[0]["context"]);
        assert_eq!("other", buckets[1]["provider"]);
        assert_eq!("model-b", buckets[1]["model"]);
        assert_eq!("priority", buckets[1]["tier"]);
        assert_eq!("long", buckets[1]["context"]);
        assert_eq!(300000, buckets[1]["tokens"]["input_tokens"]);
    }

    #[test]
    fn large_sessions_have_bounded_responses_and_no_transcript_continuations() {
        for size in [54_944_707, 114_965_362] {
            let file = giant(size);
            let mut continuation = None;
            let mut requests = 0;
            let mut pages = 0;
            loop {
                let response = run(&request(&file, continuation));
                assert!(response.to_string().len() < WIRE_BYTES);
                assert!(!response.to_string().contains("function_call_output"));
                assert!(matches!(
                    response["status"].as_str(),
                    Some("more" | "caughtUp")
                ));
                requests += response["buckets"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|bucket| bucket["requests"].as_u64().unwrap())
                    .sum::<u64>();
                pages += 1;
                assert!(pages < 100);
                if response["status"] == "caughtUp" {
                    break;
                }
                continuation = Some(response["continuation"].clone());
            }
            assert_eq!(2, requests);
            assert!(pages > 1);
        }
    }

    #[test]
    fn initial_boundary_is_fixed_and_partial_append_is_excluded() {
        let mut file = giant(10 * 1024 * 1024);
        let first = run(&request(&file, None));
        assert_eq!("more", first["status"]);
        file.write_all(event(counts(3000, 300), counts(1000, 100)).as_bytes())
            .unwrap();
        let second = run(&request(&file, Some(first["continuation"].clone())));
        assert_eq!("caughtUp", second["status"]);
        assert_eq!(first["boundary"], second["boundary"]);
        assert_eq!(1, second["buckets"][0]["requests"]);

        let mut file = fixture(&(header() + &event(counts(1000, 100), counts(1000, 100))));
        let append = event(counts(2000, 200), counts(1000, 100));
        file.write_all(&append.as_bytes()[..append.len() / 2])
            .unwrap();
        assert_eq!(1, run(&request(&file, None))["buckets"][0]["requests"]);
        file.write_all(&append.as_bytes()[append.len() / 2..])
            .unwrap();
        assert_eq!(2, run(&request(&file, None))["buckets"][0]["requests"]);
    }

    #[test]
    fn incomplete_or_invalid_accounting_never_returns_partial_cost_inputs() {
        let cases = [
            header() + &event(counts(1000, 100), counts(0, 0)),
            header() + &event(counts(3000, 300), counts(1000, 100)),
            header()
                + &event(counts(1000, 100), counts(1000, 100))
                + &event(counts(500, 50), counts(500, 50)),
            header()
                + "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{}}}\n",
            "{\"type\":\"session_meta\",\"payload\":{\"id\":\"different\"}}\n".into(),
            "{\"type\":\"session_meta\",\"payload\":{\"id\":\"chat-a\"}}\n".to_owned()
                + &event(counts(1000, 100), counts(1000, 100)),
        ];
        for text in cases {
            let file = fixture(&text);
            assert_eq!(
                json!({"version":1,"status":"unavailable"}),
                run(&request(&file, None))
            );
        }
        let mut invalid = counts(1000, 100);
        invalid["cached_input_tokens"] = json!(2000);
        let file = fixture(&(header() + &event(invalid.clone(), invalid)));
        assert_eq!("unavailable", run(&request(&file, None))["status"]);
    }

    #[test]
    fn continuations_reject_other_threads_replacement_truncation_and_rewrite() {
        let file = giant(10 * 1024 * 1024);
        let first = run(&request(&file, None));
        let checkpoint = first["continuation"].clone();
        let mut input: Value =
            serde_json::from_str(&request(&file, Some(checkpoint.clone()))).unwrap();
        input["thread"] = json!("chat-b");
        assert_eq!("unavailable", run(&input.to_string())["status"]);
        let other = giant(10 * 1024 * 1024);
        assert_eq!(
            "unavailable",
            run(&request(&other, Some(checkpoint.clone())))["status"]
        );
        let mut writer = file.reopen().unwrap();
        writer.seek(SeekFrom::Start(100)).unwrap();
        writer.write_all(b"altered").unwrap();
        assert_eq!(
            "unavailable",
            run(&request(&file, Some(checkpoint.clone())))["status"]
        );
        writer.set_len(100).unwrap();
        assert_eq!(
            "unavailable",
            run(&request(&file, Some(checkpoint)))["status"]
        );
    }

    #[test]
    fn message_text_cannot_inject_accounting_and_zero_usage_is_valid() {
        let file = fixture(
            &(header()
                + "{\"type\":\"response_item\",\"payload\":{\"text\":\"token_count session_meta turn_context\"}}\n"
                + "{\"type\":\"event_msg\",\"payload\":{\"type\":\"agent_message\",\"info\":{\"unrelated\":true},\"model\":{\"not_accounting\":true}}}\n"),
        );
        let response = run(&request(&file, None));
        assert_eq!("caughtUp", response["status"]);
        assert!(response["buckets"].as_array().unwrap().is_empty());
    }

    #[test]
    fn oversized_accounting_and_too_many_buckets_are_unavailable() {
        let oversized = header()
            + "{\"type\":\"turn_context\",\"payload\":{\"model\":\""
            + &"x".repeat(RECORD_BYTES)
            + "\"}}\n";
        let file = fixture(&oversized);
        assert_eq!("unavailable", run(&request(&file, None))["status"]);
        let mut text = header();
        for index in 0..=MAX_BUCKETS {
            text += &format!(
                "{{\"type\":\"turn_context\",\"payload\":{{\"model\":\"model-{index}\"}}}}\n"
            );
            text += &event(
                counts((index as u64 + 1) * 1000, (index as u64 + 1) * 100),
                counts(1000, 100),
            );
        }
        let file = fixture(&text);
        assert_eq!("unavailable", run(&request(&file, None))["status"]);
    }
}
