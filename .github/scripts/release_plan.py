# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""根据两个版本表规划构建和发行，不连接发布平台"""

import argparse
import collections
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone, timedelta

import json5


TIERS = {
    "lts": {"rc", "release"},
    "nlts": {"beta", "rc", "release"},
    "shift": {"alpha", "beta", "rc", "release"},
    "latest": {"snapshot", "alpha", "beta", "rc", "release"},
}
IDS = {"mod": "barusikozako", "api": "barusikozako_api", "zstd": "barusikozako_backendzstd"}
FOLDERS = {"api": "api", "zstd": "backend"}
BUILDERS = {"fabric", "quilt", "forge", "neoforge", "agent"}
SEMVER = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")
SECTION = re.compile(r"^## \[([^\]]+)\].*$", re.MULTILINE)

# 北京时区 (UTC+8)
BEIJING_TZ = timezone(timedelta(hours=8))
TAG_PREFIX = "release/"
RELEASE_TITLE_PREFIX = "BaruSikoZako · Release "


def git(*args, missing=False):
    result = subprocess.run(["git", *args], text=True, encoding="utf-8", capture_output=True)
    if result.returncode and not missing:
        raise ValueError(result.stderr.strip())
    return result.stdout if not result.returncode else None


def get_existing_release_tags():
    """从远程获取所有现有发行标签"""
    output = git("ls-remote", "--tags", "origin", "release/*", missing=True)
    if not output:
        return []
    tags = []
    for line in output.strip().split("\n"):
        parts = line.split("\t")
        if len(parts) >= 2:
            ref = parts[1]
            if ref.startswith("refs/tags/release/"):
                tags.append(ref[len("refs/tags/"):])
    return tags


def get_today_batch_number():
    """获取今天的下一个批次号，使用北京时间"""
    today = datetime.now(BEIJING_TZ).strftime("%Y.%m.%d")
    prefix = f"{TAG_PREFIX}{today}."
    
    existing_tags = get_existing_release_tags()
    used_numbers = []
    for tag in existing_tags:
        if tag.startswith(prefix):
            suffix = tag[len(prefix):]
            if suffix.isdigit():
                used_numbers.append(int(suffix))
    
    return max(used_numbers, default=0) + 1


def generate_release_tag():
    """为今天的批次生成发行标签"""
    today = datetime.now(BEIJING_TZ).strftime("%Y.%m.%d")
    batch = get_today_batch_number()
    return f"{TAG_PREFIX}{today}.{batch}"


def get_release_title(tag):
    """根据标签生成 GitHub Release 标题"""
    # 标签格式：release/YYYY.MM.DD.N
    if tag.startswith(TAG_PREFIX):
        rest = tag[len(TAG_PREFIX):]  # YYYY.MM.DD.N
        parts = rest.split(".")
        if len(parts) == 4:
            date_str = f"{parts[0]}.{parts[1]}.{parts[2]}"
            batch = parts[3]
            return f"{RELEASE_TITLE_PREFIX}{date_str} #{batch}"
    # 倒退
    today = datetime.now(BEIJING_TZ).strftime("%Y.%m.%d")
    return f"{RELEASE_TITLE_PREFIX}{today} #1"


def read_table(text):
    return json5.loads(text, allow_duplicate_keys=False)


def rules(table):
    return {"mod": table["mod"], "api": table["api"], "zstd": table["backend"]["zstd"]}


def channel(value):
    match = re.search(r"-(snapshot|alpha|beta|rc)", value.split("+", 1)[0], re.IGNORECASE)
    return match[1].lower() if match else "release"


def resolve(table, platform, game):
    result = {}
    for component, rule in rules(table).items():
        overrides = rule["overrides"]
        keys = [platform + game, platform]
        if platform == "quilt":
            keys.extend(("fabric" + game, "fabric"))
        keys.append(game)
        version = next((overrides[key] for key in keys if key in overrides), rule["version"])
        if not isinstance(version, str) or not SEMVER.fullmatch(version):
            raise ValueError(f"Invalid {component} version for {platform}{game}: {version!r}")
        result[component] = version
    return result


def targets(table):
    groups = table["platformGroups"]

    def expand(key, stack=()):
        if key in stack:
            raise ValueError(f"Circular release group: {key}")
        if key in groups:
            for item in groups[key]:
                yield from expand(item, (*stack, key))
        else:
            yield "agent" if key == "vanilla" else key

    selected = {}
    rolling = 0
    for game, entries in table["versions"].items():
        if not re.fullmatch(r"\d+(?:\.\d+)+", game):
            raise ValueError(f"Invalid game version: {game}")
        if "shift" in entries:
            rolling += 1
        for tier, values in entries.items():
            if tier not in TIERS:
                raise ValueError(f"Unknown release tier: {tier}")
            for value in values:
                for platform in expand(value):
                    key = (game, platform)
                    if key in selected:
                        raise ValueError(f"Repeated release target: {platform}{game}")
                    if platform not in BUILDERS:
                        raise ValueError(f"No release builder for enabled platform: {platform}{game}")
                    selected[key] = tier
    if rolling > table["policy"]["maxShiftVersions"]:
        raise ValueError("The release table exceeds maxShiftVersions")
    return selected


def code_changed(paths):
    for path in paths:
        if path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
                    "metadatas.json5", "LICENSE",
                    ".github/workflows/release.yml", ".github/scripts/requirements.txt"):
            return True
        if path.startswith("resource/") and Path(path).suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".json", ".properties"}:
            return True
        if path.startswith(("platforms/fabric/third-party/", "platforms/javaagent/third-party/")):
            return True
        if path.startswith(".github/scripts/") and Path(path).suffix in {".py", ".ps1", ".sh"}:
            return True
        if path.startswith(("gradle/", "app/", "api/", "backends/", "engine/", "platforms/", "build-logic/", ".github/scripts/")):
            if Path(path).suffix in {".java", ".kt", ".kts", ".toml", ".xml", ".json", ".yml", ".yaml",
                                     ".lockfile", ".properties", ".jar"} or "/META-INF/services/" in path:
                return True
    return False


def changelogs(markdown):
    sections = list(SECTION.finditer(markdown))
    return {match[1]: markdown[match.end():sections[i + 1].start() if i + 1 < len(sections) else None].strip()
            for i, match in enumerate(sections)}


def asset_names(component, version, target):
    folder = FOLDERS.get(component, "agent" if target.startswith("agent") else "mod")
    variants = ("", "_L") if component == "mod" else ("",)
    return [f"{folder}/{IDS[component]}-v{version}{variant}+{target}.jar" for variant in variants]


def commit_flags(message):
    return {flag.lower() for flag in re.findall(
        r"\[(force-release|force-relaese|release-all|release-temp|release-latest|no-release|no-build)\]",
        message, re.IGNORECASE)}


def requests_forced_release(flags):
    return bool(flags & {"force-release", "force-relaese"})


def plan(current, release_table, previous, message, changed, markdown="", *, build_only=False, force_build=False):
    selected = targets(release_table)
    flags = commit_flags(message)
    force_release = requests_forced_release(flags)
    catalog_changed = any(path in ("versions.json5", "releases.json5") for path in changed)
    allow_release = not build_only and not force_build and "no-release" not in flags and (catalog_changed or force_release)
    validate_code = force_build or code_changed(changed) or (build_only and catalog_changed)
    build_jobs = {}
    release_jobs = []
    grouped = collections.OrderedDict()
    notes = changelogs(markdown)
    narrowed = set()
    if "release-temp" in flags:
        narrowed.update(("nlts", "shift"))
    if "release-latest" in flags:
        narrowed.add("latest")

    def job(game, platforms, versions, components, build_only):
        loader = platforms[0]
        target = loader + game
        assets = []
        for component in components:
            version = versions[component]
            asset_notes = notes.get(version, "").strip()
            if not build_only and not asset_notes:
                asset_notes = "_No changelog for this release_\n\n_本次发布未找到更新日志_"
            assets.append({"component": component, "version": version,
                           "paths": asset_names(component, version, target),
                           "notes": asset_notes, "channel": channel(version)})
        return {"game": game, "loader": loader, "platforms": platforms, "target": target,
                "versions": versions, "assets": assets, "build_only": build_only}

    if force_build or "no-build" not in flags:
        for (game, platform), tier in sorted(selected.items()):
            versions = resolve(current, platform, game)
            before = resolve(previous, platform, game) if catalog_changed and previous else {}
            if tier == "latest" and (validate_code or (catalog_changed and versions != before)):
                build = job(game, [platform], versions, versions, True)
                build_jobs[build["target"]] = build
            bypass_tier = "release-all" in flags
            if not allow_release or (not bypass_tier and narrowed and tier not in narrowed):
                continue
            emitted = tuple(component for component, value in versions.items()
                            if (force_release or before.get(component) != value) and
                            (bypass_tier or channel(value) in TIERS[tier]))
            if emitted:
                loader_group = "fabric" if platform in ("fabric", "quilt") else platform
                key = (game, loader_group, tuple(versions.items()), emitted)
                grouped.setdefault(key, []).append(platform)

    for (game, _, version_entries, emitted), platforms in grouped.items():
        versions = dict(version_entries)
        release = job(game, platforms, versions, emitted, False)
        release_jobs.append(release)
        # 即使仅发布一个组件，验收也需要当前的配套 JAR
        build = job(game, platforms, versions, versions, False)
        build_jobs[build["target"]] = build

    tag = generate_release_tag() if release_jobs else None
    return {
        "tag": tag,
        "prerelease": any(asset["channel"] != "release"
                         for release in release_jobs for asset in release["assets"]),
        "jobs": release_jobs,
        "build_jobs": list(build_jobs.values()),
        "should_build": bool(build_jobs),
        "should_release": bool(release_jobs),
        "force_release": force_release and bool(release_jobs),
        "release_title": get_release_title(tag) if tag else None,
    }


def previous_catalog(base):
    if not base:
        return None
    old_versions = git("show", f"{base}:versions.json5", missing=True)
    table = read_table(old_versions) if old_versions else {}
    if "mod" in table:
        return table
    legacy = git("show", f"{base}:gradle.properties", missing=True) or ""
    match = re.search(r"(?m)^productVersion=(.+)$", legacy)
    if not match:
        return None
    old = {"version": match[1], "overrides": {}}
    return {"mod": old, "api": old, "backend": {"zstd": old}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--force-build", action="store_true", help="Build latest targets without publishing")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    base = args.base
    force_build = args.force_build
    message = "" if args.build_only or force_build else git("log", "-1", "--format=%B")
    available = not force_build and base and git("rev-parse", "--verify", base + "^{commit}", missing=True)
    if not force_build and not available:
        if requests_forced_release(commit_flags(message)):
            print("Comparison commit unavailable. Planning the explicitly requested release.", file=sys.stderr)
        else:
            print("Comparison commit unavailable. Building latest targets without publishing.", file=sys.stderr)
            force_build = True
    build_only = args.build_only or force_build
    changed = git("diff", "--name-only", "-z", base, "HEAD").split("\0") if available else []
    old = previous_catalog(base) if available and not build_only else None
    result = plan(read_table(Path("versions.json5").read_text(encoding="utf-8")),
                  read_table(Path("releases.json5").read_text(encoding="utf-8")), old,
                  message, changed,
                  Path("CHANGELOG.md").read_text(encoding="utf-8"),
                  build_only=build_only, force_build=force_build)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as stream:
            print("has_jobs=" + str(bool(result["jobs"])).lower(), file=stream)
            print("should_build=" + str(result.get("should_build", False)).lower(), file=stream)
            print("should_release=" + str(result.get("should_release", False)).lower(), file=stream)
            print("tag=" + (result.get("tag") or ""), file=stream)
            print("release_title=" + (result.get("release_title") or ""), file=stream)
            print("prerelease=" + str(result.get("prerelease", False)).lower(), file=stream)
            matrix = [{key: job[key] for key in ("target", "game", "loader")} for job in result["jobs"]]
            print("matrix=" + json.dumps({"include": matrix}, ensure_ascii=True), file=stream)
            # 构建作业的构建矩阵
            build_matrix = [{key: job[key] for key in ("target", "game", "loader")} for job in result.get("build_jobs", [])]
            print("build_matrix=" + json.dumps({"include": build_matrix}, ensure_ascii=True), file=stream)


if __name__ == "__main__":
    main()
