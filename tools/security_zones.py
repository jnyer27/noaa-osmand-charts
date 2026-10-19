#!/usr/bin/env python3
"""Write tools/security_zones.json: the sections of 33 CFR 165 (Coast Guard regulated
navigation areas and limited access areas) whose text makes a security zone, from eCFR.

  python3 tools/security_zones.py

NOAA charts these zones as restricted areas (RESARE) without a restriction code: the Cove
Point LNG terminal's zone is CATREA 1 with only "33 CFR 165.502" in INFORM, so the planner
neither blocked nor reported it. Under 33 CFR 165.33 no vessel may enter a security zone
without the Captain of the Port's permission, so build_route_pack.py makes the charted
area of a section listed here entry prohibited. Rerun when the regulations change.
"""
import json
import os
import re
import urllib.request
import xml.etree.ElementTree as ET

API = "https://www.ecfr.gov/api/versioner/v1"


def get(url):
    req = urllib.request.Request(url, headers={"Accept-Encoding": "gzip", "User-Agent": "noaa-route"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            import gzip
            data = gzip.decompress(data)
        return data


def main():
    titles = json.loads(get(f"{API}/titles.json"))["titles"]
    date = next(t["latest_issue_date"] for t in titles if t["number"] == 33)
    root = ET.fromstring(get(f"{API}/full/{date}/title-33.xml?part=165"))
    sections = {}
    for div in root.iter("DIV8"):
        num = div.get("N", "").replace("§", "").strip()
        head = " ".join((div.findtext("HEAD") or "").split())
        text = " ".join(" ".join(div.itertext()).split())
        # The zone itself is a security zone (not a section that only mentions one)
        if re.search(r"\b(is|are) (a |the )?(safety and )?security zones?\b|\bsecurity zones?:", text, re.I) or \
                re.match(r"§?\s*[\d.]+\s+(Safety and )?Security Zones?\b", head, re.I):
            sections[num] = head
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "security_zones.json")
    json.dump({"source": f"eCFR, 33 CFR part 165, issue of {date}", "sections": dict(sorted(sections.items()))},
              open(out, "w"), indent=1)
    print(f"{len(sections)} security zone sections -> {out}")


if __name__ == "__main__":
    main()
