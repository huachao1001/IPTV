#!/usr/bin/env python3
"""按实测可达主机过滤节目单，剔除死源，保留频道与清晰度标记。"""
import re
import sys

GOOD_HOSTS = {
    "dxtx.hntv.tv",
    "srs.iyb983.cn",
    "srs.iyb983.cn:443",
    "120.238.94.82:9901",
    "112.27.5.218:9901",
    "v4-e6b1c5baf4e240fe63adbf68dda23435.livehwc4.com",
    "v4-702cae17a28d6e4f0c0002bba021d934.livehwc4.com",
    "120.198.95.220:9901",
    "hkstv.tv",
    "218.84.12.186:8001",
    "113.25.252.226:9901",
    "hlscdn.liangtv.cn",
    "36.136.38.87:9901",
    "live.zohi.tv",
    "ali-xwl.cztv.com",
    "event.pull.hebtv.com",
    "124.228.160.112:9901",
    "218.13.170.98:9901",
}

HOST_RE = re.compile(r"^[a-z]+://([^/]+)")


def host_of(url):
    m = HOST_RE.match(url)
    return m.group(1) if m else ""


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        lines = f.read().splitlines()

    out = ["#EXTM3U"]
    kept = dropped = 0
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.startswith("#EXTINF"):
            i += 1
            continue
        # 找紧随其后的地址行
        j = i + 1
        while j < len(lines) and lines[j].startswith("#"):
            j += 1
        if j >= len(lines):
            break
        url = lines[j].strip()
        if host_of(url) in GOOD_HOSTS:
            out.append(line)
            out.append(url)
            kept += 1
        else:
            dropped += 1
        i = j + 1

    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print(f"kept={kept} dropped={dropped}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
