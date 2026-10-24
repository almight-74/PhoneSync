import { App, MarkdownView, Notice, Plugin, PluginSettingTab, Setting, TFile, debounce, moment, normalizePath } from "obsidian";
import { Snapshot, appendToContent, noteToRows, prependToContent, toggleInContent, uniquePath } from "./core";

interface Settings {
	pinned: string[];    // note paths, or "@daily" for today's daily note
	appendTarget: string;
	autoPush: "save" | "typing" | "off";
	debug: boolean;      // show a notice on every push
}

const DEFAULTS: Settings = { pinned: [], appendTarget: "@daily", autoPush: "save", debug: false };
const BRIDGE = "obsidianwidget://";

export default class WidgetBridge extends Plugin {
	settings: Settings = DEFAULTS;

	private pending = false;
	private lastSent = new Map<string, string>(); // key -> content signature, to skip unchanged notes

	// Android blocks activity starts from a backgrounded app, so pushes only go out while
	// Obsidian is visible; one that comes due in the background waits for the next resume.
	private schedulePush = debounce(() => {
		if (this.settings.autoPush === "off") return;
		if (document.visibilityState === "visible") this.pushAll(false);
		else this.pending = true;
	}, 700, true);

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
		}));
		this.registerDomEvent(document, "visibilitychange", () => {
			if (document.visibilityState === "visible" && this.pending) this.pushAll(false);
		});
	}

	async saveSettings() {
		await this.saveData(this.settings);
	}

	async pin(path: string) {
		this.settings.pinned.push(path);
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
				case "append": await this.append(p.file || this.settings.appendTarget, p.text ?? "", p.heading); break;
				case "capture": await this.capture(p); break;
				case "open": await this.open(p.file); break;
				case "command": this.runCommand(p.id); break;
				case "push": await this.pushAll(); break;
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
			const file = await this.ensureFile(this.resolvePath(p.file || this.settings.appendTarget));
			await this.app.vault.process(file, (data) => prependToContent(data, text, p.heading || undefined));
			await this.pushAll();
			return;
		}
		await this.append(p.file || this.settings.appendTarget, text, p.heading || undefined);
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

	// ---------- snapshots ----------

	/** `force` re-sends notes even if they did not change since the last push. */
	async pushAll(force = true) {
		this.pending = false;
		for (const { key, path } of this.resolvedPinned()) {
			const file = this.app.vault.getAbstractFileByPath(path);
			const content = file instanceof TFile ? this.liveText(file) ?? await this.app.vault.cachedRead(file) : "";
			const rows = noteToRows(content, key);
			const sig = (file?.path ?? "") + JSON.stringify(rows);
			if (!force && this.lastSent.get(key) === sig) continue;
			this.lastSent.set(key, sig);
			const snap: Snapshot = {
				key,
				title: file instanceof TFile ? file.basename : key,
				vault: this.app.vault.getName(),
				open: `obsidian://widget-bridge?do=open&file=${encodeURIComponent(key)}`,
				updated: Date.now(),
				rows,
			};
			if (this.settings.debug) new Notice(`Widget Bridge: sent ${key}`);
			this.send(`snapshot?d=${encodeURIComponent(JSON.stringify(snap))}`);
		}
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

class BridgeSettingTab extends PluginSettingTab {
	constructor(app: App, private plugin: WidgetBridge) {
		super(app, plugin);
	}

	display() {
		const { containerEl } = this;
		containerEl.empty();

		new Setting(containerEl)
			.setName("Notes shown in widgets")
			.setDesc('One path per line. Use "@daily" for today\'s daily note.')
			.addTextArea((t) => t
				.setValue(this.plugin.settings.pinned.join("\n"))
				.onChange(async (v) => {
					this.plugin.settings.pinned = v.split("\n").map((s) => s.trim()).filter(Boolean);
					await this.plugin.saveSettings();
				}));

		new Setting(containerEl)
			.setName("Default append target")
			.setDesc("Used by Quick append when no note is given.")
			.addText((t) => t
				.setValue(this.plugin.settings.appendTarget)
				.onChange(async (v) => {
					this.plugin.settings.appendTarget = v.trim() || "@daily";
					await this.plugin.saveSettings();
				}));

		new Setting(containerEl)
			.setName("Auto-refresh")
			.setDesc("When to send changed notes to the widgets. Sending only works while Obsidian is open; " +
				"anything missed goes out the next time you open it.")
			.addDropdown((d) => d
				.addOptions({ save: "When the note is saved", typing: "While typing (more frequent)", off: "Off (⟳ button only)" })
				.setValue(this.plugin.settings.autoPush)
				.onChange(async (v) => {
					this.plugin.settings.autoPush = v as Settings["autoPush"];
					await this.plugin.saveSettings();
				}));

		new Setting(containerEl)
			.setName("Debug: notice on each push")
			.setDesc("Shows a notice whenever notes are sent to the widgets.")
			.addToggle((t) => t
				.setValue(this.plugin.settings.debug)
				.onChange(async (v) => {
					this.plugin.settings.debug = v;
					await this.plugin.saveSettings();
				}));

		new Setting(containerEl)
			.setName("Refresh widgets")
			.addButton((b) => b.setButtonText("Push now").onClick(() => this.plugin.pushAll()));
	}
}
