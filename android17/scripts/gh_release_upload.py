#!/usr/bin/env python3
"""
Zero-Dependency GitHub Release Publisher for RMX1931 Android 17 Pixel OS OTA:
- Authenticates via stored Git credentials (`git credential fill` / `git.exe credential fill`).
- Creates or updates the GitHub Release matching `ota/rmx1931.json` (`tag`, `version`, `changelog`).
- Streams large release assets (< 2.0 GiB) directly from disk in 4 MiB chunks via `uploads.github.com`
  without buffering in RAM, with automatic per-asset retry and progress logging.
"""
import http.client
import json
import os
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def get_github_token() -> str:
    env_tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if env_tok:
        return env_tok.strip()

    for git_bin in ("git", "git.exe", "/mnt/c/Program Files/Git/cmd/git.exe"):
        try:
            res = subprocess.run(
                [git_bin, "credential", "fill"],
                input=b"protocol=https\nhost=github.com\n\n",
                capture_output=True,
                timeout=10,
            )
            if res.returncode == 0:
                for line in res.stdout.decode("utf-8", errors="replace").splitlines():
                    if line.startswith("password="):
                        tok = line.split("=", 1)[1].strip()
                        if tok:
                            return tok
        except Exception:
            continue
    raise RuntimeError("No GitHub token found via GITHUB_TOKEN or git credential manager.")


def gh_api(method: str, url: str, token: str, payload: dict | None = None) -> tuple[int, dict | list | str]:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "RMX1931-PixelOS-OTA-Publisher/1.0",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if data is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        return e.code, err_body


def resolve_canonical_repo(repo: str, token: str) -> str:
    code, data = gh_api("GET", f"https://api.github.com/repos/{repo}", token)
    if code == 200 and isinstance(data, dict) and data.get("full_name"):
        return str(data["full_name"])
    raise RuntimeError(f"Failed to resolve GitHub repo {repo} (HTTP {code}): {data}")


def ensure_release(
    canonical_repo: str,
    tag: str,
    title: str,
    notes: str,
    token: str,
    prerelease: bool = False,
) -> dict:
    make_latest = "false" if prerelease else "true"
    code, existing = gh_api("GET", f"https://api.github.com/repos/{canonical_repo}/releases/tags/{tag}", token)
    if code == 200 and isinstance(existing, dict):
        rel_id = existing["id"]
        u_code, updated = gh_api(
            "PATCH",
            f"https://api.github.com/repos/{canonical_repo}/releases/{rel_id}",
            token,
            {
                "name": title,
                "body": notes,
                "draft": False,
                "prerelease": prerelease,
                "make_latest": make_latest,
            },
        )
        if u_code == 200 and isinstance(updated, dict):
            return updated
        return existing

    c_code, created = gh_api(
        "POST",
        f"https://api.github.com/repos/{canonical_repo}/releases",
        token,
        {
            "tag_name": tag,
            "target_commitish": "main",
            "name": title,
            "body": notes,
            "draft": False,
            "prerelease": prerelease,
            "make_latest": make_latest,
        },
    )
    if c_code in (200, 201) and isinstance(created, dict):
        return created
    raise RuntimeError(f"Failed to create release {tag} on {canonical_repo} (HTTP {c_code}): {created}")


def delete_existing_asset_if_present(canonical_repo: str, release: dict, asset_name: str, token: str) -> None:
    code, rel_fresh = gh_api("GET", f"https://api.github.com/repos/{canonical_repo}/releases/{release['id']}", token)
    assets = rel_fresh.get("assets", []) if (code == 200 and isinstance(rel_fresh, dict)) else release.get("assets", [])
    for a in assets:
        if a.get("name") == asset_name:
            print(f"[gh-upload] Deleting existing asset {asset_name} (id={a['id']}) for clobber...", flush=True)
            gh_api("DELETE", f"https://api.github.com/repos/{canonical_repo}/releases/assets/{a['id']}", token)


def stream_upload_asset(upload_url_tmpl: str, file_path: Path, token: str) -> dict:
    size = file_path.stat().st_size
    if size >= 2147483648:
        raise RuntimeError(f"{file_path.name} ({size} B) exceeds GitHub Releases 2.0 GiB asset limit!")

    base_url = upload_url_tmpl.split("{", 1)[0]
    parsed = urllib.parse.urlparse(f"{base_url}?name={urllib.parse.quote(file_path.name)}")
    ctype = "application/zip" if file_path.suffix.lower() == ".zip" else "application/octet-stream"

    for attempt in range(1, 4):
        print(
            f"[gh-upload] Streaming {file_path.name} ({size / (1024 * 1024):.1f} MiB) -> {parsed.netloc} (attempt {attempt}/3)...",
            flush=True,
        )
        ctx = ssl.create_default_context()
        conn = http.client.HTTPSConnection(parsed.netloc, timeout=300, context=ctx)
        try:
            path_q = f"{parsed.path}?{parsed.query}"
            conn.putrequest("POST", path_q)
            conn.putheader("Authorization", f"Bearer {token}")
            conn.putheader("Accept", "application/vnd.github+json")
            conn.putheader("User-Agent", "RMX1931-PixelOS-OTA-Publisher/1.0")
            conn.putheader("Content-Type", ctype)
            conn.putheader("Content-Length", str(size))
            conn.endheaders()

            sent = 0
            next_pct = 10
            t0 = time.time()
            chunk_sz = 4 * 1024 * 1024
            with file_path.open("rb") as f:
                while True:
                    buf = f.read(chunk_sz)
                    if not buf:
                        break
                    conn.send(buf)
                    sent += len(buf)
                    pct = int((sent * 100) // max(size, 1))
                    if pct >= next_pct or sent == size:
                        elapsed = max(time.time() - t0, 0.1)
                        mbps = (sent / (1024 * 1024)) / elapsed
                        print(
                            f"  [{pct:3d}%] {sent / (1024 * 1024):7.1f} / {size / (1024 * 1024):.1f} MiB ({mbps:.1f} MiB/s)",
                            flush=True,
                        )
                        while next_pct <= pct:
                            next_pct += 10

            resp = conn.getresponse()
            raw = resp.read().decode("utf-8", errors="replace")
            if resp.status in (200, 201):
                res_json = json.loads(raw)
                print(f"[gh-upload] OK: {res_json.get('browser_download_url')}", flush=True)
                return res_json
            raise RuntimeError(f"HTTP {resp.status}: {raw}")
        except Exception as e:
            print(f"[gh-upload] Attempt {attempt} failed: {e}", flush=True)
            if attempt == 3:
                raise
            time.sleep(4 * attempt)
        finally:
            conn.close()
    raise RuntimeError("Unreachable")


def main() -> int:
    raw_args = sys.argv[1:]
    force_prerelease = False
    pos_args: list[str] = []
    for a in raw_args:
        if a == "--prerelease":
            force_prerelease = True
        else:
            pos_args.append(a)

    if len(pos_args) < 2:
        print("Usage: gh_release_upload.py [--prerelease] <ota/manifest.json> <asset1> [asset2 ...]", file=sys.stderr)
        return 2

    manifest_path = Path(pos_args[0])
    assets = [Path(p) for p in pos_args[1:]]
    for a in assets:
        if not a.is_file():
            raise FileNotFoundError(f"Missing release asset: {a}")

    entry = json.loads(manifest_path.read_text(encoding="utf-8"))["response"][0]
    dl_url = entry["url"]
    requested_repo = dl_url.split("github.com/")[1].split("/releases/")[0]
    tag = entry["tag"]
    ver = entry["version"]
    sha = entry["sha256"]
    changelog = entry["changelog"]
    channel = str(entry.get("channel", "stable")).strip().lower()
    is_prerelease = force_prerelease or (channel == "ios_beta") or tag.startswith("ios-beta-")

    token = get_github_token()
    canonical_repo = resolve_canonical_repo(requested_repo, token)
    print(
        f"[gh-upload] Repo: {canonical_repo} | Tag: {tag} | Channel: {channel} | Prerelease: {is_prerelease}",
        flush=True,
    )

    if is_prerelease:
        title = f"Android 17 — iOS Style UI Beta ({ver}) — Realme X2 Pro (RMX1931)"
    else:
        title = f"Android 17 Pixel OS Stable ({ver}) — Realme X2 Pro (RMX1931)"

    sums_block = ""
    for a in assets:
        if a.name.startswith("SHA256SUMS") and a.name.endswith(".txt"):
            zip_sums = [ln for ln in a.read_text(encoding="utf-8").splitlines() if ".zip" in ln or ".apk" in ln]
            if zip_sums:
                sums_block = "\n\n### SHA-256 Checksums\n```text\n" + "\n".join(zip_sums) + "\n```"
            break
    if not sums_block:
        sums_block = f"\n\nSHA-256 (`{entry['filename']}`): `{sha}`"

    if is_prerelease:
        install_guide = (
            f"\n\n### 🧪 Opt-In iOS Style UI Beta Channel (`ota/ios-beta/rmx1931.json`)\n"
            f"- **Automatic Wireless Opt-In**: On Pure Pixel OS `v2.1.0+`, open **Settings → System → System update**, toggle **iOS Style UI Beta** **ON**, and tap **Download update**.\n"
            f"- **Return to Pure Pixel OS Stable Anytime**: Toggle **iOS Style UI Beta** **OFF** in **Settings → System → System update** (or `Settings → General → Software Update`) to switch back to Stable Pixel OS without wiping `/data`.\n"
            f"- **Clean-Flash Users**: Always install Stable Clean-Flash `[1/2]` (`VendorBoot.zip`) first if coming from stock Realme UI 2.0.\n\n"
            f"📖 **Full Guide**: https://github.com/{canonical_repo}#how-to-install-step-by-step"
        )
    else:
        install_guide = (
            f"\n\n### 📦 Which Package Should I Download?\n"
            f"| Package | Use Case |\n"
            f"| :--- | :--- |\n"
            f"| **`RMX1931-Android17-PixelOS-VendorBoot.zip`** + **`RMX1931-Android17-PixelOS-System.zip`** | **First-time Clean Flash** in TWRP (`VendorBoot.zip` `[1/2]` first, `System.zip` `[2/2]` second, then Format Data `yes`). Also serves as the full atomic Stable system image when switching back from `iOS Style UI Beta`. |\n"
            f"| **`RMX1931-Android17-PixelOS-OTA.zip`** | **Wireless Stable Reset for existing `v1.x` / `v2.0` installs** (downloaded automatically via **Settings → System → System update**, restoring 100% Pure Google Pixel OS while preserving all user data). |\n\n"
            f"📖 **Full Step-by-Step Installation & Dual-Channel OTA Guide**: https://github.com/{canonical_repo}#how-to-install-step-by-step"
        )
    notes = f"{changelog}{install_guide}{sums_block}"
    release = ensure_release(canonical_repo, tag, title, notes, token, prerelease=is_prerelease)

    for asset_path in assets:
        delete_existing_asset_if_present(canonical_repo, release, asset_path.name, token)
        stream_upload_asset(release["upload_url"], asset_path, token)

    print(f"[gh-upload] Release {tag} published cleanly on {canonical_repo}!", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
