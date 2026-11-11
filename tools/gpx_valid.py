"""Validate GPX files against an XSD (CI's stand-in for xmllint --schema). Usage: gpx_valid.py SCHEMA FILE..."""
import sys
from lxml import etree

schema = etree.XMLSchema(etree.parse(sys.argv[1]))
bad = 0
for f in sys.argv[2:]:
    doc = etree.parse(f)
    if schema.validate(doc):
        print(f"{f} validates")
    else:
        bad += 1
        for e in schema.error_log:
            print(f"{f}:{e.line}: {e.message}")
sys.exit(1 if bad else 0)
