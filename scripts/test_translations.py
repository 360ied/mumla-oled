#!/usr/bin/env python3
# Copyright (C) 2026 Brian Zhu
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Pins that moving the chat strings to :app kept every translation."""

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

# The 29 locales that carried perm_denied in libraries/humla on master.
# Locales that never had it (values-lv, values-kab) stay untranslated by
# design: Android falls back to the default English string.
PERM_DENIED_LOCALES = {
    "values",
    "values-ar",
    "values-ca",
    "values-cs",
    "values-da",
    "values-de",
    "values-el",
    "values-es",
    "values-fi",
    "values-fr",
    "values-hu",
    "values-in",
    "values-it",
    "values-ja",
    "values-nb-rNO",
    "values-nl",
    "values-pl",
    "values-pt",
    "values-pt-rBR",
    "values-ro",
    "values-ru",
    "values-sv",
    "values-ta",
    "values-th",
    "values-tr",
    "values-uk",
    "values-vi",
    "values-zh-rCN",
    "values-zh-rTW",
}


class TestMovedTranslations(unittest.TestCase):
    def test_perm_denied_survives_the_resource_move(self):
        for locale in sorted(PERM_DENIED_LOCALES):
            path = RES / locale / "strings.xml"
            self.assertTrue(
                path.is_file(), f"{locale}/strings.xml is missing"
            )
            text = path.read_text(encoding="utf-8")
            self.assertIn(
                'name="perm_denied"',
                text,
                f"{locale}/strings.xml lost perm_denied",
            )


if __name__ == "__main__":
    unittest.main()
