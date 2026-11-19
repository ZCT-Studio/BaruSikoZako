# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""Test Bukkit preview admission and saved-data failure on isolated Windows servers."""

import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


small = load("small_world", "small-world.py")
guards = load("fault_guards", "fault-guards.py")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "server", "java", "fixture-repo"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--preview", type=lambda value: Path(value).resolve(),
                        help="Omit to run an upstream-only control.")
    parser.add_argument("--world-save-probe", type=lambda value: Path(value).resolve(),
                        help="Test public World.save() using persisted world metadata on Bukkit servers.")
    parser.add_argument("--save-agent", type=lambda value: Path(value).resolve(),
                        help="Run the opt-in, test-only save observer and require its failure signal.")
    for name in ("platform", "minecraft", "fixture-revision"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    base, server = args.base, args.server
    if server.exists() or not re.search(r"(?m)^eula=true\s*$", (base / "eula.txt").read_text(encoding="utf-8")):
        parser.error("Use a new instance and a base with accepted EULA.")
    server.mkdir(parents=True)
    test_plugins = [path for path in (args.preview, args.world_save_probe) if path]
    if test_plugins:
        plugins = server / "plugins"
        plugins.mkdir()
        for path in test_plugins:
            shutil.copyfile(path, plugins / path.name)
    for name in ("server.jar", "eula.txt"):
        shutil.copyfile(base / name, server / name)
    for name in ("libraries", "versions", "cache", "bundler"):
        if (base / name).is_dir():
            shutil.copytree(base / name, server / name)
    fixture = server / "fixture"
    small.git(args.fixture_repo, "worktree", "add", "--no-checkout", "--detach", str(fixture), args.fixture_revision)
    small.git(fixture, "read-tree", "--empty")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\n"
        "level-name=fixture/world\nlevel-seed=2612026\nview-distance=2\nsimulation-distance=2\n",
        encoding="utf-8",
    )
    command = [str(args.java), "-Dfile.encoding=UTF-8", "-Dterminal.jline=false", "-Dterminal.ansi=false", "-Xmx1g"]
    if args.save_agent:
        command.append(f"-javaagent:{args.save_agent}")
    command.extend(("-jar", "server.jar", "nogui"))
    result = dict(platform=args.platform, minecraft=args.minecraft, previewLoad="NOT_TESTED",
                  backup="NOT_TESTED", restore="NOT_TESTED", serverSha256=small.sha256(server / "server.jar"),
                  fault={})
    if args.preview:
        result["previewSha256"] = small.sha256(plugins / args.preview.name)
    if args.world_save_probe:
        result["worldSaveProbeSha256"] = small.sha256(plugins / args.world_save_probe.name)
    if args.save_agent:
        result["saveAgentSha256"] = small.sha256(args.save_agent)
    if (base / "artifact.json").is_file():
        result["upstreamArtifact"] = json.loads((base / "artifact.json").read_text(encoding="utf-8"))

    def ready(expect):
        expect(r"Starting minecraft server version " + re.escape(args.minecraft) + r"(?:\s|$)", seconds=360)
        expect(r"Done \(")

    def score(expect, send, value):
        send(f"minecraft:scoreboard players set ZakoProbe zakoaccept {value}")
        expect(rf"Set .*ZakoProbe to {value}$")

    def public_save(expect, send, value):
        send(f"zakobukkitsaveprobe save {value}")
        expect(rf"ZAKO_BUKKIT_WORLD_SAVE_RETURN value={value}$")

    def seed(expect, send):
        ready(expect)
        result["upstreamStart"] = "PASS"
        if args.preview:
            send("barusikozako status")
            expect("Online capture available: false")
            result["previewLoad"] = "PASS"
            send("barusikozako backup")
            expect("UNSUPPORTED: online backup requires verified save outcomes")
            result["backup"] = "UNSUPPORTED"
            send("barusikozako restore")
            expect("UNSUPPORTED: offline restore requires a verified pre-world-load guard")
            result["restore"] = "UNSUPPORTED"
        if args.world_save_probe:
            public_save(expect, send, 41)
            return
        send("minecraft:scoreboard objectives add zakoaccept dummy")
        expect("Created new objective")
        score(expect, send, 41)
        send("save-all flush")
        expect("Saved the game")

    def fault(expect, send):
        ready(expect)
        if args.world_save_probe:
            send("zakobukkitsaveprobe read")
            expect(r"ZAKO_BUKKIT_MARKER value=41$")
            public_save(expect, send, 77)
            result["fault"]["saveResult"] = "World.save returned normally"
            save_log = (server / "save-fault.log").read_text(encoding="utf-8", errors="replace")
            # Match only the command window, before shutdown triggers a separate save.
            result["fault"]["observedWriteError"] = bool(re.search(
                r"(?im)(?:ERROR|WARN).*?(?:failed to [^\r\n]*level\.dat|level\.dat[^\r\n]*cannot)"
                r"|FileSystemException:[^\r\n]*level\.dat|AccessDeniedException:[^\r\n]*level\.dat",
                save_log.rsplit("ZAKO_BUKKIT_MARKER value=41", 1)[1]))
        else:
            score(expect, send, 77)
            send("save-all flush")
            result["fault"]["saveResult"] = expect(r"Saved the game|Saving failed").group()
        if args.save_agent:
            filename = "level.dat" if args.world_save_probe else "scoreboard.dat"
            marker = f"ZAKO_BARRIER_PROBE_FAILURE {filename}"
            # Recorded before shutdown, while the failing command still owns this segment.
            output = (server / "save-fault.log").read_text(encoding="utf-8", errors="replace")
            result["fault"]["observerSignal"] = marker in output
        if args.preview:
            send("barusikozako backup")
            expect("UNSUPPORTED: online backup requires verified save outcomes")

    def reopened(expect, send):
        ready(expect)
        if args.world_save_probe:
            send("zakobukkitsaveprobe read")
            result["fault"]["reopenedMarker"] = int(expect(r"ZAKO_BUKKIT_MARKER value=(\d+)$").group(1))
        else:
            send("minecraft:scoreboard players get ZakoProbe zakoaccept")
            result["fault"]["reopenedScore"] = int(expect(r"ZakoProbe has (\d+)").group(1))

    result_path = server / ("world-save-result.json" if args.world_save_probe else "preview-result.json")
    try:
        small.trial(server, command, "seed", seed)
        small.git(fixture, "add", "-f", "--", ".")
        result["fixtureTree"] = small.git(fixture, "write-tree")
        if args.world_save_probe:
            save_file = fixture / "world" / "level.dat"
        else:
            save_files = list((fixture / "world").rglob("scoreboard.dat"))
            if len(save_files) != 1:
                raise RuntimeError(f"Expected one scoreboard data file, found {len(save_files)}.")
            save_file, = save_files
        if not save_file.is_file():
            raise RuntimeError(f"Expected a persisted file after the baseline save: {save_file}")
        result["fault"]["file"] = save_file.relative_to(server).as_posix()
        digest = small.sha256(save_file)
        with guards.deny_writes(save_file):
            small.trial(server, command, "save-fault", fault)
        result["fault"]["unchangedSha256"] = small.sha256(save_file) == digest
        if not args.world_save_probe:
            log = (server / "save-fault.log").read_text(encoding="utf-8", errors="replace")
            result["fault"]["observedWriteError"] = bool(re.search(
                r"(?i)Could not save data[^\n]*(?:scoreboard|\n[^\n]*scoreboard\.dat)", log))
        small.trial(server, command, "reopen", reopened)
        marker = "reopenedMarker" if args.world_save_probe else "reopenedScore"
        result["fault"]["confirmed"] = (result["fault"]["unchangedSha256"]
                                        and result["fault"]["observedWriteError"]
                                        and result["fault"][marker] == 41)
        if args.save_agent:
            for label in ("seed", "reopen"):
                output = (server / f"{label}.log").read_text(encoding="utf-8", errors="replace")
                if "ZAKO_BARRIER_PROBE_FAILURE " in output:
                    raise RuntimeError(f"Save observer reported a failure in the positive control: {label}.")
            if not result["fault"]["observerSignal"] or not result["fault"]["confirmed"]:
                raise RuntimeError("Injected write failure did not reach the verified observer.")
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        result["failure"] = str(error)
        raise
    finally:
        result_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        if "fixtureTree" in result:
            small.git(fixture, "restore", "--worktree", "--", ".")


if __name__ == "__main__":
    main()
