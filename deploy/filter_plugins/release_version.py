"""Pure version selection; Ansible owns execution and mutations."""
import re

from ansible.errors import AnsibleFilterError

VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")


def parse_version(value):
    match = VERSION.fullmatch(value) if isinstance(value, str) else None
    if not match or len(value) > 64:
        raise AnsibleFilterError("Release versions must be MAJOR.MINOR.PATCH[-prerelease], without build metadata")
    identifiers = match[4].split(".") if match[4] else []
    if any(part.isdigit() and len(part) > 1 and part.startswith("0") for part in identifiers):
        raise AnsibleFilterError("Numeric prerelease identifiers cannot have leading zeros")
    # Numeric identifiers sort below text; a normal release sorts above prereleases.
    prerelease = tuple((0, int(part)) if part.isdigit() else (1, part) for part in identifiers)
    return (*(int(match[i]) for i in (1, 2, 3)), not identifiers, prerelease)


def format_version(version):
    core = ".".join(map(str, version[:3]))
    return core if version[3] else core + "-" + ".".join(str(part[1]) for part in version[4])


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
    selected = parse_version(requested) if requested is not None else (
        newest[0], newest[1], newest[2] + int(newest[3]), True, ()
    )
    if selected <= newest:
        raise AnsibleFilterError("Requested version must be newer than repository and existing manifests")
    code = max(build_codes) + 1
    if code > 2100000000:
        raise AnsibleFilterError("Android versionCode limit exceeded")
    return {"versionName": format_version(selected), "versionCode": code}


class FilterModule:
    def filters(self):
        return {"remote_codex_select_version": select_version}
