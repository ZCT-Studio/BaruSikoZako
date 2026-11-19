# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""Resolve official version availability and render the per-platform acceptance matrix."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from xml.etree import ElementTree


VERSIONS = ("1.20.1", "1.21.1", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3")
PLATFORMS = ("Fabric", "Quilt", "Forge", "NeoForge", "CraftBukkit", "Spigot", "Paper", "Purpur", "Folia",
             "SpongeVanilla", "SpongeForge", "SpongeNeo")
STAGES = ("BUILD", "LOAD", "BACKUP", "VERIFY", "RESTORE", "RESTART", "FAULT", "ONLINE", "PERFORMANCE")
EXCLUDED_TARGETS = {("NeoForge", "1.20.1"), ("Folia", "1.20.1")}
EXCLUDED_VERSIONS = {"1.21.9", "26.1", "26.1.1"}
EXCLUDED_PLATFORMS = {"SpongeForge", "SpongeNeo"}
PREVIEW_BUILDS = {("Paper", "26.1.2"): "74", ("Paper", "26.2"): "135", ("Paper", "26.3"): "256",
                  ("Purpur", "26.3"): "2660"}


def excluded_by_project(platform, version):
    return (platform in EXCLUDED_PLATFORMS or version in EXCLUDED_VERSIONS
            or (platform, version) in EXCLUDED_TARGETS)


def version_key(value):
    return tuple(int(piece) for piece in re.findall(r"\d+", value.split("-")[0]))


def alpha_build(value):
    return bool(value and re.search(r"(?:^|[\s+._-])alpha(?:\d|[\s+._-]|$)", value, re.IGNORECASE))


def endpoints(platform, version):
    if platform == "Fabric":
        return f"https://meta.fabricmc.net/v2/versions/loader/{version}"
    if platform == "Quilt":
        return f"https://meta.quiltmc.org/v3/versions/loader/{version}"
    if platform == "Forge":
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
    if platform == "NeoForge":
        artifact = "forge" if version == "1.20.1" else "neoforge"
        return f"https://maven.neoforged.net/releases/net/neoforged/{artifact}/maven-metadata.xml"
    if platform in ("CraftBukkit", "Spigot"):
        return f"https://hub.spigotmc.org/versions/{version}.json"
    if platform in ("Paper", "Folia"):
        return f"https://fill.papermc.io/v3/projects/{platform.lower()}/versions/{version}/builds"
    if platform == "Purpur":
        return f"https://api.purpurmc.org/v2/purpur/{version}"
    return f"https://repo.spongepowered.org/repository/maven-public/org/spongepowered/{platform.lower()}/maven-metadata.xml"


def fetch(url):
    request = Request(url, headers={"User-Agent": "Mozilla/5.0 (BaruSikoZako compatibility verification)"})
    try:
        with urlopen(request, timeout=15) as response:
            payload = response.read()
    except HTTPError as error:
        return {"error": f"HTTP {error.code}", "noBuild": error.code == 404 and not url.endswith(".xml")}
    except (URLError, TimeoutError) as error:
        return {"error": str(error), "noBuild": False}
    if url.endswith(".xml"):
        return [entry.text for entry in ElementTree.fromstring(payload).findall("./versioning/versions/version")]
    return json.loads(payload)


def available_build(platform, version, data):
    if isinstance(data, dict) and "error" in data:
        return None, data["noBuild"]
    if platform in ("Fabric", "Quilt"):
        entries = [entry["loader"]["version"] for entry in data]
        return (max(entries, key=lambda value: (not alpha_build(value), "-" not in value, version_key(value)))
                if entries else None), not entries
    if platform in ("CraftBukkit", "Spigot"):
        return f'BuildTools #{data["name"]}', False
    if platform in ("Paper", "Folia"):
        builds = sorted(data, key=lambda build: (not alpha_build(build["channel"]),
                                                 build["channel"] == "STABLE", build["id"]), reverse=True)
        return (f'#{builds[0]["id"]} {builds[0]["channel"]}' if builds else None), not builds
    if platform == "Purpur":
        return "#" + data["builds"]["latest"], False
    if platform == "NeoForge" and version != "1.20.1":
        prefix = version.removeprefix("1.") if version.startswith("1.") else ("26.1.0" if version == "26.1" else version)
        revision = r"\.\d+(?:-(?:beta|alpha))?"
        build_regex = re.escape(prefix) + (r"\.0" if version in ("26.2", "26.3") else "") + revision
    else:
        build_regex = re.escape(version) + r"-(?!SNAPSHOT).*"
    builds = [build for build in data if re.fullmatch(build_regex, build) and not build.endswith("-SNAPSHOT")]
    # Official metadata lists revisions in publication order.
    preferred = [build for build in builds if not alpha_build(build)] or builds
    return (preferred[-1] if preferred else None), not builds


def load_rows():
    urls = list(dict.fromkeys(endpoints(platform, version) for platform in PLATFORMS for version in VERSIONS))
    with ThreadPoolExecutor(max_workers=10) as pool:
        results = dict(zip(urls, pool.map(fetch, urls)))
    targets = {}
    for line in Path("gradle/compatibility-targets.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            targets[key] = value
    rows = []
    for version in VERSIONS:
        for platform in PLATFORMS:
            url = endpoints(platform, version)
            data = results[url]
            build, absent = available_build(platform, version, data)
            key = (platform, version)
            supported_fml = targets.get(f"fml.runtime.{platform.lower()}.{version}")
            legacy = version.startswith("1.")
            fabric_family = platform in ("Fabric", "Quilt")
            remapped = fabric_family and f"fabric.legacy.api.{version}" in targets
            has_product = (fabric_family and (not legacy or remapped)) or supported_fml is not None
            preview = PREVIEW_BUILDS.get(key)
            project_excluded = excluded_by_project(platform, version)
            excluded = project_excluded or alpha_build(build)
            if project_excluded:
                status, reason = "NOT_APPLICABLE", "按项目支持范围排除"
            elif alpha_build(build):
                status, reason = "NOT_APPLICABLE", "官方索引仅有 Alpha 构建，按项目规则跳过"
            elif absent:
                status, reason = "NOT_APPLICABLE", "官方索引无对应版本"
            elif has_product:
                status, reason = "NOT_TESTED", ("已有 Java 25 的重映射开发入口" if remapped else "已有开发构建入口")
            elif preview:
                status, reason = "UNSUPPORTED", "预览已验证拒绝。缺少写入结果、稳定视图及启动回滚入口"
            elif not build:
                status, reason = "BLOCKED", "未能核实上游构建"
            elif legacy and platform in ("Forge", "NeoForge"):
                status, reason = "BLOCKED", "需要旧 FML 的生产映射、保存钩子与回滚入口"
            elif legacy:
                status, reason = "BLOCKED", "上游构建可用，保存结果、写入排空和启动回滚待验证"
            else:
                status, reason = "BLOCKED", "当前仓库缺少这个组合的正式适配或构建目标"
            states = dict.fromkeys(STAGES, status)
            if preview and not (excluded or absent):
                states.update(BUILD="PASS", LOAD="PASS", FAULT="NOT_TESTED", PERFORMANCE="NOT_TESTED")
            rows.append({"minecraft": version, "platform": platform, "upstream": build, "source": url,
                         "projectTarget": supported_fml or (f"Remapped Fabric {version}" if remapped else
                                                           "Zako 26.x" if has_product else None),
                         "previewBuild": preview, "excluded": excluded, "stages": states, "reason": reason})
    return rows


def attach_runs(rows, directories):
    index = {(row["platform"].lower(), row["minecraft"]): row for row in rows}
    for path in sorted(filepath for directory in directories for filepath in directory.glob("*/result.json")):
        run = json.loads(path.read_text(encoding="utf-8"))
        row = index[(run["platform"], run["minecraft"])]
        if row.get("excluded"):
            row.setdefault("historicalRuns", []).append({"raw": path.as_posix(), "result": run})
            continue
        stages = run["stages"]
        if "productSha256" in run:
            row["stages"]["BUILD"] = "PASS"
        for stage in ("LOAD", "BACKUP", "VERIFY", "RESTORE", "RESTART", "ONLINE"):
            if stages.get(stage) == "PASS":
                row["stages"][stage] = "PASS"
        budget_rejections = run.get("mainThreadBudgetRejectionsMillis")
        backup_log = path.with_name("backup.log")
        if backup_log.is_file() and (budget_rejections is None or not run.get("saveSamples")):
            log = backup_log.read_text(encoding="utf-8", errors="replace")
            if budget_rejections is None:
                budget_rejections = [int(ms) for ms in re.findall(
                    r"World save preparation took (\d+) ms on the game thread", log)]
            if not run.get("saveSamples"):
                run["saveSamples"] = [
                    {"mainThreadMillis": int(save.replace(",", "")), "copyMillis": int(copy.replace(",", ""))}
                    for save, copy in re.findall(
                        r"capture prepared: save ([\d,]+) ms, background copy ([\d,]+) ms", log)]
        if budget_rejections:
            run["mainThreadBudgetRejectionsMillis"] = budget_rejections
            row["mainThreadBudgetRejectionsMillis"] = row.get("mainThreadBudgetRejectionsMillis", []) + budget_rejections
        elif failure := run.get("failure"):
            stage_order = {"provision": ("LOAD",), "seed": ("LOAD",),
                           "backup": ("LOAD", "BACKUP", "VERIFY"), "restore": ("RESTORE", "RESTART")}
            first_incomplete = next((stage for stage in stage_order[failure["phase"]] if stage not in stages), None)
            if first_incomplete and row["stages"][first_incomplete] != "PASS":
                row["stages"][first_incomplete] = "FAIL"
            if run.get("clientType") and "ONLINE" not in stages and row["stages"]["ONLINE"] != "PASS":
                row["stages"]["ONLINE"] = "FAIL"
            if failure.get("reason") == "SHUTDOWN_TIMEOUT" or "timed out after 120 seconds" in failure["detail"]:
                for stage in ("RESTORE", "RESTART"):
                    if row["stages"][stage] != "PASS":
                        row["stages"][stage] = "BLOCKED"
                row["reason"] = "实测曾在停服后等待 JVM 退出超时"
        row.setdefault("runs", []).append({"raw": path.as_posix(), "result": run})
        if run["map"] == "git-world":
            complete = all(stages.get(stage) == "PASS" for stage in ("BACKUP", "VERIFY", "RESTORE", "RESTART"))
            row["largeRoundTripObserved"] = row.get("largeRoundTripObserved", False) or complete
    for path in sorted(filepath for directory in directories for filepath in directory.glob("*/*guards-result.json")):
        run = json.loads(path.read_text(encoding="utf-8"))
        row = index[(run["platform"], run["minecraft"])]
        field = "historicalFaultRuns" if row.get("excluded") else "partialFaultRuns"
        row.setdefault(field, []).append({"raw": path.as_posix(), "result": run})


def render(rows):
    headings = ("Minecraft", "平台", "官方构建候选", *STAGES, "范围")
    yield "# BaruSikoZako 实服验收矩阵"
    yield ""
    yield "各项按独立证据记录。小地图的 PASS 只覆盖该规模；发布验收仍要求大地图、真实在线与完整故障矩阵。预览的 BUILD / LOAD 仅记录预览 JAR。"
    yield ""
    small_pass = sum(any(run["result"]["map"] == "small" and run["result"]["stages"].get("RESTART") == "PASS"
                         for run in row.get("runs", [])) for row in rows)
    large_pass = sum(bool(row.get("largeRoundTripObserved")) for row in rows)
    online_pass = sum(row["stages"]["ONLINE"] == "PASS" for row in rows)
    yield f"本轮已记录 {small_pass} 个组合的小地图往返、{large_pass} 个组合的大地图往返和 {online_pass} 个在线备份验证。"
    yield ""
    measurements = []
    for row in rows:
        for evidence in row.get("runs", []):
            run = evidence["result"]
            if run["map"] == "git-world" and run["stages"].get("RESTART") == "PASS":
                measurements.append(run)
    if measurements:
        yield "## 大地图实测"
        yield ""
        yield "缓存复用和并行测试会影响耗时。MSPT 为每次 100 tick 的滚动统计，表中取各窗口相应百分位的最大值。"
        yield ""
        yield "| 平台 | Minecraft | 客户端 | 全量 | 增量 | 保存准备最长 | 后台复制最长 | MSPT P50 / P95 / P99 |"
        yield "| --- | --- | --- | ---: | ---: | ---: | ---: | --- |"
        for run in measurements:
            stage_times = run.get("saveSamples", [])
            percentiles = [tuple(map(float, match.groups())) for line in run.get("tickQueryWindows", [])
                           if (match := re.search(r"P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms", line))]
            mspt = " / ".join(f"{max(values):g}" for values in zip(*percentiles)) if percentiles else "—"
            longest_save = max((sample["mainThreadMillis"] for sample in stage_times), default=None)
            longest_copy = max((sample["copyMillis"] for sample in stage_times), default=None)
            save_text = f"{longest_save} ms" if longest_save is not None else "—"
            copy_text = f"{longest_copy / 1000:.2f} s" if longest_copy is not None else "—"
            yield (f'| {run["platform"]} | {run["minecraft"]} | {run.get("clientType") or "空服"} | '
                   f'{run["full"]["taskMillis"]/1000:.2f} s | {run["incremental"]["taskMillis"]/1000:.2f} s | '
                   f'{save_text} | {copy_text} | {mspt} |')
        yield ""
    yield "## 逐组合状态"
    yield ""
    yield "| " + " | ".join(headings) + " |"
    yield "| " + " | ".join("---" for _ in headings) + " |"
    for row in rows:
        official = f'[{row["upstream"] or "暂无确定构建"}]({row["source"]})'
        values = [row["minecraft"], row["platform"], official, *row["stages"].values(), row["reason"]]
        if row.get("largeRoundTripObserved"):
            values[-1] += "。大地图完成全量、增量、校验与回滚"
        elif row.get("runs"):
            values[-1] += "。大地图往返待完成"
        fault_runs = row.get("partialFaultRuns", [])
        if any(run["result"]["tested"] for run in fault_runs):
            values[-1] += "。已记录部分实服故障验证"
        preview = row.get("previewRun", {}).get("result", {})
        if preview.get("upstreamStart") == "PASS" and preview.get("previewLoad") != "PASS":
            values[-1] += "。纯上游对照服已实测启动"
        if preview.get("fault", {}).get("confirmed") and preview["fault"]["saveResult"] == "Saved the game":
            values[-1] += "。已复现保存报告成功但计分板数据未写入"
        if any(run["result"].get("emptyPauseCaptures") == 2 and run["result"]["stages"].get("RESTART") == "PASS"
               for run in row.get("runs", [])):
            values[-1] += "。原版空服暂停状态往返通过"
        if rejected := row.get("mainThreadBudgetRejectionsMillis"):
            values[-1] += f"。在启用耗时门槛的试验中曾拒绝任务，最长 {max(rejected)} ms"
        yield "| " + " | ".join(values) + " |"


def preview_evidence_rank(run):
    return (run.get("previewLoad") == "PASS", run.get("upstreamStart") == "PASS",
            bool(run.get("fault", {}).get("confirmed")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cached", type=Path, help="Use the previously saved upstream inventory.")
    parser.add_argument("--runs", type=Path, action="append", default=[])
    parser.add_argument("--experimental-runs", type=Path, action="append", default=[],
                        help="Record trials of candidate artifacts without changing the supported-build status.")
    parser.add_argument("--preview-runs", type=Path, action="append", default=[])
    parser.add_argument("--release-check", action="store_true")
    args = parser.parse_args()
    rows = json.loads(args.cached.read_text(encoding="utf-8")) if args.cached else load_rows()
    for row in rows:
        if excluded_by_project(row["platform"], row["minecraft"]):
            row.update(excluded=True, projectTarget=None, reason="按项目支持范围排除",
                       stages=dict.fromkeys(STAGES, "NOT_APPLICABLE"))
            for active, historical in (("runs", "historicalRuns"), ("partialFaultRuns", "historicalFaultRuns")):
                if active in row:
                    row.setdefault(historical, []).extend(row.pop(active))
            row.pop("largeRoundTripObserved", None)
            row.pop("mainThreadBudgetRejectionsMillis", None)
    attach_runs(rows, args.runs)
    for directory in args.experimental_runs:
        for path in sorted(directory.glob("*/result.json")):
            run = json.loads(path.read_text(encoding="utf-8"))
            row = next(row for row in rows
                       if row["platform"].lower() == run["platform"] and row["minecraft"] == run["minecraft"])
            row.setdefault("experimentalRuns", []).append({"raw": path.as_posix(), "result": run})
    for directory in args.preview_runs:
        for path in sorted(directory.glob("*/preview-result.json")):
            run = json.loads(path.read_text(encoding="utf-8"))
            row = next(row for row in rows
                       if row["platform"].lower() == run["platform"] and row["minecraft"] == run["minecraft"])
            evidence = {"raw": path.as_posix(), "result": run}
            history = row.setdefault("previewRuns", {})
            if previous := row.get("previewRun"):
                history.setdefault(previous["raw"], previous["result"])
            history[evidence["raw"]] = run
            if previous is None or preview_evidence_rank(run) >= preview_evidence_rank(previous["result"]):
                row["previewRun"] = evidence
            if run["previewLoad"] == "PASS" and not row.get("excluded"):
                row["reason"] = "官方服务端和预览 JAR 已启动，保存屏障及启动回滚入口待验证"
                row["stages"].update(BUILD="PASS", LOAD="PASS", BACKUP=run["backup"],
                                     VERIFY="UNSUPPORTED", RESTORE=run["restore"], RESTART="NOT_TESTED",
                                     FAULT="NOT_TESTED", ONLINE="UNSUPPORTED", PERFORMANCE="NOT_TESTED")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "matrix.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "matrix.md").write_text("\n".join(render(rows)) + "\n", encoding="utf-8")
    if args.release_check:
        missing = [row for row in rows
                   if any(status != "NOT_APPLICABLE" for status in row["stages"].values()) and
                   (not row.get("largeRoundTripObserved") or any(status != "PASS" for status in row["stages"].values()))]
        if missing:
            raise SystemExit(f"Release validation blocked by {len(missing)} combinations. See matrix.json.")


if __name__ == "__main__":
    main()
