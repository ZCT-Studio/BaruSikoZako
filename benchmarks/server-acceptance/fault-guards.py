# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""Exercise cancellation and a real Windows SavedData write failure on a completed small trial."""

import argparse
from contextlib import contextmanager
import ctypes
from ctypes import wintypes
import importlib.util
import json
from pathlib import Path
import re
import sys


spec = importlib.util.spec_from_file_location("small", Path(__file__).with_name("small-world.py"))
small = importlib.util.module_from_spec(spec)
spec.loader.exec_module(small)


@contextmanager
def deny_writes(path):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = (wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                                   wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE)
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel.CloseHandle.restype = wintypes.BOOL
    # FILE_SHARE_READ lets Minecraft read the committed marker but disallows writes and replacement.
    handle = kernel.CreateFileW(str(path), 0x80000000, 1, None, 3, 0, None)
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        yield
    finally:
        if not kernel.CloseHandle(handle):
            raise ctypes.WinError(ctypes.get_last_error())


def main():
    sys.stdout.reconfigure(errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--launcher", required=True)
    parser.add_argument("--run-label", default="fault")
    args = parser.parse_args()
    server = args.server
    prior = json.loads((server / "result.json").read_text(encoding="utf-8"))
    if prior["map"] != "small" or prior["stages"].get("RESTART") != "PASS":
        parser.error("A successful small-world trial is required.")
    if not re.fullmatch(r"[a-z0-9-]+", args.run_label):
        parser.error("Use a simple run label.")
    save_log = args.run_label + "-save"
    cancel_log = args.run_label + "-cancel"
    if (server / f"{save_log}.log").exists() or (server / f"{cancel_log}.log").exists():
        parser.error("Use a dedicated trial directory with no prior fault test.")
    fixture = server / "fixture"
    properties = server / "server.properties"
    if "server-ip=127.0.0.1\n" not in properties.read_text(encoding="utf-8"):
        parser.error("The test server must bind to loopback.")
    config = server / "config/barusikozako/server.yml"
    repo = config.parent / "repository/snapshots"

    def points():
        return {record.parent.name: small.sha256(record) for record in repo.glob("*/commit")}

    baseline = points()
    data_directory = fixture / "world/data"
    candidates = [path for path in (data_directory / "scoreboard.dat",
                                    data_directory / "minecraft/scoreboard.dat") if path.is_file()]
    if len(candidates) != 1:
        parser.error("Exactly one scoreboard data file is required in the world under test.")
    data_path, = candidates
    previous = config.read_bytes()
    config.write_bytes(previous + b"backup.readBytesPerSecond: 262144\n")
    launch = [args.java, "-Dfile.encoding=UTF-8", "-Xmx768m",
              *([args.launcher] if args.launcher.startswith("@") else ["-jar", args.launcher]), "nogui"]
    result = {"platform": prior["platform"], "minecraft": prior["minecraft"], "tested": {}}

    def ready(expect):
        expect(r"Starting minecraft server version " + re.escape(prior["minecraft"]) + r"(\s|$)")
        expect("BaruSikoZako services ready")

    def cancel(expect, send):
        ready(expect)
        send("barusikozako full")
        task = expect(r"(?:Backup requested:|Requested task) ([a-f0-9-]{36}:\d+)").group(1)
        expect("Best-effort background capture started")
        send(f'barusikozako cancel "{task}"')
        expect(r"Task " + re.escape(task) + r".*Task cancelled\.")
        assert points() == baseline
        result["tested"]["cancelDuringCapture"] = "PASS"

    def saved_data(expect, send):
        ready(expect)
        with deny_writes(data_path):
            digest = small.sha256(data_path)
            send("scoreboard players set ProbeMarker zako_accept 155")
            expect(r"Set .*ProbeMarker to 155$")
            send("barusikozako full")
            expect(r"Backup operation failed|Request rejected:")
            assert small.sha256(data_path) == digest
        assert points() == baseline

    try:
        small.trial(server, launch, cancel_log, cancel)
        small.trial(server, launch, save_log, saved_data)
        log = (server / f"{save_log}.log").read_text(encoding="utf-8")
        assert re.search(r"Could not save data(?: to scoreboard\.dat| .*(?:ScoreboardSaveData|class_273))", log), (
            "Scoreboard SavedData error was not observed")
        assert points() == baseline
        result["tested"]["blockedSavedDataWrite"] = "PASS"
    except (OSError, RuntimeError, AssertionError) as failure:
        result["failure"] = str(failure)
        raise
    finally:
        config.write_bytes(previous)
        small.git(fixture, "restore", "--worktree", "--", "world")
        (server / f"{args.run_label}-guards-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
