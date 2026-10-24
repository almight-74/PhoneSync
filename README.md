# Obsidian Widgets

Lightweight Android home-screen widgets for Obsidian. They work with a vault kept in Obsidian's private app storage.

| Part | What it does |
|---|---|
| `plugin/` (Obsidian plugin, Widget Bridge) | Sends pinned notes to the widgets. Handles `obsidian://widget-bridge` actions. |
| `android/` (companion app, no libraries) | Note widget, Actions widget, capture dialog (voice, share sheet, destinations, templates), launcher shortcuts. |

The widget shows the note as of the last time Obsidian was open. Android only lets Obsidian send updates while it is in the foreground, so the plugin sends changes while you are in Obsidian. Anything it couldn't send goes out the next time you open Obsidian. You can choose to send on save, while typing, or never in **Settings → Widget Bridge → Auto-refresh**. Ticking a task or capturing text opens Obsidian for a moment so it can edit the file, then returns you to the home screen.

## Install
1. **Plugin:** install the BRAT community plugin, then use *Add beta plugin* with `almight-74/PhoneSync`.
2. **App:** download `obsidian-widgets.apk` from the latest release and install it.
3. In Obsidian, open a note and run **Widget Bridge: Show current note in a widget**. To use today's daily note, add `@daily` in the plugin settings.
4. Long-press the home screen, open **Widgets**, then **Obsidian Widgets**:
   - **Obsidian note:** pick a note. Tap a task to tick it, tap a line to open the note, and tap ⟳ to refresh.
   - **Obsidian actions:** four buttons. The defaults are New, Daily, ✏️ Capture and 🎤 Voice. Long-press the widget to edit them.
5. **Capture:** long-press the app icon and pick *Capture* or *Voice note*, use the ✏️/🎤 buttons, or share text from any app and choose **Send to Obsidian**. Pick a destination, then type or dictate.

## Capture destinations
Set these up in the app (open *Obsidian Widgets* from the launcher). Each destination has:
- **Mode:** `append` or `prepend` adds to a note (a path, or `@daily`), optionally under a heading. `new` creates a note in a folder.
- **File name** (new notes only) and **Template**. Both accept the tokens `{{text}}`, `{{title}}` (first line), `{{date}}`, `{{time}}` and `{{datetime}}`.
- Example template for tasks: `- [ ] {{text}} ({{time}})`.

The same screen shows when each note last reached the app, which helps with troubleshooting refresh. To see each send as it happens, turn on *Debug: notice on each push* in the plugin settings.

## URI actions (Routines, Tasker, shortcuts)
```
obsidian://widget-bridge?action=command&id=<command-id>      run any Obsidian command
obsidian://widget-bridge?action=append&file=Inbox&text=Hi    append a line (optional &heading=Log)
obsidian://widget-bridge?action=capture&mode=prepend&file=Log&text=Hi      prepend (mode=append works too)
obsidian://widget-bridge?action=capture&mode=new&folder=Inbox&name=Idea&text=Hi   new note in a folder
obsidian://widget-bridge?action=open&file=@daily             open a note (created if missing)
obsidian://widget-bridge?action=toggle&file=Tasks.md&line=3  tick/untick a task (0-based line)
obsidian://widget-bridge?action=push                         refresh widgets
```
Add `&back=1` to any of these to return to the home screen when the action is done. URL-encode the values.

To find command ids, enable *Settings → Community plugins → Developer mode* on desktop, or check the plugin's source. Core examples are `daily-notes`, `switcher:open` and `file-explorer:new-file`.

## Build
```
cd plugin && npm ci && npm test && npm run build   # dist/main.js + manifest.json
cd android && ./gradlew assembleRelease            # needs the Android SDK
```
CI builds both on every push. Pushing a tag such as `0.1.0` (the same as the version in `manifest.json`, which BRAT requires) publishes a release named `YYYYMMDD_widgets-v0.1.0`.
