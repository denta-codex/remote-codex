"""Pure version selection; Ansible owns execution and mutations."""
import re

from ansible.errors import AnsibleFilterError

VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def parse_version(value):
    if not isinstance(value, str) or not VERSION.fullmatch(value):
        raise AnsibleFilterError("Release versions must be numeric MAJOR.MINOR.PATCH")
    return tuple(int(part) for part in value.split("."))


def select_version(gradle, manifests, codes, requested=None):
    names = re.findall(r'^\s*versionName = "([^"]+)"\s*$', gradle, re.M)
    numbers = re.findall(r"^\s*versionCode = ([0-9]+)\s*$", gradle, re.M)
    if len(names) != 1 or len(numbers) != 1:
        raise AnsibleFilterError("Expected exactly one Android versionName and versionCode")
    versions = [parse_version(names[0])]
    build_codes = [int(numbers[0])]
    for manifest in manifests:
        versions.append(parse_version(manifest["versionName"]))
        code = manifest["versionCode"]
        if isinstance(code, bool) or not isinstance(code, int) or code < 1:
            raise AnsibleFilterError("Invalid existing manifest versionCode")
        build_codes.append(code)
    for code in codes:
        if not str(code).isdigit():
            raise AnsibleFilterError("Release directory names must be numeric build numbers")
        build_codes.append(int(code))
    newest = max(versions)
    selected = parse_version(requested) if requested is not None else (newest[0], newest[1], newest[2] + 1)
    if selected <= newest:
        raise AnsibleFilterError("Requested version must be newer than repository and existing manifests")
    code = max(build_codes) + 1
    if code > 2100000000:
        raise AnsibleFilterError("Android versionCode limit exceeded")
    return {"versionName": ".".join(map(str, selected)), "versionCode": code}


class FilterModule:
    def filters(self):
        return {"remote_codex_select_version": select_version}
