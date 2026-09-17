# -*- coding: utf-8 -*-
"""NusaDesk guest-side udocker + compose runtime (product-owned, Phase 3A).

This module is imported by the packaged ``lw-udocker`` launcher and the
``lw-compose-supervisor`` entry point. It is deliberately self-contained:
it vendors nothing, extracts the pinned udocker/PyYAML source tarballs into
a guest-private cache on first use, and never executes extracted source as
a shell string.

Install layout expected by the next phase (all paths guest-side)::

    /usr/local/bin/udocker                    (this wrapper CLI)
    /usr/local/bin/lw-compose-supervisor       (global supervisor entry)
    /usr/local/lib/nusadesk/compose/
        lw_compose_runtime.py                  (this module)
        udocker-1.3.17.tar.gz                  (pinned, sha256 below)
        PyYAML-6.0.1.tar.gz                    (pinned, sha256 below)
    /etc/systemd/system/lw-compose-supervisor.service  (autostart unit)

Runtime state (all guest-private, root-owned)::

    /root/.local/share/lw-udocker/source/      extracted udocker/ + yaml/
    /root/.config/nusadesk/compose/
        supervisor.pid                         global supervisor instance lock
        supervisor.log                         global supervisor log
        <project>/project.json                 normalized plan + desired state
        <project>/compose.yaml                 verbatim copy of the source file
        <project>/run/<service>.pid            supervised child pid
        <project>/run/<service>.status         JSON status, written atomically
        <project>/run/<service>.env            udocker --env-file (0600),
                                               child-lifetime only
        <project>/logs/<service>.log           child stdout+stderr

Why no per-project systemd units: on-device evidence (parent plan, ADR-0024)
shows the vendored systemctl3 only supervises units that were enabled when
``systemctl init`` ran; a unit enabled mid-session runs but is never added to
the manager's restart supervision until the next session. ``Restart=always``
on such a unit is a lie, so per-service restart semantics live in the
product-owned global supervisor below. ``compose up/start/stop/down`` only
rewrite project state atomically and SIGHUP the supervisor; the shipped
``lw-compose-supervisor.service`` unit exists solely so the next phase can
autostart the supervisor on the following session init.

Environment overrides (tests / alternate layouts only; the guest defaults
are the product contract):

    LW_COMPOSE_HOME       directory containing the tarballs + this module
    LW_COMPOSE_STATE      default /root/.config/nusadesk/compose
    LW_COMPOSE_CACHE      default /root/.local/share/lw-udocker
    LW_COMPOSE_WORKSPACE  default /root/nusadesk
    LW_UDOCKER            path to the lw-udocker wrapper for child spawn
    LW_COMPOSE_POLL       supervisor poll interval seconds (default 1.0)
"""

import hashlib
import ipaddress
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import threading
import time

# ---------------------------------------------------------------------------
# Pinned third-party payloads (see THIRD_PARTY_NOTICES.md)
# ---------------------------------------------------------------------------

UDOCKER_VERSION = "1.3.17"
PYYAML_VERSION = "6.0.1"
UDOCKER_TAR = "udocker-%s.tar.gz" % UDOCKER_VERSION
PYYAML_TAR = "PyYAML-%s.tar.gz" % PYYAML_VERSION
UDOCKER_SHA256 = "f97ec97679133b5ada780025e6e263607cb4ec3401786f1db135e46153c90bd1"
PYYAML_SHA256 = "bfdf460b1736c775f2ba9f6a92bca30bc2095067b8a9d77876d1fad6cc3b4a43"
UDOCKER_PREFIX = "udocker-%s/udocker/" % UDOCKER_VERSION
PYYAML_PREFIX = "PyYAML-%s/lib/yaml/" % PYYAML_VERSION

# udocker runs its inner containers through the NusaDesk PRoot, never the
# upstream udocker-englib helper tarball (which is not bundled at all).
UDOCKER_ENV = {
    "UDOCKER_USE_PROOT_EXECUTABLE": "/usr/local/bin/proot",
    "PROOT_LOADER": "/usr/local/bin/proot-loader",
    "PROOT_TMP_DIR": "/tmp",
    # Empty: upstream install() short-circuits on a falsy tarball, so no
    # udocker command can trigger the ~46 MB helper-tarball download.
    "UDOCKER_TARBALL": "",
}

DEFAULT_WORKSPACE = "/root/nusadesk"
DEFAULT_STATE_DIR = "/root/.config/nusadesk/compose"
DEFAULT_CACHE_DIR = "/root/.local/share/lw-udocker"

MAX_DOC_BYTES = 1024 * 1024          # compose input cap (1 MiB)
MAX_TAR_MEMBERS = 4096
MAX_MEMBER_BYTES = 8 * 1024 * 1024   # no single extracted file may exceed 8 MiB
MAX_TOTAL_BYTES = 64 * 1024 * 1024   # total extracted payload cap
MAX_LOG_BYTES = 4 * 1024 * 1024      # per-service log rotation threshold
MAX_SUPERVISOR_LOG = 2 * 1024 * 1024
LOG_TAIL_BYTES = 128 * 1024
LOG_TAIL_LINES = 300

STOP_TIMEOUT = 8.0                   # SIGTERM grace before SIGKILL
HEALTHY_SECONDS = 30.0               # uptime that resets the restart backoff
BACKOFF_MAX = 60.0
DEFAULT_POLL = 1.0
DOWN_WAIT_TIMEOUT = 10.0

# Device-verified on the S10e (2026-09-16): udocker/PRoot strips the
# declared host_ip — a "127.0.0.1:18924:8080" publish produced no loopback
# listener while the container kept listening on *:8080, reachable via the
# device LAN IP. With no loopback-only enforcement available, every ports
# declaration is refused at runtime_problems() and again at argv build;
# the parser still models ports for future compatibility.
PORTS_UNSUPPORTED = (
    "Compose ports are not supported in this MVP: udocker/PRoot does not "
    "enforce host_ip/loopback; use an app-level web app/port surface "
    "instead.")

_NAME_RE = re.compile(r"[A-Za-z0-9._-]+\Z")
_NAME_FIRST_RE = re.compile(r"[A-Za-z0-9_]")
_IMAGE_REF_RE = re.compile(r"[A-Za-z0-9._/:@-]+\Z")
_ENV_KEY_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")
_ROOT_KEYS = ("name", "version", "services")
_SERVICE_KEYS = ("image", "command", "entrypoint", "environment", "volumes",
                 "ports", "working_dir", "depends_on", "restart")
_VOLUME_KEYS = ("type", "source", "target", "read_only")
_PORT_KEYS = ("target", "published", "protocol", "host_ip")
_RESTART_VALUES = ("no", "always", "unless-stopped")


class ComposeError(Exception):
    """A user/actionable failure: bad compose input, unsafe path, bad state."""


def _fail(path, detail):
    raise ComposeError(path + ": " + detail if path else detail)


# ---------------------------------------------------------------------------
# Locations and environment
# ---------------------------------------------------------------------------

def assets_dir():
    """Directory holding the pinned source tarballs (and this module)."""
    override = os.environ.get("LW_COMPOSE_HOME")
    if override:
        return os.path.abspath(override)
    return os.path.dirname(os.path.realpath(__file__))


def state_dir():
    return os.path.abspath(
        os.environ.get("LW_COMPOSE_STATE") or DEFAULT_STATE_DIR)


def cache_dir():
    return os.path.abspath(
        os.environ.get("LW_COMPOSE_CACHE") or DEFAULT_CACHE_DIR)


def workspace_root():
    return os.path.abspath(
        os.environ.get("LW_COMPOSE_WORKSPACE") or DEFAULT_WORKSPACE)


def ensure_runtime_env():
    """Pin the udocker/PRoot contract environment for this process tree."""
    for key, value in UDOCKER_ENV.items():
        os.environ[key] = value


def _sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_extracted():
    """Extract the pinned sources into the cache on first use.

    Only regular files under the fixed package prefixes are extracted;
    absolute/traversal names, links, devices and oversized payloads are
    refused. The tree is built in a temporary directory and renamed into
    place; a digest marker short-circuits later invocations.

    Returns the source root to put on sys.path/PYTHONPATH.
    """
    source_root = os.path.join(cache_dir(), "source")
    marker = os.path.join(source_root, ".extract-ok")
    expected_marker = "%s  %s\n%s  %s\n" % (
        UDOCKER_SHA256, UDOCKER_TAR, PYYAML_SHA256, PYYAML_TAR)
    try:
        with open(marker, "r") as handle:
            if (handle.read() == expected_marker
                    and os.path.isfile(os.path.join(
                        source_root, "udocker", "__init__.py"))
                    and os.path.isfile(os.path.join(
                        source_root, "yaml", "__init__.py"))):
                _add_source_path(source_root)
                return source_root
    except OSError:
        pass

    base = assets_dir()
    payloads = [
        (os.path.join(base, UDOCKER_TAR), UDOCKER_SHA256,
         UDOCKER_PREFIX, "udocker"),
        (os.path.join(base, PYYAML_TAR), PYYAML_SHA256,
         PYYAML_PREFIX, "yaml"),
    ]
    os.makedirs(cache_dir(), exist_ok=True)
    staging = tempfile.mkdtemp(prefix=".staging-", dir=cache_dir())
    try:
        for tar_path, sha256, prefix, package in payloads:
            actual = _sha256_file(tar_path)
            if actual != sha256:
                raise ComposeError(
                    "%s: sha256 mismatch (expected %s, got %s); refusing to "
                    "extract an unpinned payload" % (tar_path, sha256, actual))
            _extract_prefix(tar_path, prefix, os.path.join(staging, package))
        with open(os.path.join(staging, ".extract-ok"), "w") as handle:
            handle.write(expected_marker)
        # Atomic-ish swap: staging becomes source_root; any previous tree is
        # moved aside first and removed after. A crash mid-swap is recovered
        # by the marker check above on the next invocation.
        stale = source_root + ".old-%d" % os.getpid()
        if os.path.isdir(source_root):
            os.rename(source_root, stale)
        os.rename(staging, source_root)
        shutil.rmtree(stale, ignore_errors=True)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    for entry in os.listdir(cache_dir()):
        if entry.startswith(".staging-") or entry.startswith("source.old-"):
            shutil.rmtree(os.path.join(cache_dir(), entry),
                          ignore_errors=True)
    _add_source_path(source_root)
    return source_root


def _add_source_path(source_root):
    if source_root not in sys.path:
        sys.path.insert(0, source_root)
    pythonpath = os.environ.get("PYTHONPATH", "")
    entries = pythonpath.split(os.pathsep) if pythonpath else []
    if source_root not in entries:
        os.environ["PYTHONPATH"] = os.pathsep.join(
            [source_root] + entries) if entries else source_root


def _extract_prefix(tar_path, prefix, dest_dir):
    count = 0
    total = 0
    with tarfile.open(tar_path, "r:gz") as archive:
        for member in archive.getmembers():
            if not member.name.startswith(prefix):
                continue
            rel = member.name[len(prefix):]
            if member.isdir() or not rel:
                continue
            parts = rel.split("/")
            if any(part in ("", ".", "..") for part in parts):
                raise ComposeError(
                    "%s: refusing unsafe member path %r" % (tar_path, member.name))
            if member.issym():
                # In-tree symlinks only (the udocker tar ships e.g.
                # udocker -> maincmd.py); a link resolving outside dest_dir
                # is rejected.
                target = os.path.realpath(
                    os.path.join(dest_dir, os.path.dirname(rel),
                                 member.linkname))
                if not _is_within(target, os.path.realpath(dest_dir)):
                    raise ComposeError(
                        "%s: refusing escaping symlink %r -> %r"
                        % (tar_path, member.name, member.linkname))
                link_path = os.path.join(dest_dir, *parts)
                os.makedirs(os.path.dirname(link_path), exist_ok=True)
                os.symlink(member.linkname, link_path)
                continue
            if not member.isreg():
                raise ComposeError(
                    "%s: refusing non-regular member %r" % (tar_path, member.name))
            count += 1
            total += member.size
            if member.size > MAX_MEMBER_BYTES or total > MAX_TOTAL_BYTES \
                    or count > MAX_TAR_MEMBERS:
                raise ComposeError(
                    "%s: payload exceeds extraction limits" % tar_path)
            target = os.path.join(dest_dir, *parts)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            source = archive.extractfile(member)
            with open(target, "wb") as out:
                shutil.copyfileobj(source, out, 1 << 16)
            source.close()
            os.chmod(target, 0o644)
    if not count:
        raise ComposeError("%s: no files found under %s" % (tar_path, prefix))


def _yaml():
    """Import the vendored pure-Python PyYAML (extracts on first use)."""
    source_root = ensure_extracted()
    _add_source_path(source_root)
    import yaml  # noqa: E402  (vendored, from the extracted source root)
    return yaml


# ---------------------------------------------------------------------------
# Compose subset parser — mirrors the Java ComposeYamlParser/domain contract
# (services.web.ports[0]-style path errors, same supported keys, same rules).
# ---------------------------------------------------------------------------

def parse_compose(text, project_name):
    """Parse and validate a compose document.

    Returns {"services": {name: spec, ...}, "order": [topological names]}.
    Raises ComposeError with the offending path on any violation.
    """
    project_name = require_name(project_name, "project")
    if not isinstance(text, str) or not text.strip():
        _fail("", "compose yaml must not be blank")
    if len(text.encode("utf-8")) > MAX_DOC_BYTES:
        _fail("", "compose document exceeds the 1 MiB limit")
    document = _load_yaml(text)
    root = _as_map(document, "")
    _reject_unknown(root, _ROOT_KEYS, "")
    if root.get("name") is not None:
        declared = _scalar_text(root["name"], "name").strip()
        if declared != project_name:
            _fail("name", "'%s' does not match project name '%s'"
                  % (declared, project_name))
    if "version" in root:
        _scalar_text(root["version"], "version")  # scalar required; ignored

    services_node = root.get("services")
    if services_node is None:
        _fail("services", "is required")
    services_map = _as_map(services_node, "services")
    if not services_map:
        _fail("services", "must declare at least one service")
    services = {}
    for name, node in services_map.items():
        services[name] = _parse_service(name, node)
    for name, spec in services.items():
        for dep in spec["depends_on"]:
            if dep not in services:
                _fail("services.%s.depends_on" % name,
                      "unknown service '%s'" % dep)
    _check_acyclic(services)
    return {"services": services, "order": _topo_order(services)}


def _load_yaml(text):
    yaml = _yaml()
    try:
        # Anchors (&x), aliases (*x) and tags (!t / !!t) are rejected up
        # front: the subset forbids them entirely rather than capping them.
        # Class-name comparison is deliberate: it is immune to sys.modules
        # aliasing between two copies of the same PyYAML version.
        for token in yaml.scan(text):
            if type(token).__name__ in ("AnchorToken", "AliasToken",
                                        "TagToken"):
                raise ComposeError(
                    "yaml anchors, aliases and tags are not supported")
    except ComposeError:
        raise
    except Exception as invalid:
        raise ComposeError("invalid yaml: %s" % invalid)

    class _StrictLoader(yaml.SafeLoader):
        pass

    def construct_mapping(loader, node, deep=False):
        # Registered only for the default mapping tag; the tag check doubles
        # as the node-type guard (string compare, module-identity proof).
        if getattr(node, "tag", None) != "tag:yaml.org,2002:map":
            raise ComposeError("invalid yaml: expected a mapping")
        mapping = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=True)
            if key == "<<":
                raise ComposeError("yaml merge keys (<<) are not supported")
            if not isinstance(key, (str, int, float, bool, type(None))):
                raise ComposeError("yaml mapping keys must be scalars")
            if key in mapping:
                raise ComposeError("duplicate yaml key: %r" % key)
            mapping[key] = loader.construct_object(value_node, deep=True)
        return mapping

    _StrictLoader.add_constructor(
        "tag:yaml.org,2002:map", construct_mapping)
    try:
        return yaml.load(text, Loader=_StrictLoader)
    except ComposeError:
        raise
    except RecursionError:
        raise ComposeError("compose document is nested too deeply")
    except Exception as invalid:
        raise ComposeError("invalid yaml: %s" % invalid)


def _parse_service(name, node):
    path = "services." + name
    service = _as_map(node, path)
    _reject_unknown(service, _SERVICE_KEYS, path)
    spec = {
        "name": require_name(name, path),
        "image": require_image_ref(
            _required_string(service.get("image"), path + ".image"),
            path + ".image"),
        "command": _parse_args(service.get("command"), path + ".command"),
        "entrypoint": _parse_args(
            service.get("entrypoint"), path + ".entrypoint"),
        "environment": _parse_environment(
            service.get("environment"), path + ".environment"),
        "volumes": _parse_volumes(service.get("volumes"), path + ".volumes"),
        "ports": _parse_ports(service.get("ports"), path + ".ports"),
        "working_dir": _optional_string(
            service.get("working_dir"), path + ".working_dir"),
        "depends_on": _parse_depends_on(
            service.get("depends_on"), path + ".depends_on"),
        "restart": _parse_restart(service.get("restart"), path + ".restart"),
    }
    if spec["working_dir"] is not None:
        require_abs_guest_path(spec["working_dir"], path + ".working_dir")
    seen_targets = set()
    for index, volume in enumerate(spec["volumes"]):
        if volume["target"] in seen_targets:
            _fail("%s.volumes[%d]" % (path, index),
                  "duplicate volume target: " + volume["target"])
        seen_targets.add(volume["target"])
    seen_ports = set()
    for index, port in enumerate(spec["ports"]):
        identity = "%s:%s/%s" % (
            port["host_port"], port["container_port"], port["protocol"])
        if identity in seen_ports:
            _fail("%s.ports[%d]" % (path, index),
                  "duplicate host:container:protocol port mapping: " + identity)
        seen_ports.add(identity)
    for dep in spec["depends_on"]:
        if dep == name:
            _fail(path + ".depends_on",
                  "service '%s' must not depend on itself" % name)
    return spec


def _parse_args(node, path):
    """Scalar -> [/bin/sh, -c, text]; list -> literal argv (Java parity)."""
    if node is None:
        return []
    if isinstance(node, str):
        return ["/bin/sh", "-c", _no_interp(node, path)]
    if not isinstance(node, list):
        _fail(path, "must be a string or a list of arguments")
    args = []
    for index, item in enumerate(node):
        if item is None:
            _fail("%s[%d]" % (path, index), "argument must not be null")
        arg = _scalar_text(item, "%s[%d]" % (path, index))
        if not arg.strip():
            _fail("%s[%d]" % (path, index), "argument must not be blank")
        _no_nul(arg, "%s[%d]" % (path, index))
        args.append(arg)
    return args


def _parse_environment(node, path):
    environment = {}
    if node is None:
        return environment
    if isinstance(node, dict):
        for key, value in _as_map(node, path).items():
            _require_env_key(key, path)
            if value is None:
                _fail(path + "." + key,
                      "value must not be null; host environment is never read")
            environment[key] = _scalar_text(value, path + "." + key)
            _no_nul(environment[key], path + "." + key)
        return environment
    if not isinstance(node, list):
        _fail(path, "must be a mapping or a list of KEY=VALUE entries")
    for index, item in enumerate(node):
        item_path = "%s[%d]" % (path, index)
        entry = _scalar_text(item, item_path)
        equals = entry.find("=")
        if equals < 0:
            _fail(item_path, "key-only entries are not supported; use "
                  "KEY=VALUE (host environment is never read)")
        key = entry[:equals]
        _require_env_key(key, item_path)
        value = entry[equals + 1:]
        _no_nul(value, item_path)
        environment[key] = value
    return environment


def _require_env_key(key, path):
    if not _ENV_KEY_RE.match(key):
        _fail(path, "invalid environment name '%s' "
              "(expected [A-Za-z_][A-Za-z0-9_]*)" % key)


def _parse_volumes(node, path):
    if node is None:
        return []
    items = _as_list(node, path)
    volumes = []
    for index, item in enumerate(items):
        item_path = "%s[%d]" % (path, index)
        if isinstance(item, dict):
            volumes.append(_parse_long_volume(_as_map(item, item_path), item_path))
        else:
            volumes.append(_parse_short_volume(
                _scalar_text(item, item_path), item_path))
    return volumes


def _parse_short_volume(text, path):
    parts = text.split(":")
    read_only = False
    if len(parts) == 1:
        _fail(path, "anonymous volumes are not supported; declare source:target")
    if len(parts) == 3:
        if parts[2] in ("ro", "rw"):
            read_only = parts[2] == "ro"
        else:
            _fail(path, "unsupported volume mode '%s' (only ro or rw)" % parts[2])
    elif len(parts) > 3:
        _fail(path, "expected source:target[:ro|rw]")
    source, target = parts[0], parts[1]
    _require_bind_source(source, path)
    return _new_volume(source, target, read_only, path)


def _parse_long_volume(volume, path):
    _reject_unknown(volume, _VOLUME_KEYS, path)
    vol_type = _required_string(volume.get("type"), path + ".type")
    if vol_type.strip() != "bind":
        _fail(path + ".type", "only bind mounts are supported: " + vol_type)
    source = _required_string(volume.get("source"), path + ".source")
    target = _required_string(volume.get("target"), path + ".target")
    _require_bind_source(source, path + ".source")
    read_only = False
    flag_node = volume.get("read_only")
    if flag_node is not None:
        if isinstance(flag_node, bool):
            read_only = flag_node
        else:
            flag = _scalar_text(flag_node, path + ".read_only").strip()
            if flag.lower() in ("true", "false"):
                read_only = flag.lower() == "true"
            else:
                _fail(path + ".read_only", "must be true or false: " + flag)
    return _new_volume(source, target, read_only, path)


def _require_bind_source(source, path):
    if not source.strip():
        _fail(path, "volume source must not be blank")
    path_like = (source.startswith(("/", ".", "~")) or "/" in source)
    if not path_like:
        _fail(path, "named volumes are not supported; bind-mount a path "
              "instead of '%s'" % source)


def _new_volume(source, target, read_only, path):
    if not source.strip():
        _fail(path, "volume source must not be blank")
    _no_nul(source, path)
    require_abs_guest_path(target, path)
    return {"source": source, "target": target, "read_only": read_only}


def _parse_ports(node, path):
    if node is None:
        return []
    items = _as_list(node, path)
    ports = []
    for index, item in enumerate(items):
        item_path = "%s[%d]" % (path, index)
        if isinstance(item, dict):
            ports.append(_parse_long_port(_as_map(item, item_path), item_path))
        elif isinstance(item, (str, int, float)) and not isinstance(item, bool):
            ports.append(_parse_short_port(_scalar_text(item, item_path), item_path))
        else:
            _fail(item_path, "must be a port mapping string or a mapping")
    return ports


def _parse_short_port(text, path):
    protocol = None
    slash = text.rfind("/")
    endpoint = text[:slash] if slash >= 0 else text
    if slash >= 0:
        protocol = text[slash + 1:]
    host_address = None
    if endpoint.startswith("["):
        close = endpoint.find("]")
        if close < 0 or close + 1 >= len(endpoint) \
                or endpoint[close + 1] != ":":
            _fail(path, "malformed bracketed host address: " + text)
        host_address = endpoint[1:close]
        endpoint = endpoint[close + 2:]
    segments = endpoint.split(":")
    if len(segments) == 2:
        host_port, container_port = segments
    elif len(segments) == 3 and host_address is None:
        host_address, host_port, container_port = segments
    elif len(segments) == 1:
        _fail(path, "container-only ports are not supported; "
              "declare host:container")
    else:
        _fail(path, "expected [host_ip:]host:container[/protocol]; "
              "bracket IPv6 host addresses")
    if host_address is not None and not host_address.strip():
        _fail(path, "host address must not be blank: " + text)
    host = _parse_port_number(host_port, path)
    container = _parse_port_number(container_port, path)
    return _new_port(host, container, protocol, host_address, path)


def _parse_long_port(port, path):
    _reject_unknown(port, _PORT_KEYS, path)
    container = _parse_port_number(
        _required(port.get("target"), path + ".target"), path + ".target")
    if port.get("published") is None:
        _fail(path, "container-only ports are not supported; "
              "declare 'published'")
    host = _parse_port_number(port.get("published"), path + ".published")
    protocol = _optional_string(port.get("protocol"), path + ".protocol")
    host_address = _optional_string(port.get("host_ip"), path + ".host_ip")
    return _new_port(host, container, protocol, host_address, path)


def _parse_port_number(node, path):
    if isinstance(node, bool) or node is None:
        _fail(path, "port must be an integer: %s" % node)
    if isinstance(node, float):
        _fail(path, "port must be an integer: %s" % node)
    if isinstance(node, int):
        value = node
    else:
        text = _scalar_text(node, path).strip()
        try:
            value = int(text)
        except ValueError:
            _fail(path, "port must be an integer: " + text)
    if not 1 <= value <= 65535:
        _fail(path, "port must be between 1 and 65535: %d" % value)
    return value


def _new_port(host, container, protocol, host_address, path):
    if protocol is None:
        normalized = "tcp"
    else:
        normalized = protocol.strip().lower()
        if normalized not in ("tcp", "udp"):
            _fail(path, "protocol must be tcp or udp: " + protocol)
    if host_address is None or not host_address.strip():
        numeric_address = None
    else:
        numeric_address = host_address.strip()
        if "%" in numeric_address or not _is_ip_literal(numeric_address):
            _fail(path, "hostAddress must be a numeric IPv4 or IPv6 literal, "
                  "not a name: " + numeric_address)
    return {"host_port": host, "container_port": container,
            "protocol": normalized, "host_ip": numeric_address}


def _is_ip_literal(text):
    try:
        ipaddress.ip_address(text)
        return True
    except ValueError:
        return False


def _parse_depends_on(node, path):
    if node is None:
        return []
    if not isinstance(node, list):
        _fail(path, "only the short list form is supported "
              "(no condition mappings)")
    dependencies = []
    for index, item in enumerate(node):
        if not isinstance(item, str):
            _fail("%s[%d]" % (path, index), "must be a service name string")
        dependencies.append(_no_interp(item, "%s[%d]" % (path, index)))
    return dependencies


def _parse_restart(node, path):
    if node is None:
        return "no"
    if isinstance(node, bool):
        if not node:
            return "no"  # YAML 1.1 'no' parses as boolean false
        _fail(path, "boolean restart must be false; "
              "use no, always or unless-stopped")
    if not isinstance(node, str):
        _fail(path, "must be one of no, always or unless-stopped")
    normalized = _no_interp(node, path).strip().lower()
    if not normalized:
        _fail(path, "restart policy must not be blank")
    if normalized not in _RESTART_VALUES:
        _fail(path, "unsupported restart policy: " + node)
    return normalized


def _required(node, path):
    if node is None:
        _fail(path, "is required")
    return node


def _required_string(node, path):
    node = _required(node, path)
    if not isinstance(node, str):
        _fail(path, "must be a string")
    return _no_interp(node, path)


def _optional_string(node, path):
    if node is None:
        return None
    if not isinstance(node, str):
        _fail(path, "must be a string")
    return _no_interp(node, path)


def _scalar_text(node, path):
    if node is None:
        _fail(path, "must not be null")
    if isinstance(node, bool):
        return "true" if node else "false"
    if isinstance(node, (int, float)):
        return str(node)
    if isinstance(node, str):
        return _no_interp(node, path)
    _fail(path, "must be a scalar")


def _no_interp(text, path):
    if "${" in text:
        _fail(path, "must not contain ${...} interpolation; "
              "the host environment is never read")
    return text


def _no_nul(text, path):
    if text is not None and "\0" in text:
        _fail(path, "must not contain NUL")


def _as_map(node, path):
    if not isinstance(node, dict):
        _fail(path, "must be a mapping")
    result = {}
    for key, value in node.items():
        result[_scalar_text(key, path)] = value
    return result


def _as_list(node, path):
    if not isinstance(node, list):
        _fail(path, "must be a list")
    return node


def _reject_unknown(mapping, allowed, path):
    for key in mapping:
        if key not in allowed:
            _fail(path + "." + key if path else key,
                  "unsupported key; supported: %s" % list(allowed))


# ---- value-object rules mirrored from the Java domain ----------------------

def require_name(value, field):
    text = value.strip() if isinstance(value, str) else ""
    if not text:
        raise ComposeError(field + " must not be blank")
    if not _NAME_RE.match(text):
        raise ComposeError(field + " may only contain ASCII letters, digits, "
                           "'.', '_' and '-': " + text)
    if not _NAME_FIRST_RE.match(text[0]):
        raise ComposeError(field + " must not begin with '.' or '-': " + text)
    return text


def require_image_ref(value, field):
    text = value.strip() if isinstance(value, str) else ""
    if not text:
        raise ComposeError(field + " must not be blank")
    if not _IMAGE_REF_RE.match(text):
        raise ComposeError(field + " may only contain ASCII letters, digits "
                           "and './_-:@': " + text)
    if text[0] in ".-/":
        raise ComposeError(
            field + " must not begin with '.', '-' or '/': " + text)
    if text.count("@") > 1:
        raise ComposeError(field + " must not contain more than one '@': " + text)
    name, at, digest = text.partition("@")
    if not name:
        raise ComposeError(field + " must name an image: " + text)
    if at:
        hex_part = digest[len("sha256:"):]
        if (not digest.startswith("sha256:") or len(hex_part) != 64
                or not re.match(r"[0-9a-fA-F]+\Z", hex_part)):
            raise ComposeError(field + " digest must be 'sha256:' followed "
                               "by 64 hex digits: " + text)
    for segment in name.split("/"):
        if not segment:
            raise ComposeError(
                field + " must not contain an empty path component: " + text)
        if segment == "..":
            raise ComposeError(
                field + " must not contain '..' path segments: " + text)
        colon = segment.find(":")
        if colon == 0 or colon == len(segment) - 1 \
                or (colon >= 0 and segment.find(":", colon + 1) >= 0):
            raise ComposeError(
                field + " must not contain an empty name/tag piece: " + text)
    return text


def require_abs_guest_path(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ComposeError(field + " must not be blank")
    _no_nul(value, field)
    if not value.startswith("/"):
        raise ComposeError(field + " must be an absolute guest path: " + value)
    for segment in value.split("/"):
        if segment == "..":
            raise ComposeError(
                field + " must not contain '..' segments: " + value)
    return value


def _check_acyclic(services):
    done = set()
    visiting = []

    def visit(name):
        if name in done:
            return
        if name in visiting:
            cycle = visiting[visiting.index(name):] + [name]
            raise ComposeError(
                "depends_on cycle detected: " + " -> ".join(cycle))
        visiting.append(name)
        for dep in services[name]["depends_on"]:
            visit(dep)
        visiting.pop()
        done.add(name)

    for name in services:
        visit(name)


def _topo_order(services):
    """Kahn's algorithm in declared order; dependencies first."""
    indegree = {name: 0 for name in services}
    dependents = {name: [] for name in services}
    for name, spec in services.items():
        for dep in spec["depends_on"]:
            indegree[name] += 1
            dependents[dep].append(name)
    queue = [name for name in services if indegree[name] == 0]
    order = []
    while queue:
        name = queue.pop(0)
        order.append(name)
        for dependent in dependents[name]:
            indegree[dependent] -= 1
            if indegree[dependent] == 0:
                queue.append(dependent)
    if len(order) != len(services):
        raise ComposeError("depends_on cycle detected")
    return order


# ---------------------------------------------------------------------------
# Plan normalization: workspace confinement + runtime-enforceability
# ---------------------------------------------------------------------------

def normalize_plan(parsed, compose_path):
    """Resolve bind sources under the workspace and build the plan dict."""
    workspace = os.path.realpath(workspace_root())
    compose_real = os.path.realpath(compose_path)
    if not _is_within(compose_real, workspace):
        raise ComposeError(
            "%s: compose file must be inside the workspace %s "
            "(/etc, /sdcard and other host paths are not accepted)"
            % (compose_real, workspace))
    for name, spec in parsed["services"].items():
        for index, volume in enumerate(spec["volumes"]):
            volume["source"] = _resolve_bind_source(
                volume["source"], os.path.dirname(compose_real), workspace,
                "services.%s.volumes[%d]" % (name, index))
    return {
        "schema": 1,
        "project": parsed["project"],
        "source": compose_real,
        "workdir": os.path.dirname(compose_real),
        "workspace": workspace,
        "services": parsed["services"],
        "order": parsed["order"],
        "desired": True,
        "manual_stop": False,
        "created_at": time.time(),
        "updated_at": time.time(),
    }


def _is_within(path, root):
    return path == root or path.startswith(root.rstrip("/") + "/")


def _resolve_bind_source(source, base_dir, workspace, path):
    if source.startswith("~"):
        candidate = os.path.expanduser(source)
    elif source.startswith("/"):
        candidate = source
    else:
        candidate = os.path.join(base_dir, source)
    resolved = os.path.realpath(candidate)
    if not _is_within(resolved, workspace):
        _fail(path, "volume source '%s' resolves to '%s', which is outside "
              "the workspace %s" % (source, resolved, workspace))
    return resolved


def runtime_problems(spec):
    """Unenforceable declarations for one service (never silently dropped).

    udocker --volume is always read-write, so an ro bind must fail loudly.
    Every ports declaration is rejected outright: device traffic
    verification (S10e, 2026-09-16) showed udocker/PRoot strips the
    declared host_ip and provides no loopback-only enforcement — the
    container stayed reachable on the LAN — so no publish can be made
    honest. The parser still accepts port syntax into the model; this
    boundary is where it becomes a refusal. Environment values travel via
    a line-based --env-file (never argv, which would leak secrets through
    /proc/<pid>/cmdline), so newline values that would corrupt that format
    are unenforceable too.
    """
    problems = []
    for index, volume in enumerate(spec["volumes"]):
        if volume["read_only"]:
            problems.append(
                "services.%s.volumes[%d]: read-only bind %s:%s cannot be "
                "enforced (udocker --volume is always read-write)"
                % (spec["name"], index, volume["source"], volume["target"]))
    for key, value in spec["environment"].items():
        env_path = "services.%s.environment.%s" % (spec["name"], key)
        if not isinstance(value, str):
            problems.append(env_path + ": value must be a string")
        elif "\n" in value or "\r" in value:
            problems.append(
                env_path + ": value must not contain newlines "
                "(the env-file transport cannot represent them)")
    for index, port in enumerate(spec["ports"]):
        problems.append("services.%s.ports[%d]: %s"
                        % (spec["name"], index, PORTS_UNSUPPORTED))
    return problems


def container_name(project, service):
    return project + "_" + service


def wrapper_path():
    """The lw-udocker executable used to spawn supervised children."""
    override = os.environ.get("LW_UDOCKER")
    if override:
        return override
    here = os.path.dirname(os.path.realpath(__file__))
    sibling = os.path.join(here, "lw-udocker")
    if os.path.isfile(sibling):
        return sibling
    return "/usr/local/bin/udocker"


def wrapper_argv_prefix():
    """Argv prefix that execs the wrapper under the guest interpreter."""
    wrapper = wrapper_path()
    if os.access(wrapper, os.X_OK):
        return [wrapper]
    return [sys.executable or "/usr/bin/python3.12", wrapper]


def env_file_path(project, service):
    return os.path.join(project_dir(project), "run", service + ".env")


def write_env_file(project, service, environment):
    """Write the per-service udocker env file and return its path.

    Returns None when the service declares no environment. The file holds
    one KEY=VALUE line per variable and is written atomically with mode
    0600 under the project run directory; it is the only channel for env
    values, which must never reach process argv. Newlines are refused here
    even though the parser stores them: they would inject extra variables
    into udocker's line-based format, so a bad value fails closed.
    """
    if not environment:
        return None
    lines = []
    for key, value in environment.items():
        env_path = "services.%s.environment.%s" % (service, key)
        _require_env_key(key, "services.%s.environment" % service)
        if not isinstance(value, str):
            _fail(env_path, "value must be a string")
        _no_nul(value, env_path)
        if "\n" in value or "\r" in value:
            _fail(env_path, "value must not contain newlines; it cannot "
                  "be represented in an env file")
        lines.append("%s=%s\n" % (key, value))
    path = env_file_path(project, service)
    atomic_write(path, "".join(lines), mode=0o600)
    return path


def discard_env_file(path):
    """Remove an env file written by write_env_file (best effort)."""
    if not path:
        return
    try:
        os.unlink(path)
    except OSError:
        pass


def build_run_argv(plan, service_name, env_file=None):
    """Deterministic udocker argv for one service (no shell anywhere).

    Environment values never travel on argv: when the service declares any,
    env_file must name the per-service file written by write_env_file and
    only --env-file=<path> reaches the command line.
    """
    spec = plan["services"][service_name]
    container = container_name(plan["project"], service_name)
    argv = wrapper_argv_prefix() + [
        "--allow-root", "run",
        "--name=" + container,
        "--pull=reuse",
    ]
    if spec["environment"]:
        if not env_file:
            raise ComposeError(
                "services.%s.environment: refusing to place values on "
                "process argv; write an env file and pass env_file"
                % service_name)
        argv.append("--env-file=" + env_file)
    for volume in spec["volumes"]:
        argv.append("--volume=%s:%s" % (volume["source"], volume["target"]))
    if spec["ports"]:
        # runtime_problems() refuses ports at 'up' and at every supervisor
        # scan; this guard keeps a persisted or hand-edited plan that still
        # carries them from being silently dropped instead of published.
        raise ComposeError("services.%s.ports: %s"
                           % (service_name, PORTS_UNSUPPORTED))
    if spec["working_dir"]:
        argv.append("--workdir=" + spec["working_dir"])
    entrypoint = spec["entrypoint"]
    if entrypoint:
        argv.append("--entrypoint=" + entrypoint[0])
    argv.append(spec["image"])
    # Entrypoint tail args precede the command (udocker has no separate
    # entrypoint-args channel).
    argv.extend(entrypoint[1:])
    argv.extend(spec["command"])
    return argv


def redact_argv(argv):
    """Argv safe for logs: env values are replaced with a marker."""
    redacted = []
    for token in argv:
        if token.startswith("--env=") and "=" in token[len("--env="):]:
            key = token[len("--env="):].split("=", 1)[0]
            redacted.append("--env=%s=<redacted>" % key)
        else:
            redacted.append(token)
    return redacted


# ---------------------------------------------------------------------------
# Persistent state (all writes atomic: temp file + rename + dir fsync)
# ---------------------------------------------------------------------------

def project_dir(project):
    return os.path.join(state_dir(), project)


def plan_path(project):
    return os.path.join(project_dir(project), "project.json")


def load_plan(project):
    try:
        with open(plan_path(project), "r") as handle:
            plan = json.load(handle)
    except FileNotFoundError:
        raise ComposeError(
            "project '%s' has no state (was it ever 'up'?)" % project)
    except (ValueError, OSError) as invalid:
        raise ComposeError(
            "project '%s' state is unreadable: %s" % (project, invalid))
    return plan


def atomic_write(path, data, mode=0o644):
    if isinstance(data, str):
        data = data.encode("utf-8")
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".tmp-", dir=directory)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp, mode)
        os.replace(tmp, path)
        _fsync_dir(directory)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def _fsync_dir(directory):
    try:
        fd = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    except OSError:
        pass


def save_plan(plan):
    plan["updated_at"] = time.time()
    atomic_write(plan_path(plan["project"]),
                 json.dumps(plan, indent=2, sort_keys=True) + "\n")


def status_path(project, service):
    return os.path.join(project_dir(project), "run", service + ".status")


def pid_path(project, service):
    return os.path.join(project_dir(project), "run", service + ".pid")


def log_path(project, service):
    return os.path.join(project_dir(project), "logs", service + ".log")


def write_status(project, service, **fields):
    fields.setdefault("container", container_name(project, service))
    fields["updated"] = time.time()
    atomic_write(status_path(project, service),
                 json.dumps(fields, sort_keys=True) + "\n")


def read_status(project, service):
    try:
        with open(status_path(project, service), "r") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {"state": "not-started", "container":
                container_name(project, service)}


def supervisor_pid_path():
    return os.path.join(state_dir(), "supervisor.pid")


def signal_supervisor():
    """Ask the running global supervisor to rescan now (SIGHUP).

    Returns 'signaled', 'stale', 'not-running' or 'unreachable'. Polling
    alone would eventually converge; the signal only removes latency.
    """
    try:
        with open(supervisor_pid_path(), "r") as handle:
            pid = int(handle.read().strip())
    except (OSError, ValueError):
        return "not-running"
    if pid <= 0:
        return "not-running"
    try:
        os.kill(pid, signal.SIGHUP)
        return "signaled"
    except ProcessLookupError:
        try:
            os.unlink(supervisor_pid_path())
        except OSError:
            pass
        return "stale"
    except PermissionError:
        return "unreachable"


def _pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except (ProcessLookupError, OverflowError):
        return False
    except PermissionError:
        return True


def wait_project_stopped(project, timeout=None):
    """Wait until no pid file under the project names a live process."""
    if timeout is None:
        timeout = DOWN_WAIT_TIMEOUT
    deadline = time.monotonic() + timeout
    run_dir = os.path.join(project_dir(project), "run")
    while True:
        live = []
        try:
            entries = os.listdir(run_dir)
        except OSError:
            entries = []
        for entry in entries:
            if not entry.endswith(".pid"):
                continue
            try:
                with open(os.path.join(run_dir, entry)) as handle:
                    pid = int(handle.read().strip())
            except (OSError, ValueError):
                continue
            if _pid_alive(pid):
                live.append(pid)
        if not live:
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.1)


# ---------------------------------------------------------------------------
# Global supervisor
# ---------------------------------------------------------------------------

class Supervisor(object):
    """One long-lived process supervising every desired compose project.

    Polls state_dir/*/project.json, keeps one udocker child per eligible
    service, and owns restart/backoff/manual-stop semantics. It is the only
    restart authority: systemctl3 units enabled mid-session are not
    supervised on this guest, so per-project units were rejected by design.
    """

    def __init__(self, state=None, poll=None, project=None, out=None):
        self.state = state or state_dir()
        self.poll = float(poll if poll is not None else
                          os.environ.get("LW_COMPOSE_POLL", DEFAULT_POLL))
        self.project = project
        self.out = out or sys.stderr
        self.children = {}   # (project, service) -> record dict
        self._stop = False
        self._wake = threading.Event()
        self._log_handle = None

    # -- logging -----------------------------------------------------------

    def _log(self, message):
        line = "%s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S%z"), message)
        try:
            self.out.write(line)
            self.out.flush()
        except Exception:
            pass
        try:
            log_file = os.path.join(self.state, "supervisor.log")
            os.makedirs(self.state, exist_ok=True)
            if (os.path.isfile(log_file)
                    and os.path.getsize(log_file) > MAX_SUPERVISOR_LOG):
                with open(log_file, "rb") as handle:
                    handle.seek(-min(MAX_SUPERVISOR_LOG // 2,
                                     os.path.getsize(log_file)), 2)
                    tail = handle.read()
                with open(log_file, "wb") as handle:
                    handle.write(tail)
            with open(log_file, "a") as handle:
                handle.write(line)
        except OSError:
            pass

    # -- signal handling ---------------------------------------------------

    def _install_signals(self):
        def on_stop(signum, frame):
            self._stop = True
            self._wake.set()

        def on_reload(signum, frame):
            self._wake.set()

        try:
            signal.signal(signal.SIGTERM, on_stop)
            signal.signal(signal.SIGINT, on_stop)
            signal.signal(signal.SIGHUP, on_reload)
        except ValueError:
            # Not the main thread (embedded/test use): the caller must drive
            # shutdown via self._stop/self._wake instead of signals.
            pass

    # -- pid file / single instance ----------------------------------------

    def _write_pidfile(self):
        existing = None
        try:
            with open(supervisor_pid_path()) as handle:
                existing = int(handle.read().strip())
        except (OSError, ValueError):
            pass
        if existing and existing != os.getpid() and _pid_alive(existing):
            raise ComposeError(
                "lw-compose-supervisor is already running (pid %d)" % existing)
        os.makedirs(self.state, exist_ok=True)
        atomic_write(supervisor_pid_path(), "%d\n" % os.getpid())

    def _remove_pidfile(self):
        try:
            os.unlink(supervisor_pid_path())
        except OSError:
            pass

    # -- orphan recovery ----------------------------------------------------

    def _is_our_child(self, pid, container):
        """Pid alive and its cmdline names our wrapper + this container."""
        try:
            with open("/proc/%d/cmdline" % pid, "rb") as handle:
                cmdline = handle.read()
        except OSError:
            return False
        marker = os.path.basename(wrapper_path()).encode()
        return marker in cmdline and ("--name=" + container).encode() in cmdline

    def _recover_orphans(self):
        """Kill leftover supervised children from a previous supervisor."""
        try:
            projects = os.listdir(self.state)
        except OSError:
            return
        for project in projects:
            run_dir = os.path.join(self.state, project, "run")
            try:
                entries = os.listdir(run_dir)
            except OSError:
                continue
            for entry in entries:
                if not entry.endswith(".pid"):
                    continue
                service = entry[:-4]
                try:
                    with open(os.path.join(run_dir, entry)) as handle:
                        pid = int(handle.read().strip())
                except (OSError, ValueError):
                    pid = None
                container = container_name(project, service)
                if pid and _pid_alive(pid) and self._is_our_child(pid, container):
                    self._log("reaping orphan %s/%s pid=%d"
                              % (project, service, pid))
                    self._kill_pid_group(pid)
                try:
                    os.unlink(os.path.join(run_dir, entry))
                except OSError:
                    pass
                discard_env_file(env_file_path(project, service))
                write_status(project, service, state="stopped", pid=None,
                             exit_code=None, message="reaped stale child")

    @staticmethod
    def _pid_gone(pid):
        """True when pid is dead, including as-yet-unreaped zombies."""
        if not _pid_alive(pid):
            return True
        try:
            with open("/proc/%d/stat" % pid, "rb") as handle:
                return handle.read().rsplit(b")", 1)[1].split()[0] == b"Z"
        except (OSError, IndexError):
            return True

    def _kill_pid_group(self, pid):
        """Stop a foreign process group (orphan recovery); bounded wait."""
        try:
            os.killpg(os.getpgid(pid), signal.SIGTERM)
        except OSError:
            try:
                os.kill(pid, signal.SIGTERM)
            except OSError:
                return
        deadline = time.monotonic() + STOP_TIMEOUT
        while time.monotonic() < deadline and not self._pid_gone(pid):
            time.sleep(0.05)
        if not self._pid_gone(pid):
            try:
                os.killpg(os.getpgid(pid), signal.SIGKILL)
            except OSError:
                try:
                    os.kill(pid, signal.SIGKILL)
                except OSError:
                    pass

    # -- project scanning ----------------------------------------------------

    def _projects(self):
        try:
            names = sorted(os.listdir(self.state))
        except OSError:
            return {}
        found = {}
        for name in names:
            if self.project and name != self.project:
                continue
            path = os.path.join(self.state, name, "project.json")
            if not os.path.isfile(path):
                continue
            try:
                with open(path) as handle:
                    plan = json.load(handle)
                if plan.get("project") != name or not plan.get("services"):
                    raise ValueError("bad plan")
                found[name] = plan
            except (ValueError, OSError) as invalid:
                self._log("ignoring unreadable plan %s: %s" % (path, invalid))
        return found

    def scan_once(self):
        """One reconcile pass; also used by --once dry-run mode."""
        plans = self._projects()
        # Stop children of projects that vanished or became undesired.
        for key, child in list(self.children.items()):
            project, service = key
            plan = plans.get(project)
            desired = bool(plan and plan.get("desired")
                           and not plan.get("manual_stop"))
            if not desired or (plan and service not in plan["services"]):
                self._stop_child(key, "project stopped or removed")
                if not plan or service not in (plan or {}).get("services", {}):
                    self.children.pop(key, None)
        for project, plan in plans.items():
            if not plan.get("desired") or plan.get("manual_stop"):
                continue
            for service in plan.get("order", sorted(plan["services"])):
                if service not in plan["services"]:
                    continue
                self._maybe_start(project, plan, service)
        return plans

    def _maybe_start(self, project, plan, service):
        key = (project, service)
        spec = plan["services"][service]
        problems = runtime_problems(spec)
        child = self.children.get(key)
        if problems:
            if child and child.get("proc") \
                    and child["proc"].poll() is None:
                self._stop_child(key, "unsupported runtime declaration")
            if not child or child.get("state") != "error":
                for problem in problems:
                    self._log(problem)
                self.children[key] = self._record(
                    state="error", errored_at=time.time(),
                    message="; ".join(problems))
                write_status(project, service, state="error", pid=None,
                             exit_code=None,
                             message="; ".join(problems))
            return
        for dep in spec["depends_on"]:
            dep_child = self.children.get((project, dep))
            if dep_child and dep_child.get("state") == "error":
                self._mark_once(key, project, service, "blocked",
                                "dependency '%s' failed to start" % dep)
                return
            if not (dep_child and dep_child.get("ever_spawned")):
                self._mark_once(key, project, service, "waiting",
                                "waiting for dependency '%s'" % dep)
                return
        if child:
            if child.get("proc") is not None:
                # Still running, or exited-but-not-reaped: reap() owns the
                # transition so every exit passes through backoff accounting.
                return
            if child.get("state") == "exited":
                # restart=no is final until the plan is rewritten ('up' or
                # 'start' bumps updated_at and converges the service again).
                if plan.get("updated_at", 0) <= child.get("exited_at", 0):
                    return
            if child.get("state") == "error":
                # A deterministic spawn/input failure is final until the
                # plan is rewritten, same rule as 'exited' above.
                if plan.get("updated_at", 0) <= child.get("errored_at", 0):
                    return
            if child.get("state") == "backoff" \
                    and child.get("next_retry", 0) > time.monotonic():
                return
        self._spawn(project, plan, service, previous=child)

    def _mark_once(self, key, project, service, state, message):
        child = self.children.get(key)
        if not child or child.get("state") != state:
            self.children[key] = self._record(state=state, message=message)
            write_status(project, service, state=state, pid=None,
                         exit_code=None, message=message)
            self._log("%s/%s: %s" % (project, service, message))

    @staticmethod
    def _record(**fields):
        record = {"proc": None, "logf": None, "restarts": 0, "backoff": 0,
                  "next_retry": 0.0, "ever_spawned": False, "exit_code": None,
                  "state": "pending", "message": "", "spawned_at": 0.0,
                  "exited_at": 0.0, "errored_at": 0.0}
        record.update(fields)
        return record

    # -- child lifecycle -----------------------------------------------------

    def _spawn(self, project, plan, service, previous=None):
        key = (project, service)
        spec = plan["services"][service]
        env_file = None
        try:
            env_file = write_env_file(project, service, spec["environment"])
            argv = build_run_argv(plan, service, env_file=env_file)
        except ComposeError as failure:
            # Deterministic input failure: a retry cannot fix it, so mark a
            # terminal error once per plan version instead of backing off.
            self._spawn_error(key, project, service, str(failure))
            return
        path = log_path(project, service)
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if (os.path.isfile(path)
                    and os.path.getsize(path) > MAX_LOG_BYTES):
                os.replace(path, path + ".1")  # single-generation rotation
            logf = open(path, "ab", buffering=0)
        except OSError as failure:
            discard_env_file(env_file)
            self._log("%s/%s: cannot open log: %s" % (project, service, failure))
            self._spawn_failed(key, project, service, str(failure), previous)
            return
        try:
            proc = subprocess.Popen(
                argv, stdin=subprocess.DEVNULL, stdout=logf,
                stderr=subprocess.STDOUT, start_new_session=True,
                cwd=plan.get("workdir") or None)
        except OSError as failure:
            logf.close()
            discard_env_file(env_file)
            self._log("%s/%s: spawn failed: %s" % (project, service, failure))
            self._spawn_failed(key, project, service, str(failure), previous)
            return
        restarts = (previous or {}).get("restarts", 0)
        if previous and previous.get("ever_spawned"):
            restarts += 1
        record = self._record(
            proc=proc, logf=logf, restarts=restarts, ever_spawned=True,
            spawned_at=time.monotonic(), state="running",
            message="pid %d" % proc.pid)
        self.children[key] = record
        atomic_write(pid_path(project, service), "%d\n" % proc.pid)
        write_status(project, service, state="running", pid=proc.pid,
                     exit_code=None, restarts=restarts,
                     message="started")
        self._log("%s/%s started pid=%d argv=%s"
                  % (project, service, proc.pid, redact_argv(argv)))

    def _spawn_failed(self, key, project, service, message, previous):
        backoff = (previous or {}).get("backoff", 0)
        delay = min(BACKOFF_MAX, float(1 << min(backoff, 6)))
        record = self._record(backoff=backoff + 1, next_retry=time.monotonic()
                              + delay, restarts=(previous or {})
                              .get("restarts", 0),
                              ever_spawned=(previous or {})
                              .get("ever_spawned", False),
                              state="backoff",
                              message="spawn failed: %s" % message)
        self.children[key] = record
        write_status(project, service, state="backoff", pid=None,
                     exit_code=None, restarts=record["restarts"],
                     message="spawn failed: %s (retry in %ds)"
                     % (message, int(delay)))

    def _spawn_error(self, key, project, service, message):
        """Terminal per-plan failure: no backoff, retried only on rewrite."""
        self._log("%s/%s: %s" % (project, service, message))
        self.children[key] = self._record(
            state="error", errored_at=time.time(), message=message)
        write_status(project, service, state="error", pid=None,
                     exit_code=None, message=message)

    def reap(self):
        """Poll children, apply restart policy, write statuses."""
        for key, child in list(self.children.items()):
            proc = child.get("proc")
            if proc is None or proc.poll() is None:
                continue
            project, service = key
            exit_code = proc.returncode
            logf = child.pop("logf", None)
            if logf:
                try:
                    logf.close()
                except OSError:
                    pass
            child["proc"] = None
            child["exit_code"] = exit_code
            try:
                os.unlink(pid_path(project, service))
            except OSError:
                pass
            discard_env_file(env_file_path(project, service))
            uptime = time.monotonic() - child.get("spawned_at", 0)
            if uptime >= HEALTHY_SECONDS:
                child["backoff"] = 0
            restart = self._wants_restart(project, service)
            if restart:
                delay = min(BACKOFF_MAX, float(1 << min(child["backoff"], 6)))
                child["backoff"] += 1
                child["next_retry"] = time.monotonic() + delay
                child["state"] = "backoff"
                write_status(project, service, state="backoff", pid=None,
                             exit_code=exit_code,
                             restarts=child["restarts"],
                             message="exited %d; restart %d in %ds"
                             % (exit_code, child["restarts"] + 1,
                                int(delay)))
                self._log("%s/%s exited %d; restarting in %ds"
                          % (project, service, exit_code, int(delay)))
            else:
                child["state"] = "exited"
                child["exited_at"] = time.time()
                write_status(project, service, state="exited", pid=None,
                             exit_code=exit_code,
                             restarts=child["restarts"],
                             message="exited %d" % exit_code)
                self._log("%s/%s exited %d" % (project, service, exit_code))

    def _wants_restart(self, project, service):
        try:
            plan = load_plan(project)
        except ComposeError:
            return False
        if not plan.get("desired") or plan.get("manual_stop"):
            return False
        spec = (plan.get("services") or {}).get(service)
        if not spec:
            return False
        return spec.get("restart") in ("always", "unless-stopped")

    def _stop_child(self, key, reason):
        project, service = key
        child = self.children.get(key)
        if not child or (child.get("state") == "stopped"
                         and not child.get("proc")):
            return
        proc = child.get("proc")
        if proc and proc.poll() is None:
            self._log("stopping %s/%s pid=%d (%s)"
                      % (project, service, proc.pid, reason))
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            except OSError:
                pass
            # poll() reaps our own child, so a zombie never stalls the wait
            deadline = time.monotonic() + STOP_TIMEOUT
            while proc.poll() is None and time.monotonic() < deadline:
                time.sleep(0.05)
            if proc.poll() is None:
                try:
                    os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                except OSError:
                    pass
                try:
                    proc.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    pass
        logf = child.pop("logf", None)
        if logf:
            try:
                logf.close()
            except OSError:
                pass
        child["proc"] = None
        try:
            os.unlink(pid_path(project, service))
        except OSError:
            pass
        discard_env_file(env_file_path(project, service))
        write_status(project, service, state="stopped", pid=None,
                     exit_code=child.get("exit_code"),
                     restarts=child.get("restarts", 0), message=reason)
        child["state"] = "stopped"
        child["next_retry"] = 0.0

    def _stop_all(self):
        for key in list(self.children):
            self._stop_child(key, "supervisor shutdown")

    # -- main loop ------------------------------------------------------------

    def run(self, once=False):
        self._install_signals()
        os.makedirs(self.state, exist_ok=True)
        self._write_pidfile()
        self._log("lw-compose-supervisor started poll=%.1fs state=%s%s"
                  % (self.poll, self.state,
                     " project=%s" % self.project if self.project else ""))
        try:
            self._recover_orphans()
            if once:
                plans = self._projects()
                for name, plan in plans.items():
                    self._log("project %s desired=%s manual_stop=%s "
                              "services=%s"
                              % (name, plan.get("desired"),
                                 plan.get("manual_stop"),
                                 sorted(plan.get("services", {}))))
                return 0
            while not self._stop:
                self.reap()
                self.scan_once()
                self._wake.wait(self.poll)
                self._wake.clear()
        finally:
            self._log("supervisor shutting down")
            self._stop_all()
            self._remove_pidfile()
        return 0


def supervise_main(args):
    project = None
    poll = None
    once = False
    all_projects = False
    index = 0
    while index < len(args):
        arg = args[index]
        if arg == "--all":
            all_projects = True
        elif arg == "--once":
            once = True
        elif arg.startswith("--project="):
            project = arg.split("=", 1)[1]
            require_name(project, "project")
        elif arg == "--project" and index + 1 < len(args):
            index += 1
            project = require_name(args[index], "project")
        elif arg.startswith("--poll="):
            try:
                poll = float(arg.split("=", 1)[1])
            except ValueError:
                raise ComposeError("--poll must be seconds: " + arg)
        elif arg in ("-h", "--help"):
            sys.stdout.write(
                "usage: udocker compose-supervise --all [--project=NAME] "
                "[--poll=SECONDS] [--once]\n"
                "Runs the global compose supervisor in the foreground.\n")
            return 0
        else:
            raise ComposeError("unknown compose-supervise option: " + arg)
        index += 1
    if not all_projects and not project:
        raise ComposeError("compose-supervise requires --all (or --project=NAME)")
    return Supervisor(poll=poll, project=project).run(once=once)


# ---------------------------------------------------------------------------
# compose CLI
# ---------------------------------------------------------------------------

_COMPOSE_USAGE = """usage: udocker compose -f FILE [--project-name NAME] ACTION [ARGS]

actions:
  up -d          validate + persist the project plan; the global
                 lw-compose-supervisor starts the services (required -d)
  down           stop the project and remove its state
  stop           mark the project stopped (services stay stopped)
  start          mark the project desired again
  ps             show per-service status
  logs [SVC]     tail bounded service logs

The global supervisor (udocker compose-supervise --all, or the
lw-compose-supervisor.service unit enabled before session init) is the only
restart authority; this CLI never starts containers itself.
"""


def _derive_project_name(compose_file, explicit):
    if explicit:
        return require_name(explicit, "project name")
    stem = os.path.basename(compose_file)
    for suffix in (".yaml", ".yml"):
        if stem.lower().endswith(suffix):
            stem = stem[:-len(suffix)]
            break
    try:
        return require_name(stem, "project name")
    except ComposeError:
        raise ComposeError(
            "cannot derive a project name from '%s'; pass --project-name"
            % stem)


def compose_main(args):
    compose_file = None
    project_name = None
    action = None
    action_args = []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in ("-f", "--file"):
            if index + 1 >= len(args):
                raise ComposeError("-f requires a file path")
            index += 1
            compose_file = args[index]
        elif arg.startswith("--file="):
            compose_file = arg.split("=", 1)[1]
        elif arg.startswith("-f") and len(arg) > 2:
            compose_file = arg[2:]
        elif arg == "--project-name":
            if index + 1 >= len(args):
                raise ComposeError("--project-name requires a value")
            index += 1
            project_name = args[index]
        elif arg.startswith("--project-name="):
            project_name = arg.split("=", 1)[1]
        elif arg in ("-h", "--help"):
            sys.stdout.write(_COMPOSE_USAGE)
            return 0
        elif arg.startswith("-"):
            raise ComposeError("unknown compose option: " + arg)
        else:
            action = arg
            action_args = args[index + 1:]
            break
        index += 1
    if compose_file is None:
        raise ComposeError("compose requires -f FILE before the action\n"
                           + _COMPOSE_USAGE)
    if action is None:
        raise ComposeError("missing compose action\n" + _COMPOSE_USAGE)
    for token in action_args:
        if token in ("-f", "--file") or token.startswith("--file="):
            raise ComposeError("-f must appear before the action")
    project = _derive_project_name(compose_file, project_name)
    if action == "up":
        return _compose_up(project, compose_file, action_args)
    if action == "down":
        return _compose_down(project)
    if action in ("stop", "start"):
        return _compose_set_desired(project, desired=(action == "start"))
    if action == "ps":
        return _compose_ps(project)
    if action == "logs":
        return _compose_logs(project, action_args)
    raise ComposeError("unknown compose action '%s'\n%s" % (action, _COMPOSE_USAGE))


def _compose_up(project, compose_file, action_args):
    detached = False
    for arg in action_args:
        if arg in ("-d", "--detach"):
            detached = True
        else:
            raise ComposeError("unsupported 'up' argument: " + arg)
    if not detached:
        raise ComposeError(
            "foreground 'up' is not supported: services are owned by the "
            "global supervisor; run 'udocker compose -f %s up -d'"
            % compose_file)
    compose_real = os.path.realpath(compose_file)
    if not os.path.isfile(compose_real):
        raise ComposeError("compose file not found: " + compose_file)
    with open(compose_real, "rb") as handle:
        raw = handle.read(MAX_DOC_BYTES + 1)
    if len(raw) > MAX_DOC_BYTES:
        raise ComposeError("compose document exceeds the 1 MiB limit")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as invalid:
        raise ComposeError("compose file is not utf-8: %s" % invalid)
    parsed = parse_compose(text, project)
    parsed["project"] = project
    plan = normalize_plan(parsed, compose_real)
    problems = []
    for name in plan["order"]:
        problems.extend(runtime_problems(plan["services"][name]))
    if problems:
        raise ComposeError(
            "compose file declares behaviour the runtime cannot honor:\n  "
            + "\n  ".join(problems))
    previous = None
    try:
        previous = load_plan(project)
    except ComposeError:
        pass
    if previous and previous.get("created_at"):
        plan["created_at"] = previous["created_at"]
    directory = project_dir(project)
    os.makedirs(os.path.join(directory, "run"), exist_ok=True)
    os.makedirs(os.path.join(directory, "logs"), exist_ok=True)
    atomic_write(os.path.join(directory, "compose.yaml"), raw)
    save_plan(plan)
    signaled = signal_supervisor()
    services = ", ".join(plan["order"])
    sys.stdout.write(
        "project '%s' is up (%d service%s: %s)\n"
        "  state: %s\n" % (project, len(plan["services"]),
                           "s" if len(plan["services"]) != 1 else "",
                           services, plan_path(project)))
    if signaled == "signaled":
        sys.stdout.write("  supervisor: signaled (services start within "
                         "the poll interval)\n")
    else:
        sys.stdout.write(
            "  supervisor: %s — start it with "
            "'udocker compose-supervise --all' or enable "
            "lw-compose-supervisor.service before session init\n" % signaled)
    return 0


def _compose_down(project):
    plan = load_plan(project)
    plan["desired"] = False
    plan["manual_stop"] = True
    save_plan(plan)
    signaled = signal_supervisor()
    stopped = wait_project_stopped(project)
    if not stopped:
        # A pid file still names a live child (the supervisor is absent or
        # dead, or a child ignored SIGTERM). Removing containers or project
        # state now would orphan it, so keep the state and let a later
        # supervisor (or a retried 'down') reap it.
        sys.stdout.write(
            "project '%s' is marked down but NOT removed: some services are "
            "still running\n"
            "  state kept at %s (desired=false, manual_stop=true)\n"
            "  action: start 'udocker compose-supervise --all' to reap them, "
            "then retry 'down'\n" % (project, plan_path(project)))
        if signaled != "signaled":
            sys.stdout.write("  note: supervisor %s\n" % signaled)
        return 1
    # Every pid file is confirmed dead: removing containers and project
    # state cannot orphan a live child. The rmtree also removes any
    # leftover env files under run/.
    wrapper = wrapper_argv_prefix()
    if os.path.isfile(wrapper[-1]):
        for service in plan.get("services", {}):
            name = container_name(project, service)
            try:
                subprocess.Popen(
                    wrapper + ["--allow-root", "rm", name],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL).wait(timeout=30)
            except (OSError, subprocess.TimeoutExpired):
                pass
    shutil.rmtree(project_dir(project), ignore_errors=True)
    sys.stdout.write("project '%s' is down\n" % project)
    if signaled != "signaled":
        sys.stdout.write("  note: supervisor %s\n" % signaled)
    return 0


def _compose_set_desired(project, desired):
    plan = load_plan(project)
    plan["desired"] = desired
    plan["manual_stop"] = not desired
    save_plan(plan)
    signaled = signal_supervisor()
    action = "started" if desired else "stopped"
    sys.stdout.write("project '%s' marked %s\n" % (project, action))
    if signaled != "signaled":
        sys.stdout.write("  note: supervisor %s\n" % signaled)
    return 0


def _compose_ps(project):
    plan = load_plan(project)
    sys.stdout.write("project %s (desired=%s manual_stop=%s)\n"
                     % (project, plan.get("desired"),
                        plan.get("manual_stop")))
    sys.stdout.write("%-20s %-10s %-6s %-9s %-24s %s\n"
                     % ("SERVICE", "STATE", "EXIT", "RESTARTS",
                        "CONTAINER", "MESSAGE"))
    for service in plan.get("order", sorted(plan.get("services", {}))):
        status = read_status(project, service)
        exit_code = status.get("exit_code")
        sys.stdout.write("%-20s %-10s %-6s %-9s %-24s %s\n" % (
            service, status.get("state", "?"),
            "-" if exit_code is None else exit_code,
            status.get("restarts", 0),
            status.get("container", container_name(project, service)),
            status.get("message", "")))
    return 0


def _compose_logs(project, action_args):
    plan = load_plan(project)
    if len(action_args) > 1:
        raise ComposeError("logs takes at most one service name")
    services = plan.get("order", sorted(plan.get("services", {})))
    if action_args:
        wanted = action_args[0]
        if wanted not in plan.get("services", {}):
            raise ComposeError("unknown service '%s' for project '%s' "
                               "(services: %s)" % (wanted, project,
                                                   ", ".join(services)))
        services = [wanted]
    for service in services:
        sys.stdout.write("==> %s/%s <==\n" % (project, service))
        sys.stdout.write(_tail_file(log_path(project, service)))
        sys.stdout.write("\n")
    return 0


def _tail_file(path):
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as handle:
            if size > LOG_TAIL_BYTES:
                handle.seek(-LOG_TAIL_BYTES, 2)
            data = handle.read()
    except OSError:
        return "(no log)\n"
    text = data.decode("utf-8", "replace")
    lines = text.splitlines()
    if len(lines) > LOG_TAIL_LINES:
        lines = lines[-LOG_TAIL_LINES:]
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# lw-udocker dispatch
# ---------------------------------------------------------------------------

def _command_index(args):
    """First token starting with an ASCII letter = the udocker command word
    (mirrors udocker's own CmdParser: earlier tokens are global options)."""
    for index, arg in enumerate(args):
        if arg and ("A" <= arg[0] <= "Z" or "a" <= arg[0] <= "z"):
            return index
    return None


def lw_udocker_main(argv):
    """Entry point of the packaged lw-udocker launcher."""
    ensure_runtime_env()
    args = list(argv[1:])
    index = _command_index(args)
    command = args[index] if index is not None else None
    rest = args[index + 1:] if index is not None else []
    try:
        if command == "compose":
            return compose_main(rest)
        if command == "compose-supervise":
            return supervise_main(rest)
        if command == "install":
            sys.stderr.write(
                "lw-udocker: 'udocker install' is disabled — NusaDesk "
                "supplies the inner PRoot at /usr/local/bin/proot and does "
                "not download the upstream udocker-englib helper tarball. "
                "The runtime is already configured; no install step is "
                "needed.\n")
            return 1
    except ComposeError as failure:
        sys.stderr.write("lw-udocker: %s\n" % failure)
        return 1
    if "--allow-root" not in args:
        args.insert(0, "--allow-root")
    try:
        ensure_extracted()
    except ComposeError as failure:
        sys.stderr.write("lw-udocker: %s\n" % failure)
        return 1
    sys.argv = ["udocker"] + args
    from udocker.maincmd import main as upstream_main
    try:
        upstream_main()
    except SystemExit as exit_request:
        try:
            return int(exit_request.code or 0)
        except (TypeError, ValueError):
            return 1
    return 0
