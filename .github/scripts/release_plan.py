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

import json5


TIERS = {
    "lts": {"rc", "release"},
    "nlts": {"beta", "rc", "release"},
    "shift": {"alpha", "beta", "rc", "release"},
    "latest": {"snapshot", "alpha", "beta", "rc", "release"},
}
TIER_ORDER = {"lts": 0, "nlts": 1, "shift": 2, "latest": 3}
MERGE_LOADERS = ("fabric", "forge", "neoforge")
FML_LOADERS = ("forge", "neoforge")
IDS = {"mod": "barusikozako", "api": "barusikozako_api"}
BUILDERS = {"fabric", "quilt", "forge", "neoforge", "agent"}
SEMVER = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")
SECTION = re.compile(r"^## \[([^\]]+)\].*$", re.MULTILINE)

def git(*args, missing=False):
    result = subprocess.run(["git", *args], text=True, encoding="utf-8", capture_output=True)
    if result.returncode and not missing:
        raise ValueError(result.stderr.strip())
    return result.stdout if not result.returncode else None


def read_table(text):
    return json5.loads(text, allow_duplicate_keys=False)


def channel(value):
    match = re.search(r"-(snapshot|alpha|beta|rc)", value.split("+", 1)[0], re.IGNORECASE)
    return match[1].lower() if match else "release"


def resolve_version(component, rule, platform, game):
    overrides = rule["overrides"]
    keys = [platform + game, platform]
    if platform == "quilt":
        keys.extend(("fabric" + game, "fabric"))
    keys.append(game)
    version = next((overrides[key] for key in keys if key in overrides), rule["version"])
    if not isinstance(version, str) or not SEMVER.fullmatch(version):
        raise ValueError(f"Invalid {component} version for {platform}{game}: {version!r}")
    return version


def resolve(table, platform, game):
    return {component: resolve_version(component, table[component], platform, game) for component in IDS}


def bundled_zstd_version(table, platform, game):
    rule = table.get("backend", {}).get("zstd")
    return resolve_version("backend.zstd", rule, platform, game) if rule is not None else None


def targets(table):
    groups = table["platformGroups"]

    def expand(key, stack=()):
        if key in stack:
            raise ValueError(f"Circular release group: {key}")
        if key in groups:
            for item in groups[key]:
                yield from expand(item, (*stack, key))
        else:
            yield key

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
        if path.startswith("platforms/fabric/third-party/"):
            return True
        if path.startswith(".github/scripts/") and Path(path).suffix in {".py", ".ps1", ".sh"}:
            return True
        if (path.startswith(";lists/") and Path(path).suffix == ".json5"
                and path not in (";lists/version.json5", ";lists/release.json5")):
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
    folder = "api" if component == "api" else "agent" if target.startswith("agent") else "mod"
    return [f"{folder}/{IDS[component]}-v{version}+{target}.jar"]


def commit_flags(message):
    return {flag.lower() for flag in re.findall(
        r"\[(force-release|force-relaese|release-all|release-temp|release-latest|build-all|build-lts|merge-jars|merge-fml-jars|no-changelog|no-release|no-build|no-modrinth|no-curseforge|no-api|no-mod)\]",
        message, re.IGNORECASE)}


def requests_forced_release(flags):
    return bool(flags & {"force-release", "force-relaese"})


def plan(current, release_table, previous, message, changed, markdown="", *, api_markdown="", build_only=False,
         force_build=False, merge_games=frozenset()):
    selected = targets(release_table)
    flags = commit_flags(message)
    # [no-api]/[no-mod] 让本次构建与发布只包含其中一个组件
    components = tuple(name for name in IDS if f"no-{name}" not in flags)
    force_release = requests_forced_release(flags)
    build_all = "build-all" in flags
    build_lts = "build-lts" in flags
    # 合并构建与合并发布都是可选的，只有提交信息带 [merge-jars]/[merge-fml-jars] 才启用
    merge_build = "merge-jars" in flags
    merge_fml_build = "merge-fml-jars" in flags and not merge_build
    if not merge_build and not merge_fml_build:
        merge_games = frozenset()
    # [build-all]/[build-lts] 只规划构建，不生成发布；[no-build] 仍然优先。
    if (build_all or build_lts) and "no-build" not in flags:
        force_build = True
    catalog_changed = any(path in (";lists/version.json5", ";lists/release.json5") for path in changed)
    storefronts = [name for name in ("modrinth", "curseforge") if f"no-{name}" not in flags]
    allow_release = (bool(storefronts) and not build_only and not force_build
                     and "no-release" not in flags and (catalog_changed or force_release))
    validate_code = force_build or code_changed(changed) or (build_only and catalog_changed)
    build_jobs = {}
    release_jobs = []
    grouped = collections.OrderedDict()
    notes = changelogs(markdown)
    api_notes = changelogs(api_markdown) if api_markdown else notes
    narrowed = set()
    if "release-temp" in flags:
        narrowed.update(("nlts", "shift"))
    if "release-latest" in flags:
        narrowed.add("latest")

    def job(game, platforms, versions, components, build_only, require_notes=False, loader=None):
        loader = loader or platforms[0]
        target = loader + game
        assets = []
        for component in [name for name in components if name in versions]:
            version = versions[component]
            changelog_file = "CHANGELOG.API.md" if component == "api" else "CHANGELOG.md"
            source_notes = api_notes if component == "api" else notes
            asset_notes = source_notes.get(version, "").strip()
            if not build_only and not asset_notes:
                if require_notes and "no-changelog" not in flags:
                    raise SystemExit(f"No changelog entry for version {version} in {changelog_file}; "
                                     "add a section or use [no-changelog]")
                asset_notes = "_No changelog for this release_\n\n_本次发布未找到更新日志_"
            assets.append({"component": component, "version": version,
                           "paths": asset_names(component, version, target),
                           "notes": asset_notes, "channel": channel(version)})
        return {"game": game, "loader": loader, "platforms": platforms, "target": target,
                "versions": versions, "assets": assets, "build_only": build_only}

    units = []
    for game in sorted({game for game, _ in selected}):
        members = sorted(platform for (candidate, platform) in selected if candidate == game)
        merge_members = [platform for platform in members if platform in MERGE_LOADERS]
        if merge_fml_build:
            fml_members = [platform for platform in members if platform in FML_LOADERS]
            if game in merge_games:
                if len(fml_members) >= 2:
                    tier = max((selected[(game, platform)] for platform in fml_members), key=TIER_ORDER.__getitem__)
                    units.append((game, fml_members, tier))
                    # 只合并 Forge/NeoForge，Fabric 与 Quilt 继续单独分发
                    units.extend((game, [platform], selected[(game, platform)])
                                 for platform in members if platform not in fml_members)
                else:
                    units.extend((game, [platform], selected[(game, platform)]) for platform in members)
                continue
        if game in merge_games and len(merge_members) >= 2:
            tier = max((selected[(game, platform)] for platform in merge_members), key=TIER_ORDER.__getitem__)
            # Quilt 直接兼容 Fabric 实现，随合并产物一起分发，避免额外生成重复的 Fabric 包
            units.append((game, merge_members + [platform for platform in members if platform == "quilt"], tier))
        else:
            units.extend((game, [platform], selected[(game, platform)]) for platform in members)

    if force_build or "no-build" not in flags:
        for game, platforms, tier in units:
            merged = len(platforms) > 1
            loader = ("fml" if merge_fml_build else "universal") if merged else platforms[0]
            versions = resolve(current, platforms[0], game)
            if merged:
                for platform in platforms[1:]:
                    if resolve(current, platform, game) != versions:
                        raise ValueError(f"Cannot merge {', '.join(platforms)} for {game}: component versions differ")
            before = resolve(previous, platforms[0], game) if catalog_changed and previous else {}
            zstd = bundled_zstd_version(current, platforms[0], game)
            previous_zstd = bundled_zstd_version(previous, platforms[0], game) if catalog_changed and previous else None
            requested = "no-build" not in flags and (
                build_all or (build_lts and tier == "lts") or (merge_build and merged)
                or (merge_fml_build and game in merge_games))
            automatic = not build_all and not build_lts and tier == "latest"
            if requested or (automatic and
                             (validate_code or (catalog_changed and (versions != before or zstd != previous_zstd)))):
                build = job(game, platforms, versions, components, True, loader=loader)
                if build["assets"]:
                    build_jobs[build["target"]] = build
            bypass_tier = "release-all" in flags
            if not allow_release or (not bypass_tier and narrowed and tier not in narrowed):
                continue
            emitted = tuple(component for component, value in versions.items()
                            if component in components and
                            (force_release or before.get(component) != value) and
                            (bypass_tier or channel(value) in TIERS[tier]))
            if emitted:
                loader_group = (loader if merged else
                                "fabric" if platforms[0] in ("fabric", "quilt") else platforms[0])
                key = (game, loader_group, tuple(versions.items()), zstd, emitted)
                grouped.setdefault(key, []).append((platforms, loader))

    for (game, _, version_entries, _, emitted), members in grouped.items():
        versions = dict(version_entries)
        platforms = []
        loader = members[0][1]
        for entry_platforms, _ in members:
            platforms.extend(entry_platforms)
        is_agent = platforms[0] == "agent"
        if not is_agent:
            release_jobs.append(job(game, platforms, versions, emitted, False, require_notes=True, loader=loader))
        # 即使仅发布一个组件，验收也需要当前的配套 JAR
        build = job(game, platforms, versions, components, is_agent, loader=loader)
        if build["assets"]:
            build_jobs[build["target"]] = build

    return {
        "jobs": release_jobs,
        "storefronts": storefronts,
        "build_jobs": list(build_jobs.values()),
        "should_build": bool(build_jobs),
        "should_release": bool(release_jobs),
        "force_release": force_release and bool(release_jobs),
    }


def merge_games():
    try:
        table = read_table(Path(";lists/targets.json5").read_text(encoding="utf-8"))
    except (OSError, KeyError, ValueError):
        return frozenset()
    return frozenset(str(game) for game in table.get("merge", []))


def previous_catalog(base):
    if not base:
        return None
    old_versions = git("show", f"{base}:;lists/version.json5", missing=True)
    if not old_versions:
        # 清单从 .lists/ 迁移到 ;lists/ 之前的提交仍从旧路径读取。
        old_versions = git("show", f"{base}:.lists/version.json5", missing=True)
    if not old_versions:
        # 清单迁入 .lists/ 之前的提交仍从旧路径读取，用于跨迁移比较。
        old_versions = git("show", f"{base}:versions.json5", missing=True)
    table = read_table(old_versions) if old_versions else {}
    if "mod" in table:
        return table
    legacy = git("show", f"{base}:gradle.properties", missing=True) or ""
    match = re.search(r"(?m)^productVersion=(.+)$", legacy)
    if not match:
        return None
    old = {"version": match[1], "overrides": {}}
    return {"mod": old, "api": old}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--force-build", action="store_true", help="Build latest targets without publishing")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    base = args.base
    force_build = args.force_build
    message = "" if force_build else git("log", "-1", "--format=%B")
    available = not force_build and base and git("rev-parse", "--verify", base + "^{commit}", missing=True)
    if not force_build and not available:
        if not args.build_only and requests_forced_release(commit_flags(message)):
            print("Comparison commit unavailable. Planning the explicitly requested release.", file=sys.stderr)
        else:
            print("Comparison commit unavailable. Building latest targets without publishing.", file=sys.stderr)
            force_build = True
    build_only = args.build_only or force_build
    changed = git("diff", "--name-only", "-z", base, "HEAD").split("\0") if available else []
    old = previous_catalog(base) if available and not build_only else None
    try:
        markdown = Path("CHANGELOG.md").read_text(encoding="utf-8")
    except FileNotFoundError:
        markdown = ""
    try:
        api_markdown = Path("CHANGELOG.API.md").read_text(encoding="utf-8")
    except FileNotFoundError:
        api_markdown = ""
    result = plan(read_table(Path(";lists/version.json5").read_text(encoding="utf-8")),
                  read_table(Path(";lists/release.json5").read_text(encoding="utf-8")), old,
                  message, changed, markdown, api_markdown=api_markdown,
                  build_only=build_only, force_build=force_build, merge_games=merge_games())
    if result["should_release"]:
        server = os.environ.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
        repository = os.environ.get("GITHUB_REPOSITORY", "ZCT-Studio/BaruSikoZako")
        commit = git("rev-parse", "HEAD").strip()
        for job in result["jobs"]:
            for asset in job["assets"]:
                file = "CHANGELOG.API.md" if asset["component"] == "api" else "CHANGELOG.md"
                asset["notes"] += ("\n\n---\n\nFull changelog / 完整更新日志:\n\n"
                                   f"[{file}]({server}/{repository}/blob/{commit}/{file})\n")
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as stream:
            print("has_jobs=" + str(bool(result["jobs"])).lower(), file=stream)
            print("should_build=" + str(result.get("should_build", False)).lower(), file=stream)
            print("should_release=" + str(result.get("should_release", False)).lower(), file=stream)
            matrix = [{key: job[key] for key in ("target", "game", "loader")} for job in result["jobs"]]
            print("matrix=" + json.dumps({"include": matrix}, ensure_ascii=True), file=stream)
            # 构建作业的构建矩阵
            build_matrix = [{key: job[key] for key in ("target", "game", "loader")} for job in result.get("build_jobs", [])]
            print("build_matrix=" + json.dumps({"include": build_matrix}, ensure_ascii=True), file=stream)


if __name__ == "__main__":
    main()
