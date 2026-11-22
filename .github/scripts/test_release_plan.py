# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""release_plan.py 的单元测试"""

import json
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
import tempfile
import os
import sys
from unittest.mock import patch, MagicMock

# 将脚本目录添加到路径
sys.path.insert(0, str(Path(__file__).parent))

import release_plan
from release_plan import (
    read_table, rules, channel, resolve, targets, code_changed,
    changelogs, asset_names, plan, previous_catalog,
    get_today_batch_number, generate_release_tag, get_release_title,
    BEIJING_TZ, TAG_PREFIX
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
                "agent": ["vanilla"]
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
        self.assertEqual(result["zstd"], "1.0.0")

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
            "mod/barusikozako-v1.0.0_L+fabric1.20.1.jar",
        ])

    def test_generate_release_tag_format(self):
        tag = generate_release_tag()
        self.assertTrue(tag.startswith("release/"))
        # 格式：发布/YYYY.MM.DD.N
        parts = tag.split("/")
        self.assertEqual(len(parts), 2)
        date_parts = parts[1].split(".")
        self.assertEqual(len(date_parts), 4)
        # 检查日期格式
        year, month, day, batch = date_parts
        self.assertTrue(year.isdigit() and len(year) == 4)
        self.assertTrue(month.isdigit() and len(month) == 2)
        self.assertTrue(day.isdigit() and len(day) == 2)
        self.assertTrue(batch.isdigit())

    def test_get_release_title(self):
        tag = "release/2026.11.22.1"
        title = get_release_title(tag)
        self.assertEqual(title, "BaruSikoZako · Release 2026.11.22 #1")

        tag = "release/2026.12.01.5"
        title = get_release_title(tag)
        self.assertEqual(title, "BaruSikoZako · Release 2026.12.01 #5")

        # 测试无效格式的回退
        title = get_release_title("invalid-tag")
        today = datetime.now(BEIJING_TZ).strftime("%Y.%m.%d")
        self.assertEqual(title, f"BaruSikoZako · Release {today} #1")

    def test_beijing_timezone(self):
        # 北京时间 UTC+8
        self.assertEqual(BEIJING_TZ.utcoffset(None), timedelta(hours=8))

    def test_plan_should_release(self):
        # 使用 versions.json5 和 CHANGELOG.md 创建临时目录
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = None
            changed = ["versions.json5"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertTrue(result["should_release"])
            self.assertIsNotNone(result["tag"])
            self.assertTrue(result["tag"].startswith("release/"))

    def test_build_only_keeps_fml_targets_and_never_allocates_a_tag(self):
        self.sample_releases["versions"]["1.20.1"] = {"latest": ["fabric", "quilt", "forge"]}
        with patch("release_plan.generate_release_tag", side_effect=AssertionError):
            result = plan(self.sample_versions, self.sample_releases, None, "bump version",
                          ["versions.json5"], build_only=True)
        self.assertEqual({job["loader"] for job in result["build_jobs"]}, {"fabric", "quilt", "forge"})
        self.assertFalse(result["should_release"])
        self.assertIsNone(result["tag"])

    def test_manual_build_is_independent_of_the_previous_commit(self):
        result = plan(self.sample_versions, self.sample_releases, None, "docs [no-build]",
                      [], build_only=True, force_build=True)
        self.assertEqual({job["loader"] for job in result["build_jobs"]}, {"fabric", "quilt"})
        self.assertFalse(result["should_release"])

    def test_build_inputs_include_packaged_resources_and_ci_requirements(self):
        for path in (".github/scripts/requirements.txt", "LICENSE", "resource/barusikozako/agent/messages_en_us.properties"):
            with self.subTest(path=path):
                self.assertTrue(code_changed([path]))

    def test_plan_no_release_flag(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = None
            changed = ["versions.json5"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "feat: bump version [no-release]", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertFalse(result["should_release"])
            self.assertIsNone(result["tag"])

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
            self.assertIsNone(result["tag"])

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
            changed = ["versions.json5"]
            result = plan(
                versions_with_beta, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.0-beta]\n\nBeta release.\n"
            )
            self.assertTrue(result["should_release"])
            self.assertTrue(result["prerelease"])

    def test_release_title_in_plan(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(self.sample_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(self.sample_releases))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.0]\n\nInitial release.\n")
            
            old = None
            changed = ["versions.json5"]
            result = plan(
                self.sample_versions, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.0]\n\nInitial release.\n"
            )
            self.assertIn("release_title", result)
            self.assertIsNotNone(result["release_title"])
            self.assertTrue(result["release_title"].startswith("BaruSikoZako · Release"))

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
            self.assertIsNone(result["tag"])
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
            changed = ["versions.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.1]\n\nPatch release.\n"
            )
            self.assertTrue(result["should_build"])
            self.assertTrue(result["should_release"])
            self.assertIsNotNone(result["tag"])
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
            changed = ["versions.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version [no-build]", changed,
                "## [1.0.1]\n\nPatch release.\n"
            )
            self.assertFalse(result["should_build"])
            self.assertFalse(result["should_release"])
            self.assertEqual(len(result.get("build_jobs", [])), 0)
            self.assertEqual(len(result.get("jobs", [])), 0)

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
            changed = ["versions.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version [no-release]", changed + ["platforms/fabric/src/main/java/Foo.java"],
                "## [1.0.1]\n\nPatch release.\n"
            )
            self.assertTrue(result["should_build"])
            self.assertFalse(result["should_release"])
            self.assertIsNone(result["tag"])
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
                # 应该有 mod、api、zstd 用于验证构建
                self.assertIn("mod", components)
                self.assertIn("api", components)
                self.assertIn("zstd", components)
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
            changed = ["versions.json5"]
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
                self.assertEqual(components, {"mod", "api", "zstd"})

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
            changed = ["versions.json5"]
            result = plan(
                current_versions, self.sample_releases, old,
                "feat: bump version", changed,
                "## [1.0.1]\n\nPatch.\n"
            )
            # 检查结构目标是否存在构建作业
            fabric_jobs = [j for j in result.get("build_jobs", []) if j["loader"] == "fabric"]
            self.assertGreater(len(fabric_jobs), 0)

    def test_agent_targets_included(self):
        """构建和发布应包含 Agent 目标"""
        releases_with_agent = {
            "policy": {"maxShiftVersions": 5},
            "platformGroups": {
                "mod": ["fabric"],
                "agent": ["vanilla"]
            },
            "versions": {
                "1.20.1": {
                    "lts": ["fabric"],
                    "shift": ["agent"]
                }
            }
        }
        current_versions = {
            "mod": {"version": "1.0.1", "overrides": {}},
            "api": {"version": "1.0.0", "overrides": {}},
            "backend": {"zstd": {"version": "1.0.0", "overrides": {}}}
        }
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)
            (tmpdir / "versions.json5").write_text(json.dumps(current_versions))
            (tmpdir / "releases.json5").write_text(json.dumps(releases_with_agent))
            (tmpdir / "CHANGELOG.md").write_text("## [1.0.1]\n\nPatch.\n")
            
            old = self.sample_versions
            changed = ["versions.json5"]
            result = plan(
                current_versions, releases_with_agent, old,
                "feat: bump version", changed,
                "## [1.0.1]\n\nPatch.\n"
            )
            # 检查构建和发布中是否存在代理目标
            agent_build = [j for j in result.get("build_jobs", []) if j["loader"] == "agent"]
            agent_release = [j for j in result.get("jobs", []) if j["loader"] == "agent"]
            self.assertGreater(len(agent_build), 0)
            self.assertGreater(len(agent_release), 0)

    @patch("release_plan.generate_release_tag", return_value="release/2026.11.22.1")
    def test_prerelease_filters_and_build_plan_covers_release_assets(self, _tag):
        current = dict(self.sample_versions, mod={"version": "1.1.0-ALPHA.1", "overrides": {}})
        args = (current, self.sample_releases, self.sample_versions)
        changed = ["versions.json5", "platforms/fabric/src/main/java/Foo.java"]
        result = plan(*args, "Bump alpha", changed)
        builds = {job["target"]: job for job in result["build_jobs"]}
        self.assertEqual(set(builds), {"fabric1.20.1", "quilt1.20.1"})
        self.assertEqual([job["target"] for job in result["jobs"]], ["fabric1.20.1"])
        for job in result["jobs"]:
            self.assertTrue(set(path for entry in job["assets"] for path in entry["paths"]) <=
                            set(path for entry in builds[job["target"]]["assets"] for path in entry["paths"]))
        self.assertTrue(result["prerelease"])

        forced = plan(*args, "Bump alpha [release-all]", changed)
        self.assertIn("forge1.20.1", {job["target"] for job in forced["jobs"]})

    def test_force_release_bypasses_version_check(self):
        """[force-release] should trigger release even without version changes."""
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "Rerelease [force-release]", ["platforms/fabric/src/main/java/Foo.java"])
        self.assertTrue(result["should_release"])
        # fabric and quilt share a release job (loader_group="fabric"), so only 2 release jobs
        self.assertEqual({job["target"] for job in result["jobs"]}, {"fabric1.20.1", "forge1.20.1"})
        # All components should be emitted despite no version change
        for job in result["jobs"]:
            self.assertEqual(len(job["assets"]), 3)  # mod, api, zstd
        # But build jobs should include both fabric and quilt
        self.assertEqual({job["target"] for job in result["build_jobs"]},
                         {"fabric1.20.1", "quilt1.20.1", "forge1.20.1"})

    def test_force_release_with_no_code_change(self):
        """[force-release] should work even with only non-code changes."""
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "Manual rerelease [force-release]", ["README.md"])
        self.assertTrue(result["should_release"])
        self.assertEqual({job["target"] for job in result["jobs"]}, {"fabric1.20.1", "forge1.20.1"})
        self.assertEqual({job["target"] for job in result["build_jobs"]},
                         {"fabric1.20.1", "forge1.20.1"})

    @patch("release_plan.generate_release_tag", return_value="release/2026.11.23.1")
    def test_force_release_and_release_all(self, _tag):
        self.sample_versions["mod"]["version"] = "1.0.0-alpha"
        args = (self.sample_versions, self.sample_releases, self.sample_versions)
        ordinary = plan(*args, "Retry [force-release]", [])
        combined = plan(*args, "Retry\n\n[FORCE-RELEASE] [release-all]", [])
        keys = lambda result: {(job["target"], asset["component"])
                               for job in result["jobs"] for asset in job["assets"]}
        self.assertNotIn(("forge1.20.1", "mod"), keys(ordinary))
        self.assertIn(("forge1.20.1", "mod"), keys(combined))
        self.assertTrue(combined["force_release"])
        for flag in ("[no-release]", "[no-build]"):
            self.assertFalse(plan(*args, f"Retry [force-release] {flag}", [])["should_release"])
        self.assertFalse(plan(*args, "Retry [force-release]", [], build_only=True)["should_release"])

    @patch("release_plan.generate_release_tag", return_value="release/2026.11.23.1")
    def test_release_title_alias_can_trigger_release(self, _tag):
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "chore(release): Release v1.0.0 [force-relaese]", [])
        self.assertTrue(result["force_release"])

    @patch("release_plan.generate_release_tag", return_value="release/2026.11.23.1")
    def test_cli_missing_push_base_after_force_push(self, _tag):
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
                files = {"versions.json5": json.dumps(self.sample_versions),
                         "releases.json5": json.dumps(self.sample_releases), "CHANGELOG.md": ""}

                def git_missing_base(*args, **_):
                    if args[0] == "log":
                        return message
                    self.assertEqual(args[0], "rev-parse")
                    return None

                args = ["release_plan.py", "--base", "f" * 40, "--output", str(output), *options]
                with patch.object(sys, "argv", args), patch.object(Path, "read_text", autospec=True,
                        side_effect=lambda path, **_: files[path.name]), patch("release_plan.git", git_missing_base):
                    release_plan.main()
                result = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual(publishes, result["should_release"])
                if publishes:
                    self.assertTrue(result["force_release"])
                    self.assertEqual({job["target"] for job in result["jobs"]}, {"fabric1.20.1", "forge1.20.1"})

    def test_version_table_edit_without_effective_version_change_is_a_no_op(self):
        result = plan(self.sample_versions, self.sample_releases, self.sample_versions,
                      "Reformat version tables", ["versions.json5", "releases.json5"])
        self.assertFalse(result["should_release"])
        self.assertFalse(result["should_build"])

    def test_packaged_third_party_notice_is_build_input(self):
        self.assertTrue(code_changed(["platforms/fabric/third-party/snakeyaml-2.6.jar/LICENSE"]))
        self.assertTrue(code_changed(["platforms/javaagent/third-party/asm-9.10.1.jar/LICENSE.txt"]))

    def test_ci_scripts_are_build_inputs(self):
        self.assertTrue(code_changed([".github/scripts/release_plan.py"]))
        self.assertTrue(code_changed([".github/scripts/build_target.ps1"]))

    @patch("release_plan.git")
    @patch("release_plan.datetime")
    def test_get_today_batch_number(self, mock_datetime, mock_git):
        mock_datetime.now.return_value = datetime(2026, 11, 22, 12, 0, tzinfo=BEIJING_TZ)

        mock_git.return_value = ""
        batch = get_today_batch_number()
        self.assertEqual(batch, 1)

        # 使用现有标签进行测试
        mock_git.return_value = "abc123\trefs/tags/release/2026.11.22.1\n" \
                                "def456\trefs/tags/release/2026.11.22.3\n" \
                                "ghi789\trefs/tags/release/2026.11.21.1"
        batch = get_today_batch_number()
        self.assertEqual(batch, 4)  # Next after 3
        mock_datetime.now.assert_called_with(BEIJING_TZ)

    @patch("release_plan.git")
    @patch("release_plan.datetime")
    def test_get_today_batch_number_resets_on_new_day(self, mock_datetime, mock_git):
        mock_datetime.now.return_value = datetime(2026, 11, 23, 0, 30, tzinfo=BEIJING_TZ)
        mock_git.return_value = "abc123\trefs/tags/release/2026.11.22.1\n" \
                                "def456\trefs/tags/release/2026.11.22.3\n" \
                                "ghi789\trefs/tags/release/2026.11.21.4"

        self.assertEqual(get_today_batch_number(), 1)
        mock_datetime.now.assert_called_with(BEIJING_TZ)


if __name__ == "__main__":
    unittest.main()
