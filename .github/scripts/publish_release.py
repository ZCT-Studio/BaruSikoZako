# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""只发布已有发行计划中指定的文件"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import quote


MODRINTH = "https://api.modrinth.com/v2"
CURSEFORGE = "https://minecraft.curseforge.com/api"
CURSEFORGE_CORE = "https://api.curseforge.com/v1"
TYPES = {"release": "release", "rc": "beta", "beta": "beta", "alpha": "alpha", "snapshot": "alpha"}
FIELDS = {"mod": "MOD", "api": "API", "zstd": "ZSTD"}
MAIN_SLUG = "barusikozako"
REQUIRED_PROJECTS = {"api": ("mod",), "zstd": ("mod",), "mod": ()}
LOADERS = {"fabric": "Fabric", "quilt": "Quilt", "forge": "Forge", "neoforge": "NeoForge"}


def storefront_loaders(job):
    loaders = job["platforms"]
    target_loader = job["loader"]
    if (job["target"] != target_loader + job["game"] or not loaders or loaders[0] != target_loader
            or len(set(loaders)) != len(loaders)
            or any(loader not in LOADERS for loader in loaders)
            or (len(loaders) > 1 and set(loaders) != {"fabric", "quilt"})):
        raise ValueError(f"Invalid storefront loader selection for {job['target']}: {loaders}")
    return loaders


def digest(path, algorithm):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def env(name, pattern=None):
    value = os.environ.get(name, "")
    if not value or (pattern and not re.fullmatch(pattern, value)):
        raise ValueError(f"Configure {name} before publishing")
    return value


def invoke(args):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{args[0]} operation failed: {result.stderr.strip()}")
    return result.stdout


def request(url, method="GET", *, auth, fields=None, files=(), allow_missing=False):
    args = ["curl", "--silent", "--show-error", "--max-time", "180", "--request", method,
            "--user-agent", f'{os.environ.get("GITHUB_REPOSITORY", "ZCT-Studio/BaruSikoZako")} release-ci',
            "--header", ": ".join(auth), "--write-out", "\n%{http_code}"]
    with tempfile.TemporaryDirectory() as temp:
        if fields:
            field, payload = fields
            data = Path(temp) / "metadata.json"
            data.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            if field == "json":
                args.extend(("--header", "Content-Type: application/json", "--data-binary", f"@{data}"))
            else:
                args.extend(("--form", f"{field}=<{data};type=application/json"))
        for field, path in files:
            args.extend(("--form", f"{field}=@{path};type=application/java-archive"))
        raw = invoke([*args, url])
    body, code = raw.rsplit("\n", 1)
    if code == "404" and allow_missing:
        return None
    if not code.startswith("2"):
        raise RuntimeError(f"{method} {url} returned HTTP {code}: {body[:600]}")
    return json.loads(body) if body.strip() else {}


def assets(plan, directory, skip_missing=False):
    seen = set()
    for job in plan["jobs"]:
        for entry in job["assets"]:
            files = []
            for name in entry["paths"]:
                file = directory / f'target-{job["target"]}' / name
                if skip_missing and not file.exists():
                    continue
                if (file.name in seen or not file.is_file() or not file.name.endswith(f'+{job["target"]}.jar') or
                        re.search(r"-(sources|javadoc)(?:[.+-]|$)", file.name)):
                    raise ValueError(f"Missing, duplicated or non-product release asset: {file}")
                seen.add(file.name)
                files.append(file.resolve())
            yield job, entry, files


def release_notes(entries):
    versions = {}
    for job, entry, _ in entries:
        version = entry["version"]
        if version not in versions:
            versions[version] = ([], entry["notes"])
        versions[version][0].append(f'{entry["component"]} · {job["target"]}')
    return "\n\n".join(f'## [{version}]\n\n' + ", ".join(labels) + "\n\n" + notes
                       for version, (labels, notes) in versions.items()) + "\n"


def project_ids(prefix, auth):
    numeric = prefix == "CURSEFORGE"
    projects = {}
    slugs = {}
    for component, field in FIELDS.items():
        if numeric:
            reference = env(f"{prefix}_{field}_ID", r"\d+")
            project = request(f"{CURSEFORGE_CORE}/mods/{reference}", auth=auth)["data"]
        else:
            reference = MAIN_SLUG if component == "mod" else env(f"{prefix}_{field}_ID", r"[A-Za-z0-9_-]+")
            project = request(f"{MODRINTH}/project/{reference}", auth=auth)
        projects[component], slugs[component] = str(project["id"]), project["slug"]
    if slugs["mod"] != MAIN_SLUG:
        raise ValueError(f"{prefix} main project must use the slug {MAIN_SLUG}")
    if len(set(projects.values())) != len(projects):
        raise ValueError(f"{prefix} requires separate projects for mod, API and Zstd")
    return projects, slugs


def modrinth(job, entry, files, ids):
    component = entry["component"]
    project = ids[component]
    auth = ("Authorization", env("MODRINTH_TOKEN"))
    loaders = storefront_loaders(job)
    dependencies = [{"project_id": ids[required], "dependency_type": "required"}
                    for required in REQUIRED_PROJECTS[component]]
    version = entry["version"] + ("." if "+" in entry["version"] else "+") + job["target"]
    old = request(f"{MODRINTH}/project/{project}/version/{quote(version, safe='')}", auth=auth, allow_missing=True)
    if old is not None:
        expected = {f.name: digest(f, "sha512") for f in files}
        existing = {f["filename"]: f["hashes"]["sha512"] for f in old["files"]}
        if existing != expected:
            raise ValueError(f"Modrinth already has different files for {component} {version}")
        fields = {}
        for key, desired in (("loaders", loaders), ("game_versions", [job["game"]])):
            if set(old[key]) != set(desired):
                fields[key] = desired
        old_deps = old.get("dependencies", [])
        unmanaged = [dep for dep in old_deps if dep.get("project_id") not in ids.values()]
        managed = {(dep.get("project_id"), dep["dependency_type"], dep.get("version_id"))
                   for dep in old_deps if dep.get("project_id") in ids.values()}
        desired = {(dep["project_id"], dep["dependency_type"], None) for dep in dependencies}
        if managed != desired:
            fields["dependencies"] = unmanaged + dependencies
        if fields:
            request(f"{MODRINTH}/version/{old['id']}", "PATCH", auth=auth, fields=("json", fields))
        return
    parts = [(f"jar{i}", file) for i, file in enumerate(files)]
    metadata = {"project_id": project, "name": f'{entry["version"]} · {job["target"]}', "version_number": version,
                "changelog": entry["notes"], "game_versions": [job["game"]], "loaders": loaders,
                "version_type": TYPES[entry["channel"]], "environment": "dedicated_server_only",
                "featured": False, "file_parts": [part for part, _ in parts], "primary_file": parts[0][0],
                "dependencies": dependencies}
    request(f"{MODRINTH}/version", "POST", auth=auth, fields=("data", metadata), files=parts)


def curseforge_versions(job, version_catalog):
    names = [job["game"], *(LOADERS[loader] for loader in storefront_loaders(job))]
    version_ids = []
    for name in names:
        matches = {value["id"] for value in version_catalog if value["name"].casefold() == name.casefold()}
        if len(matches) != 1:
            raise ValueError(f"CurseForge has no unique game or loader ID for {name}")
        version_ids.extend(matches)
    return version_ids


def curseforge(job, entry, files, ids, version_ids, slugs):
    component = entry["component"]
    project = ids[component]
    auth = ("X-Api-Token", env("CURSEFORGE_TOKEN"))
    core = ("x-api-key", env("CURSEFORGE_API_KEY"))
    existing = {}
    index = 0
    while True:
        page = request(f"{CURSEFORGE_CORE}/mods/{project}/files?index={index}&pageSize=50", auth=core)
        existing.update((file["fileName"], file) for file in page["data"])
        index += len(page["data"])
        if not page["data"] or index >= page["pagination"]["totalCount"]:
            break
    required = REQUIRED_PROJECTS[component]
    relations = [{"projectID": ids[key], "slug": slugs[key], "type": "requiredDependency"} for key in required]
    for file in reversed(files):
        if old := existing.get(file.name):
            expected = digest(file, "sha1")
            if not any(item["algo"] == 1 and item["value"].lower() == expected for item in old["hashes"]):
                raise ValueError(f"CurseForge already has a different file named {file.name}")
            desired = {(int(ids[key]), 3) for key in required}
            old_deps = old.get("dependencies", [])
            our_ids = {int(value) for value in ids.values()}
            managed = {(dep["modId"], dep["relationType"]) for dep in old_deps if dep["modId"] in our_ids}
            update = {}
            old_types = {name.casefold() for name in old["gameVersions"]}
            old_loaders = old_types & LOADERS.keys()
            if old_loaders != set(job["platforms"]) or job["game"].casefold() not in old_types:
                update["gameVersions"] = version_ids
            if managed != desired:
                extras = [dep for dep in old_deps if dep["modId"] not in our_ids]
                relation_types = {1: "embeddedLibrary", 2: "optionalDependency", 3: "requiredDependency",
                                  4: "tool", 5: "incompatible", 6: "include"}
                preserved = []
                for dep in extras:
                    kind = relation_types.get(dep["relationType"])
                    if kind is None or kind == "include":
                        raise ValueError(f"Cannot preserve CurseForge dependency type {dep['relationType']}")
                    dep_id = dep["modId"]
                    slug = request(f"{CURSEFORGE_CORE}/mods/{dep_id}", auth=core)["data"]["slug"]
                    preserved.append({"projectID": str(dep_id), "slug": slug, "type": kind})
                update["relations"] = {"projects": preserved + relations}
            if update:
                update["fileID"] = old["id"]
                request(f"{CURSEFORGE}/projects/{project}/update-file", "POST", auth=auth,
                        fields=("metadata", update))
            continue
        metadata = {"displayName": file.stem, "changelog": entry["notes"], "changelogType": "markdown",
                    "gameVersions": version_ids, "releaseType": TYPES[entry["channel"]]}
        if relations:
            metadata["relations"] = {"projects": relations}
        request(f"{CURSEFORGE}/projects/{project}/upload-file", "POST", auth=auth,
                fields=("metadata", metadata), files=[("file", file)])


def github_destination(repository, planned_tag, commit_sha, marker):
    """在已串行化的发布作业中选择批次号，重试回到原有草稿"""
    match = re.fullmatch(r"(release/\d{4}\.\d{2}\.\d{2}\.)(\d+)", planned_tag)
    if match is None:
        raise ValueError(f"Invalid release batch tag: {planned_tag}")
    prefix, planned_batch = match[1], int(match[2])
    pages = json.loads(invoke(["gh", "api", "--paginate", "--slurp", f"repos/{repository}/releases?per_page=100"]))
    releases = [release for page in pages for release in page]
    ours = [release for release in releases if marker in (release.get("body") or "").splitlines()]
    if not ours:
        ours = [release for release in releases if release["tag_name"] == planned_tag
                and release["target_commitish"] == commit_sha]
    if len(ours) > 1:
        raise ValueError("Multiple GitHub releases match this workflow run")
    if ours:
        return ours[0]["tag_name"], ours[0]
    refs = json.loads(invoke(["gh", "api", f"repos/{repository}/git/matching-refs/tags/{prefix}"]))
    occupied = {release["tag_name"] for release in releases}
    occupied.update(ref["ref"].removeprefix("refs/tags/") for ref in refs)
    batches = [int(tag[len(prefix):]) for tag in occupied
               if tag.startswith(prefix) and tag[len(prefix):].isdigit()]
    batch = max(planned_batch, max(batches, default=0) + 1)
    return f"{prefix}{batch}", None


def github_release(repository, tag, existing, prerelease, notes, files, commit_sha):
    """准备 GitHub Release，返回发布平台完成后是否需要转为公开"""
    if existing is None:
        date, batch = tag.removeprefix("release/").rsplit(".", 1)
        title = f"BaruSikoZako · Release {date} #{batch}"
        invoke(["gh", "release", "create", tag, "--target", commit_sha,
                "--draft", "--title", title, "--notes-file", str(notes),
                *(["--prerelease"] if prerelease else []), *map(str, files)])
        return True
    else:
        if existing["draft"] and existing["target_commitish"] != commit_sha:
            raise ValueError(f"Release draft {tag} belongs to a different commit")
        if not existing["draft"]:
            commit = json.loads(invoke(["gh", "api",
                                        f"repos/{repository}/commits/{quote(tag, safe='')}"]))
            if commit["sha"] != commit_sha:
                raise ValueError(f"Release tag {tag} belongs to a different commit")
        incoming = {file.name: "sha256:" + digest(file, "sha256") for file in files}
        old = {file["name"]: file.get("digest") for file in existing["assets"]}
        
        # 检查是否有冲突
        if any(incoming.get(name) != digest or name not in incoming for name, digest in old.items()):
            raise ValueError(f"Release {tag} already contains a different set of assets")
        
        missing = [str(file) for file in files if file.name not in old]
        if missing:
            if not existing["draft"]:
                raise ValueError(f"Published release {tag} is missing planned assets")
            invoke(["gh", "release", "upload", tag, *missing])
        return existing["draft"]


def publish_modrinth(storefront, ids):
    """发布至 Modrinth，操作具有幂等性，可安全重试"""
    for job, entry, files in sorted(storefront, key=lambda row: ("mod", "api", "zstd").index(row[1]["component"])):
        modrinth(job, entry, files, ids)


def publish_curseforge(storefront, ids, versions, slugs):
    """发布至 CurseForge，操作具有幂等性，可安全重试"""
    for job, entry, files in sorted(storefront, key=lambda row: ("mod", "api", "zstd").index(row[1]["component"])):
        curseforge(job, entry, files, ids, versions[job["target"]], slugs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--sync", choices=("modrinth", "curseforge"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.sync:
        if args.sync == "modrinth":
            request(f"{MODRINTH}/project/{MAIN_SLUG}", "PATCH", auth=("Authorization", env("MODRINTH_TOKEN")),
                    fields=("json", {"body": Path("Modrinth.md").read_text(encoding="utf-8")}))
        else:
            project = env("CURSEFORGE_MOD_ID", r"\d+")
            request(f"{CURSEFORGE}/projects/{project}/update-project", "POST",
                    auth=("X-Api-Token", env("CURSEFORGE_TOKEN")),
                    fields=("metadata", {"description": Path("CurseForge.md").read_text(encoding="utf-8"),
                                         "descriptionType": "markdown"}))
        return
    if args.plan is None or args.artifacts is None:
        parser.error("Provide --plan and --artifacts")
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    if not plan.get("should_release", False):
        raise ValueError("Release not triggered: version unchanged or [no-release] flag present")
    if not plan.get("tag"):
        raise ValueError("No release tag in plan")
    
    tag = plan["tag"]
    prerelease = plan.get("prerelease", False)
    release_title = plan.get("release_title", "")
    commit_sha = os.environ.get("GITHUB_SHA", "")
    
    entries = list(assets(plan, args.artifacts, skip_missing=args.dry_run))
    if not entries:
        raise ValueError("No release assets")
    storefront = [entry for entry in entries if entry[0]["loader"] != "agent"]
    
    if args.dry_run:
        print(f"Tag: {tag}")
        print(f"Title: {release_title}")
        print(f"Prerelease: {prerelease}")
        print(f"Commit SHA: {commit_sha}")
        print(release_notes(entries))
        for _, _, files in entries:
            print(*(file.name for file in files), sep="\n")
        return
    
    # 如果是标签推送，请验证标签是否与工作流程引用匹配
    if os.environ.get("GITHUB_REF_TYPE") == "tag" and tag != os.environ.get("GITHUB_REF_NAME"):
        raise ValueError(f"The release plan tag {tag} must match the workflow ref {os.environ.get('GITHUB_REF_NAME')}")
    env("GITHUB_SHA", r"[0-9a-f]{40}")

    # 在创建草稿或上传前核对两个平台的项目身份和 CurseForge 游戏版本与加载器标签
    if storefront:
        mr, _ = project_ids("MODRINTH", ("Authorization", env("MODRINTH_TOKEN")))
        cf, slugs = project_ids("CURSEFORGE", ("x-api-key", env("CURSEFORGE_API_KEY")))
        catalog = request(f"{CURSEFORGE}/game/versions", auth=("X-Api-Token", env("CURSEFORGE_TOKEN")))
        versions = {job["target"]: curseforge_versions(job, catalog) for job, _, _ in storefront}

    # 准备发行说明
    repository = env("GITHUB_REPOSITORY", r"[\w.-]+/[\w.-]+")
    run_id = env("GITHUB_RUN_ID", r"\d+")
    marker = f"<!-- barusikozako-release-run:{run_id} -->"
    tag, existing = github_destination(repository, tag, commit_sha, marker)
    with tempfile.TemporaryDirectory() as temp:
        notes = Path(temp) / "CHANGELOG.md"
        notes.write_text(release_notes(entries) + "\n" + marker + "\n", encoding="utf-8")
        
        # 第 1 步：创建/更新 GitHub 版本
        print(f"Creating/updating GitHub Release for tag {tag}...")
        draft = github_release(repository, tag, existing, prerelease, notes,
                               [file for _, _, files in entries for file in files], commit_sha)
    
    # 第2步：发布到Modrinth（独立，可以单独重试）
    if storefront:
        print("Publishing to Modrinth...")
        publish_modrinth(storefront, mr)
        print("Modrinth: done")
        
        # 步骤3：发布到CurseForge（独立，可以单独重试）
        print("Publishing to CurseForge...")
        publish_curseforge(storefront, cf, versions, slugs)
        print("CurseForge: done")
        
    if draft:
        print("Publishing GitHub Release...")
        invoke(["gh", "release", "edit", tag, "--draft=false"])
        print("GitHub Release published")


if __name__ == "__main__":
    main()
