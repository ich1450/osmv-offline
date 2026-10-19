#!/usr/bin/env bash
# Renders an OpenTopoMap-style raster MBTiles file for the region in render/region.env.
# Runs on a GitHub Actions ubuntu-24.04 runner (uses sudo, apt and a local PostgreSQL).
#
# Data:  OpenStreetMap (Geofabrik extracts), Copernicus GLO-30 DEM (AWS open data),
#        OSM water polygons (osmdata.openstreetmap.de)
# Style: https://github.com/der-stefan/OpenTopoMap (mapnik)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/region.env"
WORK="${WORK:-$HOME/work}"
OTM="$WORK/OpenTopoMap"
DEM="$OTM/mapnik/dem"
DATA="$OTM/mapnik/data"
OUT="${OUT:-$WORK/out}"
mkdir -p "$WORK" "$OUT"

IFS=, read -r W S E N <<< "$BBOX"
BUF=0.05   # degrees of extra data around the box, so labels and lines at the edges are complete
BW=$(echo "$W - $BUF" | bc -l); BS=$(echo "$S - $BUF" | bc -l)
BE=$(echo "$E + $BUF" | bc -l); BN=$(echo "$N + $BUF" | bc -l)

step() { echo; echo "===== $(date +%H:%M:%S) $*"; }

# ---------------------------------------------------------------- software
step "Installing software"
sudo apt-get update -q
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends \
  postgresql-16-postgis-3 postgresql-contrib osm2pgsql osmium-tool \
  gdal-bin python3-gdal libgdal-dev python3-mapnik mapnik-utils \
  fonts-dejavu-core fonts-dejavu-extra fonts-unifont fonts-noto-core \
  libdbd-pg-perl bc unzip sqlite3 build-essential python3-pil
echo "ttf-mscorefonts-installer msttcorefonts/accepted-mscorefonts-eula select true" | sudo debconf-set-selections
for pkg in fonts-roboto-unhinted fonts-open-sans ttf-mscorefonts-installer fonts-wine fonts-urw-base35 fonts-texgyre; do   # optional fonts
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends "$pkg" || echo "WARN: $pkg not available"
done
python3 -c "import mapnik; print('mapnik', mapnik.mapnik_version())"

# ---------------------------------------------------------------- style
step "Getting OpenTopoMap style"
rm -rf "$OTM"
git clone -q --depth 1 https://github.com/der-stefan/OpenTopoMap "$OTM"
git -C "$OTM" log -1 --format='OpenTopoMap commit %H (%cd)'
python3 "$HERE/customize_style.py" "$OTM/mapnik"
mkdir -p "$DEM" "$DATA"

# ---------------------------------------------------------------- database
step "Starting PostgreSQL"
sudo systemctl start postgresql
for setting in "shared_buffers='1GB'" "work_mem='256MB'" "maintenance_work_mem='1GB'" "fsync=off" "synchronous_commit=off" "full_page_writes=off"; do
  sudo -u postgres psql -q -c "ALTER SYSTEM SET $setting"
done
sudo systemctl restart postgresql
sudo -u postgres createuser -s "$USER" 2>/dev/null || true
for db in gis contours; do
  dropdb --if-exists "$db"; createdb "$db"
  psql -q -d "$db" -c "CREATE EXTENSION postgis; CREATE EXTENSION hstore; CREATE EXTENSION dblink;"
done

# ---------------------------------------------------------------- OSM data
step "Downloading and clipping OSM data"
cd "$WORK"
parts=()
i=0
for url in $PBF_URLS; do
  i=$((i+1))
  curl -fsSL --retry 3 -o "src$i.osm.pbf" "$url"
  osmium extract -s smart -b "$BW,$BS,$BE,$BN" "src$i.osm.pbf" -o "part$i.osm.pbf" --overwrite
  rm "src$i.osm.pbf"
  parts+=("part$i.osm.pbf")
done
if [ ${#parts[@]} -gt 1 ]; then
  osmium merge "${parts[@]}" -o region.osm.pbf --overwrite
else
  mv "${parts[0]}" region.osm.pbf
fi
ls -la region.osm.pbf

step "Importing OSM data (osm2pgsql)"
osm2pgsql --slim -d gis -C 4000 --number-processes 4 \
  --style "$OTM/mapnik/osm2pgsql/opentopomap.style" region.osm.pbf

# ---------------------------------------------------------------- DEM
step "Downloading Copernicus GLO-30 DEM"
mkdir -p "$WORK/dem-src"
for lat in $(seq "$(printf %.0f "$(echo "($BS)/1 - 0.5" | bc -l)")" "$(printf %.0f "$(echo "($BN)/1 - 0.5" | bc -l)")"); do
  for lon in $(seq "$(printf %.0f "$(echo "($BW)/1 - 0.5" | bc -l)")" "$(printf %.0f "$(echo "($BE)/1 - 0.5" | bc -l)")"); do
    if [ "$lat" -lt 0 ]; then ns=S; la=$(( -lat )); else ns=N; la=$lat; fi
    if [ "$lon" -lt 0 ]; then ew=W; lo=$(( -lon )); else ew=E; lo=$lon; fi
    tile=$(printf "Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM" "$ns" "$la" "$ew" "$lo")
    echo "  $tile"
    curl -fsSL --retry 3 -o "$WORK/dem-src/$tile.tif" \
      "https://copernicus-dem-30m.s3.amazonaws.com/$tile/$tile.tif" || echo "  (not available, probably sea)"
  done
done

step "Processing DEM (warp, hillshade, relief, contours)"
cd "$DEM"
GDAL="-co TILED=YES -co COMPRESS=LZW -co PREDICTOR=2 -co BIGTIFF=IF_SAFER"
MERC="+proj=merc +ellps=sphere +R=6378137 +a=6378137 +units=m"
gdalbuildvrt -q src.vrt "$WORK"/dem-src/*.tif
gdalwarp -q -overwrite $GDAL -te "$BW" "$BS" "$BE" "$BN" src.vrt raw.tif
ln -sf raw.tif dem-srtm.tiff   # used by the saddle/isolation tools
for r in 5000 1000 700 500 90; do
  gdalwarp -q -overwrite $GDAL -t_srs "$MERC" -r bilinear -tr $r $r raw.tif warp-$r.tif
done
gdalwarp -q -overwrite $GDAL -t_srs "$MERC" -r cubicspline -tr 30 30 raw.tif warp-30.tif

gdaldem color-relief -q -co COMPRESS=LZW -co PREDICTOR=2 -alpha warp-5000.tif "$OTM/mapnik/relief_color_text_file.txt" relief-5000.tif
gdaldem color-relief -q -co COMPRESS=LZW -co PREDICTOR=2 -alpha warp-500.tif "$OTM/mapnik/relief_color_text_file.txt" relief-500.tif
gdaldem hillshade -q -z 7 -compute_edges -co COMPRESS=JPEG warp-5000.tif hillshade-5000.tif
gdaldem hillshade -q -z 7 -compute_edges -co TILED=YES -co COMPRESS=JPEG warp-1000.tif hillshade-1000.tif
gdaldem hillshade -q -z 4 -compute_edges -co TILED=YES -co COMPRESS=JPEG warp-700.tif hillshade-700.tif
gdaldem hillshade -q -z 5 -compute_edges -co TILED=YES -co COMPRESS=JPEG warp-500.tif hillshade-500.tif
gdaldem hillshade -q -z 2 -compute_edges -co TILED=YES -co COMPRESS=JPEG warp-90.tif hillshade-90.tif
gdaldem hillshade -q -z 2 -compute_edges -co TILED=YES -co COMPRESS=JPEG warp-30.tif hillshade-30m-jpeg.tif

# contour lines every 10 m from the 30 m DEM, into database "contours", table "contours(way, ele)"
gdal_contour -q -a ele -i 10 -f GPKG warp-30.tif "$WORK/contours.gpkg"
ogr2ogr -f PostgreSQL PG:dbname=contours "$WORK/contours.gpkg" -nln contours -a_srs EPSG:3857 \
  -lco GEOMETRY_NAME=way -nlt LINESTRING -explodecollections -overwrite
psql -q -d contours -c "ALTER TABLE contours ALTER COLUMN ele TYPE integer USING round(ele)::integer; DELETE FROM contours WHERE ST_Length(way) < 20; ANALYZE contours;"
psql -d contours -t -c "SELECT 'contour lines: ' || count(*) FROM contours;"
ls -la "$DEM"

# ---------------------------------------------------------------- water polygons (oceans)
step "Water polygons"
cd "$WORK"
read -r MX0 MY0 <<< "$(echo "$BW $BS" | gdaltransform -s_srs EPSG:4326 -t_srs EPSG:3857 -output_xy)"
read -r MX1 MY1 <<< "$(echo "$BE $BN" | gdaltransform -s_srs EPSG:4326 -t_srs EPSG:3857 -output_xy)"
for set in simplified-water-polygons-split-3857:simplified_water_polygons water-polygons-split-3857:water_polygons; do
  dir=${set%%:*}; layer=${set##*:}
  curl -fsSL --retry 3 -o "$dir.zip" "https://osmdata.openstreetmap.de/download/$dir.zip"
  unzip -q -o "$dir.zip" && rm "$dir.zip"
  mkdir -p "$DATA/$dir"
  ogr2ogr -f "ESRI Shapefile" -spat "$MX0" "$MY0" "$MX1" "$MY1" "$DATA/$dir/$layer.shp" "$dir/$layer.shp" -overwrite
  rm -rf "$dir"
done

# ---------------------------------------------------------------- OpenTopoMap preprocessing
step "OpenTopoMap preprocessing"
cd "$OTM/mapnik/tools"
cc -o saddledirection saddledirection.c -lm -lgdal -I/usr/include/gdal || echo "WARN: saddledirection not compiled"
cc -Wall -o isolation isolation.c -lgdal -lm -O2 -I/usr/include/gdal || echo "WARN: isolation not compiled"

for t in planet_osm_point planet_osm_polygon; do
  psql -q -d gis -c "ALTER TABLE $t ADD COLUMN IF NOT EXISTS otm_isolation text; ALTER TABLE $t ADD COLUMN IF NOT EXISTS hiking text;"
done
psql -q -d gis -c "ALTER TABLE planet_osm_point ADD COLUMN IF NOT EXISTS direction text;"

psql -q -d gis < arealabel.sql || echo "WARN: arealabel.sql failed"
bash update_lowzoom.sh || echo "WARN: update_lowzoom.sh failed"

# the update scripts expect fixed home paths; patch them to ours
for s in update_saddles.sh update_isolations.sh update_parking.sh; do
  sed -e "s#cd ~/OpenTopoMap/#cd $OTM/#" \
      -e "s#cd /home/otmuser/OpenTopoMap/mapnik/tools/#cd $OTM/mapnik/tools/#" \
      -e "s#/home/otmuser/OpenTopoMap#$OTM#g" "$s" > "local_$s"
  bash "local_$s" || echo "WARN: $s failed"
done
for f in stationdirection.sql viewpointdirection.sql pitchicon.sql; do
  psql -q -d gis < "$f" || echo "WARN: $f failed"
done
psql -q -d gis -c "ANALYZE;"

# ---------------------------------------------------------------- render
cd "$OTM/mapnik"
if [ "${MODE:-tiles}" = "preview" ]; then
  step "Rendering preview images"
  # fixed places plus example spots found in the data (viewpoint, invisible path, private way)
  PLACES="$WORK/places.txt"
  cp "$HERE/preview_places.txt" "$PLACES"
  ENV="ST_Transform(ST_MakeEnvelope($W,$S,$E,$N,4326),3857)"
  psql -d gis -At -F';' >> "$PLACES" <<SQL
SELECT 'Aussichtspunkt', ST_Y(p), ST_X(p), 16 FROM (SELECT ST_Transform(way,4326) p FROM planet_osm_point
  WHERE tourism='viewpoint' AND way && $ENV AND (direction IS NULL OR direction='') ORDER BY (name IS NULL), osm_id LIMIT 1) t;
SELECT 'Pfad_unsichtbar', ST_Y(p), ST_X(p), 16 FROM (SELECT ST_Transform(ST_LineInterpolatePoint(way,0.5),4326) p FROM planet_osm_line
  WHERE highway IN ('path','footway','track') AND trail_visibility IN ('no','horrible') AND way && $ENV AND GeometryType(way)='LINESTRING'
  ORDER BY ST_Length(way) DESC LIMIT 1) t;
SELECT 'Weg_privat', ST_Y(p), ST_X(p), 16 FROM (SELECT ST_Transform(ST_LineInterpolatePoint(way,0.5),4326) p FROM planet_osm_line
  WHERE highway IN ('path','footway','track') AND access IN ('no','private') AND (foot IS NULL OR foot IN ('no','private')) AND way && $ENV
  AND GeometryType(way)='LINESTRING' ORDER BY ST_Length(way) DESC LIMIT 1) t;
SQL
  psql -d gis -At -c "SELECT 'viewpoints: ' || count(*) || ', with direction: ' || count(direction) FROM planet_osm_point WHERE tourism='viewpoint';"
  psql -d gis -At -c "SELECT 'paths not visible: ' || count(*) FILTER (WHERE trail_visibility IN ('no','horrible')) || ', no access: ' || count(*) FILTER (WHERE access IN ('no','private') OR foot IN ('no','private')) FROM planet_osm_line WHERE highway IN ('path','footway','bridleway','track');"
  cat "$PLACES"
  python3 "$HERE/render_preview.py" --style opentopomap.xml --outdir "$OUT/preview" \
    --places "$PLACES" --fonts "${PREVIEW_FONTS:-dejavu,noto,roboto,opensans}" --only "${PREVIEW_PLACES:-}" --scale "$SCALE"
else
  step "Rendering tiles"
  [ -n "${FONT:-}" ] && python3 "$HERE/render_preview.py" --style opentopomap.xml --set-font "$FONT"
  python3 "$HERE/render_tiles.py" \
    --style opentopomap.xml --out "$OUT/$NAME-otm.mbtiles" \
    --bbox "$BBOX" --minzoom "$MINZOOM" --maxzoom "$MAXZOOM" --scale "$SCALE" \
    --name "$TITLE" --processes "$(nproc)"
fi

ls -laR "$OUT"
step "Done"
