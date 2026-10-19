#!/usr/bin/env python3
"""Own changes to the OpenTopoMap style, applied to a fresh checkout before import and rendering.

Each patch checks that the original text is still there, so a change upstream fails loudly
instead of being silently skipped.

  1. extra OSM keys imported: locked, 4wd_only, smoothness, motor_vehicle
  2. viewpoints without a "direction" tag are drawn (all-round symbol), and never hidden by labels
  3. tracks in OSM-Carto style: brown, dash pattern by tracktype (grade1 solid ... grade5 dotted),
     "4WD" label for 4wd_only=yes or very bad smoothness
  4. paths/tracks not visible on the ground or without public access: light grey instead of black/brown
  5. camp sites: green symbol from zoom 11, name from zoom 13
  6. locked gates and barriers: red "no entry" disc from zoom 13
"""
import shutil
import sys
from pathlib import Path

here = Path(__file__).resolve().parent
mapnik_dir = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
xml_path = mapnik_dir / "opentopomap.xml"
xml = xml_path.read_text()


def patch(old, new, what, text=None):
    global xml
    src = xml if text is None else text
    if old not in src:
        sys.exit(f"style patch failed ({what}): original text not found")
    out = src.replace(old, new, 1)
    print(f"patched: {what}")
    if text is None:
        xml = out
    return out


def one_line(sql):
    return " ".join(sql.split())


# ---------------------------------------------------------------- 1) import more keys
style_file = mapnik_dir / "osm2pgsql" / "opentopomap.style"
s = style_file.read_text()
for line in ["node,way   locked       text         linear",
             "way        4wd_only     text         linear",
             "way        smoothness   text         linear",
             "node,way   motor_vehicle text        linear"]:
    key = line.split()[1]
    if not any(l.split()[1:2] == [key] for l in s.splitlines() if l.strip() and not l.startswith("#")):
        s += line + "\n"
style_file.write_text(s)
print("patched: osm2pgsql style (locked, 4wd_only, smoothness, motor_vehicle)")

# own symbols
for svg in (here / "symbols").glob("*.svg"):
    shutil.copy(svg, mapnik_dir / "symbols-otm" / svg.name)

# ---------------------------------------------------------------- 2) viewpoints
patch("viewpointdirection(direction) AS int_dir FROM planet_osm_point WHERE (tourism='viewpoint' AND direction IS NOT NULL)",
      "viewpointdirection(COALESCE(NULLIF(direction,''),'0-360')) AS int_dir FROM planet_osm_point WHERE tourism='viewpoint'",
      "viewpoints without direction")
vp = mapnik_dir / "styles-otm" / "symbols-viewpoint.xml"
vp_text = vp.read_text()
if 'ignore-placement="true"' not in vp_text:
    sys.exit("style patch failed (viewpoint overlap): original text not found")
vp.write_text(vp_text.replace('ignore-placement="true"', 'ignore-placement="true" allow-overlap="true"'))
print("patched: viewpoint symbols always drawn")

# ---------------------------------------------------------------- 3+4) tracks and faded ways
NOACCESS = ("(foot IN ('no','private') OR (access IN ('no','private') "
            "AND COALESCE(foot,'') NOT IN ('yes','designated','permissive')))")
INVISIBLE = "trail_visibility IN ('no','horrible')"
PATHLIKE = "highway IN ('path','footway','bridleway','steps','cycleway')"

# take tracks and faded path-like ways out of OpenTopoMap's own road layer
start = xml.index('<Layer name="roads">')
end = xml.index("</Layer>", start) + len("</Layer>")
roads = xml[start:end]
excl = f"NOT COALESCE(highway = 'track' OR ({PATHLIKE} AND ({INVISIBLE} OR {NOACCESS})), false) AND ("
roads = patch("FROM planet_osm_line WHERE ", "FROM planet_osm_line WHERE " + excl, "roads layer: exclude tracks", roads)
roads = patch(" ORDER BY (CASE WHEN highway IN ('motorway'", ") ORDER BY (CASE WHEN highway IN ('motorway'", "roads layer: close condition", roads)
xml = xml[:start] + roads + xml[end:]

BROWN = "#996600"
GREY = "#b4b4b4"
# OSM-Carto track dash patterns (px at scale 1)
GRADES = [
    ("grade1", None),
    ("grade2", "8.8,3.2"),
    ("grade3", "5.6,4.0"),
    ("grade4", "3.2,4.8"),
    ("grade5", "1.6,6.4"),
    (None, "6,3,1.5,3"),  # tracktype unknown: dash-dot
]
# (zoom band entities, width)
BANDS = [("&maxscale_zoom12;&minscale_zoom13;", 1.0), ("&maxscale_zoom14;&minscale_zoom14;", 1.5),
         ("&maxscale_zoom15;&minscale_zoom15;", 1.9), ("&maxscale_zoom16;&minscale_zoom18;", 2.4)]


def track_rules():
    out = ['<Style name="own-tracks-casing">']
    for band, width in BANDS[1:]:
        out.append(f'<Rule>{band}<LineSymbolizer stroke="#ffffff" stroke-width="{width + 2.2}" stroke-opacity="0.55" stroke-linecap="round"/></Rule>')
    out.append('</Style><Style name="own-tracks">')
    for band, width in BANDS:
        for grade, dash in GRADES:
            gfilter = f"[tracktype] = '{grade}'" if grade else \
                "not ([tracktype] = 'grade1' or [tracktype] = 'grade2' or [tracktype] = 'grade3' or [tracktype] = 'grade4' or [tracktype] = 'grade5')"
            w = width * (1.25 if grade == "grade1" else 1.0)
            dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
            for faded, color in (("no", BROWN), ("yes", GREY)):
                out.append(f"<Rule>{band}<Filter>({gfilter}) and [otm_faded] = '{faded}'</Filter>"
                           f'<LineSymbolizer stroke="{color}" stroke-width="{w:.2f}"{dash_attr} stroke-linecap="butt"/></Rule>')
    out.append('</Style><Style name="own-tracks-4wd">')
    out.append("<Rule>&maxscale_zoom14;<Filter>[otm_4wd] = 'yes'</Filter>"
               f'<TextSymbolizer fontset-name="sans-condensed-bold" size="8" fill="{BROWN}" halo-radius="1.2" halo-fill="rgba(255,255,255,0.85)" '
               """placement="line" spacing="260" max-char-angle-delta="20" dy="-6">'4WD'</TextSymbolizer></Rule>""")
    out.append("</Style>")
    return "\n".join(out)


FADED_STYLE = f"""<Style name="own-faded-ways">
<Rule>&maxscale_zoom14;<Filter>[highway] != 'steps'</Filter>
<LineSymbolizer stroke="{GREY}" stroke-width="0.9" stroke-dasharray="1.6,1.6"/></Rule>
<Rule>&maxscale_zoom15;<Filter>[highway] = 'steps'</Filter>
<LineSymbolizer stroke="{GREY}" stroke-width="2.5" stroke-dasharray="1,1.5"/></Rule>
</Style>"""

TRACKS_SQL = one_line(f"""(SELECT way, tracktype,
  CASE WHEN {NOACCESS} OR {INVISIBLE} THEN 'yes' ELSE 'no' END AS otm_faded,
  CASE WHEN "4wd_only" = 'yes' OR smoothness IN ('very_bad','horrible','very_horrible','impassable') THEN 'yes' ELSE 'no' END AS otm_4wd
  FROM planet_osm_line WHERE highway = 'track' AND (tunnel IS NULL OR tunnel = 'no')) AS owntracks""")
FADED_SQL = one_line(f"""(SELECT way, highway FROM planet_osm_line
  WHERE {PATHLIKE} AND ({INVISIBLE} OR {NOACCESS})) AS fadedways""")


def xml_escape(sql):
    return sql.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def layer(name, styles, sql):
    style_refs = "".join(f"<StyleName>{st}</StyleName>" for st in styles)
    return (f'<Layer name="{name}">{style_refs}<Datasource>'
            f'<Parameter name="table">{xml_escape(sql)}</Parameter>'
            '<Parameter name="geometry_field">way</Parameter><Parameter name="srid">3857</Parameter>'
            '<Parameter name="dbname">gis</Parameter>&postgis-settings;</Datasource></Layer>')


insert = "\n".join([FADED_STYLE, track_rules(),
                    layer("own-faded-ways", ["own-faded-ways"], FADED_SQL),
                    layer("own-tracks", ["own-tracks-casing", "own-tracks", "own-tracks-4wd"], TRACKS_SQL)])
end = xml.index("</Layer>", xml.index('<Layer name="roads">')) + len("</Layer>")
xml = xml[:end] + "\n" + insert + "\n" + xml[end:]
print("patched: own track and faded-way layers")

# ---------------------------------------------------------------- 5+6) camp sites, locked gates (on top)
CAMPS_SQL = one_line("""(SELECT way, name FROM planet_osm_point WHERE tourism = 'camp_site'
  UNION ALL SELECT ST_PointOnSurface(way) AS way, name FROM planet_osm_polygon WHERE tourism = 'camp_site') AS camps""")
GATES_SQL = one_line("""(SELECT way FROM planet_osm_point
  WHERE barrier IN ('gate','lift_gate','swing_gate','chain','bar') AND locked = 'yes') AS lockedgates""")
TOP = f"""<Style name="own-camps">
<Rule>&maxscale_zoom11;&minscale_zoom12;<PointSymbolizer file="symbols-otm/camp_highlight.svg" transform="scale(0.75)" allow-overlap="true"/></Rule>
<Rule>&maxscale_zoom13;<PointSymbolizer file="symbols-otm/camp_highlight.svg" allow-overlap="true"/>
<TextSymbolizer fontset-name="sans-condensed-bold" size="10" fill="#1b7f3b" halo-radius="1.5" halo-fill="rgba(255,255,255,0.9)" dy="14" wrap-width="90">[name]</TextSymbolizer></Rule>
</Style>
<Style name="own-gates">
<Rule>&maxscale_zoom13;&minscale_zoom14;<PointSymbolizer file="symbols-otm/gate_locked.svg" transform="scale(0.8)" allow-overlap="true"/></Rule>
<Rule>&maxscale_zoom15;<PointSymbolizer file="symbols-otm/gate_locked.svg" allow-overlap="true"/></Rule>
</Style>
{layer("own-camps", ["own-camps"], CAMPS_SQL)}
{layer("own-gates", ["own-gates"], GATES_SQL)}
"""
xml = patch("</Map>", TOP + "</Map>", "camp sites and locked gates on top")

xml_path.write_text(xml)
