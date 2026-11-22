# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import publish_release as publish
from release_plan import code_changed


class StorefrontTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.file = Path(temp.name) / "product.jar"
        self.file.write_bytes(b"release fixture")
        self.addCleanup(patch.stopall)
        patch.dict("os.environ", MODRINTH_TOKEN="test", CURSEFORGE_TOKEN="test",
                   CURSEFORGE_API_KEY="test").start()
        self.ids = {"mod": "11", "api": "22", "zstd": "33"}
        self.slugs = {"mod": "barusikozako", "api": "barusikozako-api", "zstd": "barusikozako-zstd"}

    def job(self, *loaders):
        return {"game": "26.3", "loader": loaders[0], "platforms": list(loaders), "target": loaders[0] + "26.3"}

    def entry(self, component):
        return {"component": component, "version": "0.5.0", "channel": "release", "notes": "notes"}

    @patch.object(publish, "request")
    def test_modrinth_new_release_metadata(self, request):
        for component in self.ids:
            with self.subTest(component=component):
                request.side_effect = [None, {}]
                publish.modrinth(self.job("fabric", "quilt"), self.entry(component), [self.file], self.ids)
                kind, payload = request.call_args.kwargs["fields"]
                self.assertEqual(kind, "data")
                self.assertEqual(payload["project_id"], self.ids[component])
                self.assertEqual(payload["loaders"], ["fabric", "quilt"])
                self.assertEqual(payload["dependencies"], [] if component == "mod" else [
                    {"project_id": "11", "dependency_type": "required"}])

    @patch.object(publish, "request")
    def test_modrinth_retry_repairs_loader_and_dependency(self, request):
        request.side_effect = [{"files": [{"filename": self.file.name,
                                           "hashes": {"sha512": publish.digest(self.file, "sha512")}}],
                                "id": "existing", "loaders": ["fabric"], "game_versions": ["26.3"],
                                "dependencies": [{"project_id": "external", "dependency_type": "required"}]}, {}]
        publish.modrinth(self.job("quilt"), self.entry("api"), [self.file], self.ids)
        self.assertEqual(request.call_args.args, (publish.MODRINTH + "/version/existing", "PATCH"))
        self.assertEqual(request.call_args.kwargs["fields"], ("json", {
            "loaders": ["quilt"], "dependencies": [{"project_id": "external", "dependency_type": "required"},
                                                  {"project_id": "11", "dependency_type": "required"}]}))

    @patch.object(publish, "request")
    def test_curseforge_all_loader_labels_and_dependencies(self, request):
        for loader, label in publish.LOADERS.items():
            for component in self.ids:
                with self.subTest(loader=loader, component=component):
                    request.side_effect = [{"data": [], "pagination": {"totalCount": 0}}, {"id": 100}]
                    job = self.job(loader)
                    version_names = publish.curseforge_versions(job)
                    publish.curseforge(job, self.entry(component), [self.file], self.ids, version_names, self.slugs)
                    kind, payload = request.call_args.kwargs["fields"]
                    self.assertEqual(kind, "metadata")
                    self.assertEqual(payload["gameVersionNames"], ["26.3", label])
                    if component == "mod":
                        self.assertNotIn("relations", payload)
                    else:
                        self.assertEqual(payload["relations"], {"projects": [
                            {"projectID": "11", "slug": "barusikozako", "type": "requiredDependency"}]})
        self.assertEqual(publish.curseforge_versions(self.job("fabric", "quilt")), ["26.3", "Fabric", "Quilt"])
        legacy = {"game": "1.20.1", "loader": "forge", "platforms": ["forge"], "target": "forge1.20.1"}
        self.assertEqual(publish.curseforge_versions(legacy), ["1.20.1", "Forge"])
        with self.assertRaisesRegex(ValueError, "Invalid storefront loader"):
            publish.curseforge_versions(self.job("forge", "neoforge"))

    @patch.object(publish, "request")
    def test_curseforge_retry_updates_matched_bytes(self, request):
        request.side_effect = [{"data": [{"id": 12, "fileName": self.file.name,
                                         "gameVersions": ["26.3", "Forge"], "dependencies": [],
                                         "hashes": [{"algo": 1, "value": publish.digest(self.file, "sha1")}] }],
                                "pagination": {"totalCount": 1}}, {"id": 12}]
        publish.curseforge(self.job("neoforge"), self.entry("zstd"), [self.file], self.ids, ["26.3", "NeoForge"], self.slugs)
        self.assertEqual(request.call_args.args, (publish.CURSEFORGE + "/projects/33/update-file", "POST"))
        self.assertEqual(request.call_args.kwargs["fields"][1]["fileID"], 12)
        self.assertEqual(request.call_args.kwargs["fields"][1]["gameVersionNames"], ["26.3", "NeoForge"])

    @patch.object(publish, "request")
    def test_force_release_uses_distinct_storefront_keys_for_different_bytes(self, request):
        request.side_effect = [{"files": [{"filename": self.file.name, "hashes": {"sha512": "different"}}]}, None, {}]
        publish.modrinth(self.job("fabric"), self.entry("api"), [self.file], self.ids, force_run="12345")
        self.assertEqual(request.call_args.kwargs["fields"][1]["version_number"], "0.5.0+fabric26.3.build.12345")
        request.side_effect = [{"data": [{"fileName": self.file.name, "hashes": [{"algo": 1, "value": "different"}]}],
                                "pagination": {"totalCount": 1}}, {}]
        publish.curseforge(self.job("forge"), self.entry("api"), [self.file], self.ids, ["26.3", "Forge"], self.slugs,
                          force_run="12345")
        self.assertEqual(request.call_args.kwargs["files"], [("file", self.file, "product.build.12345.jar")])

    @patch.object(publish, "request")
    def test_resolves_exact_main_project(self, request):
        with patch.dict("os.environ", MODRINTH_API_ID="side-api", MODRINTH_ZSTD_ID="side-zstd",
                        CURSEFORGE_MOD_ID="11", CURSEFORGE_API_ID="22", CURSEFORGE_ZSTD_ID="33"):
            request.side_effect = [{"id": id_, "slug": self.slugs[key]} for key, id_ in self.ids.items()]
            self.assertEqual(publish.project_ids("MODRINTH", ("Authorization", "test"))[0], self.ids)
            self.assertEqual(request.call_args_list[0].args[0], publish.MODRINTH + "/project/barusikozako")
            request.side_effect = [{"data": {"id": int(id_), "slug": "unrelated" if key == "mod" else self.slugs[key]}}
                                   for key, id_ in self.ids.items()]
            with self.assertRaisesRegex(ValueError, "main project must use the slug"):
                publish.project_ids("CURSEFORGE", ("x-api-key", "test"))

    @patch.object(publish, "invoke")
    def test_github_batch_collision_and_retry(self, invoke):
        sha = "a" * 40
        marker = "<!-- barusikozako-release-run:123 -->"
        old = {"tag_name": "release/2026.11.22.1", "target_commitish": "b" * 40, "draft": True, "body": ""}
        invoke.side_effect = [json.dumps([[old]]), json.dumps([{"ref": "refs/tags/release/2026.11.22.2"}])]
        tag, release = publish.github_destination("org/repo", old["tag_name"], sha, marker)
        self.assertEqual(tag, "release/2026.11.22.3")
        self.assertIsNone(release)
        draft = {"tag_name": tag, "target_commitish": sha, "draft": True, "body": "notes\n" + marker, "assets": []}
        invoke.side_effect = [json.dumps([[old, draft]]), ""]
        self.assertEqual(publish.github_destination("org/repo", old["tag_name"], sha, marker), (tag, draft))
        self.assertTrue(publish.github_release("org/repo", tag, draft, False, self.file, [self.file], sha))
        self.assertIn("upload", invoke.call_args.args[0])

    def test_packaged_resource_changes_needs_ci(self):
        self.assertTrue(code_changed(["metadatas.json5"]))
        self.assertTrue(code_changed(["resource/barusikozako/icon.png"]))
        self.assertFalse(code_changed(["README.md", "resource/README.md"]))


if __name__ == "__main__":
    unittest.main()
