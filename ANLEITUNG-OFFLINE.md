# Simple OSM Viewer – Offline-Karten

Dieser Umbau erweitert Simple OSM Viewer (MIT-Lizenz, github.com/applikationsprogramvara/osmv)
um Offline-Karten aus Dateien. Grundlage: Version 1.8.2 (Commit ad0a6d6).

## Was neu ist

- **Offline-Karten importieren:** Kartensymbol lange drücken → „Offline maps…“ → „+ Import map file…“.
  Unterstützt: `.mbtiles` (empfohlen), osmdroid-`.sqlite`, osmdroid-`.zip`, `.gemf`.
  Die Datei wird in den app-eigenen Ordner kopiert, mit Fortschrittsanzeige und Abbrechen-Knopf.
- **Dritte Kartenkategorie:** Kurz auf das Kartensymbol tippen schaltet Standard → Topo → Offline
  (sobald mindestens eine Offline-Karte vorhanden ist). Eigenes Symbol für Offline.
- **Mehrere Karten:** Im Offline-Menü auswählen, löschen, Speicherort anzeigen.
- **Springt zur Karte:** Nach dem Import (und beim Auswählen, falls man woanders ist) zoomt die App
  auf das Gebiet der MBTiles-Datei.
- **Komplett offline:** Offline-Karten verwenden kein Netz. Über die höchste Zoomstufe der Datei hinaus
  werden die Kacheln vergrössert angezeigt.
- GPS, Follow-Modus, Lineal usw. funktionieren unverändert.
- Der Kartenordner ist vom Android-Backup ausgenommen (sonst würde das 25-MB-Limit gesprengt).
- Debug-Build bekommt die Paket-ID `…osmviewer.offline` und installiert sich **neben** der Play-Store-Version.

Speicherort der Karten: `Android/data/com.applikationsprogramvara.osmviewer.offline/files/maps`
(Debug-Build). Dorthin kann man Dateien auch per USB kopieren.

## Bauen

1. Android Studio installieren (aktuelle Version, bringt JDK 17 mit).
2. Diesen Ordner öffnen (File → Open), Gradle-Sync abwarten.
3. Handy per USB verbinden (Entwickleroptionen + USB-Debugging an) → grüner Play-Knopf „Run“.
   Oder: Build → Build App Bundle(s) / APK(s) → Build APK(s), dann die APK aus
   `app/build/outputs/apk/debug/` aufs Handy kopieren und installieren.

Für den Debug-Build ist kein Keystore nötig.

## Karten erzeugen

- **MOBAC:** Gebiet und Zoomstufen wählen, Format „MBTiles SQLite“.
- **QGIS:** Werkzeug „XYZ-Kacheln erzeugen (MBTiles)“, z.B. für eigene Layer wie TASVEG.
- Nur **Raster**-MBTiles (PNG/JPG) funktionieren, keine Vektor-MBTiles (PBF).

## Geänderte Dateien

- `app/src/main/java/.../OfflineMapManager.java` (neu): Import, Dateiliste, Kartengrenzen
- `app/src/main/java/.../MapsCatalog.java`: Offline-Kategorie und Menüs
- `app/src/main/java/.../MainActivity.java`: Wechsel auf `OfflineTileProvider`, Import-Dialog
- `app/src/main/res/values/strings.xml`, `drawable/ic_map_offline.xml`, `xml/backup_rules.xml`,
  `xml/data_extraction_rules.xml`, `AndroidManifest.xml`, `app/build.gradle`
