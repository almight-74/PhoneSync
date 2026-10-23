import { App, Notice, Plugin, PluginSettingTab, Setting, TFile, debounce, moment, normalizePath } from "obsidian";
import { Snapshot, appendToContent, noteToRows, toggleInContent } from "./core";

interface Settings {
	pinned: string[];    // note paths, or "@daily" for today's daily note
	appendTarget: string;
}

const DEFAULTS: Settings = { pinned: [], appendTarget: "@daily" };
const BRIDGE = "obsidianwidget://";

export default class WidgetBridge extends Plugin {
	settings: Settings = DEFAULTS;

	private schedulePush = debounce(() => this.pushAll(), 2000, true);

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
		this.registerEvent(this.app.vault.on("rename", (f, old) => {
			const i = this.settings.pinned.indexOf(old);
			if (i >= 0) { this.settings.pinned[i] = f.path; this.saveSettings(); this.schedulePush(); }
		}));
		// Leaving Obsidian is the best moment to hand the widget fresh content.
		this.registerDomEvent(document, "visibilitychange", () => {
			if (document.visibilityState === "hidden") this.pushAll();
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

	// ---------- obsidian://widget-bridge?action=... ----------

	async handle(p: Record<string, string>) {
		try {
			switch (p.action) {
				case "toggle": await this.toggle(p.file, Number(p.line), p.match); break;
				case "append": await this.append(p.file || this.settings.appendTarget, p.text ?? "", p.heading); break;
				case "open": await this.open(p.file); break;
				case "command": this.runCommand(p.id); break;
				case "push": await this.pushAll(); break;
				default: new Notice(`Widget Bridge: unknown action "${p.action}"`);
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
		const resolved = this.resolvePath(path);
		let file = this.app.vault.getAbstractFileByPath(resolved);
		if (!file) {
			const dir = resolved.split("/").slice(0, -1).join("/");
			if (dir && !this.app.vault.getAbstractFileByPath(dir)) await this.app.vault.createFolder(dir);
			file = await this.app.vault.create(resolved, "");
		}
		await this.app.vault.process(this.fileOrThrow(resolved), (data) => appendToContent(data, text, heading));
		await this.pushAll();
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

	async pushAll() {
		for (const { key, path } of this.resolvedPinned()) {
			const file = this.app.vault.getAbstractFileByPath(path);
			const content = file instanceof TFile ? await this.app.vault.cachedRead(file) : "";
			const snap: Snapshot = {
				key,
				title: file instanceof TFile ? file.basename : key,
				vault: this.app.vault.getName(),
				open: `obsidian://widget-bridge?action=open&file=${encodeURIComponent(key)}`,
				updated: Date.now(),
				rows: noteToRows(content, key),
			};
			this.send(`snapshot?d=${encodeURIComponent(JSON.stringify(snap))}`);
		}
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
			.setName("Refresh widgets")
			.addButton((b) => b.setButtonText("Push now").onClick(() => this.plugin.pushAll()));
	}
}
