#!/usr/bin/env python3
"""Convert the desktop app's Chiaki.conf into the Android app's settings import file.

Carries over registered consoles, manually added hosts, the PSN login
(account id, access/refresh token) and the subtitle translation settings with the Claude API key, so the Android / Google TV app can stream
and connect over PSN without logging in or registering again.

    scripts/desktop-settings-to-android.py [Chiaki.conf] [-o out.json] [--push DEVICE]

--push sends the settings to the app on the device over ADB; the app shows
what will be imported and asks before importing.
The output contains secrets (registration keys, PSN tokens, Claude API key): it is written
with mode 600 and should not be shared.
"""
import argparse
import base64
import json
import os
import subprocess
import sys

DEFAULT_CONF_PATHS = [
    "~/.var/app/io.github.streetpea.Chiaki4deck/config/Chiaki/Chiaki.conf",
    "~/.config/Chiaki/Chiaki.conf",
]

TARGETS = {
    0: "PS4_UNKNOWN", 800: "PS4_8", 900: "PS4_9", 1000: "PS4_10",
    1000000: "PS5_UNKNOWN", 1000100: "PS5_1",
}



def qt_unescape(raw: str) -> bytes:
    """Undo QSettings' INI escaping (iniUnescapedStringList), returning Latin-1 bytes.

    Handles surrounding quotes, \\a \\b \\f \\n \\r \\t \\v \\\\ \\" \\' \\?,
    greedy \\xHEX and greedy octal escapes, exactly as Qt writes them.
    """
    out = bytearray()
    i, n = 0, len(raw)
    in_quotes = False
    simple = {"a": 7, "b": 8, "f": 12, "n": 10, "r": 13, "t": 9, "v": 11,
              "\\": 92, '"': 34, "'": 39, "?": 63, ";": 59, ",": 44}
    while i < n:
        c = raw[i]
        if c == '"':
            in_quotes = not in_quotes
            i += 1
            continue
        if c != "\\":
            out += c.encode("utf-8") if ord(c) > 0xFF else bytes([ord(c)])
            i += 1
            continue
        i += 1
        if i >= n:
            break
        c = raw[i]
        if c in simple:
            out.append(simple[c])
            i += 1
        elif c in "xX":
            i += 1
            j = i
            while j < n and raw[j] in "0123456789abcdefABCDEF":
                j += 1
            out.append(int(raw[i:j] or "0", 16) & 0xFF)
            i = j
        elif c in "01234567":
            j = i
            while j < n and raw[j] in "01234567":
                j += 1
            out.append(int(raw[i:j], 8) & 0xFF)
            i = j
        else:
            out += c.encode("latin-1", "replace")
            i += 1
    return bytes(out)


def parse_value(raw: str):
    raw = raw.strip()
    if raw.startswith("@ByteArray(") and raw.endswith(")"):
        return qt_unescape(raw[len("@ByteArray("):-1])
    return qt_unescape(raw).decode("utf-8", "replace")


def parse_ini(path):
    sections = {}
    section = "General"
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith(";"):
                continue
            if line.startswith("[") and line.endswith("]"):
                section = line[1:-1]
                continue
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            sections.setdefault(section, {})[key.strip().replace("\\\\", "/").replace("\\", "/")] = value
    return sections


def qt_array(section):
    """[foo] 1/key=..., size=N  ->  list of dicts"""
    size = int(parse_value(section.get("size", "0")) or 0)
    items = []
    for idx in range(1, size + 1):
        prefix = f"{idx}/"
        items.append({k[len(prefix):]: v for k, v in section.items() if k.startswith(prefix)})
    return items


def b64(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def mac_str(b: bytes) -> str:
    if len(b) != 6:
        raise ValueError(f"MAC address must be 6 bytes, got {len(b)}")
    return ":".join(f"{x:02x}" for x in b)


def convert(conf_path):
    ini = parse_ini(conf_path)
    registered = []
    for h in qt_array(ini.get("registered_hosts", {})):
        regist_key = parse_value(h["rp_regist_key"])
        rp_key = parse_value(h["rp_key"])
        if len(regist_key) != 16 or len(rp_key) != 16:
            raise ValueError(f"unexpected key sizes {len(regist_key)}/{len(rp_key)} for {parse_value(h.get('server_nickname', '?'))}")
        target = int(parse_value(h.get("target", "1000")))
        def opt(k):
            v = parse_value(h[k]) if k in h else ""
            return v if v else None
        registered.append({
            "target": TARGETS.get(target, "PS4_10"),
            "ap_ssid": opt("ap_ssid"),
            "ap_bssid": opt("ap_bssid"),
            "ap_key": opt("ap_key"),
            "ap_name": opt("ap_name"),
            "server_mac": mac_str(parse_value(h["server_mac"])),
            "server_nickname": opt("server_nickname"),
            "rp_regist_key": b64(regist_key),
            "rp_key_type": int(parse_value(h.get("rp_key_type", "0"))),
            "rp_key": b64(rp_key),
        })

    manual = []
    for h in qt_array(ini.get("manual_hosts", {})):
        registered_flag = parse_value(h.get("registered", "false")) == "true"
        mac = None
        if registered_flag and "registered_mac" in h:
            mac = mac_str(parse_value(h["registered_mac"]))
        manual.append({"host": parse_value(h["host"]), "server_mac": mac})

    s = ini.get("settings", {})
    psn = None
    if "psn_refresh_token" in s or "psn_account_id" in s:
        psn = {
            "account_id": parse_value(s.get("psn_account_id", "")) or None,
            "auth_token": parse_value(s.get("psn_auth_token", "")) or None,
            "refresh_token": parse_value(s.get("psn_refresh_token", "")) or None,
            "auth_token_expiry": parse_value(s.get("psn_auth_token_expiry", "")) or None,
        }

    subtitles = None
    if any(k.startswith("subtitles_") or k == "claude_api_key" for k in s):
        def flag(k, default):
            return parse_value(s[k]) == "true" if k in s else default
        subtitles = {
            "enabled": flag("subtitles_enabled", False),
            "language": parse_value(s.get("subtitles_language", "ckb")) or "ckb",
            "model": parse_value(s.get("subtitles_model", "claude-haiku-5-5")) or "claude-haiku-5-5",
            "style": int(parse_value(s.get("subtitles_style", "0")) or 0),
            "instant": flag("subtitles_instant", True),
            "show_original": flag("subtitles_show_original", False),
            "claude_api_key": parse_value(s.get("claude_api_key", "")) or None,
        }

    return {
        "format": "chiaki-settings",
        "version": 3,
        "settings": {"registered_hosts": registered, "manual_hosts": manual, "psn": psn, "subtitles": subtitles},
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("conf", nargs="?")
    ap.add_argument("-o", "--output", default="chiaki-settings-android.json")
    ap.add_argument("--push", metavar="DEVICE", help="adb device, e.g. 192.168.1.50 or 192.168.1.50:5555")
    args = ap.parse_args()

    conf = args.conf
    if not conf:
        conf = next((os.path.expanduser(p) for p in DEFAULT_CONF_PATHS if os.path.exists(os.path.expanduser(p))), None)
        if not conf:
            sys.exit("Chiaki.conf not found; pass its path")

    data = convert(conf)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=2)
    st = data["settings"]
    print(f"Wrote {args.output}: {len(st['registered_hosts'])} registered console(s), "
          f"{len(st['manual_hosts'])} manual host(s), PSN login: {'yes' if st['psn'] else 'no'}, "
          f"subtitle settings: {'yes' if st['subtitles'] else 'no'}"
          f"{' (with Claude API key)' if st['subtitles'] and st['subtitles']['claude_api_key'] else ''}")

    if args.push:
        dev = args.push if ":" in args.push else args.push + ":5555"
        subprocess.run(["adb", "connect", dev], check=False, capture_output=True)
        payload = base64.b64encode(json.dumps(data, separators=(",", ":")).encode()).decode("ascii")
        subprocess.run(["adb", "-s", dev, "shell", "am", "start", "-W",
                        "-n", "com.metallic.chiaki/.main.MainActivity",
                        "-a", "com.metallic.chiaki.IMPORT_SETTINGS",
                        "--es", "settings_base64", payload], check=True, capture_output=True)
        print(f"Sent to {dev}: confirm the import on the screen")


if __name__ == "__main__":
    main()
