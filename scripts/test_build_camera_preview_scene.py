#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image, ImageChops, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_camera_preview_scene as scene  # noqa: E402


class CameraPreviewSceneToolTest(unittest.TestCase):
    WIDTH = 960
    HEIGHT = 720
    CROP = [326, 268, 590, 466]
    ANCHOR = {"x": 450 / 960, "y": 370 / 720}

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest_path = self.root / "manifest.json"
        self.manifest = self.make_manifest()
        self.write_manifest()
        self.root_guide = self.root / "root-guide.png"
        self.make_alpha_image().save(self.root_guide)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def make_manifest(self) -> dict:
        step = self.WIDTH / (self.CROP[2] - self.CROP[0])
        levels = []
        for index in range(scene.LEVEL_COUNT):
            levels.append({
                "id": f"level-{index}",
                "parent": None if index == 0 else f"level-{index - 1}",
                "width": self.WIDTH,
                "height": self.HEIGHT,
                "worldWidthMetres": 1350.0 / step ** index,
                "anchor": self.ANCHOR.copy(),
                "parentCrop": None if index == 0 else self.CROP.copy(),
            })
        return {
            "schemaVersion": 1,
            "cumulativeWorldWidthRatio": (
                levels[0]["worldWidthMetres"] / levels[-1]["worldWidthMetres"]
            ),
            "levels": levels,
        }

    def write_manifest(self, manifest: dict | None = None) -> None:
        self.manifest_path.write_text(
            json.dumps(self.manifest if manifest is None else manifest), encoding="utf-8"
        )

    def make_alpha_image(self) -> Image.Image:
        image = Image.new("RGBA", (self.WIDTH, self.HEIGHT), (30, 55, 40, 255))
        draw = ImageDraw.Draw(image)
        for offset in range(0, self.WIDTH, 40):
            draw.line((offset, 0, offset, self.HEIGHT), fill=(80, offset % 255, 40, 255), width=3)
        for offset in range(0, self.HEIGHT, 40):
            draw.line((0, offset, self.WIDTH, offset), fill=(offset % 255, 90, 60, 255), width=3)
        alpha = Image.new("L", image.size)
        ImageDraw.Draw(alpha).polygon(
            ((0, 0), (79, 0), (799, self.HEIGHT), (0, self.HEIGHT)), fill=255
        )
        image.putalpha(alpha)
        return image

    def quiet(self):
        return contextlib.redirect_stdout(io.StringIO())

    def build_chain(self) -> tuple[list[Path], list[Path]]:
        accepted_dir = self.root / "accepted"
        guides_dir = self.root / "guides"
        guides = [self.root_guide]
        accepted = []
        for index, level in enumerate(self.manifest["levels"]):
            if index:
                guide = guides_dir / f"level-{index}.png"
                with self.quiet():
                    scene.prepare(
                        self.manifest_path, level["id"], accepted[-1], guide
                    )
                guides.append(guide)
            candidate = accepted_dir / f"level-{index}.webp"
            with self.quiet():
                metadata = scene.build(
                    self.manifest_path, level["id"], guides[-1], candidate
                )
            metadata["path"] = str(candidate.relative_to(self.root))
            level["accepted"] = metadata
            accepted.append(candidate)
            self.write_manifest()
        return guides, accepted

    @staticmethod
    def file_snapshot(root: Path) -> dict[str, str]:
        return {
            str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in root.rglob("*") if path.is_file()
        }

    def test_complete_exact_chain_check_and_diagnostics(self) -> None:
        guides, accepted = self.build_chain()
        levels = self.manifest["levels"]
        self.assertGreaterEqual(self.manifest["cumulativeWorldWidthRatio"], 625)
        for index in range(1, scene.LEVEL_COUNT):
            parent_level, child_level = levels[index - 1:index + 1]
            scale = parent_level["worldWidthMetres"] / child_level["worldWidthMetres"]
            self.assertLessEqual(scale, 3.7)
            with Image.open(accepted[index - 1]) as parent, Image.open(guides[index]) as guide:
                expected = parent.convert("RGBA").crop(tuple(self.CROP)).resize(
                    (self.WIDTH, self.HEIGHT), Image.Resampling.LANCZOS
                )
                expected_alpha = expected.getchannel("A").point(
                    lambda value: 255 if value >= 128 else 0
                )
                expected.putalpha(expected_alpha)
                self.assertIsNone(ImageChops.difference(expected, guide.convert("RGBA")).getbbox())
            crop_width = self.CROP[2] - self.CROP[0]
            crop_height = self.CROP[3] - self.CROP[1]
            x_error = abs(self.ANCHOR["x"] * self.WIDTH - (
                self.CROP[0] + self.ANCHOR["x"] * crop_width
            ))
            y_error = abs(self.ANCHOR["y"] * self.HEIGHT - (
                self.CROP[1] + self.ANCHOR["y"] * crop_height
            ))
            self.assertLessEqual(max(x_error, y_error), 1.0)

        before = self.file_snapshot(self.root)
        with self.quiet():
            scene.check(self.manifest_path)
        self.assertEqual(before, self.file_snapshot(self.root))

        diagnostics = self.root / "diagnostics"
        with self.quiet():
            scene.diagnose(self.manifest_path, diagnostics)
        sheets = sorted(diagnostics.glob("[0-9][0-9]-*.png"))
        self.assertEqual(5, len(sheets))
        for sheet_path in sheets:
            with Image.open(sheet_path) as sheet:
                self.assertEqual((self.WIDTH, 2376), sheet.size)
                parent_panel = sheet.crop((0, 120, self.WIDTH, 840))
                child_panel = sheet.crop((0, 888, self.WIDTH, 1608))
                self.assertIsNone(ImageChops.difference(parent_panel, child_panel).getbbox())
        with Image.open(diagnostics / "pyramid-overview.png") as overview:
            self.assertEqual((1440, 816), overview.size)

    def test_recursive_prepare_preserves_transparent_and_opaque_regions(self) -> None:
        parent = self.root / "alpha-level-0.png"
        image = self.make_alpha_image()
        image.save(parent)
        for index in range(1, scene.LEVEL_COUNT):
            child = self.root / f"alpha-level-{index}.png"
            with self.quiet():
                scene.prepare(self.manifest_path, f"level-{index}", parent, child)
            with self.subTest(level=index), Image.open(child) as prepared:
                prepared_alpha = prepared.getchannel("A")
                self.assertEqual((0, 255), prepared_alpha.getextrema())
                self.assertEqual((0, 0), prepared_alpha.crop((672, 0, 960, 144)).getextrema())
                self.assertEqual(
                    (255, 255), prepared_alpha.crop((0, 650, 100, 720)).getextrema()
                )
            parent = child

    def test_unrefined_build_is_byte_deterministic(self) -> None:
        first = self.root / "first.webp"
        second = self.root / "second.webp"
        with self.quiet():
            first_metadata = scene.build(
                self.manifest_path, "level-0", self.root_guide, first
            )
            second_metadata = scene.build(
                self.manifest_path, "level-0", self.root_guide, second
            )
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(first_metadata["sha256"], second_metadata["sha256"])
        self.assertEqual("12.3.0", first_metadata["pillowVersion"])
        with Image.open(first) as candidate:
            self.assertIn("A", candidate.getbands())

        opaque_guide = self.root / "opaque.png"
        Image.new("RGBA", (self.WIDTH, self.HEIGHT), "black").save(opaque_guide)
        first.write_bytes(b"unchanged")
        with self.assertRaisesRegex(scene.SceneToolError, "must contain an alpha channel"):
            scene.build(self.manifest_path, "level-0", opaque_guide, first)
        self.assertEqual(b"unchanged", first.read_bytes())

    def test_refinement_restores_guide_alpha_and_emits_comparison(self) -> None:
        refinement = Image.new("RGB", (self.WIDTH, self.HEIGHT), "#1d6f8a")
        ImageDraw.Draw(refinement).rectangle((0, 0, 50, 50), fill="#ff00ff")
        refinement_path = self.root / "refinement.png"
        refinement.save(refinement_path)
        output = self.root / "refined.webp"
        comparison = self.root / "comparison.png"
        with self.quiet(), contextlib.redirect_stderr(io.StringIO()):
            scene.build(
                self.manifest_path, "level-0", self.root_guide, output,
                refinement_path, comparison, (255, 0, 255)
            )
        with Image.open(self.root_guide) as guide, Image.open(output) as candidate:
            self.assertEqual(guide.size, candidate.size)
            self.assertEqual(guide.getchannel("A").tobytes(), candidate.getchannel("A").tobytes())
        self.assertTrue(comparison.is_file())
        sentinel = b"unchanged"
        output.write_bytes(sentinel)
        with self.assertRaisesRegex(scene.SceneToolError, "comparison path"):
            scene.build(
                self.manifest_path, "level-0", self.root_guide, output, refinement_path
            )
        self.assertEqual(sentinel, output.read_bytes())

    def assert_prepare_manifest_failure(self, manifest: dict, message: str) -> None:
        self.write_manifest(manifest)
        output = self.root / "sentinel.png"
        output.write_bytes(b"unchanged")
        with self.assertRaisesRegex(scene.SceneToolError, message):
            scene.prepare(self.manifest_path, "level-1", self.root_guide, output)
        self.assertEqual(b"unchanged", output.read_bytes())

    def test_invalid_manifest_geometry_fails_before_replacement(self) -> None:
        cases = []
        out_of_bounds = copy.deepcopy(self.manifest)
        out_of_bounds["levels"][1]["parentCrop"][2] = self.WIDTH + 1
        cases.append((out_of_bounds, "out of bounds"))
        excessive_step = copy.deepcopy(self.manifest)
        excessive_step["levels"][1]["worldWidthMetres"] = 1350.0 / 3.71
        cases.append((excessive_step, "must be >1 and <=3.7"))
        insufficient_ratio = copy.deepcopy(self.manifest)
        insufficient_ratio["cumulativeWorldWidthRatio"] = 624.9
        cases.append((insufficient_ratio, "both be at least 625"))
        invalid_anchor = copy.deepcopy(self.manifest)
        invalid_anchor["levels"][1]["anchor"]["x"] = 1.1
        cases.append((invalid_anchor, "within 0..1"))
        distorted = copy.deepcopy(self.manifest)
        distorted["levels"][1]["parentCrop"][3] += 3
        cases.append((distorted, "aspect ratio"))
        wrong_count = copy.deepcopy(self.manifest)
        wrong_count["levels"].pop()
        cases.append((wrong_count, "exactly 6"))
        for invalid_version in (True, 1.0):
            wrong_schema = copy.deepcopy(self.manifest)
            wrong_schema["schemaVersion"] = invalid_version
            cases.append((wrong_schema, "must be an integer"))
        for manifest, message in cases:
            with self.subTest(message=message):
                self.assert_prepare_manifest_failure(manifest, message)

    def test_malformed_manifest_dimensions_and_alpha_fail_safely(self) -> None:
        output = self.root / "sentinel.png"
        output.write_bytes(b"unchanged")
        self.manifest_path.write_text("{not json", encoding="utf-8")
        with self.assertRaisesRegex(scene.SceneToolError, "Cannot read manifest"):
            scene.prepare(self.manifest_path, "level-1", self.root_guide, output)
        self.assertEqual(b"unchanged", output.read_bytes())

        self.write_manifest()
        wrong_size = self.root / "wrong-size.png"
        Image.new("RGBA", (100, 100), (0, 0, 0, 0)).save(wrong_size)
        with self.assertRaisesRegex(scene.SceneToolError, "dimensions"):
            scene.prepare(self.manifest_path, "level-1", wrong_size, output)
        rgb = self.root / "rgb.png"
        Image.new("RGB", (self.WIDTH, self.HEIGHT), "#ff00ff").save(rgb)
        with self.assertRaisesRegex(scene.SceneToolError, "no alpha channel"):
            scene.prepare(self.manifest_path, "level-1", rgb, output)
        self.assertEqual(b"unchanged", output.read_bytes())
        with self.quiet():
            scene.prepare(self.manifest_path, "level-1", rgb, output, (255, 0, 255))
        with Image.open(output) as guide:
            self.assertIn("A", guide.getbands())
        candidate = self.root / "chroma-guide.webp"
        with self.quiet():
            scene.build(
                self.manifest_path, "level-0", rgb, candidate,
                chroma_key=(255, 0, 255)
            )
        with Image.open(candidate) as built:
            self.assertIn("A", built.getbands())

    def test_decompression_bomb_warning_and_error_propagate_safely(self) -> None:
        output = self.root / "sentinel.png"
        for limit, error_type in (
            (500_000, Image.DecompressionBombWarning),
            (300_000, Image.DecompressionBombError),
        ):
            output.write_bytes(b"unchanged")
            with self.subTest(error=error_type.__name__), mock.patch.object(
                Image, "MAX_IMAGE_PIXELS", limit
            ), self.assertRaises(error_type):
                scene.prepare(self.manifest_path, "level-1", self.root_guide, output)
            self.assertEqual(b"unchanged", output.read_bytes())

    def test_check_rejects_changed_metadata_and_non_webp(self) -> None:
        _, accepted = self.build_chain()
        original = copy.deepcopy(self.manifest)
        mutations = {
            "bytes": self.manifest["levels"][0]["accepted"]["bytes"] + 1,
            "sha256": "0" * 64,
            "pillowVersion": "0.0.0",
            "webpCodecVersion": "0.0.0",
            "width": self.WIDTH + 1,
            "height": self.HEIGHT + 1,
        }
        for key, value in mutations.items():
            with self.subTest(key=key):
                changed = copy.deepcopy(original)
                changed["levels"][0]["accepted"][key] = value
                self.write_manifest(changed)
                with self.assertRaisesRegex(scene.SceneToolError, key):
                    scene.check(self.manifest_path)
        changed = copy.deepcopy(original)
        png_path = self.root / "not-webp.png"
        self.make_alpha_image().save(png_path)
        data = png_path.read_bytes()
        changed["levels"][0]["accepted"].update({
            "path": png_path.name,
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
        self.write_manifest(changed)
        with self.assertRaisesRegex(scene.SceneToolError, "must be a WEBP"):
            scene.check(self.manifest_path)
        self.assertTrue(all(path.is_relative_to(self.root) for path in accepted))


if __name__ == "__main__":
    unittest.main()
