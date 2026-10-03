#!/usr/bin/env python3
# Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT
"""Behaviour of advisory_scan.py. The tests marked "live" query api.osv.dev.

Run: python3 config/security/advisory_scan_test.py
"""

import contextlib
import datetime
import io
import json
import os
import pathlib
import socket
import tempfile
import unittest
from unittest import mock

import advisory_scan

CANARY = str(advisory_scan.CANARY)
PGJDBC_ITERATIONS = "GHSA-98qh-xjc8-98pq"
PGJDBC_DOWNGRADE = "GHSA-j92g-9f8w-j867"
CANARY_RESULT = {
    "results": [
        {
            "packages": [
                {
                    "package": {"name": "postgresql", "version": "42.7.7"},
                    "vulnerabilities": [{"id": PGJDBC_ITERATIONS}, {"id": PGJDBC_DOWNGRADE}],
                    "groups": [{"ids": [PGJDBC_ITERATIONS]}, {"ids": [PGJDBC_DOWNGRADE]}],
                }
            ]
        }
    ]
}
REVIEWED = '"Reviewed."'


def component(version="1.0", group="org.example", name="lib", **overrides):
    purl = f"pkg:maven/{group}/{name}@{version}?type=jar"
    return {"group": group, "name": name, "version": version, "purl": purl, **overrides}


def pgjdbc(version, **overrides):
    return component(version, "org.postgresql", "postgresql", **overrides)


def inventory(*components):
    return json.dumps({"components": list(components)})


def looked_up(*components):
    """What the scanner prints after looking these components up and finding no advisory."""
    packages = [{"package": {"name": c["name"], "version": c["version"]}} for c in components]
    return {"results": [{"packages": packages}]}


NO_ADVISORY = looked_up(component(), component("2.0+build"))


def entry(**fields):
    return "[[IgnoredVulns]]\n" + "".join(f"{key} = {value}\n" for key, value in fields.items())


class AdvisoryScanTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = pathlib.Path(directory.name)
        classified = "pkg:maven/org.example/lib@2.0%2Bbuild?classifier=tests&type=test-jar"
        self.inventory = self.write(
            "clean.cdx.json", inventory(component(), component("2.0+build", purl=classified))
        )

    def write(self, name, text):
        path = self.directory / name
        path.write_text(text, encoding="utf-8")
        return str(path)

    def scan(self, *inventories, **environment):
        output, diagnostics = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(diagnostics):
            status = advisory_scan.main(list(inventories), {**os.environ, **environment})
        return status, output.getvalue(), diagnostics.getvalue()

    def assert_untrusted(self, outcome, diagnostic):
        status, output, diagnostics = outcome
        self.assertEqual(advisory_scan.UNTRUSTED, status, diagnostics)
        self.assertIn("SCAN ERROR", diagnostics)
        self.assertIn(diagnostic, diagnostics)
        self.assertNotIn("CLEAN", output)

    def scanner(self, canary_reply, inventory_reply):
        """A stand-in scanner: each reply is the shell that answers that kind of scan."""
        path = self.directory / "scanner"
        path.write_text(
            '#!/bin/sh\ncase "$*" in\n'
            f"*canary.cdx.json) {canary_reply};;\n*) {inventory_reply};;\nesac\n"
        )
        path.chmod(0o755)
        return str(path)

    def replying(self, result, status):
        reply = self.write(f"reply-{len(list(self.directory.iterdir()))}.json", json.dumps(result))
        return f"cat {reply}; exit {status}"

    def detecting_scanner(self, inventory_reply):
        return self.scanner(self.replying(CANARY_RESULT, 1), inventory_reply)

    def exceptions(self, text):
        return self.write("exceptions.toml", text)

    def exception(self, advisory, until):
        return self.exceptions(entry(id=f'"{advisory}"', ignoreUntil=until, reason=REVIEWED))

    def test_live_vulnerable_fixture_fails_and_names_each_advisory_with_its_fix(self):
        status, output, _ = self.scan(CANARY)

        self.assertEqual(advisory_scan.VULNERABLE, status)
        self.assertIn(f"VULNERABLE: {CANARY}", output)
        self.assertIn("org.postgresql:postgresql 42.7.7: " + PGJDBC_ITERATIONS, output)
        self.assertIn("CVE-2026-42198, severity 7.5, fixed in 42.7.11, https://osv.dev/", output)
        self.assertIn("org.postgresql:postgresql 42.7.7: " + PGJDBC_DOWNGRADE, output)
        self.assertIn("CVE-2026-54291, severity 8.2, fixed in 42.7.12, https://osv.dev/", output)

    def test_live_unreachable_advisory_service_is_not_a_clean_scan(self):
        with socket.socket() as unused:
            unused.bind(("127.0.0.1", 0))
            closed_port = unused.getsockname()[1]

        outcome = self.scan(self.inventory, HTTPS_PROXY=f"http://127.0.0.1:{closed_port}")

        self.assert_untrusted(outcome, "scanner exited 127")

    def test_live_exception_silences_only_its_own_advisory(self):
        in_force = datetime.date.today() + datetime.timedelta(days=30)

        status, output, _ = self.scan(
            CANARY, ADVISORY_EXCEPTIONS=self.exception(PGJDBC_ITERATIONS, in_force)
        )

        self.assertEqual(advisory_scan.VULNERABLE, status)
        self.assertNotIn(PGJDBC_ITERATIONS, output)
        self.assertIn(PGJDBC_DOWNGRADE, output)

    def test_live_expired_exception_no_longer_silences_its_advisory(self):
        expired = datetime.date.today() - datetime.timedelta(days=1)

        status, output, _ = self.scan(
            CANARY, ADVISORY_EXCEPTIONS=self.exception(PGJDBC_ITERATIONS, expired)
        )

        self.assertEqual(advisory_scan.VULNERABLE, status)
        self.assertIn(PGJDBC_ITERATIONS, output)

    def test_inventory_without_advisories_is_clean(self):
        scanner = self.detecting_scanner(self.replying(NO_ADVISORY, 0))

        status, output, diagnostics = self.scan(self.inventory, OSV_SCANNER=scanner)

        self.assertEqual(advisory_scan.CLEAN, status, diagnostics)
        self.assertEqual(f"CLEAN: {self.inventory}: no known advisory\n", output)

    def test_one_vulnerable_inventory_fails_the_scan_of_several(self):
        other = self.write("other.cdx.json", inventory(pgjdbc("42.7.7")))
        scanner = self.scanner(
            self.replying(CANARY_RESULT, 1),
            f'case "$*" in *other.cdx.json) {self.replying(CANARY_RESULT, 1)};;'
            f" *) {self.replying(NO_ADVISORY, 0)};; esac",
        )

        status, output, _ = self.scan(self.inventory, other, OSV_SCANNER=scanner)

        self.assertEqual(advisory_scan.VULNERABLE, status)
        self.assertIn(f"CLEAN: {self.inventory}", output)
        self.assertIn(f"VULNERABLE: {other}: 2 advisories", output)
        self.assertIn("postgresql 42.7.7: " + PGJDBC_ITERATIONS + ", severity unscored", output)
        self.assertIn("fixed in no fixed version published", output)

    def test_scanner_that_misses_a_canary_advisory_is_not_a_clean_scan(self):
        scanner = self.scanner(
            self.replying(looked_up(pgjdbc("42.7.7")), 0), self.replying(NO_ADVISORY, 0)
        )

        outcome = self.scan(self.inventory, OSV_SCANNER=scanner)

        self.assert_untrusted(outcome, "advisory data is missing or stale")
        self.assert_untrusted(outcome, f"{PGJDBC_ITERATIONS}, {PGJDBC_DOWNGRADE}")

    def test_scanner_that_does_not_look_up_every_component_is_not_a_clean_scan(self):
        scanner = self.detecting_scanner(self.replying(looked_up(component()), 0))

        outcome = self.scan(self.inventory, OSV_SCANNER=scanner)

        self.assert_untrusted(
            outcome,
            f"inventory {self.inventory} lists 2 components and the scanner looked up 1;"
            " not looked up: lib 2.0+build",
        )

    def test_live_inventory_whose_every_component_is_looked_up_is_clean(self):
        status, output, diagnostics = self.scan(self.inventory)

        self.assertEqual(advisory_scan.CLEAN, status, diagnostics)
        self.assertEqual(f"CLEAN: {self.inventory}: no known advisory\n", output)

    def test_live_vulnerable_component_the_scanner_skips_is_not_a_clean_scan(self):
        coordinates = "pkg:maven/org.postgresql/postgresql@42.7.7"
        skipped = {
            "repeated qualifier": (pgjdbc("42.7.12"), coordinates + "?type=jar&type=jar"),
            "undecodable qualifier": (pgjdbc("42.7.12"), coordinates + "?type=%ZZ"),
            "namesake that is looked up": (
                component("42.7.7", name="postgresql"),
                coordinates + "?type=%ZZ",
            ),
        }
        for name, (scanned, purl) in skipped.items():
            with self.subTest(name):
                partial = self.write(
                    f"{name}.cdx.json", inventory(scanned, pgjdbc("42.7.7", purl=purl))
                )

                outcome = self.scan(partial)

                self.assert_untrusted(
                    outcome,
                    f"inventory {partial} lists 2 components and the scanner looked up 1;"
                    " not looked up: postgresql 42.7.7",
                )

    def test_scanner_failure_is_reported_with_its_last_diagnostic(self):
        scanner = self.detecting_scanner("echo first >&2; echo database unavailable >&2; exit 127")

        outcome = self.scan(self.inventory, OSV_SCANNER=scanner)

        self.assert_untrusted(
            outcome, f"scanner exited 127 on {self.inventory}: database unavailable"
        )

    def test_silent_scanner_failure_is_not_a_clean_scan(self):
        outcome = self.scan(self.inventory, OSV_SCANNER=self.detecting_scanner("exit 3"))

        self.assert_untrusted(outcome, "scanner exited 3")
        self.assert_untrusted(outcome, "no diagnostic")

    def test_unreadable_scanner_output_is_not_a_clean_scan(self):
        for reply in ("echo not json", "echo '{}'", "echo '{\"results\": null}'"):
            with self.subTest(reply):
                outcome = self.scan(self.inventory, OSV_SCANNER=self.detecting_scanner(reply))

                self.assert_untrusted(outcome, "is not a result")

    def test_exit_status_that_contradicts_the_findings_is_not_a_clean_scan(self):
        contradictions = {
            self.replying(NO_ADVISORY, 1): "exited 1 on",
            self.replying(CANARY_RESULT, 0): "but listed 2 advisories",
        }
        for reply, diagnostic in contradictions.items():
            with self.subTest(reply):
                outcome = self.scan(self.inventory, OSV_SCANNER=self.detecting_scanner(reply))

                self.assert_untrusted(outcome, diagnostic)

    def test_scanner_that_cannot_be_started_is_not_a_clean_scan(self):
        outcome = self.scan(self.inventory, OSV_SCANNER=str(self.directory / "absent"))

        self.assert_untrusted(outcome, "cannot run")

    def test_cached_scanner_with_wrong_checksum_is_removed_and_not_run(self):
        target = advisory_scan.SCANNER_PLATFORMS[("Linux", os.uname().machine)]
        binary = self.directory / "helios" / f"osv-scanner-{advisory_scan.SCANNER_VERSION}-{target}"
        binary.parent.mkdir()
        binary.write_text("#!/bin/sh\necho '{\"results\": []}'\n")

        outcome = self.scan(self.inventory, XDG_CACHE_HOME=str(self.directory))

        self.assert_untrusted(outcome, "did not match its pinned checksum")
        self.assertFalse(binary.exists())

    def test_live_scanner_release_that_cannot_be_downloaded_is_not_a_clean_scan(self):
        with mock.patch.object(advisory_scan, "SCANNER_VERSION", "0.0.0"):
            outcome = self.scan(self.inventory, XDG_CACHE_HOME=str(self.directory))

        self.assert_untrusted(outcome, "cannot download OSV-Scanner")

    def test_unusable_scanner_cache_is_not_reported_as_an_advisory(self):
        not_a_directory = self.write("cache", "")

        outcome = self.scan(self.inventory, XDG_CACHE_HOME=not_a_directory)

        self.assert_untrusted(outcome, f"Not a directory: '{not_a_directory}/helios'")

    def test_failure_nobody_anticipated_is_not_reported_as_an_advisory(self):
        scanner = self.detecting_scanner("printf '\\377'")

        outcome = self.scan(self.inventory, OSV_SCANNER=scanner)

        self.assert_untrusted(outcome, "the scan failed")
        self.assert_untrusted(outcome, "UnicodeDecodeError")

    def test_platform_without_a_pinned_scanner_is_not_a_clean_scan(self):
        with mock.patch.object(advisory_scan, "SCANNER_PLATFORMS", {}):
            outcome = self.scan(self.inventory, OSV_SCANNER="")

        self.assert_untrusted(outcome, "no pinned OSV-Scanner")

    def test_inventory_that_cannot_prove_what_was_scanned_is_rejected(self):
        credential = inventory({"url": "https://deploy:hunter2@repo.example.com/maven"})
        rejected = {
            str(self.directory / "absent.cdx.json"): "cannot read inventory",
            self.write("broken.cdx.json", "not json"): "cannot read inventory",
            self.write("list.cdx.json", "[]"): "cannot read inventory",
            self.write("empty.cdx.json", '{"components": []}'): "lists no component",
            self.write("table.cdx.json", '{"components": {"lib": 1}}'): "lists no component",
            self.write("credential.cdx.json", credential): "embedded credentials",
        }
        for rejected_inventory, diagnostic in rejected.items():
            with self.subTest(rejected_inventory):
                outcome = self.scan(rejected_inventory)

                self.assert_untrusted(outcome, diagnostic)
                self.assertNotIn("hunter2", outcome[2])

    def test_inventory_with_a_component_the_scanner_would_not_look_up_is_rejected(self):
        without_purl = component("42.7.7")
        del without_purl["purl"]
        unproven = {
            "without purl": without_purl,
            "versionless purl": component(purl="pkg:maven/org.example/lib"),
            "purl of another version": component(purl="pkg:maven/org.example/lib@1.1?type=jar"),
            "purl of another artifact": component(purl="pkg:maven/org.example/other@1.0"),
            "purl of another ecosystem": component(purl="pkg:npm/lib@1.0"),
            "nested": component(components=[component("2.0")]),
            "not an object": "pkg:maven/org.example/lib@1.0",
        }
        for name, unscanned in unproven.items():
            with self.subTest(name):
                partial = self.write(f"{name}.cdx.json", inventory(component(), unscanned))

                outcome = self.scan(partial)

                self.assert_untrusted(outcome, "the scanner would skip 1 of 2 components")
                self.assert_untrusted(outcome, f"First: {json.dumps(unscanned)[:300]}")

    def test_exception_file_that_is_not_reviewable_and_expiring_is_rejected(self):
        soon = datetime.date.today() + datetime.timedelta(days=30)
        too_late = datetime.date.today() + datetime.timedelta(days=91)
        unbounded = "ignoreUntil must be a date at most 90 days ahead"
        rejected = {
            "not toml [": "cannot read exception file",
            '[[PackageOverrides]]\nname = "lib"\n': "unsupported key PackageOverrides",
            entry(ignoreUntil=soon, reason=REVIEWED): "an entry without id: id and reason are",
            entry(id='"GHSA-1"', ignoreUntil=soon, reason='" "'): "GHSA-1: id and reason are",
            entry(id='"GHSA-2"', reason=REVIEWED): "GHSA-2: " + unbounded,
            entry(id='"GHSA-3"', ignoreUntil=too_late, reason=REVIEWED): "GHSA-3: " + unbounded,
            entry(id='"GHSA-4"', ignoreUntil=f"{soon}T00:00:00", reason=REVIEWED): "GHSA-4: "
            + unbounded,
            'IgnoredVulns = "GHSA-5"\n': "IgnoredVulns must be an array of tables",
            'IgnoredVulns = ["GHSA-6"]\n': "IgnoredVulns must be an array of tables",
            '[IgnoredVulns]\nid = "GHSA-7"\n': "IgnoredVulns must be an array of tables",
        }
        for text, diagnostic in rejected.items():
            with self.subTest(diagnostic):
                outcome = self.scan(self.inventory, ADVISORY_EXCEPTIONS=self.exceptions(text))

                self.assert_untrusted(outcome, diagnostic)

    def test_exception_cannot_carry_a_second_expiry_the_scanner_would_honour(self):
        expired = datetime.date.today() - datetime.timedelta(days=1)
        for spelling in ("IgnoreUntil", "ignoreuntil", "IGNOREUNTIL", "ID", "Reason", "note"):
            with self.subTest(spelling):
                text = entry(
                    id=f'"{PGJDBC_ITERATIONS}"',
                    ignoreUntil=expired,
                    reason=REVIEWED,
                    **{spelling: "2099-01-01"},
                )

                outcome = self.scan(CANARY, ADVISORY_EXCEPTIONS=self.exceptions(text))

                self.assert_untrusted(outcome, f"{PGJDBC_ITERATIONS}: unsupported key {spelling}")

    def test_exception_dated_what_the_scanner_reads_as_no_expiry_is_rejected(self):
        unlimited = self.exception(PGJDBC_ITERATIONS, "0001-01-01")

        outcome = self.scan(CANARY, ADVISORY_EXCEPTIONS=unlimited)

        self.assert_untrusted(
            outcome, f"{PGJDBC_ITERATIONS}: ignoreUntil 0001-01-01 never expires"
        )

    def test_missing_exception_file_is_not_a_clean_scan(self):
        absent = str(self.directory / "absent.toml")

        outcome = self.scan(self.inventory, ADVISORY_EXCEPTIONS=absent)

        self.assert_untrusted(outcome, "cannot read exception file")

    def test_checked_in_exception_file_is_accepted(self):
        scanner = self.detecting_scanner(self.replying(NO_ADVISORY, 0))

        with mock.patch.dict(os.environ):
            os.environ.pop("ADVISORY_EXCEPTIONS", None)
            status, _, diagnostics = self.scan(self.inventory, OSV_SCANNER=scanner)

        self.assertEqual(advisory_scan.CLEAN, status, diagnostics)

    def test_no_inventory_is_a_usage_error(self):
        status, output, diagnostics = self.scan()

        self.assertEqual(advisory_scan.UNTRUSTED, status)
        self.assertIn("Usage: advisory_scan.py", diagnostics)
        self.assertEqual("", output)


if __name__ == "__main__":
    unittest.main()
