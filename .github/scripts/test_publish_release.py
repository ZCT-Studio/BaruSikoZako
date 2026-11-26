# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import publish_release as publish
from release_plan import code_changed, read_table, targets


class StorefrontTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.file = Path(temp.name) / "product.jar"
        self.file.write_bytes(b"release fixture")
        self.addCleanup(patch.stopall)
        patch.dict("os.environ", MODRINTH_TOKEN="test", CURSEFORGE_TOKEN="test",
                   CURSEFORGE_API_KEY="test").start()
        self.ids = {"mod": "11", "api": "22"}
        self.slugs = {"mod": "barusikozako", "api": "barusikozako-api"}

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
                self.assertEqual(payload["environment"], "server_only")
                self.assertEqual(payload["dependencies"], [] if component == "mod" else [
                    {"project_id": "11", "dependency_type": "required"}])

    @patch.object(publish, "request")
    def test_modrinth_retry_repairs_loader_and_dependency(self, request):
        request.side_effect = [{"files": [{"filename": self.file.name,
                                           "hashes": {"sha512": publish.digest(self.file, "sha512")}}],
                                "id": "existing", "loaders": ["fabric"], "game_versions": ["26.3"], "environment": "server_only",
                                "dependencies": [{"project_id": "external", "dependency_type": "required"}]}, {}]
        publish.modrinth(self.job("quilt"), self.entry("api"), [self.file], self.ids)
        self.assertEqual(request.call_args.args, (publish.MODRINTH + "/version/existing", "PATCH"))
        self.assertEqual(request.call_args.kwargs["fields"], ("json", {
            "loaders": ["quilt"], "dependencies": [{"project_id": "external", "dependency_type": "required"},
                                                  {"project_id": "11", "dependency_type": "required"}]}))

    @patch.object(publish, "request")
    def test_all_storefront_targets_support_dedicated_servers_and_singleplayer(self, request):
        catalog = Path(__file__).resolve().parents[2] / ";lists" / "release.json5"
        for game, loader in targets(read_table(catalog.read_text(encoding="utf-8"))):
            if loader not in publish.LOADERS:
                continue
            for component in self.ids:
                with self.subTest(game=game, loader=loader, component=component):
                    job = dict(self.job(loader), game=game, target=loader + game)
                    request.side_effect = [None, {}]
                    publish.modrinth(job, self.entry(component), [self.file], self.ids)
                    self.assertEqual(request.call_args.kwargs["fields"][1]["environment"], "server_only")
                    self.assertEqual(publish.curseforge_versions(job),
                                     [game, publish.LOADERS[loader], "Client", "Server"])

    @patch.object(publish, "request")
    def test_modrinth_updates_environment_of_existing_version(self, request):
        for previous in ({"environment": "dedicated_server_only"}, {}):
            with self.subTest(previous=previous):
                request.side_effect = [dict(previous, id="existing", loaders=["fabric", "quilt"],
                                            game_versions=["26.3"], files=[{"filename": self.file.name,
                                                "hashes": {"sha512": publish.digest(self.file, "sha512")}}]), None]
                publish.modrinth(self.job("fabric", "quilt"), self.entry("mod"), [self.file], self.ids)
                self.assertEqual(request.call_args.args, ("https://api.modrinth.com/v3/version/existing", "PATCH"))
                self.assertEqual(request.call_args.kwargs["fields"],
                                 ("json", {"minecraft_mod": {"environment": "server_only"}}))

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
                    self.assertEqual(payload["gameVersionNames"], ["26.3", label, "Client", "Server"])
                    if component == "mod":
                        self.assertNotIn("relations", payload)
                    else:
                        self.assertEqual(payload["relations"], {"projects": [
                            {"projectID": 11, "slug": "barusikozako", "type": "requiredDependency"}]})
        self.assertEqual(publish.curseforge_versions(self.job("fabric", "quilt")),
                         ["26.3", "Fabric", "Quilt", "Client", "Server"])
        legacy = {"game": "1.20.1", "loader": "forge", "platforms": ["forge"], "target": "forge1.20.1"}
        self.assertEqual(publish.curseforge_versions(legacy), ["1.20.1", "Forge", "Client", "Server"])
        with self.assertRaisesRegex(ValueError, "Invalid storefront loader"):
            publish.curseforge_versions(self.job("forge", "neoforge"))

    @patch.object(publish, "request")
    def test_curseforge_retry_adds_missing_environment(self, request):
        request.side_effect = [{"data": [{"id": 12, "fileName": self.file.name,
                                         "gameVersions": ["26.3", "NeoForge"],
                                         "dependencies": [{"modId": 11, "relationType": 3}],
                                         "hashes": [{"algo": 1, "value": publish.digest(self.file, "sha1")}] }],
                                "pagination": {"totalCount": 1}}, {"id": 12}]
        job = self.job("neoforge")
        publish.curseforge(job, self.entry("api"), [self.file], self.ids, publish.curseforge_versions(job), self.slugs)
        self.assertEqual(request.call_args.args, (publish.CURSEFORGE + "/projects/22/update-file", "POST"))
        self.assertEqual(request.call_args.kwargs["fields"][1]["fileID"], 12)
        self.assertEqual(request.call_args.kwargs["fields"][1]["gameVersionNames"],
                         ["26.3", "NeoForge", "Client", "Server"])

    @patch.object(publish, "request")
    def test_force_release_uses_distinct_storefront_keys_for_different_bytes(self, request):
        request.side_effect = [{"files": [{"filename": self.file.name, "hashes": {"sha512": "different"}}]}, None, {}]
        publish.modrinth(self.job("fabric"), self.entry("api"), [self.file], self.ids, force_run="12345")
        self.assertEqual(request.call_args.kwargs["fields"][1]["version_number"], "0.5.0+fabric26.3.build.12345")
        request.side_effect = [{"data": [{"fileName": self.file.name, "hashes": [{"algo": 1, "value": "different"}]}],
                                "pagination": {"totalCount": 1}}, {}]
        job = self.job("forge")
        publish.curseforge(job, self.entry("api"), [self.file], self.ids, publish.curseforge_versions(job),
                          self.slugs, force_run="12345")
        self.assertEqual(request.call_args.kwargs["files"], [("file", self.file, "product.build.12345.jar")])

    @patch.object(publish, "request")
    def test_resolves_exact_main_project(self, request):
        with patch.dict("os.environ", MODRINTH_API_ID="side-api",
                        CURSEFORGE_MOD_ID="11", CURSEFORGE_API_ID="22"):
            request.side_effect = [{"id": id_, "slug": self.slugs[key]} for key, id_ in self.ids.items()]
            self.assertEqual(publish.project_ids("MODRINTH", ("Authorization", "test"))[0], self.ids)
            self.assertEqual(request.call_args_list[0].args[0], publish.MODRINTH + "/project/barusikozako")
            request.side_effect = [{"data": {"id": int(id_), "slug": "unrelated" if key == "mod" else self.slugs[key]}}
                                   for key, id_ in self.ids.items()]
            with self.assertRaisesRegex(ValueError, "main project must use the slug"):
                publish.project_ids("CURSEFORGE", ("x-api-key", "test"))

    def test_publish_plan_without_github_release_metadata(self):
        directory = self.file.parent
        job = self.job("fabric")
        entry = self.entry("mod")
        entry["paths"] = ["barusikozako-0.5.0+fabric26.3.jar"]
        artifacts = directory / "target-fabric26.3"
        artifacts.mkdir()
        (artifacts / entry["paths"][0]).write_bytes(self.file.read_bytes())
        plan = directory / "plan.json"
        plan.write_text(json.dumps({"should_release": True, "jobs": [dict(job, assets=[entry])]}),
                        encoding="utf-8")
        with (patch("sys.argv", ["publish_release.py", "--plan", str(plan), "--artifacts", str(directory)]),
              patch.dict("os.environ", {"MODRINTH_TOKEN": "test", "CURSEFORGE_API_KEY": "test",
                                        "CURSEFORGE_TOKEN": "test"}, clear=True),
              patch.object(publish, "invoke", side_effect=AssertionError("unexpected CLI call")),
              patch.object(publish, "project_ids", side_effect=[(self.ids, {}), (self.ids, self.slugs)]),
              patch.object(publish, "curseforge_versions", return_value=["26.3", "Fabric"]),
              patch.object(publish, "publish_modrinth") as modrinth,
              patch.object(publish, "publish_curseforge", return_value=set()) as curseforge):
            publish.main()
        self.assertEqual(modrinth.call_args.args[0][0][2], [(artifacts / entry["paths"][0]).resolve()])
        self.assertEqual(curseforge.call_args.args[0], modrinth.call_args.args[0])

    @patch.object(publish, "request")
    def test_curseforge_repairs_relations_with_numeric_project_ids(self, request):
        job = self.job("forge")
        names = publish.curseforge_versions(job)
        request.side_effect = [
            {"data": [{"id": 12, "fileName": self.file.name, "gameVersions": names,
                       "dependencies": [{"modId": 99, "relationType": 2}],
                       "hashes": [{"algo": 1, "value": publish.digest(self.file, "sha1")}]}],
             "pagination": {"totalCount": 1}},
            {"data": {"slug": "external"}},
            {},
        ]
        publish.curseforge(job, self.entry("api"), [self.file], self.ids, names, self.slugs)
        self.assertTrue(request.call_args.args[0].endswith("/update-file"))
        self.assertEqual(request.call_args.kwargs["fields"][1]["relations"]["projects"], [
            {"projectID": 99, "slug": "external", "type": "optionalDependency"},
            {"projectID": 11, "slug": "barusikozako", "type": "requiredDependency"},
        ])

    def test_only_selected_storefront_uses_credentials_and_publishes(self):
        directory = self.file.parent
        job = self.job("fabric")
        entry = dict(self.entry("mod"), paths=["barusikozako-0.5.0+fabric26.3.jar"])
        artifacts = directory / "target-fabric26.3"
        artifacts.mkdir()
        (artifacts / entry["paths"][0]).write_bytes(self.file.read_bytes())
        plan = directory / "plan.json"
        credentials = {
            "modrinth": {"MODRINTH_TOKEN": "test"},
            "curseforge": {"CURSEFORGE_API_KEY": "test", "CURSEFORGE_TOKEN": "test"},
        }
        for platform, environment in credentials.items():
            with self.subTest(platform=platform):
                plan.write_text(json.dumps({"should_release": True, "force_release": True,
                                            "storefronts": [platform], "jobs": [dict(job, assets=[entry])]}),
                                encoding="utf-8")
                with (patch("sys.argv", ["publish_release.py", "--plan", str(plan), "--artifacts", str(directory)]),
                      patch.dict("os.environ", dict(environment, GITHUB_RUN_ID="123"), clear=True),
                      patch.object(publish, "project_ids", return_value=(self.ids, self.slugs)) as lookup,
                      patch.object(publish, "publish_modrinth") as modrinth,
                      patch.object(publish, "publish_curseforge", return_value=set()) as curseforge):
                    publish.main()
                lookup.assert_called_once()
                self.assertEqual(lookup.call_args.args[0], platform.upper())
                selected, skipped = (modrinth, curseforge) if platform == "modrinth" else (curseforge, modrinth)
                selected.assert_called_once()
                self.assertEqual(selected.call_args.kwargs["force_run"], "123")
                skipped.assert_not_called()

    def test_both_storefronts_publish_newest_first_then_loader_and_component(self):
        cases = [
            ("26.3", ("forge",), "mod"),
            ("26.3", ("forge",), "api"),
            ("26.3", ("fabric", "quilt"), "mod"),
            ("26.3", ("neoforge",), "mod"),
            ("26.2", ("forge",), "mod"),
            ("1.21.10", ("fabric",), "mod"),
            ("1.21.10", ("quilt",), "mod"),
            ("1.21.10", ("neoforge",), "api"),
            ("1.21.9", ("forge",), "mod"),
        ]
        rows = [(dict(self.job(*loaders), game=game, target=loaders[0] + game),
                 self.entry(component), [self.file]) for game, loaders, component in cases]
        expected = [(job["target"], entry["component"]) for job, entry, _ in rows]
        shuffled = rows[::2][::-1] + rows[1::2]
        versions = {job["target"]: publish.curseforge_versions(job) for job, _, _ in rows}
        with patch.object(publish, "modrinth") as modrinth, patch.object(publish, "curseforge") as curseforge:
            publish.publish_modrinth(shuffled, self.ids)
            publish.publish_curseforge(shuffled, self.ids, versions, self.slugs)
        for target in (modrinth, curseforge):
            with self.subTest(platform=target):
                self.assertEqual([(call.args[0]["target"], call.args[1]["component"])
                                  for call in target.call_args_list], expected)

    @patch.object(publish, "invoke")
    def test_curseforge_defers_rejected_main_dependency(self, invoke):
        job = self.job("forge")
        later = self.job("neoforge")
        manifest = json.dumps({"data": [], "pagination": {"totalCount": 0}}) + "\n200"
        rejected = json.dumps({
            "errorCode": 1018,
            "errorMessage": "Invalid slug in project relations: 'barusikozako' does not exist, is not accessible, or belongs to an unrelated root category.",
        }) + "\n400"
        invoke.side_effect = [manifest, '{"id":100}\n200', manifest, rejected, manifest, '{"id":101}\n200']
        rows = [(job, self.entry("api"), [self.file]), (job, self.entry("mod"), [self.file]),
                (later, self.entry("mod"), [self.file])]
        with patch("builtins.print") as output:
            deferred = publish.publish_curseforge(rows, self.ids, {
                job["target"]: publish.curseforge_versions(job),
                later["target"]: publish.curseforge_versions(later),
            }, self.slugs)
        self.assertEqual(deferred, {"api"})
        urls = [call.args[0][-1] for call in invoke.call_args_list]
        self.assertEqual(urls[1::2], [
            publish.CURSEFORGE + f"/projects/{project}/upload-file" for project in ("11", "22", "11")
        ])
        self.assertIn("::warning", str(output.call_args))
        self.assertIn("api", str(output.call_args))

    @patch.object(publish, "invoke")
    def test_other_curseforge_relation_errors_still_fail(self, invoke):
        job = self.job("forge")
        manifest = json.dumps({"data": [], "pagination": {"totalCount": 0}}) + "\n200"
        invoke.side_effect = [manifest, json.dumps({
            "errorCode": 1018, "errorMessage": "Invalid slug in project relations: 'other-mod' is not accessible.",
        }) + "\n400"]
        with self.assertRaisesRegex(RuntimeError, "other-mod"):
            publish.publish_curseforge([(job, self.entry("api"), [self.file])], self.ids,
                                      {job["target"]: publish.curseforge_versions(job)}, self.slugs)

    def test_packaged_resource_changes_needs_ci(self):
        self.assertTrue(code_changed(["metadatas.json5"]))
        self.assertTrue(code_changed(["resource/barusikozako/icon.png"]))
        self.assertFalse(code_changed(["README.md", "resource/README.md"]))


if __name__ == "__main__":
    unittest.main()
