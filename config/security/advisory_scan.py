#!/usr/bin/env python3
# Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT
"""Scans CycloneDX inventories for known advisories with OSV-Scanner.

Usage: advisory_scan.py <inventory.cdx.json>...

Exit status:
  0  every inventory was scanned and no advisory applies
  1  at least one advisory applies; each is printed with its package, version, severity,
     fixed versions and URL
  2  the result cannot be trusted: the scanner, the network, the advisory data, an inventory or
     the exception file is at fault. Never a clean scan.

The policy this enforces is documented in CLAUDE.md, "Supply-chain gate".
"""

import datetime
import hashlib
import json
import os
import pathlib
import platform
import re
import subprocess
import sys
import tomllib
import urllib.request

CLEAN, VULNERABLE, UNTRUSTED = 0, 1, 2

SCANNER_VERSION = "2.6.0"
SCANNER_SHA256 = {
    "linux_amd64": "ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108",
    "linux_arm64": "2c71403eb443d05891c4f268c3ad771cf4f16e5443463fd7851ef8f454d3c7e4",
}
SCANNER_PLATFORMS = {("Linux", "x86_64"): "linux_amd64", ("Linux", "aarch64"): "linux_arm64"}
SCANNER_FOUND_NOTHING, SCANNER_FOUND_ADVISORIES = 0, 1
DOWNLOAD_TIMEOUT_SECONDS = 120

CONFIG_DIR = pathlib.Path(__file__).resolve().parent
CANARY = CONFIG_DIR / "canary.cdx.json"
CANARY_ADVISORIES = {"GHSA-98qh-xjc8-98pq", "GHSA-j92g-9f8w-j867"}
MAX_EXCEPTION_DAYS = 90
CREDENTIAL_IN_URL = re.compile(r"[a-z][a-z0-9+.-]*://[^/\s\"@]*:[^/\s\"@]*@")


class ScanError(Exception):
    """The scan produced no result that can be trusted."""


def main(arguments, environment):
    if not arguments:
        print(__doc__, file=sys.stderr)
        return UNTRUSTED
    try:
        exceptions = validated_exceptions(
            pathlib.Path(
                environment.get("ADVISORY_EXCEPTIONS", CONFIG_DIR / "advisory-exceptions.toml")
            )
        )
        inventories = [validated_inventory(pathlib.Path(argument)) for argument in arguments]
        scanner = resolved_scanner(environment)
        prove_scanner_detects_canary(scanner, environment)
        findings = {
            inventory: scan(scanner, inventory, exceptions, environment)
            for inventory in inventories
        }
    except ScanError as error:
        print(f"SCAN ERROR: {error}", file=sys.stderr)
        return UNTRUSTED
    for inventory, found in findings.items():
        report(inventory, found)
    return VULNERABLE if any(findings.values()) else CLEAN


def validated_exceptions(path):
    try:
        with open(path, "rb") as file:
            config = tomllib.load(file)
    except (OSError, tomllib.TOMLDecodeError) as error:
        raise ScanError(f"cannot read exception file {path}: {error}") from error
    problems = [f"unsupported key {key}" for key in config if key != "IgnoredVulns"]
    latest = datetime.date.today() + datetime.timedelta(days=MAX_EXCEPTION_DAYS)
    for entry in config.get("IgnoredVulns", []):
        advisory = entry.get("id") or "an entry without id"
        if not entry.get("id") or not str(entry.get("reason", "")).strip():
            problems.append(f"{advisory}: id and reason are required")
        until = entry.get("ignoreUntil")
        if type(until) is not datetime.date or until > latest:
            problems.append(
                f"{advisory}: ignoreUntil must be a date at most {MAX_EXCEPTION_DAYS} days ahead"
            )
    if problems:
        raise ScanError(f"exception file {path} is rejected: " + "; ".join(problems))
    return path


def validated_inventory(path):
    try:
        text = path.read_text(encoding="utf-8")
        components = json.loads(text).get("components", [])
    except (OSError, ValueError, AttributeError) as error:
        raise ScanError(f"cannot read inventory {path}: {error}") from error
    if not components:
        raise ScanError(f"inventory {path} lists no component")
    if CREDENTIAL_IN_URL.search(text):
        raise ScanError(f"inventory {path} carries a URL with embedded credentials")
    return path


def resolved_scanner(environment):
    if environment.get("OSV_SCANNER"):
        return pathlib.Path(environment["OSV_SCANNER"])
    target = SCANNER_PLATFORMS.get((platform.system(), platform.machine()))
    if target is None:
        raise ScanError(
            f"no pinned OSV-Scanner for {platform.system()} {platform.machine()};"
            f" point OSV_SCANNER at a {SCANNER_VERSION} binary"
        )
    cache = pathlib.Path(environment.get("XDG_CACHE_HOME") or pathlib.Path.home() / ".cache")
    binary = cache / "helios" / f"osv-scanner-{SCANNER_VERSION}-{target}"
    if not binary.exists():
        download_scanner(target, binary)
    if hashlib.sha256(binary.read_bytes()).hexdigest() != SCANNER_SHA256[target]:
        binary.unlink()
        raise ScanError(f"OSV-Scanner at {binary} did not match its pinned checksum; removed")
    binary.chmod(0o755)
    return binary


def download_scanner(target, binary):
    url = (
        "https://github.com/google/osv-scanner/releases/download/"
        f"v{SCANNER_VERSION}/osv-scanner_{target}"
    )
    binary.parent.mkdir(parents=True, exist_ok=True)
    try:
        with urllib.request.urlopen(url, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
            binary.write_bytes(response.read())
    except OSError as error:
        raise ScanError(f"cannot download OSV-Scanner from {url}: {error}") from error


def prove_scanner_detects_canary(scanner, environment):
    reported = {
        advisory
        for found in scan(scanner, CANARY, os.devnull, environment)
        for advisory in found["ids"]
    }
    missing = CANARY_ADVISORIES - reported
    if missing:
        raise ScanError(
            "advisory data is missing or stale: the scanner did not report "
            + ", ".join(sorted(missing))
            + f" for the known-vulnerable canary {CANARY.name}"
        )


def scan(scanner, inventory, exceptions, environment):
    command = [scanner, "scan", "source", "--format", "json"]
    command += ["--config", exceptions, "-L", inventory]
    try:
        completed = subprocess.run(
            command, env=dict(environment), capture_output=True, text=True, check=False
        )
    except OSError as error:
        raise ScanError(f"cannot run {scanner}: {error}") from error
    if completed.returncode not in (SCANNER_FOUND_NOTHING, SCANNER_FOUND_ADVISORIES):
        raise ScanError(
            f"scanner exited {completed.returncode} on {inventory}: "
            + (completed.stderr.strip().splitlines() or ["no diagnostic"])[-1]
        )
    try:
        findings = [
            finding(package, group)
            for result in json.loads(completed.stdout)["results"]
            for package in result["packages"]
            for group in package["groups"]
        ]
    except (ValueError, KeyError, TypeError) as error:
        raise ScanError(f"scanner output for {inventory} is not a result: {error!r}") from error
    if bool(findings) != (completed.returncode == SCANNER_FOUND_ADVISORIES):
        raise ScanError(
            f"scanner exited {completed.returncode} on {inventory}"
            f" but listed {len(findings)} advisories"
        )
    return findings


def finding(package, group):
    artifact = package["package"]["name"]
    affected = [
        affected
        for vulnerability in package["vulnerabilities"]
        if vulnerability["id"] in group["ids"]
        for affected in vulnerability.get("affected", [])
        if affected["package"]["name"].endswith(":" + artifact)
    ]
    fixed = sorted(
        {
            event["fixed"]
            for entry in affected
            for affected_range in entry.get("ranges", [])
            for event in affected_range["events"]
            if "fixed" in event
        }
    )
    coordinates = affected[0]["package"]["name"] if affected else artifact
    return {
        "ids": group["ids"],
        "aliases": sorted(set(group.get("aliases", [])) - set(group["ids"])),
        "package": f"{coordinates} {package['package']['version']}",
        "severity": group.get("max_severity") or "unscored",
        "fixed": ", ".join(fixed) or "no fixed version published",
    }


def report(inventory, findings):
    if not findings:
        print(f"CLEAN: {inventory}: no known advisory")
        return
    print(f"VULNERABLE: {inventory}: {len(findings)} advisories")
    for found in findings:
        print(
            f"  {found['package']}: {' '.join(found['ids'] + found['aliases'])},"
            f" severity {found['severity']}, fixed in {found['fixed']},"
            f" https://osv.dev/{found['ids'][0]}"
        )


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
