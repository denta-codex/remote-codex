"""Compact console output, with detailed no_log-aware results in a private log."""
import json
import os

from ansible.plugins.callback import CallbackBase


class CallbackModule(CallbackBase):
    CALLBACK_VERSION = 2.0
    CALLBACK_TYPE = "stdout"
    CALLBACK_NAME = "remote_codex"

    def _write(self, kind, task, data=None):
        path = os.environ.get("REMOTE_CODEX_RUN_LOG")
        if path:
            with open(path, "a", encoding="utf-8") as stream:
                stream.write(json.dumps({"event": kind, "task": task, "result": data}, default=str) + "\n")

    def v2_playbook_on_task_start(self, task, is_conditional):
        name = task.get_name()
        self._write("task", name)
        if name.startswith("Stage:"):
            self._display.display(name)

    def _result(self, result, kind):
        # Use Ansible's serializer: it removes internal fields and censors no_log.
        safe = json.loads(self._dump_results(result._result))
        name = result._task.get_name()
        self._write(kind, name, safe)
        return safe

    def v2_runner_on_ok(self, result):
        safe = self._result(result, "ok")
        if result._task.get_name() == "Result":
            self._display.display(json.dumps(safe.get("msg", safe), default=str))

    def v2_runner_on_failed(self, result, ignore_errors=False):
        safe = self._result(result, "failed")
        excerpt = "\n".join(str(safe.get(key, "")) for key in ("msg", "stderr", "stdout")).strip()
        excerpt = "\n".join(excerpt.splitlines()[-25:])[-4000:]
        self._display.display("FAILED: " + result._task.get_name() + "\n" + excerpt)

    def v2_runner_on_unreachable(self, result):
        self.v2_runner_on_failed(result)

    def v2_runner_on_skipped(self, result):
        self._result(result, "skipped")

    def v2_playbook_on_stats(self, stats):
        self._write("stats", "", {host: stats.summarize(host) for host in stats.processed})
