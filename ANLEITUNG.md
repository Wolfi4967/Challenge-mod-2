# ⚔ Challenge-Mod – Anleitung

Ein Fabric-Mod für **Minecraft 26.3** im BastiGHG-Stil. Ihr baut euch eure Challenge im Menü zusammen:
geteilter Schmerz, Seelenband, Kette wie in *Chained Together*, Mutationen, Mob- und Block-Randomizer,
Zufallseffekte, Ultra Hardcore – und ein Ziel (Enderdrache, Wither, Wärter, Ältester Wächter oder alle vier).

**Nur wer die Welt hostet, braucht den Mod.** Deine Freunde können ganz normal (auch mit Lunar) joinen.

---

## Teil 1 – Den Mod bauen lassen (einmalig, ca. 10 Minuten)

Der Mod liegt hier als „Bauplan“ (Quellcode). GitHub macht daraus kostenlos die fertige `.jar`-Datei.
Du musst dafür nichts installieren. Es sind nur **12 Dateien** – GitHub schafft höchstens 100 auf einmal.

1. **Konto:** Auf <https://github.com> anmelden (kostenloses Konto).
2. **Neues Projekt:** Oben rechts **+** → **New repository** → Name z. B. `challenge-mod` → **Public** →
   **Create repository**.
   💡 Hattest du schon ein Projekt mit halb hochgeladenen Dateien? Dann leg lieber ein **neues** an
   (z. B. `challenge-mod-2`), damit keine alten Dateien stören.
3. **Dateien hochladen:** Auf den blauen Link **„uploading an existing file“** klicken.
   - Die ZIP-Datei entpacken und den Ordner `challenge-mod` öffnen.
   - **Alles darin markieren** (den Ordner `src` und die Dateien `build.gradle`, `settings.gradle`,
     `gradle.properties`, `challenge-data.zip`, `github-workflow.txt`, `ANLEITUNG.md`) und **ins
     Browserfenster ziehen**.
   - `challenge-data.zip` **nicht** entpacken – die muss genau so hochgeladen werden.
   - Unten auf **Commit changes** klicken.
4. **Bau-Rezept anlegen** (das kann man nicht hochladen, man muss es einfügen):
   - Oben auf den Reiter **Actions** klicken.
   - Auf den Link **„set up a workflow yourself“** klicken.
   - Im Textfeld **alles löschen** (Strg+A, Entf).
   - Die Datei `github-workflow.txt` (auf deinem PC) mit dem Editor öffnen, **alles kopieren** und ins
     Textfeld **einfügen**.
   - Oben rechts **Commit changes…** und im Fenster nochmal **Commit changes** klicken.
5. **Warten:** Wieder auf **Actions** klicken. Dort läuft jetzt „Mod bauen“ (gelber Punkt).
   Nach 3–8 Minuten kommt ein **grüner Haken** ✅. Auf den Lauf klicken → ganz unten bei **Artifacts**
   auf **challenge-mod** klicken → ZIP herunterladen → entpacken.
   Darin liegt **`challenge-mod-1.0.0.jar`** – das ist dein Mod!
6. **Rotes X ❌?** Auf den Lauf klicken → auf **build** klicken → den roten Fehlertext kopieren und an
   Claude schicken. Dann wird der Fehler repariert. (Das kann beim allerersten Bauen passieren, weil der
   Code vorher nicht mit Minecraft 26.3 getestet werden konnte.)

---

## Teil 2 – In Lunar Client einbauen

1. Lunar Client Launcher öffnen → Version **26.3** wählen → **Fabric** aktivieren.
2. Die Datei `challenge-mod-1.0.0.jar` in den **Mods**-Bereich ziehen.
3. **Fabric API:** Lunar bringt sie oft schon mit. Falls Minecraft beim Start sagt, dass `fabric-api`
   fehlt: „Fabric API“ für 26.3 von <https://modrinth.com/mod/fabric-api> herunterladen und ebenfalls
   in den Mods-Bereich ziehen.
4. Spiel starten.

---

## Teil 3 – Spielen

1. Eine ganz normale Welt erstellen. Der Mod ist in jeder Welt dabei, aber **erst mal AUS** – die Welt
   bleibt normales Minecraft, bis ihr eine Challenge einrichtet.
2. Menü öffnen: **Taste G** · oder **Esc → „⚔ Challenge“** · oder **`/challenge`**
3. **„⚔ Challenge einrichten“** → ihr seid in der Lobby.
4. Einstellungen durchklicken (3 Seiten, jeweils **„Speichern & weiter“**):
   - **Seite 1 – Regeln & Distanz:** Seelenband-Distanz (Schieberegler), Schwierigkeit, Regeneration,
     Ultra Hardcore, geteilter Schmerz, Seelenband, Lagerfeuer-Rast
   - **Seite 2 – Chaos & Kette:** Kettenlänge, Kette, Mutationen (wechselnd/dauerhaft),
     Mob-Randomizer, Block-Randomizer, Zufallseffekte
   - **Seite 3 – Ziel & Leben:** Ziel (Enderdrache, Wither, Wärter, Ältester Wächter, alle vier)
     und was beim Tod passiert (alle verlieren / nur er scheidet aus)
5. **„▶ Challenge starten“** – 3, 2, 1, los!
6. Freunde: Welt **im LAN öffnen** (oder Lunar „Hosted World“). Freunde brauchen den Mod **nicht**.

### Befehle (für alle Spieler, keine Cheats nötig)

| Befehl | Was passiert |
|---|---|
| `/challenge` | Menü öffnen |
| `/start challenge` oder `/challenge start` | Challenge starten |
| `/pause challenge` oder `/challenge pause` | Pausieren / weiterspielen |
| `/disable challenge` oder `/challenge disable` | Challenge ausschalten (mit Rückfrage) |
| `/challenge setup` | Challenge einrichten (Lobby) |
| `/challenge settings` · `chaos` · `goal` | Einstellungsseite 1 · 2 · 3 |
| `/challenge rules` · `stats` | Regeln · Statistik |
| `/challenge chat` | Notfall-Menü im Chat |
| `/challenge close` | Offenes Fenster schließen |

### Was der Mod mehr kann als das Datapack

- **Echte Befehle** wie `/start challenge`
- **Echte Ketten-Physik:** Die Kette zieht wirklich – auch nach oben, wenn jemand abstürzt
- **Block-Randomizer per Schalter** im Menü – mit einer anderen Zuordnung in jeder Welt
- **Beliebig viele Spieler** (Kette: alle hängen hintereinander an einer Kette)
- **Wählbares Ziel** und **Hardcore-Modus** (wer stirbt, scheidet aus)
- Einmal installiert, **in jeder Welt** verfügbar

### Biom-Randomizer (optional)

Die Weltgenerierung kann man nicht im laufenden Spiel ändern. Dafür gibt es weiter das Zusatz-Datapack
`Seelenband-Addon-Biom-Randomizer.zip`: beim Welt-Erstellen unter **Mehr → Datenpakete** hineinziehen.

⚠️ **Das alte Seelenband-Datapack nicht zusammen mit dem Mod benutzen** – der Mod enthält alles davon.

---

## Probleme?

- **Mod startet nicht / Absturz:** Die Datei `logs/latest.log` (bzw. den Crash-Report) an Claude schicken.
- **Menü geht nicht auf:** `/challenge chat` öffnet das Notfall-Menü im Chat.
- **Fenster hängt:** Escape drücken oder `/challenge close`.
