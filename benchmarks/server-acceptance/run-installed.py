# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""Run selected mod combinations from a prepared Windows fixture cache."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import sys


def command_for(args, target, port):
    platform, version = target.split("-", 1)
    runtime = args.runtime_cache
    product_version = args.product_version
    if platform in ("fabric", "quilt"):
        loader = "0.20.1" if platform == "fabric" else "0.30.2"
        cache_name = "quilt-stable" if platform == "quilt" else platform
        base = runtime / ("loader26-smoke" if version == "26.3" else "plugin26") / (
            cache_name if version == "26.3" else target)
        launcher = "quilt-server-launch.jar" if platform == "quilt" else (
            "fabric-server-launch.jar" if version == "26.3" else "fabric-verified-launch.jar")
        module = "platform-fabric"
        if version.startswith("1."):
            base = runtime / "pre26-runtime" / target
            module = "platform-fabric-pre26"
            if platform == "fabric":
                launcher = "fabric-server-launch.jar"
        product = Path(module) / "build/libs" / f"barusikozako-fabric-{version}-development-{product_version}.jar"
        api = Path("public-api/build/libs") / f"zakobak_api-fabric-{product_version}.jar"
    else:
        runtime_version = args.fml[f"fml.runtime.{platform}.{version}"]
        loader = runtime_version.removeprefix(version + "-")
        group = "net/minecraftforge/forge" if platform == "forge" else "net/neoforged/neoforge"
        cache_group = "pre26-runtime" if version.startswith("1.") else (
            "loader26-smoke" if version == "26.3" else "fml-range")
        base = runtime / cache_group / (
            platform if version == "26.3" else target)
        launcher = f"@libraries/{group}/{runtime_version}/win_args.txt"
        dist = Path("platform-fml26/build/libs")
        product = dist / f"barusikozako-{platform}-{version}-{product_version}.jar"
        api = dist / f"zakobak_api-{platform}-{version}-{product_version}.jar"
    command = [
        sys.executable, str(Path(__file__).with_name("small-world.py")),
        "--server", str(args.output / target), "--base", str(base),
        "--java", str(args.java), "--product", str(product), "--api", str(api),
        "--fixture-repo", str(args.fixture_repo), "--fixture-revision", args.fixture_revision,
        "--platform", platform, "--loader", loader, "--minecraft", version,
        "--port", str(port), "--launcher", launcher,
    ]
    if args.main_thread_budget is not None:
        command.extend(("--main-thread-budget", str(args.main_thread_budget)))
    if args.existing_world:
        command.append("--use-existing-world")
    if args.paused_empty:
        command.append("--paused-empty")
    for name in ("client_fixture", "client_cache", "client_version_dir"):
        if value := getattr(args, name):
            command.extend(("--" + name.replace("_", "-"), str(value)))
    if args.client_fixture:
        command.extend(("--client-warmup-seconds", str(args.client_warmup_seconds)))
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-cache", type=Path, default=Path(".gradle"))
    parser.add_argument("--fixture-repo", type=Path, required=True)
    parser.add_argument("--fixture-revision", required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--product-version", default="0.1.0-dev")
    parser.add_argument("--first-port", type=int, default=25701)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--main-thread-budget", type=int)
    parser.add_argument("--existing-world", action="store_true")
    parser.add_argument("--paused-empty", action="store_true")
    parser.add_argument("--client-fixture", type=Path)
    parser.add_argument("--client-cache", type=Path)
    parser.add_argument("--client-version-dir", type=Path)
    parser.add_argument("--client-warmup-seconds", type=int, default=15)
    targets = Path("gradle/compatibility-targets.properties").read_text(encoding="utf-8").splitlines()
    fml = dict(line.split("=", 1) for line in targets if line.startswith("fml.runtime."))
    legacy = [line.split("=", 1)[0].removeprefix("fabric.legacy.api.")
              for line in targets if line.startswith("fabric.legacy.api.")]
    supported = [f"{loader}-{version}" for loader in ("fabric", "quilt")
                 for version in ("26.1.2", "26.2", "26.3", *legacy)]
    supported += [entry.split(".")[2] + "-" + entry.split(".", 3)[3] for entry in fml]
    parser.add_argument("targets", nargs="+", choices=supported)
    args = parser.parse_args()
    if args.jobs < 1 or len(set(args.targets)) != len(args.targets):
        parser.error("Use positive concurrency and distinct targets.")
    if args.existing_world and len({target.split("-", 1)[1] for target in args.targets}) != 1:
        parser.error("Use a version-matched large world and one Minecraft version per run.")
    args.fml = fml
    if any((args.output / target).exists() for target in args.targets):
        parser.error("Each target must have a new test directory.")
    args.output.mkdir(parents=True, exist_ok=True)
    commands = {target: command_for(args, target, args.first_port + index)
                for index, target in enumerate(args.targets)}
    (args.output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n", encoding="utf-8")

    def run(item):
        target, command = item
        with (args.output / f"{target}.log").open("w", encoding="utf-8") as log:
            code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
        result = args.output / target / "result.json"
        if code and not result.is_file():
            platform, minecraft = target.split("-", 1)
            result.parent.mkdir(parents=True, exist_ok=True)
            result.write_text(json.dumps({
                "platform": platform, "minecraft": minecraft, "stages": {},
                "map": "git-world" if args.existing_world else "small",
                "failure": {"phase": "provision", "detail": f"See {target}.log, exit {code}"}
            }, indent=2) + "\n", encoding="utf-8")
        print(target, code, flush=True)
        return code

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        exit_codes = list(pool.map(run, commands.items()))
    sys.exit(int(any(exit_codes)))


if __name__ == "__main__":
    main()
