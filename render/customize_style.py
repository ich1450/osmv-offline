#!/usr/bin/env python3
"""Own changes to the OpenTopoMap style, applied to a fresh checkout before rendering.

Each patch checks that the original text is still there, so a change upstream fails loudly
instead of being silently skipped.
"""
import sys
from pathlib import Path

mapnik_dir = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
xml_path = mapnik_dir / "opentopomap.xml"
xml = xml_path.read_text()


def patch(old, new, what):
    global xml
    if old not in xml:
        sys.exit(f"style patch failed ({what}): original text not found")
    xml = xml.replace(old, new, 1)
    print(f"patched: {what}")


# 1) viewpoints without a "direction" tag were not drawn at all -> show them with the all-round symbol
patch(
    "viewpointdirection(direction) AS int_dir FROM planet_osm_point WHERE (tourism='viewpoint' AND direction IS NOT NULL)",
    "viewpointdirection(COALESCE(NULLIF(direction,''),'0-360')) AS int_dir FROM planet_osm_point WHERE tourism='viewpoint'",
    "viewpoints without direction",
)

# 2) marks on paths and tracks (zoom 14+):
#    - no public access (access/foot = no or private, unless foot is explicitly allowed):
#      red dashed line along one side of the way
#    - path not visible on the ground (trail_visibility = no / horrible): orange dots on the path
PATH_FLAGS = """
	<Style name="path-flags">
		<Rule>
			&maxscale_zoom14;
			<Filter>[otm_invisible] = 'yes'</Filter>
			<LineSymbolizer stroke="#ff7300" stroke-width="1.8" stroke-dasharray="0.1,4.5" stroke-linecap="round" stroke-opacity="0.95"/>
		</Rule>
		<Rule>
			&maxscale_zoom14;
			<Filter>[otm_noaccess] = 'yes'</Filter>
			<LineSymbolizer stroke="#d40000" stroke-width="1.1" stroke-dasharray="3.5,2.5" offset="2.4" stroke-opacity="0.85"/>
		</Rule>
	</Style>
	<Layer name="path-flags">
		<StyleName>path-flags</StyleName>
		<Datasource>
			<Parameter name="table">(SELECT way, CASE WHEN foot IN ('no','private') OR (access IN ('no','private') AND (foot IS NULL OR foot NOT IN ('yes','designated','permissive'))) THEN 'yes' ELSE 'no' END AS otm_noaccess, CASE WHEN trail_visibility IN ('no','horrible') THEN 'yes' ELSE 'no' END AS otm_invisible FROM planet_osm_line WHERE highway IN ('path','footway','bridleway','track','cycleway','steps','service') AND (foot IN ('no','private') OR access IN ('no','private') OR trail_visibility IN ('no','horrible'))) AS pathflags</Parameter>
			<Parameter name="geometry_field">way</Parameter>
			<Parameter name="srid">3857</Parameter>
			<Parameter name="dbname">gis</Parameter>
			&postgis-settings;
		</Datasource>
	</Layer>
"""
start = xml.index('<Layer name="roads">')
end = xml.index("</Layer>", start) + len("</Layer>")
xml = xml[:end] + "\n" + PATH_FLAGS + xml[end:]
print("patched: path flags layer (no access / invisible trail)")

xml_path.write_text(xml)
