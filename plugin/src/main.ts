import { App, MarkdownView, Notice, Plugin, PluginSettingTab, Setting, TFile, TFolder, debounce, moment, normalizePath } from "obsidian";
import {
	Catalog, MAX_CATALOG_NOTES, MAX_EDIT_CHARS, Snapshot, appendToContent, hashText, noteToRows, prependToContent,
	replaceIfUnchanged, toggleInContent, uniquePath,
} from "./core";

interface Settings {
	pinned: string[];    // note paths, or "@daily" for today's daily note; managed from the phone app
	autoPush: "save" | "typing" | "off";
	debug: boolean;      // show a notice on every push (toggled by command)
}

const DEFAULTS: Settings = { pinned: [], autoPush: "save", debug: false };
const DEFAULT_TARGET = "@daily";
const BRIDGE = "obsidianwidget://";

export default class WidgetBridge extends Plugin {
	settings: Settings = DEFAULTS;

	private pending = false;
	private catalogPending = false;
	private lastSent = new Map<string, string>(); // key -> content signature, to skip unchanged notes
	private lastCatalog = "";

	// Android blocks activity starts from a backgrounded app, so pushes only go out while
	// Obsidian is visible; one that comes due in the background waits for the next resume.
	private schedulePush = debounce(() => {
		if (this.settings.autoPush === "off") return;
		if (document.visibilityState === "visible") this.pushAll(false);
		else this.pending = true;
	}, 700, true);

	private scheduleCatalog = debounce(() => {
		if (document.visibilityState === "visible") this.sendCatalog(false);
		else this.catalogPending = true;
	}, 5000, true);

	async onload() {
		this.settings = Object.assign({}, DEFAULTS, await this.loadData());
		this.addSettingTab(new BridgeSettingTab(this.app, this));

		this.registerObsidianProtocolHandler("widget-bridge", (p) => this.handle(p));

		this.addCommand({
			id: "pin-current-note",
			name: "Show current note in a widget",
			checkCallback: (checking) => {
				const f = this.app.workspace.getActiveFile();
				if (!f || this.settings.pinned.includes(f.path)) return false;
				if (!checking) this.pin(f.path);
				return true;
			},
		});
		this.addCommand({
			id: "unpin-current-note",
			name: "Stop showing current note in widgets",
			checkCallback: (checking) => {
				const f = this.app.workspace.getActiveFile();
				if (!f || !this.settings.pinned.includes(f.path)) return false;
				if (!checking) this.unpin(f.path);
				return true;
			},
		});
		this.addCommand({ id: "push-widgets", name: "Refresh widgets now", callback: () => this.pushAll() });
		this.addCommand({ id: "send-catalog", name: "Send command and note lists to the app", callback: () => this.sendCatalog(true) });
		this.addCommand({
			id: "toggle-debug",
			name: "Toggle debug notices",
			callback: async () => {
				this.settings.debug = !this.settings.debug;
				await this.saveSettings();
				new Notice(`Widget Bridge debug ${this.settings.debug ? "on" : "off"}`);
			},
		});

		this.registerEvent(this.app.vault.on("modify", (f) => {
			if (this.resolvedPinned().some((p) => p.path === f.path)) this.schedulePush();
		}));
		// Typing: don't wait for Obsidian's own save (~2 s) before the widget sees the change.
		this.registerEvent(this.app.workspace.on("editor-change", (_editor, info) => {
			if (this.settings.autoPush !== "typing") return;
			if (info.file && this.resolvedPinned().some((p) => p.path === info.file!.path)) this.schedulePush();
		}));
		this.registerEvent(this.app.workspace.on("active-leaf-change", () => this.schedulePush()));
		this.registerEvent(this.app.workspace.on("file-open", () => this.schedulePush()));
		this.registerEvent(this.app.vault.on("rename", (f, old) => {
			const i = this.settings.pinned.indexOf(old);
			if (i >= 0) { this.settings.pinned[i] = f.path; this.saveSettings(); this.schedulePush(); }
			this.scheduleCatalog();
		}));
		this.registerEvent(this.app.vault.on("create", () => this.scheduleCatalog()));
		this.registerEvent(this.app.vault.on("delete", () => this.scheduleCatalog()));
		this.registerDomEvent(document, "visibilitychange", () => {
			if (document.visibilityState !== "visible") return;
			if (this.pending) this.pushAll(false);
			if (this.catalogPending) this.sendCatalog(false);
		});
		this.app.workspace.onLayoutReady(() => this.scheduleCatalog());
	}

	async saveSettings() {
		await this.saveData(this.settings);
	}

	async pin(path: string) {
		if (!this.settings.pinned.includes(path)) this.settings.pinned.push(path);
		await this.saveSettings();
		await this.pushAll();
		new Notice(`Widget: ${path} is available. Add a Note widget on the home screen and pick it.`);
	}

	async unpin(path: string) {
		this.settings.pinned = this.settings.pinned.filter((p) => p !== path);
		await this.saveSettings();
	}

	// ---------- obsidian://widget-bridge?do=... ----------
	// Obsidian overwrites the `action` query param with the handler name, so the verb travels as `do`.

	async handle(p: Record<string, string>) {
		try {
			switch (p.do) {
				case "toggle": await this.toggle(p.file, Number(p.line), p.match); break;
				case "append": await this.append(p.file || DEFAULT_TARGET, p.text ?? "", p.heading); break;
				case "capture": await this.capture(p); break;
				case "replace": await this.replace(p.file, p.text ?? "", p.base ?? ""); break;
				case "open": await this.open(p.file); break;
				case "command": this.runCommand(p.id); break;
				case "pin": await this.pin(p.file); break;
				case "unpin": await this.unpin(p.file); new Notice(`Widget: removed ${p.file}`); break;
				case "push": await this.pushAll(); break;
				case "catalog": await this.sendCatalog(true); break;
				default: new Notice(`Widget Bridge: unknown action "${p.do}"`);
			}
		} catch (e) {
			new Notice(`Widget Bridge: ${(e as Error).message}`);
			return;
		}
		if (p.back === "1") window.setTimeout(() => this.send("home"), 300);
	}

	private async toggle(path: string, line: number, match?: string) {
		const file = this.fileOrThrow(this.resolvePath(path));
		let changed = false;
		await this.app.vault.process(file, (data) => {
			const next = toggleInContent(data, line, match);
			if (next === null) return data;
			changed = true;
			return next;
		});
		if (!changed) throw new Error("task not found, widget refreshed");
		await this.pushAll();
	}

	private async append(path: string, text: string, heading?: string) {
		if (!text.trim()) return;
		const file = await this.ensureFile(this.resolvePath(path));
		await this.app.vault.process(file, (data) => appendToContent(data, text, heading));
		await this.pushAll();
	}

	/** Saves the home-screen editor. If the note changed meanwhile, the edit goes to a copy instead. */
	private async replace(path: string, text: string, base: string) {
		const file = await this.ensureFile(this.resolvePath(path));
		let conflict = false;
		await this.app.vault.process(file, (data) => {
			const next = replaceIfUnchanged(data, base, text);
			if (next === null) { conflict = true; return data; }
			return next;
		});
		if (conflict) {
			const copy = uniquePath(file.parent?.path ?? "", `${file.basename} (widget edit)`,
				(x) => !!this.app.vault.getAbstractFileByPath(normalizePath(x)));
			await this.app.vault.create(normalizePath(copy), text);
			new Notice(`${file.basename} changed elsewhere; your edit was saved as ${copy}`);
		}
		await this.pushAll();
	}

	/** mode=append|prepend (file, heading?) or mode=new (folder, name). */
	private async capture(p: Record<string, string>) {
		const text = p.text ?? "";
		if (!text.trim()) return;
		if (p.mode === "new") {
			const folder = (p.folder ?? "").trim();
			if (folder && !this.app.vault.getAbstractFileByPath(normalizePath(folder))) {
				await this.app.vault.createFolder(normalizePath(folder));
			}
			const path = uniquePath(folder, p.name || moment().format("YYYY-MM-DD HHmm"),
				(x) => !!this.app.vault.getAbstractFileByPath(normalizePath(x)));
			await this.app.vault.create(normalizePath(path), text.endsWith("\n") ? text : text + "\n");
			new Notice(`Saved ${path}`);
			return;
		}
		if (p.mode === "prepend") {
			const file = await this.ensureFile(this.resolvePath(p.file || DEFAULT_TARGET));
			await this.app.vault.process(file, (data) => prependToContent(data, text, p.heading || undefined));
			await this.pushAll();
			return;
		}
		await this.append(p.file || DEFAULT_TARGET, text, p.heading || undefined);
	}

	private async open(path: string) {
		const resolved = this.resolvePath(path);
		if (!this.app.vault.getAbstractFileByPath(resolved)) await this.app.vault.create(resolved, "");
		await this.app.workspace.openLinkText(resolved, "", false);
	}

	private runCommand(id: string) {
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
		const ok = (this.app as any).commands.executeCommandById(id);
		if (!ok) throw new Error(`command "${id}" not found`);
	}

	// ---------- snapshots and catalog ----------

	/** `force` re-sends notes even if they did not change since the last push. */
	async pushAll(force = true) {
		this.pending = false;
		for (const { key, path } of this.resolvedPinned()) {
			const file = this.app.vault.getAbstractFileByPath(path);
			const content = file instanceof TFile ? this.liveText(file) ?? await this.app.vault.cachedRead(file) : "";
			const hash = hashText(content);
			const sig = (file?.path ?? "") + hash;
			if (!force && this.lastSent.get(key) === sig) continue;
			this.lastSent.set(key, sig);
			const snap: Snapshot = {
				key,
				title: file instanceof TFile ? file.basename : key,
				vault: this.app.vault.getName(),
				open: `obsidian://widget-bridge?do=open&file=${encodeURIComponent(key)}`,
				updated: Date.now(),
				rows: noteToRows(content, key),
				text: content.length <= MAX_EDIT_CHARS ? content : undefined,
				hash,
			};
			if (this.settings.debug) new Notice(`Widget Bridge: sent ${key}`);
			this.send(`snapshot?d=${encodeURIComponent(JSON.stringify(snap))}`);
		}
	}

	/** Commands, folders and notes for the app's pickers. Skipped when nothing changed unless forced. */
	async sendCatalog(force: boolean) {
		this.catalogPending = false;
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
		const commands = ((this.app as any).commands.listCommands() as { id: string; name: string }[])
			.map((c) => [c.id, c.name] as [string, string])
			.sort((a, b) => a[1].localeCompare(b[1]));
		const folders = this.app.vault.getAllLoadedFiles()
			.filter((f): f is TFolder => f instanceof TFolder && f.path !== "/")
			.map((f) => f.path).sort();
		const notes = this.app.vault.getMarkdownFiles()
			.sort((a, b) => b.stat.mtime - a.stat.mtime)
			.slice(0, MAX_CATALOG_NOTES).map((f) => f.path);
		const catalog: Catalog = { commands, folders, notes };
		const json = JSON.stringify(catalog);
		if (!force && json === this.lastCatalog) return;
		this.lastCatalog = json;
		if (this.settings.debug) new Notice(`Widget Bridge: sent lists (${commands.length} commands, ${notes.length} notes)`);
		this.send(`catalog?d=${encodeURIComponent(json)}`);
	}

	/** Unsaved editor text if the note is open in the active editor. */
	private liveText(file: TFile): string | null {
		const view = this.app.workspace.getActiveViewOfType(MarkdownView);
		return view?.file?.path === file.path ? view.editor.getValue() : null;
	}

	private send(path: string) {
		window.open(BRIDGE + path);
	}

	private resolvedPinned() {
		return this.settings.pinned.map((key) => ({ key, path: this.resolvePath(key) }));
	}

	/** "@daily" -> today's daily note path (core Daily notes settings); adds ".md" if missing. */
	resolvePath(path: string): string {
		if (path === "@daily") {
			// eslint-disable-next-line @typescript-eslint/no-explicit-any
			const opts = (this.app as any).internalPlugins?.getPluginById?.("daily-notes")?.instance?.options ?? {};
			const name = moment().format(opts.format || "YYYY-MM-DD");
			path = (opts.folder ? `${opts.folder}/` : "") + name;
		}
		if (!path.endsWith(".md")) path += ".md";
		return normalizePath(path);
	}

	private async ensureFile(path: string): Promise<TFile> {
		const existing = this.app.vault.getAbstractFileByPath(path);
		if (existing instanceof TFile) return existing;
		const dir = path.split("/").slice(0, -1).join("/");
		if (dir && !this.app.vault.getAbstractFileByPath(dir)) await this.app.vault.createFolder(dir);
		return this.app.vault.create(path, "");
	}

	private fileOrThrow(path: string): TFile {
		const f = this.app.vault.getAbstractFileByPath(path);
		if (!(f instanceof TFile)) throw new Error(`note not found: ${path}`);
		return f;
	}
}

/** One setting; everything else is set up in the phone app. */
class BridgeSettingTab extends PluginSettingTab {
	constructor(app: App, private plugin: WidgetBridge) {
		super(app, plugin);
	}

	display() {
		const { containerEl } = this;
		containerEl.empty();

		new Setting(containerEl)
			.setName("Auto-refresh widgets")
			.setDesc("Notes, buttons and capture options are set up in the Obsidian Widgets app.")
			.addDropdown((d) => d
				.addOptions({ save: "When the note is saved", typing: "While typing", off: "Only with the ⟳ button" })
				.setValue(this.plugin.settings.autoPush)
				.onChange(async (v) => {
					this.plugin.settings.autoPush = v as Settings["autoPush"];
					await this.plugin.saveSettings();
				}));
	}
}
