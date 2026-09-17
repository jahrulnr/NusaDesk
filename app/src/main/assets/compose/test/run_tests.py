#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Host-runnable deterministic tests for the NusaDesk compose assets.

stdlib-only (unittest); no third-party test dependencies. Everything runs in
a temporary sandbox via the module's LW_COMPOSE_* overrides: the vendored
tarballs are extracted for real, the compose parser is exercised against the
extracted PyYAML, and the supervisor is driven against a fake lw-udocker
child — no real udocker, proot, network or systemd is touched.

Run from the repository root or anywhere:

    python3 app/src/main/assets/compose/test/run_tests.py
"""

import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest

ASSETS = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
sys.path.insert(0, ASSETS)

import lw_compose_runtime as rt  # noqa: E402

VALID_COMPOSE = """\
version: "3"
services:
  web:
    image: docker.io/library/nginx:alpine
    command: ["nginx", "-g", "daemon off;"]
    environment:
      MODE: prod
      EMPTY: ""
    volumes:
      - ./site:/usr/share/nginx/html:rw
    working_dir: /srv
    depends_on: [db]
    restart: always
  db:
    image: postgres:16
    command: postgres -c log_statement=all
    restart: "no"
"""


class SandboxTest(unittest.TestCase):
    """Sandboxed state/cache/workspace per test."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="lwtest-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.state = os.path.join(self.tmp, "state")
        self.cache = os.path.join(self.tmp, "cache")
        self.workspace = os.path.join(self.tmp, "nusadesk")
        os.makedirs(self.workspace)
        # Stage the layout the guest sees: the packaged assets are .tgz (AGP
        # gunzips .gz assets at merge), while the installed overlay keeps the
        # upstream .tar.gz names the runner requires.
        home = os.path.join(self.tmp, "home")
        os.makedirs(home)
        for tar_name in (rt.UDOCKER_TAR, rt.PYYAML_TAR):
            staged = os.path.join(home, tar_name)
            packaged = os.path.join(
                ASSETS, tar_name[:-len(".tar.gz")] + ".tgz")
            try:
                os.symlink(packaged, staged)
            except OSError:
                shutil.copy2(packaged, staged)
        self._env = {}
        for key, value in (
                ("LW_COMPOSE_HOME", home),
                ("LW_COMPOSE_STATE", self.state),
                ("LW_COMPOSE_CACHE", self.cache),
                ("LW_COMPOSE_WORKSPACE", self.workspace)):
            self._env[key] = os.environ.get(key)
            os.environ[key] = value
        self.addCleanup(self._restore_env)

    def _restore_env(self):
        for key, value in self._env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def write_compose(self, text, name="compose.yaml", subdir=""):
        directory = os.path.join(self.workspace, subdir) if subdir \
            else self.workspace
        os.makedirs(directory, exist_ok=True)
        path = os.path.join(directory, name)
        with open(path, "w") as handle:
            handle.write(text)
        return path


class ExtractionTest(SandboxTest):

    def test_extracts_pinned_sources_once(self):
        source = rt.ensure_extracted()
        self.assertTrue(os.path.isfile(
            os.path.join(source, "udocker", "maincmd.py")))
        self.assertTrue(os.path.isfile(
            os.path.join(source, "yaml", "__init__.py")))
        self.assertIn(source, sys.path)
        # warm path is a no-op and keeps sys.path intact
        self.assertEqual(source, rt.ensure_extracted())
        self.assertIn(source, sys.path)
        for module in ("yaml", "udocker"):
            sys.modules.pop(module, None)
        import yaml
        import udocker
        self.assertTrue(yaml.__file__.startswith(source))
        self.assertTrue(udocker.__file__.startswith(source))

    def test_runtime_env_contract(self):
        rt.ensure_runtime_env()
        self.assertEqual("/usr/local/bin/proot",
                         os.environ["UDOCKER_USE_PROOT_EXECUTABLE"])
        self.assertEqual("/usr/local/bin/proot-loader",
                         os.environ["PROOT_LOADER"])
        self.assertEqual("/tmp", os.environ["PROOT_TMP_DIR"])
        self.assertEqual("", os.environ["UDOCKER_TARBALL"])


class ParserTest(SandboxTest):
    """Mirrors the Java ComposeYamlParser contract."""

    def parse(self, text, project="proj"):
        return rt.parse_compose(text, project)

    def test_valid_document(self):
        parsed = self.parse(VALID_COMPOSE)
        self.assertEqual(["db", "web"], parsed["order"])
        web = parsed["services"]["web"]
        self.assertEqual("docker.io/library/nginx:alpine", web["image"])
        self.assertEqual(["nginx", "-g", "daemon off;"], web["command"])
        self.assertEqual("prod", web["environment"]["MODE"])
        self.assertEqual("always", web["restart"])
        self.assertEqual(["db"], web["depends_on"])
        self.assertEqual([], web["ports"])
        db = parsed["services"]["db"]
        self.assertEqual("no", db["restart"])
        # scalar command -> [/bin/sh, -c, text] (Java parity)
        self.assertEqual(["/bin/sh", "-c", "postgres -c log_statement=all"],
                         db["command"])

    def test_anchors_aliases_tags_rejected(self):
        for bad in ("services:\n  a:\n    image: &x nginx\n    command: *x\n",
                    "services:\n  a:\n    image: !cust nginx\n",
                    "services:\n  a:\n    image: !!str nginx\n"):
            with self.assertRaises(rt.ComposeError):
                self.parse(bad)

    def test_duplicate_key_rejected(self):
        with self.assertRaises(rt.ComposeError) as ctx:
            self.parse("services:\n  a:\n    image: x\n    image: y\n")
        self.assertIn("duplicate", str(ctx.exception))

    def test_merge_key_rejected(self):
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    <<: {image: x}\n")

    def test_interpolation_rejected(self):
        with self.assertRaises(rt.ComposeError) as ctx:
            self.parse("services:\n  a:\n    image: ${REG}/x\n")
        self.assertIn("image", str(ctx.exception))

    def test_unknown_keys_report_path(self):
        with self.assertRaises(rt.ComposeError) as ctx:
            self.parse("services:\n  a:\n    image: x\n    build: .\n")
        self.assertIn("services.a.build", str(ctx.exception))
        with self.assertRaises(rt.ComposeError) as ctx:
            self.parse("services:\n  a:\n    image: x\nnetworks:\n  n: {}\n")
        self.assertIn("networks", str(ctx.exception))

    def test_top_level_name_must_match(self):
        with self.assertRaises(rt.ComposeError):
            self.parse("name: other\nservices:\n  a:\n    image: x\n")
        self.parse("name: proj\nservices:\n  a:\n    image: x\n")

    def test_environment_rules(self):
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    environment: [KEYONLY]\n")
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    environment: {NULLKEY: }\n")
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    environment: {'BAD-KEY': v}\n")
        parsed = self.parse("services:\n  a:\n    image: x\n"
                            "    environment: [A=1, B=]\n")
        self.assertEqual({"A": "1", "B": ""},
                         parsed["services"]["a"]["environment"])

    def test_volume_rules(self):
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    volumes: [data:/data]\n")  # named volume
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    volumes: [/only-target]\n")  # anonymous
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    volumes: ['./a:/x', './b:/x']\n")  # dup target
        parsed = self.parse("services:\n  a:\n    image: x\n"
                            "    volumes: [{type: bind, source: ./d, "
                            "target: /d, read_only: true}]\n")
        self.assertTrue(parsed["services"]["a"]["volumes"][0]["read_only"])

    def test_port_rules(self):
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n    ports: [8080]\n")
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    ports: ['example.com:80:80']\n")  # hostname
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    ports: ['70000:80']\n")
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    ports: ['80:80/sctp']\n")
        parsed = self.parse("services:\n  a:\n    image: x\n"
                            "    ports: ['[::1]:8080:80/udp']\n")
        port = parsed["services"]["a"]["ports"][0]
        self.assertEqual(("::1", 8080, 80, "udp"),
                         (port["host_ip"], port["host_port"],
                          port["container_port"], port["protocol"]))
        # Port syntax stays parseable into the model (host_ip may be null);
        # runtime_problems()/up is the boundary that now rejects every
        # ports declaration — see test_ports_rejected_at_admission.
        parsed = self.parse("services:\n  a:\n    image: x\n"
                            "    ports: ['8080:80']\n")
        self.assertIsNone(parsed["services"]["a"]["ports"][0]["host_ip"])

    def test_restart_rules(self):
        self.assertEqual("no", self.parse(
            "services:\n  a:\n    image: x\n    restart: false\n")
            ["services"]["a"]["restart"])
        self.assertEqual("unless-stopped", self.parse(
            "services:\n  a:\n    image: x\n    restart: unless-stopped\n")
            ["services"]["a"]["restart"])
        for bad in ("on-failure", "true", "\"\""):
            with self.assertRaises(rt.ComposeError):
                self.parse("services:\n  a:\n    image: x\n"
                           "    restart: %s\n" % bad)

    def test_depends_on_rules(self):
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    depends_on: [missing]\n")
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    depends_on: [a]\n")
        with self.assertRaises(rt.ComposeError) as ctx:
            self.parse("services:\n  a:\n    image: x\n    depends_on: [b]\n"
                       "  b:\n    image: x\n    depends_on: [a]\n")
        self.assertIn("cycle", str(ctx.exception))
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x\n"
                       "    depends_on: {b: {condition: service_started}}\n")

    def test_image_and_name_validation(self):
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: 'bad ref'\n")
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  'a b':\n    image: x\n")
        with self.assertRaises(rt.ComposeError):
            self.parse("services:\n  a:\n    image: x@sha256:deadbeef\n")


class WorkspaceTest(SandboxTest):

    def test_compose_must_be_inside_workspace(self):
        outside = os.path.join(self.tmp, "compose.yaml")
        with open(outside, "w") as handle:
            handle.write("services:\n  a:\n    image: x\n")
        with self.assertRaises(rt.ComposeError) as ctx:
            rt.compose_main(["-f", outside, "up", "-d"])
        self.assertIn("workspace", str(ctx.exception))

    def test_volume_escape_rejected(self):
        # ../out from workspace/app stays inside the workspace -> allowed;
        # ../../out escapes the workspace root entirely -> rejected.
        path = self.write_compose(
            "services:\n  a:\n    image: x\n    volumes: [../../out:/x]\n",
            subdir="app")
        with self.assertRaises(rt.ComposeError) as ctx:
            rt.compose_main(["-f", path, "up", "-d"])
        self.assertIn("outside", str(ctx.exception))
        path = self.write_compose(
            "services:\n  a:\n    image: x\n    volumes: [/etc:/x]\n",
            name="abs.yaml")
        with self.assertRaises(rt.ComposeError):
            rt.compose_main(["-f", path, "up", "-d"])

    def test_relative_source_resolves_under_workspace(self):
        os.makedirs(os.path.join(self.workspace, "app", "data"))
        path = self.write_compose(
            "services:\n  a:\n    image: x\n    volumes: [./data:/d]\n",
            subdir="app")
        self.assertEqual(0, rt.compose_main(["-f", path, "up", "-d"]))
        plan = rt.load_plan("compose")  # project name = filename stem
        self.assertEqual(os.path.join(self.workspace, "app", "data"),
                         plan["services"]["a"]["volumes"][0]["source"])


class WrapperPathTest(SandboxTest):
    """Child-spawn wrapper resolution: LW_UDOCKER > sibling > product path."""

    def _set_env(self, key, value):
        saved = os.environ.get(key)
        if value is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = value

        def restore():
            if saved is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = saved
        self.addCleanup(restore)

    def test_installed_fallback_is_usr_local_bin_udocker(self):
        # Product layout: the launcher installs as /usr/local/bin/udocker
        # (the catalog maps compose/lw-udocker -> usr/local/bin/udocker)
        # and no lw-udocker sibling sits next to the installed module, so
        # the fallback is what supervised children exec. Device-verified:
        # the previous /usr/local/bin/lw-udocker fallback made every
        # supervised child exit 2.
        self._set_env("LW_UDOCKER", None)
        self.addCleanup(setattr, rt, "__file__", rt.__file__)
        rt.__file__ = os.path.join(
            self.tmp, "usr", "local", "lib", "nusadesk", "compose",
            "lw_compose_runtime.py")
        self.assertEqual("/usr/local/bin/udocker", rt.wrapper_path())
        # The run argv execs that same path: whether the file is
        # executable or not, the wrapper path is the last prefix token.
        self.assertEqual("/usr/local/bin/udocker",
                         rt.wrapper_argv_prefix()[-1])
        plan = {"project": "p", "services": {"s": {
            "image": "img:1", "command": [], "entrypoint": [],
            "environment": {}, "volumes": [], "ports": [],
            "working_dir": None, "depends_on": [], "restart": "no"}},
            "order": ["s"]}
        argv = rt.build_run_argv(plan, "s")
        self.assertEqual("/usr/local/bin/udocker",
                         argv[len(rt.wrapper_argv_prefix()) - 1])

    def test_override_and_sibling_discovery_still_win(self):
        # LW_UDOCKER beats every other resolution step (fake-child tests
        # and alternate layouts depend on it).
        self._set_env("LW_UDOCKER", "/fake/udocker")
        self.assertEqual("/fake/udocker", rt.wrapper_path())
        # Without the override, an lw-udocker sibling next to the module
        # (this repo layout) still wins over the product fallback.
        self._set_env("LW_UDOCKER", None)
        self.assertEqual(os.path.join(ASSETS, "lw-udocker"),
                         rt.wrapper_path())


FAKE_CHILD = """\
#!%(python)s
import os, sys, time
argv_log = os.environ.get("FAKE_CHILD_ARGV_LOG")
if argv_log:
    with open(argv_log, "a") as handle:
        handle.write(" ".join(sys.argv) + "\\n")
if "rm" in sys.argv:
    sys.exit(0)  # udocker rm is a quick local operation
mode = os.environ.get("FAKE_CHILD_MODE", "sleep")
sys.stdout.write("child %%s mode=%%s\\n" %% (os.getpid(), mode))
sys.stdout.flush()
if mode == "sleep":
    while True:
        time.sleep(0.2)
elif mode == "exit7":
    sys.exit(7)
sys.exit(0)
"""


class CliFlowTest(SandboxTest):

    def setUp(self):
        super().setUp()
        self.fake = os.path.join(self.tmp, "lw-udocker")
        with open(self.fake, "w") as handle:
            handle.write(FAKE_CHILD % {"python": sys.executable})
        os.chmod(self.fake, 0o755)
        os.environ["LW_UDOCKER"] = self.fake
        self.addCleanup(lambda: os.environ.pop("LW_UDOCKER", None))

    def test_up_down_state_cycle(self):
        path = self.write_compose(VALID_COMPOSE)
        self.assertEqual(0, rt.compose_main(["-f", path, "up", "-d"]))
        plan = rt.load_plan("compose")
        self.assertTrue(plan["desired"])
        self.assertFalse(plan["manual_stop"])
        self.assertTrue(os.path.isfile(
            os.path.join(rt.project_dir("compose"), "compose.yaml")))
        # stop/start flip the persisted flags
        self.assertEqual(0, rt.compose_main(["-f", path, "stop"]))
        self.assertTrue(rt.load_plan("compose")["manual_stop"])
        self.assertEqual(0, rt.compose_main(["-f", path, "start"]))
        self.assertTrue(rt.load_plan("compose")["desired"])
        # ps + logs work against stored state
        self.assertEqual(0, rt.compose_main(["-f", path, "ps"]))
        self.assertEqual(0, rt.compose_main(["-f", path, "logs"]))
        # down removes everything
        self.assertEqual(0, rt.compose_main(["-f", path, "down"]))
        self.assertFalse(os.path.exists(rt.project_dir("compose")))

    def test_up_requires_detach(self):
        path = self.write_compose(VALID_COMPOSE)
        with self.assertRaises(rt.ComposeError):
            rt.compose_main(["-f", path, "up"])

    def test_down_refuses_while_child_alive(self):
        path = self.write_compose(VALID_COMPOSE)
        self.assertEqual(0, rt.compose_main(["-f", path, "up", "-d"]))
        project = "compose"
        # A live pid file that cannot die (this test process): down must
        # not run 'udocker rm' nor delete state, or the child is orphaned.
        rt.atomic_write(rt.pid_path(project, "web"), "%d\n" % os.getpid())
        argv_log = os.path.join(self.tmp, "argv.log")
        os.environ["FAKE_CHILD_ARGV_LOG"] = argv_log
        self.addCleanup(lambda: os.environ.pop("FAKE_CHILD_ARGV_LOG", None))
        saved_timeout = rt.DOWN_WAIT_TIMEOUT
        rt.DOWN_WAIT_TIMEOUT = 0.3
        self.addCleanup(setattr, rt, "DOWN_WAIT_TIMEOUT", saved_timeout)
        self.assertEqual(1, rt.compose_main(["-f", path, "down"]))
        # state kept for a later supervisor to reap; still marked down
        self.assertTrue(os.path.isfile(rt.plan_path(project)))
        plan = rt.load_plan(project)
        self.assertFalse(plan["desired"])
        self.assertTrue(plan["manual_stop"])
        self.assertFalse(os.path.exists(argv_log))  # udocker rm never ran
        # once every pid file is dead, down removes containers and state
        os.unlink(rt.pid_path(project, "web"))
        self.assertEqual(0, rt.compose_main(["-f", path, "down"]))
        self.assertFalse(os.path.exists(rt.project_dir(project)))
        with open(argv_log) as handle:
            logged = handle.read()
        self.assertIn("rm", logged)
        self.assertIn("compose_web", logged)

    def test_file_flag_must_precede_action(self):
        path = self.write_compose(VALID_COMPOSE)
        with self.assertRaises(rt.ComposeError):
            rt.compose_main(["up", "-d", "-f", path])
        with self.assertRaises(rt.ComposeError):
            rt.compose_main([path, "up"])  # missing -f entirely

    def test_runtime_rejections(self):
        path = self.write_compose(
            "services:\n  a:\n    image: x\n    volumes: [./d:/d:ro]\n",
            name="ro.yaml")
        with self.assertRaises(rt.ComposeError) as ctx:
            rt.compose_main(["-f", path, "up", "-d"])
        self.assertIn("read-only", str(ctx.exception))
        # env values that cannot fit the KEY=VALUE env-file format fail
        # closed at admission, not inside the spawned child
        path = self.write_compose(
            "services:\n  a:\n    image: x\n"
            "    environment: {BAD: \"line1\\nline2\"}\n",
            name="nl.yaml")
        with self.assertRaises(rt.ComposeError) as ctx:
            rt.compose_main(["-f", path, "up", "-d"])
        self.assertIn("environment.BAD", str(ctx.exception))

    def test_ports_rejected_at_admission(self):
        # Device evidence (S10e traffic test, 2026-09-16): udocker/PRoot
        # strips the declared host_ip — a "127.0.0.1" publish produced no
        # loopback listener while the container stayed on *:8080, reachable
        # via the device LAN IP. With no loopback-only enforcement, EVERY
        # ports declaration fails closed at 'up'; nothing is published and
        # nothing is silently dropped.
        for name, ports in (
                ("short.yaml", "    ports: ['8080:80']\n"),
                ("wild.yaml", "    ports: ['0.0.0.0:8080:80']\n"),
                ("lo4.yaml", "    ports: ['127.0.0.1:8080:80']\n"),
                ("lo6.yaml", "    ports: ['[::1]:8080:80']\n"),
                ("udp.yaml", "    ports: ['53:53/udp']\n"),
                ("long.yaml", "    ports: [{target: 80, published: 8080,"
                 " protocol: tcp, host_ip: 127.0.0.1}]\n")):
            path = self.write_compose(
                "services:\n  a:\n    image: x\n" + ports, name=name)
            with self.assertRaises(rt.ComposeError) as ctx:
                rt.compose_main(["-f", path, "up", "-d"])
            self.assertIn("ports", str(ctx.exception))
            self.assertIn("not supported in this MVP", str(ctx.exception))
            self.assertIn("does not enforce host_ip/loopback",
                          str(ctx.exception))
            # rejected before any project state is persisted
            stem = name.rsplit(".", 1)[0]
            self.assertFalse(os.path.exists(rt.project_dir(stem)))

    def test_run_argv_and_redaction(self):
        plan = {"project": "p", "services": {"s": {
            "image": "img:1", "command": ["run", "me"],
            "entrypoint": ["/entry", "-e"],
            "environment": {"SECRET": "v4lue", "PLAIN": "x"},
            "volumes": [{"source": "/w/d", "target": "/d",
                         "read_only": False}],
            "ports": [],
            "working_dir": "/w", "depends_on": [], "restart": "always"}},
            "order": ["s"]}
        # environment without an env file fails closed: values must never
        # reach process argv (they would leak via /proc/<pid>/cmdline)
        with self.assertRaises(rt.ComposeError):
            rt.build_run_argv(plan, "s")
        env_file = os.path.join(self.tmp, "s.env")
        argv = rt.build_run_argv(plan, "s", env_file=env_file)
        self.assertIn("--name=p_s", argv)
        self.assertIn("--pull=reuse", argv)
        self.assertIn("--env-file=" + env_file, argv)
        self.assertIn("--volume=/w/d:/d", argv)
        # a port-free service emits no publish argv at all
        self.assertNotIn("--publish", " ".join(argv))
        self.assertIn("--workdir=/w", argv)
        self.assertIn("--entrypoint=/entry", argv)
        tail = argv[argv.index("img:1"):]
        self.assertEqual(["img:1", "-e", "run", "me"], tail)
        # the secret value is nowhere on the command line
        self.assertNotIn("v4lue", " ".join(argv))
        for token in argv:
            self.assertFalse(token.startswith("--env="))
        # redact_argv still masks a stray --env= value (defense in depth)
        redacted = rt.redact_argv(argv + ["--env=SECRET=v4lue"])
        self.assertIn("--env=SECRET=<redacted>", redacted)
        self.assertNotIn("v4lue", " ".join(redacted))
        # a service with no environment needs no env file
        plan["services"]["s"]["environment"] = {}
        argv = rt.build_run_argv(plan, "s")
        self.assertNotIn("--env-file", " ".join(argv))

    def test_run_argv_refuses_ports(self):
        # Defense in depth behind runtime_problems(): a persisted or
        # hand-edited plan that still carries ports fails closed at argv
        # build — the declaration is never silently dropped, and no
        # --publish token can reach the child.
        plan = {"project": "p", "services": {"s": {
            "image": "img:1", "command": [], "entrypoint": [],
            "environment": {}, "volumes": [],
            "ports": [{"host_port": 8080, "container_port": 80,
                       "protocol": "tcp", "host_ip": "127.0.0.1"}],
            "working_dir": None, "depends_on": [], "restart": "no"}},
            "order": ["s"]}
        with self.assertRaises(rt.ComposeError) as ctx:
            rt.build_run_argv(plan, "s")
        self.assertIn("ports", str(ctx.exception))
        self.assertIn("not supported in this MVP", str(ctx.exception))

    def test_env_file_0600_and_newline_rejected(self):
        path = rt.write_env_file("p", "s", {"SECRET": "v4lue", "PLAIN": ""})
        self.assertEqual(rt.env_file_path("p", "s"), path)
        with open(path) as handle:
            self.assertEqual("SECRET=v4lue\nPLAIN=\n", handle.read())
        self.assertEqual(0o600, os.stat(path).st_mode & 0o777)
        # a value that would corrupt the KEY=VALUE format fails closed
        for bad in ("line1\nline2", "line1\rline2"):
            with self.assertRaises(rt.ComposeError):
                rt.write_env_file("p", "s", {"BAD": bad})
        with self.assertRaises(rt.ComposeError):
            rt.write_env_file("p", "s", {"BAD\nKEY": "v"})
        self.assertIsNone(rt.write_env_file("p", "s", {}))


class SupervisorTest(SandboxTest):

    def setUp(self):
        super().setUp()
        self.fake = os.path.join(self.tmp, "lw-udocker")
        with open(self.fake, "w") as handle:
            handle.write(FAKE_CHILD % {"python": sys.executable})
        os.chmod(self.fake, 0o755)
        os.environ["LW_UDOCKER"] = self.fake
        self.addCleanup(lambda: os.environ.pop("LW_UDOCKER", None))

    def _up(self, text, name="compose.yaml"):
        path = self.write_compose(text, name=name)
        self.assertEqual(0, rt.compose_main(["-f", path, "up", "-d"]))
        stem = name.rsplit(".", 1)[0]
        return stem

    def _run_supervisor(self):
        sup = rt.Supervisor(poll=0.05)
        # The supervisor runs in a thread inside this test process, so it
        # cannot own SIGHUP; compose_main() signals our own pid. Install a
        # main-thread forwarder so the real SIGHUP path is still exercised.
        import signal
        previous = signal.signal(
            signal.SIGHUP, lambda *a: sup._wake.set())
        self.addCleanup(signal.signal, signal.SIGHUP, previous)
        thread = threading.Thread(target=sup.run, daemon=True)
        thread.start()
        self.addCleanup(self._stop_supervisor, sup, thread)
        return sup

    @staticmethod
    def _stop_supervisor(sup, thread):
        sup._stop = True
        sup._wake.set()
        thread.join(timeout=10)

    def _wait_status(self, project, service, want, timeout=10.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status = rt.read_status(project, service)
            if status.get("state") == want:
                return status
            time.sleep(0.05)
        self.fail("service %s/%s never reached %s (last: %s)"
                  % (project, service, want, status))

    def test_running_then_manual_stop(self):
        os.environ["FAKE_CHILD_MODE"] = "sleep"
        self.addCleanup(lambda: os.environ.pop("FAKE_CHILD_MODE", None))
        project = self._up("services:\n  a:\n    image: x\n"
                           "    restart: always\n")
        self._run_supervisor()
        status = self._wait_status(project, "a", "running")
        self.assertTrue(rt._pid_alive(status["pid"]))
        # manual stop: desired=false -> child stopped, no restart
        path = os.path.join(self.workspace, "compose.yaml")
        rt.compose_main(["-f", path, "stop"])
        self._wait_status(project, "a", "stopped")
        self.assertFalse(os.path.exists(rt.pid_path(project, "a")))

    def test_restart_always_backoff(self):
        os.environ["FAKE_CHILD_MODE"] = "exit7"
        self.addCleanup(lambda: os.environ.pop("FAKE_CHILD_MODE", None))
        project = self._up("services:\n  a:\n    image: x\n"
                           "    restart: always\n")
        self._run_supervisor()
        deadline = time.monotonic() + 15
        restarts = 0
        while time.monotonic() < deadline:
            restarts = rt.read_status(project, "a").get("restarts", 0)
            if restarts >= 2:
                break
            time.sleep(0.1)
        self.assertGreaterEqual(restarts, 2)

    def test_restart_no_stays_exited(self):
        os.environ["FAKE_CHILD_MODE"] = "exit0"
        self.addCleanup(lambda: os.environ.pop("FAKE_CHILD_MODE", None))
        project = self._up("services:\n  a:\n    image: x\n"
                           "    restart: \"no\"\n")
        self._run_supervisor()
        status = self._wait_status(project, "a", "exited")
        self.assertEqual(0, status["exit_code"])
        time.sleep(0.4)
        self.assertEqual("exited", rt.read_status(project, "a")["state"])

    def test_depends_on_blocks_until_started(self):
        os.environ["FAKE_CHILD_MODE"] = "sleep"
        self.addCleanup(lambda: os.environ.pop("FAKE_CHILD_MODE", None))
        project = self._up(
            "services:\n  dep:\n    image: x\n    restart: \"no\"\n"
            "  app:\n    image: x\n    depends_on: [dep]\n")
        self._run_supervisor()
        self._wait_status(project, "dep", "running")
        self._wait_status(project, "app", "running")

    def test_supervisor_marks_persisted_ports_error(self):
        # A plan that bypassed admission (written by an older build or
        # hand-edited) still fails closed at scan time: the service is
        # marked error and no child is ever spawned for it.
        project = "legacy"
        plan = {"schema": 1, "project": project,
                "source": os.path.join(self.workspace, "legacy.yaml"),
                "workdir": self.workspace, "workspace": self.workspace,
                "services": {"a": {
                    "name": "a", "image": "img:1", "command": [],
                    "entrypoint": [], "environment": {}, "volumes": [],
                    "ports": [{"host_port": 8080, "container_port": 80,
                               "protocol": "tcp", "host_ip": "127.0.0.1"}],
                    "working_dir": None, "depends_on": [],
                    "restart": "always"}},
                "order": ["a"], "desired": True, "manual_stop": False,
                "created_at": time.time(), "updated_at": time.time()}
        os.makedirs(os.path.join(rt.project_dir(project), "run"),
                    exist_ok=True)
        rt.save_plan(plan)
        self._run_supervisor()
        status = self._wait_status(project, "a", "error")
        self.assertIn("not supported in this MVP", status["message"])
        self.assertFalse(os.path.exists(rt.pid_path(project, "a")))

    def test_env_file_lifecycle_no_secret_leak(self):
        os.environ["FAKE_CHILD_MODE"] = "sleep"
        self.addCleanup(lambda: os.environ.pop("FAKE_CHILD_MODE", None))
        project = self._up(
            "services:\n  a:\n    image: x\n"
            "    environment: {SECRET: s3cret, PLAIN: x}\n")
        self._run_supervisor()
        status = self._wait_status(project, "a", "running")
        env_path = rt.env_file_path(project, "a")
        self.assertTrue(os.path.isfile(env_path))
        self.assertEqual(0o600, os.stat(env_path).st_mode & 0o777)
        # project.json is stored sorted, so the loaded plan (and the env
        # file) order keys alphabetically
        with open(env_path) as handle:
            self.assertEqual("PLAIN=x\nSECRET=s3cret\n", handle.read())
        # the secret reaches neither the service log nor the supervisor log
        with open(rt.log_path(project, "a"), "rb") as handle:
            self.assertNotIn(b"s3cret", handle.read())
        sup_log = os.path.join(self.state, "supervisor.log")
        with open(sup_log, "rb") as handle:
            self.assertNotIn(b"s3cret", handle.read())
        # child stop removes the env file with the pid file
        path = os.path.join(self.workspace, "compose.yaml")
        rt.compose_main(["-f", path, "stop"])
        self._wait_status(project, "a", "stopped")
        self.assertFalse(os.path.exists(env_path))


if __name__ == "__main__":
    unittest.main(verbosity=2)
