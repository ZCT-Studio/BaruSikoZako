# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""release_plan.py 的单元测试"""

import json
import unittest
from pathlib import Path
import tempfile
import os
import sys
from unittest.mock import patch, MagicMock

NOTES = "## [1.0.0]\n\nInitial release.\n"

# 将脚本目录添加到路径
sys.path.insert(0, str(Path(__file__).parent))

import release_plan
import publish_release
from release_plan import (
    read_table, channel, resolve, targets, code_changed,
    changelogs, asset_names, plan, previous_catalog,
)


class TestReleasePlan(unittest.TestCase):
    def setUp(self):
        self.sample_versions = {
            "mod": {"version": "1.0.0", "overrides": {}},
            "api": {"version": "1.0.0", "overrides": {}},
            "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
        }
        self.sample_releases = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {
                "mod": ["fabric", "quilt"],
                "other": ["fabric"]
            },
            "versions": {
                "1.20.1": {
                    "latest": ["fabric", "quilt"],
                    "lts": ["forge"]
                }
            }
        }

    def test_channel(self):
        self.assertEqual(channel("1.0.0"), "release")
        self.assertEqual(channel("1.0.0-alpha"), "alpha")
        self.assertEqual(channel("1.0.0-beta"), "beta")
        self.assertEqual(channel("1.0.0-rc"), "rc")
        self.assertEqual(channel("1.0.0-snapshot"), "snapshot")
        self.assertEqual(channel("1.0.0+build"), "release")
        self.assertEqual(channel("1.0.0-alpha+build"), "alpha")

    def test_resolve(self):
        result = resolve(self.sample_versions, "fabric", "1.20.1")
        self.assertEqual(result["mod"], "1.0.0")
        self.assertEqual(result["api"], "1.0.0")
        self.assertEqual(set(result), {"mod", "api"})

    def test_targets(self):
        selected = targets(self.sample_releases)
        self.assertIn(("1.20.1", "fabric"), selected)
        self.assertIn(("1.20.1", "quilt"), selected)
        self.assertEqual(selected[("1.20.1", "fabric")], "latest")

    def test_changelogs(self):
        md = "## [1.0.0]\n\nInitial release.\n\n## [0.9.0]\n\nBeta."
        sections = changelogs(md)
        self.assertEqual(sections["1.0.0"], "Initial release.")
        self.assertEqual(sections["0.9.0"], "Beta.")

    def test_asset_names(self):
        paths = asset_names("mod", "1.0.0", "fabric1.20.1")
        self.assertEqual(paths, [
            "mod/barusikozako-v1.0.0+fabric1.20.1.jar",
        ])

    def test_bundled_zstd_version_change_builds_without_publishing(self):
        current = dict(self.sample_versions, backend={"zstd": {"version": "1.0.1", "overrides": {}}})
        result = plan(current, self.sample_releases, self.sample_versions, "", [";lists/version.json5"])
        self.assertTrue(result["should_build"])
        self.assertFalse(result["should_release"])
        for job in result["build_jobs"]:
            self.assertEqual({asset["component"] for asset in job["assets"]}, {"mod", "api"})

    def test_plan_should_release(self):
        # 使用 versions.json5 和 CHANGELOG.md 创建临时目录
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = None
            changed = [";lists/version.json5"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertTrue(result["should_release"])

    def test_api_release_notes_come_from_the_api_changelog(self):
        result = plan(self.sample_versions, self.sample_releases, None, "feat: bump version",
                      [";lists/version.json5"], "## [1.0.0]\n\nMod release.\n",
                      api_markdown="## [1.0.0]\n\nAPI release.\n")
        notes = {}
        for job in result["jobs"]:
            for asset in job["assets"]:
                notes[asset["component"]] = asset["notes"]
        self.assertEqual(notes["mod"], "Mod release.")
        self.assertEqual(notes["api"], "API release.")

    def test_build_only_keeps_fml_targets(self):
        self.sample_releases["versions"]["1.20.1"] = {"latest": ["fabric", "quilt", "forge"]}
        result = plan(self.sample_versions, self.sample_releases, None, "bump version",
                      [";lists/version.json5"], build_only=True)
        self.assertEqual({job["loader"] for job in result["build_jobs"]}, {"fabric", "quilt", "forge"})
        self.assertFalse(result["should_release"])

    def test_manual_build_is_independent_of_the_previous_commit(self):
        result = plan(self.sample_versions, self.sample_releases, None, "docs [no-build]",
                      [], build_only=True, force_build=True)
        self.assertEqual({job["loader"] for job in result["build_jobs"]}, {"fabric", "quilt"})
        self.assertFalse(result["should_release"])

    def test_build_inputs_include_packaged_resources_and_ci_requirements(self):
        for path in (".github/scripts/requirements.txt", "LICENSE", "resource/barusikozako/lang/en_us.json"):
            with self.subTest(path=path):
                self.assertTrue(code_changed([path]))

    def test_plan_no_release_flag(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = None
            changed = [";lists/version.json5"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "feat: bump version [no-release]", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertFalse(result["should_release"])

    def test_plan_version_unchanged(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = self.sample_versions
            changed = ["README.md"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "docs: update readme", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertFalse(result["should_release"])

    def test_prerelease_detection(self):
        versions_with_beta = {
            "mod": {"version": "1.0.0-beta", "overrides": {}},
            "api": {"version": "1.0.0-beta", "overrides": {}},
            "backend": {"zstd": {"version": "1.0.0-beta", "overrides": {}}}
        }
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(versions_with_beta))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0-beta]\n\nBeta release.\n")
            
            old = None
            changed = [";lists/version.json5"]
            result = plan(
                versions_with_beta, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.0-beta]\n\nBeta release.\n"
            )
            self.assertTrue(result["should_release"])
            self.assertTrue(any(asset["channel"] == "beta"
                                    for job in result["jobs"] for asset in job["assets"]))

    def test_plan_should_build_on_code_change(self):
        """代码变化而版本未变化时应构建，但不应发布"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = self.sample_versions
            changed = ["engine/core/src/main/java/Foo.java"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "refactor: improve something", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertTrue(result["should_build"])
            self.assertFalse(result["should_release"])
            self.assertEqual({job["target"] for job in result["build_jobs"]},
                             {"fabric1.20.1", "quilt1.20.1"})
            # 但没有发布工作
            self.assertEqual(len(result.get("jobs", [])), 0)

    def test_plan_should_build_on_version_change(self):
        """版本变化应触发构建和发布"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            current_versions = {
                "mod": {"version": "1.0.1", "overrides": {}},
                "api": {"version": "1.0.0", "overrides": {}},
                "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
            }
            (tmpdir / "versions.json5").write_text(json.dumps(current_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.1]\n\nPatch release.\n")
            
            old = self.sample_versions  # 1.0.0
            changed = [";lists/version.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.1]\n\nPatch release.\n"
            )
            self.assertTrue(result["should_build"])
            self.assertTrue(result["should_release"])
            self.assertGreater(len(result.get("build_jobs", [])), 0)
            self.assertGreater(len(result.get("jobs", [])), 0)

    def test_plan_no_build_flag(self):
        """[no-build] should disable both build and release."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            current_versions = {
                "mod": {"version": "1.0.1", "overrides": {}},
                "api": {"version": "1.0.0", "overrides": {}},
                "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
            }
            (tmpdir / "versions.json5").write_text(json.dumps(current_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.1]\n\nPatch release.\n")
            
            old = self.sample_versions
            changed = [";lists/version.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version [no-build]", changed,
                "## [1.0.1]\n\nPatch release.\n"
            )
            self.assertFalse(result["should_build"])
            self.assertFalse(result["should_release"])
            self.assertEqual(len(result.get("build_jobs", [])), 0)
            self.assertEqual(len(result.get("jobs", [])), 0)

    def test_plan_no_api_flag_skips_the_api_component(self):
        """[no-api] should build and release only the mod."""
        current_versions = {
            "mod": {"version": "1.0.1", "overrides": {}},
            "api": {"version": "1.0.1", "overrides": {}},
            "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
        }
        result = plan(current_versions, self.sample_releases, self.sample_versions,
                      "feat: bump [no-api]", [";lists/version.json5"],
                      "## [1.0.1]\n\nPatch release.\n")
        self.assertTrue(result["should_build"])
        for job in result["build_jobs"] + result["jobs"]:
            self.assertEqual([asset["component"] for asset in job["assets"]], ["mod"])

    def test_plan_no_mod_flag_skips_the_mod_component(self):
        """[no-mod] should build and release only the API."""
        current_versions = {
            "mod": {"version": "1.0.1", "overrides": {}},
            "api": {"version": "1.0.1", "overrides": {}},
            "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
        }
        result = plan(current_versions, self.sample_releases, self.sample_versions,
                      "feat: bump [no-mod]", [";lists/version.json5"],
                      "## [1.0.1]\n\nPatch release.\n")
        self.assertTrue(result["should_build"])
        for job in result["build_jobs"] + result["jobs"]:
            self.assertEqual([asset["component"] for asset in job["assets"]], ["api"])

    def test_plan_no_release_flag_allows_build(self):
        """[no-release] should disable release but allow build."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            current_versions = {
                "mod": {"version": "1.0.1", "overrides": {}},
                "api": {"version": "1.0.0", "overrides": {}},
                "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
            }
            (tmpdir / "versions.json5").write_text(json.dumps(current_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.1]\n\nPatch release.\n")
            
            old = self.sample_versions
            changed = [";lists/version.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version [no-release]", changed + ["platforms/fabric/src/main/java/Foo.java"],
                "## [1.0.1]\n\nPatch release.\n"
            )
            self.assertTrue(result["should_build"])
            self.assertFalse(result["should_release"])
            self.assertGreater(len(result.get("build_jobs", [])), 0)
            self.assertEqual(len(result.get("jobs", [])), 0)

    def test_plan_doc_only_change(self):
        """仅文档变更不应触发构建或发布"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = self.sample_versions
            changed = ["README.md", "docs/guide.md"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "docs: update readme", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertFalse(result["should_build"])
            self.assertFalse(result["should_release"])
            self.assertEqual(len(result.get("build_jobs", [])), 0)
            self.assertEqual(len(result.get("jobs", [])), 0)

    def test_build_jobs_include_all_components_on_code_change(self):
        """代码变化时构建任务应包含全部组件"""
        versions_with_api = {
            "mod": {"version": "1.0.0", "overrides": {}},
            "api": {"version": "1.0.0", "overrides": {}},
            "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
        }
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(versions_with_api))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = versions_with_api
            changed = ["engine/core/src/main/java/Foo.java"]
            result = plan(
                versions_with_api, self.sample_releases, old,
                "refactor: improve something", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            # 检查构建作业是否包含所有组件
            for job in result.get("build_jobs", []):
                components = {asset["component"] for asset in job["assets"]}
                self.assertEqual(components, {"mod", "api"})
                self.assertTrue(job.get("build_only", False))

    def test_release_jobs_only_emitted_components(self):
        """发布任务只应包含版本发生变化的组件"""
        versions_with_api = {
            "mod": {"version": "1.0.1", "overrides": {}},
            "api": {"version": "1.0.0", "overrides": {}},
            "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
        }
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(versions_with_api))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.1]\n\nMod version bump.\n")
            
            old = {
                "mod": {"version": "1.0.0", "overrides": {}},
                "api": {"version": "1.0.0", "overrides": {}},
                "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
            }
            changed = [";lists/version.json5"]
            result = plan(
                versions_with_api, self.sample_releases, old,
                "feat: bump mod version", changed,
                "## [1.0.1]\n\nMod version bump.\n"
            )
            # 发布作业应该只包含 mod 组件
            for job in result.get("jobs", []):
                components = {asset["component"] for asset in job["assets"]}
                self.assertEqual(components, {"mod"})
                self.assertFalse(job.get("build_only", True))
            # 验收版本包括伴随工件，但不发布它们
            for job in result["build_jobs"]:
                components = {asset["component"] for asset in job["assets"]}
                self.assertEqual(components, {"mod", "api"})

    def test_fabric_quilt_sharing_in_build_jobs(self):
        """版本一致时 Fabric 和 Quilt 应共享构建目标"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            current_versions = {
                "mod": {"version": "1.0.1", "overrides": {}},
                "api": {"version": "1.0.0", "overrides": {}},
                "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
            }
            (tmpdir / "versions.json5").write_text(json.dumps(current_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.1]\n\nPatch.\n")
            
            old = self.sample_versions
            changed = [";lists/version.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.1]\n\nPatch.\n"
            )
            # 检查结构目标是否存在构建作业
            fabric_jobs = [j for j in result.get("build_jobs", []) if j["loader"] == "fabric"]
            self.assertGreater(len(fabric_jobs), 0)

    def test_prerelease_filters_and_build_plan_covers_release_assets(self):
        current = dict(self.sample_versions, mod={"version": "1.1.0-ALPHA.1", "overrides": {}})
        args = (current, self.sample_releases, self.sample_versions)
        changed = [";lists/version.json5", "platforms/fabric/src/main/java/Foo.java"]
        notes = "## [1.1.0-ALPHA.1]\n\nAlpha.\n\n" + NOTES
        result = plan(*args, "Bump alpha", changed, notes)
        builds = {job["target"]: job for job in result["build_jobs"]}
        self.assertEqual(set(builds), {"fabric1.20.1", "quilt1.20.1"})
        self.assertEqual([job["target"] for job in result["jobs"]], ["fabric1.20.1"])
        for job in result["jobs"]:
            self.assertTrue(set(path for entry in job["assets"] for path in entry["paths"]) <=
                            set(path for entry in builds[job["target"]]["assets"] for path in entry["paths"]))
        self.assertTrue(any(asset["channel"] != "release"
                            for job in result["jobs"] for asset in job["assets"]))

        forced = plan(*args, "Bump alpha [release-all]", changed, notes)
        self.assertIn("forge1.20.1", {job["target"] for job in forced["jobs"]})

    def test_force_release_bypasses_version_check(self):
        """[force-release] should trigger release even without version changes."""
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "Rerelease [force-release]", ["platforms/fabric/src/main/java/Foo.java"], NOTES)
        self.assertTrue(result["should_release"])
        # fabric and quilt share a release job (loader_group="fabric"), so only 2 release jobs
        self.assertEqual({job["target"] for job in result["jobs"]}, {"fabric1.20.1", "forge1.20.1"})
        # All components should be emitted despite no version change
        for job in result["jobs"]:
            self.assertEqual(len(job["assets"]), 2)  # mod, api
        # But build jobs should include both fabric and quilt
        self.assertEqual({job["target"] for job in result["build_jobs"]},
                         {"fabric1.20.1", "quilt1.20.1", "forge1.20.1"})

    def test_force_release_with_no_code_change(self):
        """[force-release] should work even with only non-code changes."""
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "Manual rerelease [force-release]", ["README.md"], NOTES)
        self.assertTrue(result["should_release"])
        self.assertEqual({job["target"] for job in result["jobs"]}, {"fabric1.20.1", "forge1.20.1"})
        self.assertEqual({job["target"] for job in result["build_jobs"]},
                         {"fabric1.20.1", "forge1.20.1"})

    def test_missing_changelog_requires_marker(self):
        with self.assertRaises(SystemExit):
            plan(self.sample_versions, self.sample_releases, self.sample_versions,
                 "Rerelease [force-release]", [])

    def test_no_changelog_marker_allows_missing_section(self):
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "Rerelease [force-release] [no-changelog]", [])
        self.assertTrue(result["should_release"])
        for job in result["jobs"]:
            for asset in job["assets"]:
                self.assertIn("No changelog", asset["notes"])

    def test_build_all_marker_builds_every_target_without_releasing(self):
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "chore: verify all targets [build-all]", [";lists/version.json5"])
        self.assertEqual({job["target"] for job in result["build_jobs"]},
                         {"fabric1.20.1", "quilt1.20.1", "forge1.20.1"})
        self.assertTrue(result["should_build"])
        self.assertFalse(result["should_release"])
        self.assertEqual(result["jobs"], [])
        for job in result["build_jobs"]:
            self.assertEqual({asset["component"] for asset in job["assets"]}, {"mod", "api"})

    def test_build_lts_marker_builds_only_lts_targets_without_releasing(self):
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "chore: verify lts [BUILD-LTS]", [";lists/version.json5"])
        self.assertEqual({job["target"] for job in result["build_jobs"]}, {"forge1.20.1"})
        self.assertTrue(result["should_build"])
        self.assertFalse(result["should_release"])
        self.assertEqual(result["jobs"], [])

    def test_build_markers_apply_to_pull_requests(self):
        result = plan(self.sample_versions, self.sample_releases, None,
                      "ci: verify all targets [build-all]", [], build_only=True)
        self.assertEqual({job["target"] for job in result["build_jobs"]},
                         {"fabric1.20.1", "quilt1.20.1", "forge1.20.1"})
        self.assertFalse(result["should_release"])
        lts = plan(self.sample_versions, self.sample_releases, None,
                   "ci: verify lts [build-lts]", [], build_only=True)
        self.assertEqual({job["target"] for job in lts["build_jobs"]}, {"forge1.20.1"})
        self.assertFalse(lts["should_release"])

    def test_storefront_skip_flags_preserve_only_the_selected_release_destinations(self):
        for flags, expected in (("[no-modrinth]", ["curseforge"]),
                                ("[NO-CURSEFORGE]", ["modrinth"]),
                                ("[no-modrinth] [no-curseforge]", [])):
            with self.subTest(flags=flags):
                result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                              f"fix(ci): update publisher [force-release] {flags}",
                              [".github/scripts/publish_release.py"], NOTES)
                self.assertEqual(result["storefronts"], expected)
                self.assertEqual(result["should_release"], bool(expected))
                self.assertEqual(result["force_release"], bool(expected))
                self.assertTrue(result["should_build"])
                if not expected:
                    self.assertEqual(result["jobs"], [])

    def test_force_release_and_release_all(self):
        self.sample_versions["mod"]["version"] = "1.0.0-alpha"
        args = (self.sample_versions, self.sample_releases, self.sample_versions)
        notes = "## [1.0.0-alpha]\n\nAlpha.\n\n" + NOTES
        ordinary = plan(*args, "Retry [force-release]", [], notes)
        combined = plan(*args, "Retry\n\n[FORCE-RELEASE] [release-all]", [], notes)
        keys = lambda result: {(job["target"], asset["component"])
                               for job in result["jobs"] for asset in job["assets"]}
        self.assertNotIn(("forge1.20.1", "mod"), keys(ordinary))
        self.assertIn(("forge1.20.1", "mod"), keys(combined))
        self.assertTrue(combined["force_release"])
        for flag in ("[no-release]", "[no-build]"):
            self.assertFalse(plan(*args, f"Retry [force-release] {flag}", [])["should_release"])
        self.assertFalse(plan(*args, "Retry [force-release]", [], build_only=True)["should_release"])

    def test_release_title_alias_can_trigger_release(self):
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "chore(release): Release v1.0.0 [force-relaese]", [], NOTES)
        self.assertTrue(result["force_release"])

    def test_cli_missing_push_base_after_force_push(self):
        cases = [
            ("chore(release): Release v1.0.0 [force-relaese]", [], True),
            ("Retry [force-release]", [], True),
            ("Ordinary change", [], False),
            ("Retry [force-release] [no-release]", [], False),
            ("Retry [force-release]", ["--build-only"], False),
            ("Retry [force-release]", ["--force-build"], False),
        ]
        for message, options, publishes in cases:
            with self.subTest(message=message, options=options), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "plan.json"
                commit = "a" * 40
                changelog = "## [1.0.0]\n\nInitial release." if publishes else ""
                api_changelog = "## [1.0.0]\n\nAPI release." if publishes else ""
                files = {"version.json5": json.dumps(self.sample_versions),
                         "release.json5": json.dumps(self.sample_releases), "CHANGELOG.md": changelog,
                         "CHANGELOG.API.md": api_changelog}

                def git_missing_base(*args, **_):
                    if args[0] == "log":
                        return message
                    if args == ("rev-parse", "HEAD"):
                        return commit + "\n"
                    self.assertEqual(args[0], "rev-parse")
                    return None

                args = ["release_plan.py", "--base", "f" * 40, "--output", str(output), *options]
                github_env = {"GITHUB_SERVER_URL": "https://github.example",
                              "GITHUB_REPOSITORY": "Example/BaruSikoZako"}
                with (
                    patch.dict(os.environ, github_env),
                    patch.object(sys, "argv", args),
                    patch.object(Path, "read_text", autospec=True, side_effect=lambda path, **_: files[path.name]),
                    patch("release_plan.git", git_missing_base),
                ):
                    release_plan.main()
                result = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual(publishes, result["should_release"])
                if publishes:
                    self.assertTrue(result["force_release"])
                    self.assertEqual({job["target"] for job in result["jobs"]}, {"fabric1.20.1", "forge1.20.1"})
                    for job in result["jobs"]:
                        for asset in job["assets"]:
                            api = asset["component"] == "api"
                            file = "CHANGELOG.API.md" if api else "CHANGELOG.md"
                            body = (("API release." if api else "Initial release.") if changelog else
                                    "_No changelog for this release_\n\n_本次发布未找到更新日志_")
                            expected = (f"{body}\n\n---\n\nFull changelog / 完整更新日志:\n\n"
                                        f"[{file}](https://github.example/Example/BaruSikoZako/blob/{commit}/{file})\n")
                            self.assertEqual(asset["notes"], expected)

    def test_version_table_edit_without_effective_version_change_is_a_no_op(self):
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "Reformat version tables", [";lists/version.json5", ";lists/release.json5"])
        self.assertFalse(result["should_release"])
        self.assertFalse(result["should_build"])

    def test_packaged_third_party_notice_is_build_input(self):
        self.assertTrue(code_changed(["platforms/fabric/third-party/snakeyaml-2.6.jar/LICENSE"]))
        self.assertTrue(code_changed(["platforms/fabric/third-party/asm-9.10.1.jar/LICENSE.txt"]))

    def test_ci_scripts_are_build_inputs(self):
        self.assertTrue(code_changed([".github/scripts/release_plan.py"]))
        self.assertTrue(code_changed([".github/scripts/build_target.ps1"]))

    def test_lists_build_manifests_are_build_inputs(self):
        self.assertTrue(code_changed([";lists/targets.json5"]))
        self.assertTrue(code_changed([";lists/sources.json5"]))
        self.assertTrue(code_changed([";lists/;rules/main.json5"]))
        self.assertFalse(code_changed([";lists/version.json5"]))
        self.assertFalse(code_changed([";lists/release.json5"]))

    def test_merge_games_plan_one_merged_unit(self):
        releases = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {"mod_fabric": ["fabric", "quilt"], "mod": ["mod_fabric", "forge", "neoforge"]},
            "versions": {"26.3": {"latest": ["mod"]}},
        }
        result = plan(self.sample_versions, releases, self.sample_versions,
                      "chore: verify merged target [build-all] [merge-jars]", [], merge_games=frozenset({"26.3"}))
        self.assertEqual([job["target"] for job in result["build_jobs"]], ["universal26.3"])
        job = result["build_jobs"][0]
        self.assertEqual(job["loader"], "universal")
        # Quilt 复用 Fabric 实现，随合并产物一起分发
        self.assertEqual(job["platforms"], ["fabric", "forge", "neoforge", "quilt"])
        self.assertEqual({asset["component"] for asset in job["assets"]}, {"mod", "api"})
        self.assertEqual([path for asset in job["assets"] for path in asset["paths"]],
                         ["mod/barusikozako-v1.0.0+universal26.3.jar",
                          "api/barusikozako_api-v1.0.0+universal26.3.jar"])

    def test_merge_only_applies_to_listed_versions(self):
        releases = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {"mod_fabric": ["fabric", "quilt"], "mod": ["mod_fabric", "forge", "neoforge"]},
            "versions": {"26.2": {"shift": ["mod"]}, "1.20.1": {"lts": ["forge", "mod_fabric"]}},
        }
        result = plan(self.sample_versions, releases, self.sample_versions,
                      "chore: verify targets [build-all] [merge-jars]", [], merge_games=frozenset({"26.2"}))
        self.assertEqual({job["target"] for job in result["build_jobs"]},
                         {"universal26.2", "fabric1.20.1", "quilt1.20.1", "forge1.20.1"})

    def test_merge_requires_commit_flag(self):
        releases = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {"mod_fabric": ["fabric", "quilt"], "mod": ["mod_fabric", "forge", "neoforge"]},
            "versions": {"26.3": {"latest": ["mod"]}},
        }
        result = plan(self.sample_versions, releases, self.sample_versions,
                      "chore: verify targets [build-all]", [], merge_games=frozenset({"26.3"}))
        targets = {job["target"] for job in result["build_jobs"]}
        self.assertNotIn("universal26.3", targets)
        self.assertLessEqual({"fabric26.3", "forge26.3", "neoforge26.3"}, targets)

    def test_merge_flag_triggers_merged_build(self):
        releases = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {"mod_fabric": ["fabric", "quilt"], "mod": ["mod_fabric", "forge", "neoforge"]},
            "versions": {"26.3": {"latest": ["mod"]}},
        }
        result = plan(self.sample_versions, releases, self.sample_versions,
                      "chore: verify merged [merge-jars]", [], merge_games=frozenset({"26.3"}))
        self.assertEqual([job["target"] for job in result["build_jobs"]], ["universal26.3"])
        self.assertEqual(result["build_jobs"][0]["loader"], "universal")

    def test_merge_fml_flag_merges_only_forge_and_neoforge(self):
        releases = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {"mod_fabric": ["fabric", "quilt"], "mod": ["mod_fabric", "forge", "neoforge"]},
            "versions": {"26.3": {"shift": ["mod"]}},
        }
        result = plan(self.sample_versions, releases, self.sample_versions,
                      "chore: verify merged [merge-fml-jars]", [], merge_games=frozenset({"26.3"}))
        jobs = {job["target"]: job for job in result["build_jobs"]}
        self.assertEqual(set(jobs), {"fml26.3", "fabric26.3", "quilt26.3"})
        self.assertEqual(jobs["fml26.3"]["loader"], "fml")
        self.assertEqual(jobs["fml26.3"]["platforms"], ["forge", "neoforge"])
        self.assertEqual(jobs["fml26.3"]["assets"][0]["paths"], ["mod/barusikozako-v1.0.0+fml26.3.jar"])
        self.assertEqual(jobs["fabric26.3"]["platforms"], ["fabric"])
        self.assertEqual(jobs["quilt26.3"]["platforms"], ["quilt"])

    def test_merged_unit_requires_matching_component_versions(self):
        releases = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {"mod_fabric": ["fabric", "quilt"], "mod": ["mod_fabric", "forge", "neoforge"]},
            "versions": {"26.3": {"latest": ["mod"]}},
        }
        current = dict(self.sample_versions, api={"version": "1.0.0", "overrides": {"neoforge26.3": "1.0.1"}})
        with self.assertRaises(ValueError):
            plan(current, releases, None, "chore: verify targets [build-all] [merge-jars]", [],
                 merge_games=frozenset({"26.3"}))


class TestPublishRelease(unittest.TestCase):
    MERGED = {"target": "universal26.3", "game": "26.3", "loader": "universal",
              "platforms": ["fabric", "forge", "neoforge", "quilt"]}
    FML = {"target": "fml26.3", "game": "26.3", "loader": "fml",
           "platforms": ["forge", "neoforge"]}

    def test_storefront_loaders_accept_merged_target(self):
        self.assertEqual(publish_release.storefront_loaders(self.MERGED), self.MERGED["platforms"])

    def test_storefront_loaders_accept_fml_target(self):
        self.assertEqual(publish_release.storefront_loaders(self.FML), ["forge", "neoforge"])

    def test_storefront_loaders_reject_invalid_merged_selection(self):
        for platforms in ([], ["fabric"], ["fabric", "bukkit"]):
            with self.subTest(platforms=platforms), self.assertRaises(ValueError):
                publish_release.storefront_loaders(dict(self.MERGED, platforms=platforms))

    def test_merged_curseforge_versions_cover_every_platform(self):
        self.assertEqual(publish_release.curseforge_versions(self.MERGED),
                         ["26.3", "Fabric", "Forge", "NeoForge", "Quilt", "Client", "Server"])

    def test_ordered_storefront_accepts_merged_jobs(self):
        entry = {"component": "mod", "version": "1.0.0", "paths": [], "notes": "", "channel": "release"}
        self.assertEqual(len(publish_release.ordered_storefront([(self.MERGED, entry, [])])), 1)


if __name__ == "__main__":
    unittest.main()
