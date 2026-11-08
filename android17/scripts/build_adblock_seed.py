#!/usr/bin/env python3
"""
Builds the pre-seeded offline System AdBlocker bundle (adblock_seed.gz) for RMX1931 Android 17 Pixel OS.
- Fetches HaGeZi Multi Pro (onlydomains) + StevenBlack unified hosts (cached in DL_DIR for reproducible offline builds)
- Merges curated high-priority mobile ad-SDK, Chrome/WebView ad-exchange, and Meta Audience Network domains
- Enforces a strict functional allowlist so Google Account/Play/GMS, YouTube watch history, Instagram/Facebook core
  messaging/feeds, WhatsApp, GitHub OTA, and Indian UPI/banking apps never break
- Compresses parent-deduplicated suffix rules so samurai_adblockd loads 150k-250k+ domains in <6 MiB RAM
"""
import gzip
import os
import re
import sys
import urllib.request

BLOCKLIST_URLS = [
    ("hagezi_pro.txt", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt"),
    ("stevenblack_hosts.txt", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
]

# Functional domains that MUST NEVER be sinkholed (prevents breaking logins, DMs, payments, watch history, or OTAs)
ALLOWLIST_EXACT_AND_SUFFIX = {
    # Google Account, Play Store, GMS, Push, Fonts, Gboard, Captive Portal check
    "google.com",
    "www.google.com",
    "accounts.google.com",
    "clients1.google.com",
    "clients2.google.com",
    "clients3.google.com",
    "clients4.google.com",
    "clients5.google.com",
    "clients6.google.com",
    "connectivitycheck.gstatic.com",
    "gstatic.com",
    "www.gstatic.com",
    "googleapis.com",
    "play.googleapis.com",
    "android.clients.google.com",
    "mtalk.google.com",
    "alt1-mtalk.google.com",
    "alt2-mtalk.google.com",
    "dl.google.com",
    "dl-ssl.google.com",
    "gvt1.com",
    "gvt2.com",
    "gvt3.com",
    "googleusercontent.com",
    "lh3.googleusercontent.com",
    "photos.googleapis.com",
    "photosdata-pa.googleapis.com",
    "people-pa.googleapis.com",
    "oauthaccountmanager.googleapis.com",
    "firebaseremoteconfig.googleapis.com",
    "firebaseinstallations.googleapis.com",
    "fcm.googleapis.com",
    # YouTube functional playback & watch history (video ads are stripped in bytecode by Morphe!)
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtu.be",
    "googlevideo.com",
    "ytimg.com",
    "i.ytimg.com",
    "s.ytimg.com",
    "yt3.ggpht.com",
    "ggpht.com",
    "s.youtube.com",
    "youtubei.googleapis.com",
    # Meta functional endpoints (in-feed/story/reel/inbox ads are stripped in bytecode by SamuraiModHelper!)
    "instagram.com",
    "www.instagram.com",
    "i.instagram.com",
    "graph.instagram.com",
    "z-p42-chat-e2ee-ig.facebook.com",
    "cdninstagram.com",
    "scontent.cdninstagram.com",
    "facebook.com",
    "www.facebook.com",
    "m.facebook.com",
    "graph.facebook.com",
    "b-graph.facebook.com",
    "b-api.facebook.com",
    "edge-mqtt.facebook.com",
    "mqtt-mini.facebook.com",
    "rupload.facebook.com",
    "fbcdn.net",
    "fbsbx.com",
    "messenger.com",
    "whatsapp.com",
    "whatsapp.net",
    "g.whatsapp.net",
    "v.whatsapp.net",
    "mmg.whatsapp.net",
    # GitHub Releases & Samurai OTA channel
    "github.com",
    "api.github.com",
    "raw.githubusercontent.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
    # Indian UPI, Banking & Payment Gateways
    "npci.org.in",
    "upi.org.in",
    "razorpay.com",
    "api.razorpay.com",
    "checkout.razorpay.com",
    "billdesk.com",
    "payu.in",
    "cashfree.com",
    "juspay.in",
    "phonepe.com",
    "paytm.com",
    "sbi.co.in",
    "hdfcbank.com",
    "icicibank.com",
    "axisbank.com",
    "kotak.com",
    # Public DNS bootstrap names
    "one.one.one.one",
    "dns.google",
}

# High-impact guaranteed mobile ad-SDK, Chrome/WebView ad-exchange & Meta Audience Network domains
CORE_GUARANTEED_BLOCKS = [
    # Google Ads / DoubleClick / AdMob / Chrome Ad-Tech
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "ads.google.com",
    "pagead2.googlesyndication.com",
    "tpc.googlesyndication.com",
    "googleads.g.doubleclick.net",
    "pubads.g.doubleclick.net",
    "securepubads.g.doubleclick.net",
    "cm.g.doubleclick.net",
    "adclick.g.doubleclick.net",
    "stats.g.doubleclick.net",
    "admob.com",
    "ads.youtube.com",
    "2mdn.net",
    "admeld.com",
    "imasdk.googleapis.com",
    "fundingchoicesmessages.google.com",
    "google-analytics.com",
    "ssl.google-analytics.com",
    "googletagmanager.com",
    "googletagservices.com",
    "app-measurement.com",
    # Meta Audience Network & Off-Facebook Pixel Trackers (safe to sinkhole; does not affect IG/FB feeds/DMs)
    "an.facebook.com",
    "pixel.facebook.com",
    "ad.doubleclick.net",
    "ads.facebook.com",
    "tr.facebook.com",
    "graph.accountkit.com",
    # Major In-App Mobile Ad SDKs (Unity, AppLovin, IronSource, Vungle, InMobi, Mintegral, Pangle, Chartboost)
    "unityads.unity3d.com",
    "adserver.unityads.unity3d.com",
    "auction.unityads.unity3d.com",
    "config.unityads.unity3d.com",
    "applovin.com",
    "applvn.com",
    "a.applovin.com",
    "d.applovin.com",
    "ms.applovin.com",
    "rt.applovin.com",
    "ms.applvn.com",
    "vungle.com",
    "ads.api.vungle.com",
    "config.ads.vungle.com",
    "ironsrc.mobi",
    "ironsrc.com",
    "supersonicads.com",
    "init.supersonicads.com",
    "inmobi.com",
    "inmobicdn.net",
    "config.inmobi.com",
    "chartboost.com",
    "live.chartboost.com",
    "adcolony.com",
    "ads30.adcolony.com",
    "fyber.com",
    "inner-active.mobi",
    "startappservice.com",
    "startad.com",
    "mintegral.com",
    "rayjump.com",
    "net.rayjump.com",
    "pangle.io",
    "pangleglobal.com",
    "adx.pangleglobal.com",
    "tapjoy.com",
    "tapjoyads.com",
    "smaato.net",
    "admarvel.com",
    "mopub.com",
    "ogury.io",
    "liftoff.io",
    "moloco.com",
    "bidmachine.io",
    # Web / Chrome Banner, Native, Popup & Video Ad Exchanges
    "taboola.com",
    "trc.taboola.com",
    "cdn.taboola.com",
    "outbrain.com",
    "widgets.outbrain.com",
    "criteo.com",
    "criteo.net",
    "pubmatic.com",
    "ads.pubmatic.com",
    "rubiconproject.com",
    "fastlane.rubiconproject.com",
    "openx.net",
    "adnxs.com",
    "ib.adnxs.com",
    "casalemedia.com",
    "indexww.com",
    "smartadserver.com",
    "amazon-adsystem.com",
    "aax.amazon-adsystem.com",
    "c.amazon-adsystem.com",
    "moatads.com",
    "adsafeprotected.com",
    "doubleverify.com",
    "scorecardresearch.com",
    "sb.scorecardresearch.com",
    "quantserve.com",
    "popads.net",
    "popcash.net",
    "propellerads.com",
    "adsterra.com",
    "exoclick.com",
    "syndication.exoclick.com",
    "juicyads.com",
    "trafficjunky.net",
    "mgid.com",
    "revcontent.com",
    "teads.tv",
    "sharethrough.com",
    "3lift.com",
    "triplelift.com",
    "sovrn.com",
    "lijit.com",
    "gumgum.com",
    "kargo.com",
    "yieldmo.com",
    "zedo.com",
    "adform.net",
    "flashtalking.com",
    "serving-sys.com",
    "Innovid.com",
    "spotxchange.com",
    # Mobile Attribution / Telemetry Trackers
    "adjust.com",
    "app.adjust.com",
    "appsflyer.com",
    "launches.appsflyer.com",
    "inapps.appsflyer.com",
    "kochava.com",
    "control.kochava.com",
    "singular.net",
    "sdk-api-v1.singular.net",
    "bugsnag.com",
    "Mixpanel.com",
    "api.mixpanel.com",
    "amplitude.com",
    "api2.amplitude.com",
    "segment.io",
    "api.segment.io",
    "clevertap-prod.com",
    "wzrk.com",
    "moengage.com",
    "webengage.com",
    "onesignal.com",
    # Xiaomi / Oppo / Realme / OEM telemetry (in case any vendor SDK probes)
    "heytapmobile.com",
    "oppomobile.com",
    "nearme.com.cn",
    "coloros.com",
]

DOMAIN_RE = re.compile(r"^[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?)+$")


def normalize_domain(raw: str) -> str:
    s = raw.strip().lower()
    if not s or s.startswith("#") or s.startswith("!"):
        return ""
    if "#" in s:
        s = s.split("#", 1)[0].strip()
    parts = s.split()
    if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1", "::", "::1"):
        s = parts[1]
    elif len(parts) == 1:
        s = parts[0]
    else:
        return ""
    if s.startswith("*."):
        s = s[2:]
    elif s.startswith("."):
        s = s[1:]
    s = s.rstrip(".")
    if len(s) > 253 or "." not in s:
        return ""
    if s in ("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback"):
        return ""
    if not DOMAIN_RE.match(s):
        return ""
    return s


def is_allowed(dom: str, allow_set: set[str]) -> bool:
    if dom in allow_set:
        return True
    # Note: do NOT allowlist subdomains of google.com like adservice.google.com!
    # Only exact match on allow_set or safe CDN suffixes below:
    for safe_suffix in (".googlevideo.com", ".gvt1.com", ".cdninstagram.com", ".fbcdn.net", ".whatsapp.net"):
        if dom.endswith(safe_suffix):
            return True
    return False


def main() -> int:
    if len(sys.argv) < 3:
        print("Usage: build_adblock_seed.py <cache_dir> <out_seed_gz>")
        return 1

    cache_dir, out_gz = sys.argv[1], sys.argv[2]
    os.makedirs(cache_dir, exist_ok=True)
    os.makedirs(os.path.dirname(out_gz), exist_ok=True)

    allow_set = {d.lower().strip(".") for d in ALLOWLIST_EXACT_AND_SUFFIX}
    raw_domains: set[str] = set()
    core_hosts_ordered: list[str] = []
    seen_core: set[str] = set()

    for d in CORE_GUARANTEED_BLOCKS:
        nd = normalize_domain(d)
        if nd and not is_allowed(nd, allow_set):
            raw_domains.add(nd)
            if nd not in seen_core:
                seen_core.add(nd)
                core_hosts_ordered.append(nd)

    for cache_fn, url in BLOCKLIST_URLS:
        local_p = os.path.join(cache_dir, cache_fn)
        if not os.path.isfile(local_p) or os.path.getsize(local_p) < 10000:
            print(f"[adblock-seed] Downloading {url} -> {local_p}...")
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 17; RMX1931)"})
            with urllib.request.urlopen(req, timeout=45) as resp:
                data = resp.read()
            with open(local_p, "wb") as f:
                f.write(data)

        with open(local_p, "r", encoding="utf-8", errors="ignore") as f:
            is_steven = "stevenblack" in cache_fn
            for line in f:
                nd = normalize_domain(line)
                if not nd or is_allowed(nd, allow_set):
                    continue
                raw_domains.add(nd)
                # Populate up to 16,000 high-value hosts entries from StevenBlack for fast <0.4ms /etc/hosts IPv6 fallback
                if is_steven and len(core_hosts_ordered) < 16000 and nd not in seen_core:
                    seen_core.add(nd)
                    core_hosts_ordered.append(nd)

    # Deduplicate subdomains already covered by a blocked parent domain (unless an intermediate parent is in allow_set!)
    # Wait: if google.com is NOT in raw_domains, adservice.google.com stays in raw_domains.
    # If doubleclick.net IS in raw_domains, googleads.g.doubleclick.net is covered by suffix lookup in samurai_adblockd!
    # Check that no allowlisted subdomain gets shadowed by a blocked parent:
    allow_parents_needed: set[str] = set()
    for a_dom in allow_set:
        parts = a_dom.split(".")
        for i in range(1, len(parts)):
            allow_parents_needed.add(".".join(parts[i:]))

    pruned_domains: list[str] = []
    for dom in sorted(raw_domains):
        parts = dom.split(".")
        covered = False
        for i in range(1, len(parts) - 1):
            parent = ".".join(parts[i:])
            if parent in raw_domains and parent not in allow_parents_needed:
                covered = True
                break
        if not covered:
            pruned_domains.append(dom)

    # Write structured seed bundle:
    #   @ALLOW
    #   <allowlisted domains>
    #   @HOSTS
    #   <16,000 core hosts domains for /system/etc/hosts IPv6-only netd fast-path>
    #   @DOMAINS
    #   <full parent-compressed HaGeZi Pro + StevenBlack suffix domain set>
    lines_out: list[str] = ["# Samurai System AdBlocker Seed v1 (Android 17 RMX1931)", "@ALLOW"]
    lines_out.extend(sorted(allow_set))
    lines_out.append("@HOSTS")
    lines_out.extend(core_hosts_ordered)
    lines_out.append("@DOMAINS")
    lines_out.extend(pruned_domains)

    payload = ("\n".join(lines_out) + "\n").encode("utf-8")
    with gzip.open(out_gz, "wb", compresslevel=9) as gf:
        gf.write(payload)

    sz_kb = os.path.getsize(out_gz) / 1024.0
    print(
        f"[adblock-seed] Built {out_gz} ({sz_kb:.1f} KiB): "
        f"{len(raw_domains)} raw domains -> {len(pruned_domains)} suffix rules, "
        f"{len(core_hosts_ordered)} core /etc/hosts entries, {len(allow_set)} allowlist rules"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
