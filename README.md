# LoriMaps

![LoriMaps](app/src/main/res/mipmap-xxhdpi/ic_launcher3.png)

LoriMaps ist eine schlanke Android-App für **Offline-Karten** mit GPS-Standort – gedacht für Wanderungen
und Touren abseits des Mobilfunknetzes, z.B. in Tasmanien. Die Karten sind eigene Raster-MBTiles-Dateien
(z.B. selbst gerenderte OpenTopoMap-Karten), die auf dem Handy liegen.

## Funktionen

- **Offline-Karten** im MBTiles-Format (PNG, JPG oder WebP, 256 oder 512 px Kacheln). Auch osmdroid-`.sqlite`,
  `.zip` und `.gemf` werden gelesen. Vektor-MBTiles (PBF) werden nicht unterstützt.
- **Kartenmenü** (Kartensymbol oben rechts): Karte wählen, umbenennen (Stift), löschen, mit
  „+ Karte hinzufügen“ eine neue Datei importieren. Nach dem Import springt die Karte auf das Gebiet der Datei.
- **Scharfe Darstellung:** 512-px-Kacheln werden in voller Auflösung angezeigt.
- **Stufenweiser Zoom:** Plus/Minus unten rechts und Pinch-Zoom rasten auf ganze Zoomstufen ein,
  die aktuelle Stufe steht klein über dem Standort-Knopf.
- **GPS-Standort** wie bei MyTrails: roter Punkt, blauer Genauigkeitskreis und Blickrichtungs-Kegel (Kompass).
  Ein-/ausschaltbar in den Einstellungen (Standard: ein).
- **Standort-Knopf** unten links: einmal tippen springt zum aktuellen Standort (ohne Hinauszoomen),
  nochmals tippen springt zurück an die vorherige Stelle und Zoomstufe. Lange drücken: Standort verfolgen.
- **Massstab** über die ganze Breite, in der Mitte geteilt (halbe Strecke ablesbar), metrisch oder imperial.
- **Lineal:** Einstellungs-Knopf lange drücken blendet es ein.
- Online-Karten im Kartenmenü: OpenTopoMap (Standard), OpenCycleMap (API-Schlüssel von Thunderforest in den Einstellungen) und CyclOSM.

Die Karten liegen im app-eigenen Ordner
`Android/data/com.applikationsprogramvara.osmviewer.offline/files/maps` und sind vom Android-Backup ausgenommen.

## Installieren

Jeder Push auf `main` baut die App per GitHub Actions. Die fertige APK liegt unter
[Releases](../../releases) (`build-N`). Alle Builds sind mit demselben Schlüssel signiert und lassen sich
als Update über eine vorhandene Installation installieren.

## Selbst bauen

1. Android Studio installieren (bringt JDK 17 mit) und diesen Ordner öffnen.
2. Gradle-Sync abwarten, dann **Run** (Handy per USB mit USB-Debugging) oder
   *Build → Build APK(s)*; die APK liegt danach in `app/build/outputs/apk/debug/`.

Ohne Android Studio: `gradle assembleDebug` mit Gradle 8.13 und Java 17.

## Code-Übersicht

- `MainActivity.java` – Karte, Knöpfe, GPS, Zoom, Sprung zum Standort
- `MapsCatalog.java` – Kartenmenü (online und offline), Namen
- `OfflineMapManager.java` – Import, Dateiliste, Kartengrenzen, Kachelgrösse
- `LocationMarkerOverlay.java` – Standortpunkt mit Genauigkeit und Richtung
- `ScaleBarView.java` – Massstab
- `app/make_icon.py` – erzeugt das App-Symbol

## Herkunft und Lizenz

LoriMaps basiert auf der Open-Source-App „Simple OSM Viewer“ (OSMv) und verwendet die Kartenbibliothek
osmdroid. Lizenz: MIT, siehe [LICENSE](LICENSE).

Kartendaten © OpenStreetMap-Mitwirkende. Kartenstil der selbst gerenderten Karten: OpenTopoMap (CC-BY-SA).
