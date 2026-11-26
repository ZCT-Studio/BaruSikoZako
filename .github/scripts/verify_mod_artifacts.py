# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""Verify the public API and runtime are distributed separately."""

import argparse
from io import BytesIO
import json
from pathlib import Path
import tomllib
from zipfile import ZipFile


MAIN_ID = "barusikozako"
API_ID = "barusikozako_api"
BASE = "io/github/zct_studio/barusikozako/"
FABRIC_LOADERS = ("fabric", "quilt")


def verify_bytecode(archive, label, java):
    for name in archive.namelist():
        if name.startswith(BASE) and name.endswith(".class"):
            with archive.open(name) as stream:
                header = stream.read(8)
            major = int.from_bytes(header[6:8], "big")
            if header[:4] != b"\xca\xfe\xba\xbe" or major > java + 44 or header[4:6] == b"\xff\xff":
                raise ValueError(f"{label} contains {name} incompatible with Java {java} (class version {major})")
        elif name.startswith("META-INF/") and name.endswith(".jar"):
            with ZipFile(BytesIO(archive.read(name))) as nested:
                verify_bytecode(nested, f"{label}!/{name}", java)


def metadata(archive, loader, mod_id):
    if loader in FABRIC_LOADERS:
        data = json.loads(archive.read("fabric.mod.json"))
        if data["id"] != mod_id:
            raise ValueError(f"Expected mod ID {mod_id}, found {data['id']}")
        return set(data.get("depends", {})), [entry["file"] for entry in data.get("jars", [])]
    name = "mods.toml" if loader == "forge" else "neoforge.mods.toml"
    data = tomllib.loads(archive.read(f"META-INF/{name}").decode("utf-8"))
    if mod_id not in {entry["modId"] for entry in data["mods"]}:
        raise ValueError(f"Missing mod ID {mod_id}")
    required = {entry["modId"] for entry in data.get("dependencies", {}).get(mod_id, [])
                if entry.get("mandatory") or entry.get("type") == "required"}
    descriptor = "META-INF/jarjar/metadata.json"
    nested = json.loads(archive.read(descriptor))["jars"] if descriptor in archive.namelist() else []
    return required, [entry["path"] for entry in nested]


def verify(job, directory):
    loaders = job["platforms"] if job["loader"] in ("universal", "fml") else [job["loader"]]
    game = job["game"]
    java = 17 if game.startswith("1.20.") else 21 if game.startswith("1.21.") else 25
    paths = {asset["component"]: asset["paths"] for asset in job["assets"]}
    for names in paths.values():
        for name in names:
            with ZipFile(directory / name) as archive:
                verify_bytecode(archive, name, java)
    for name in paths["mod"]:
        with ZipFile(directory / name) as archive:
            classes = set(archive.namelist())
            for loader in loaders:
                required, nested = metadata(archive, loader, MAIN_ID)
                if API_ID in required or any(Path(path).name.startswith(f"{API_ID}-") for path in nested):
                    raise ValueError(f"{name} requires or embeds the standalone API mod")
                for path in nested:
                    with ZipFile(BytesIO(archive.read(path))) as jar:
                        classes.update(jar.namelist())
            if BASE + "runtime/BackupService.class" not in classes or any(
                    path.startswith(BASE + "api/") and path.endswith(".class") for path in classes):
                raise ValueError(f"{name} must contain the internal service without public API classes")
    for name in paths["api"]:
        with ZipFile(directory / name) as archive:
            classes = set(archive.namelist())
            for loader in loaders:
                required, _ = metadata(archive, loader, API_ID)
                if MAIN_ID not in required or BASE + "api/BackupService.class" not in classes:
                    raise ValueError(f"{name} must declare the main mod and provide the public API for compilation")
                if loader not in FABRIC_LOADERS and BASE + f"api/{loader}/ZakoBakIntegration.class" not in classes:
                    raise ValueError(f"{name} is missing the {loader} integration extension")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    job = next(job for job in plan["build_jobs"] if job["target"] == args.target)
    if job["loader"] != "agent":
        verify(job, args.artifacts)


if __name__ == "__main__":
    main()
