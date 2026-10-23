# Obsidian Widgets

Lightweight Android home-screen widgets for Obsidian. They work with a vault kept in Obsidian's private app storage.

| Part | What it does |
|---|---|
| `plugin/` (Obsidian plugin, Widget Bridge) | Sends pinned notes to the widgets. Handles `obsidian://widget-bridge` actions. |
| `android/` (companion app, no libraries) | Note widget, Actions widget, Quick append dialog and launcher shortcut. |

The widget shows the note as of the last time Obsidian was open. The plugin pushes changes on every edit and whenever you leave Obsidian. Ticking a checkbox or appending text opens Obsidian for a moment so it can edit the file, then returns you to the home screen.

## Install
1. **Plugin:** install the BRAT community plugin, then use *Add beta plugin* with `almight-74/PhoneSync`.
2. **App:** download `obsidian-widgets.apk` from the latest release and install it.
3. In Obsidian, open a note and run **Widget Bridge: Show current note in a widget**. To use today's daily note, add `@daily` in the plugin settings.
4. Long-press the home screen, open **Widgets**, then **Obsidian Widgets**:
   - **Obsidian note:** pick a note. Tap a task to tick it, tap a line to open the note, and tap ⟳ to refresh.
   - **Obsidian actions:** four buttons. The defaults are New, Daily, Find and Append. Long-press the widget to edit them.
5. **Quick append** is also available by long-pressing the app icon.

## URI actions (Routines, Tasker, shortcuts)
```
obsidian://widget-bridge?action=command&id=<command-id>      run any Obsidian command
obsidian://widget-bridge?action=append&file=Inbox&text=Hi    append a line (optional &heading=Log)
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
