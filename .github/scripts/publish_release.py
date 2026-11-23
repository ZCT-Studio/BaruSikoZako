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
MODRINTH_ENVIRONMENT = "server_only"


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


class HttpError(RuntimeError):
    def __init__(self, method, url, code, body):
        super().__init__(f"{method} {url} returned HTTP {code}: {body[:600]}")
        self.code = code
        self.body = body

    def rejects_curseforge_dependency(self, slug):
        if self.code != "400":
            return False
        try:
            payload = json.loads(self.body)
        except json.JSONDecodeError:
            return False
        return (isinstance(payload, dict) and payload.get("errorCode") == 1018
                and str(payload.get("errorMessage", "")).startswith(f"Invalid slug in project relations: '{slug}'"))


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
        for field, path, *name in files:
            filename = f";filename={name[0]}" if name else ""
            args.extend(("--form", f"{field}=@{path};type=application/java-archive{filename}"))
        raw = invoke([*args, url])
    body, code = raw.rsplit("\n", 1)
    if code == "404" and allow_missing:
        return None
    if not code.startswith("2"):
        raise HttpError(method, url, code, body)
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


def modrinth(job, entry, files, ids, *, force_run=None):
    component = entry["component"]
    project = ids[component]
    auth = ("Authorization", env("MODRINTH_TOKEN"))
    loaders = storefront_loaders(job)
    dependencies = [{"project_id": ids[required], "dependency_type": "required"}
                    for required in REQUIRED_PROJECTS[component]]
    version = entry["version"] + ("." if "+" in entry["version"] else "+") + job["target"]
    old = request(f"{MODRINTH}/project/{project}/version/{quote(version, safe='')}", auth=auth, allow_missing=True)
    if old is not None and force_run:
        hashes = {f["filename"]: f["hashes"]["sha512"] for f in old["files"]}
        if hashes != {file.name: digest(file, "sha512") for file in files}:
            version += f".build.{force_run}"
            old = request(f"{MODRINTH}/project/{project}/version/{quote(version, safe='')}",
                          auth=auth, allow_missing=True)
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
        if old.get("environment") != MODRINTH_ENVIRONMENT:
            # v2 的版本编辑接口不更新运行环境
            route = MODRINTH.removesuffix("/v2") + f"/v3/version/{old['id']}"
            request(route, "PATCH", auth=auth, fields=("json", {"minecraft_mod": {"environment": MODRINTH_ENVIRONMENT}}))
        return
    parts = [(f"jar{i}", file) for i, file in enumerate(files)]
    metadata = {"project_id": project, "name": f'{entry["version"]} · {job["target"]}', "version_number": version,
                "changelog": entry["notes"], "game_versions": [job["game"]], "loaders": loaders,
                "version_type": TYPES[entry["channel"]],
                "environment": MODRINTH_ENVIRONMENT,
                "featured": False, "file_parts": [part for part, _ in parts], "primary_file": parts[0][0],
                "dependencies": dependencies}
    request(f"{MODRINTH}/version", "POST", auth=auth, fields=("data", metadata), files=parts)


def curseforge_versions(job):
    # 由上传 API 在项目上下文中解析标签名称
    return [job["game"], *(LOADERS[loader] for loader in storefront_loaders(job)), "Client", "Server"]


def curseforge(job, entry, files, ids, version_names, slugs, *, force_run=None):
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
    relations = [{"projectID": int(ids[key]), "slug": slugs[key], "type": "requiredDependency"} for key in required]
    for file in reversed(files):
        name = file.name
        old = existing.get(name)
        if old:
            expected = digest(file, "sha1")
            if not curseforge_has_hash(old, expected) and force_run:
                name = f"{file.stem}.build.{force_run}{file.suffix}"
                old = existing.get(name)
            if old and not curseforge_has_hash(old, expected):
                raise ValueError(f"CurseForge already has a different file named {name}")
        if old:
            desired = {(int(ids[key]), 3) for key in required}
            old_deps = old.get("dependencies", [])
            our_ids = {int(value) for value in ids.values()}
            managed = {(dep["modId"], dep["relationType"]) for dep in old_deps if dep["modId"] in our_ids}
            update = {}
            old_types = {name.casefold() for name in old["gameVersions"]}
            old_loaders = old_types & LOADERS.keys()
            if old_loaders != set(job["platforms"]) or not {name.casefold() for name in version_names} <= old_types:
                update["gameVersionNames"] = version_names
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
                    preserved.append({"projectID": int(dep_id), "slug": slug, "type": kind})
                update["relations"] = {"projects": preserved + relations}
            if update:
                update["fileID"] = old["id"]
                request(f"{CURSEFORGE}/projects/{project}/update-file", "POST", auth=auth,
                        fields=("metadata", update))
            continue
        metadata = {"displayName": Path(name).stem, "changelog": entry["notes"], "changelogType": "markdown",
                    "gameVersionNames": version_names, "releaseType": TYPES[entry["channel"]]}
        if relations:
            metadata["relations"] = {"projects": relations}
        request(f"{CURSEFORGE}/projects/{project}/upload-file", "POST", auth=auth,
                fields=("metadata", metadata), files=[("file", file, name)])


def curseforge_has_hash(old, expected):
    return any(item["algo"] == 1 and item["value"].lower() == expected for item in old["hashes"])


def ordered_storefront(entries):
    loader_order = ("forge", "fabric", "quilt", "neoforge")
    component_order = ("mod", "api", "zstd")

    def release_key(entry):
        job, asset, _ = entry
        game_version = tuple(map(int, job["game"].split(".")))
        return (game_version, -loader_order.index(job["loader"]), -component_order.index(asset["component"]))

    return sorted(entries, key=release_key, reverse=True)


def publish_modrinth(storefront, ids, *, force_run=None):
    """发布至 Modrinth，操作具有幂等性，可安全重试"""
    for job, entry, files in ordered_storefront(storefront):
        modrinth(job, entry, files, ids, force_run=force_run)


def publish_curseforge(storefront, ids, versions, slugs, *, force_run=None):
    """发布至 CurseForge，暂缓平台拒绝关联必需主项目的组件"""
    deferred = set()
    main_slug = slugs["mod"]
    for job, entry, files in ordered_storefront(storefront):
        component = entry["component"]
        if component in deferred:
            continue
        try:
            curseforge(job, entry, files, ids, versions[job["target"]], slugs, force_run=force_run)
        except HttpError as error:
            if "mod" not in REQUIRED_PROJECTS[component] or not error.rejects_curseforge_dependency(main_slug):
                raise
            deferred.add(component)
            print(f"::warning title=CurseForge dependency unavailable::Deferred {component} starting at {job['target']}. "
                  f"CurseForge rejected the required {main_slug} project relation (1018). "
                  "Retry publishing when the main project can be linked.")
    return deferred


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
    storefront = [entry for entry in assets(plan, args.artifacts, skip_missing=args.dry_run)
                  if entry[0]["loader"] != "agent"]
    if not storefront:
        raise ValueError("No Modrinth or CurseForge release assets")
    
    if args.dry_run:
        ordered = ordered_storefront(storefront)
        print(release_notes(ordered))
        for _, _, files in ordered:
            print(*(file.name for file in files), sep="\n")
        return
    
    destinations = plan.get("storefronts", ["modrinth", "curseforge"])
    if not destinations or set(destinations) - {"modrinth", "curseforge"}:
        raise ValueError(f"Invalid storefronts in release plan: {destinations}")

    # 上传前核对所选平台的项目身份和凭据
    if "modrinth" in destinations:
        mr, _ = project_ids("MODRINTH", ("Authorization", env("MODRINTH_TOKEN")))
    if "curseforge" in destinations:
        cf, slugs = project_ids("CURSEFORGE", ("x-api-key", env("CURSEFORGE_API_KEY")))
        env("CURSEFORGE_TOKEN")
        versions = {job["target"]: curseforge_versions(job) for job, _, _ in storefront}

    run_id = env("GITHUB_RUN_ID", r"\d+") if plan.get("force_release") else None
    if "modrinth" in destinations:
        print("Publishing to Modrinth...")
        publish_modrinth(storefront, mr, force_run=run_id)
        print("Modrinth: done")
    if "curseforge" in destinations:
        print("Publishing to CurseForge...")
        deferred = publish_curseforge(storefront, cf, versions, slugs, force_run=run_id)
        if deferred:
            print("CurseForge: deferred " + ", ".join(sorted(deferred)))
        else:
            print("CurseForge: done")


if __name__ == "__main__":
    main()
