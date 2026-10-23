// Pure logic shared by the plugin and the tests (no Obsidian imports).

export type Row =
	| { k: "h"; s: string }                                        // heading / day header
	| { k: "x"; s: string }                                        // plain text line
	| { k: "t"; s: string; done: boolean; f: string; l: number }; // task

export interface Snapshot {
	key: string;      // note path
	title: string;
	vault: string;
	open: string;     // obsidian:// URI used when the header is tapped
	updated: number;  // epoch ms
	rows: Row[];
}

export const MAX_ROWS = 200;

const TASK_RE = /^(\s*(?:[-*+]|\d+[.)])\s+\[)(.)(\].*)$/;
// Inline field blocks some plugins add to tasks, e.g. `{{dateDue:: 2026-10-23}}`.
const FIELD_RE = /\{\{\s*([A-Za-z]+)\s*::\s*([^}]*?)\s*\}\}/g;

export function isTaskLine(line: string): boolean {
	return TASK_RE.test(line);
}

/** Flip `- [ ]` <-> `- [x]`. Returns null if the line is not a task. */
export function toggleTaskLine(line: string): string | null {
	const m = TASK_RE.exec(line);
	if (!m) return null;
	return m[1] + (m[2] === " " ? "x" : " ") + m[3];
}

/**
 * Toggle a task in `content`. `line` is the expected 0-based line; `match` is the
 * task text the widget showed, used to re-locate the task if the file shifted.
 */
export function toggleInContent(content: string, line: number, match?: string): string | null {
	const lines = content.split("\n");
	let idx = line;
	const ok = (i: number) => i >= 0 && i < lines.length && isTaskLine(lines[i]) &&
		(!match || cleanTaskText(lines[i]) === match);
	if (!ok(idx)) {
		if (!match) return null;
		idx = lines.findIndex((_, i) => ok(i));
		if (idx < 0) return null;
	}
	lines[idx] = toggleTaskLine(lines[idx])!;
	return lines.join("\n");
}

/** Append `text` to the end of the file, or to the end of the section under `heading`. */
export function appendToContent(content: string, text: string, heading?: string): string {
	if (heading) {
		const lines = content.split("\n");
		const h = lines.findIndex((l) => /^#{1,6}\s/.test(l) && l.replace(/^#{1,6}\s+/, "").trim() === heading.trim());
		if (h >= 0) {
			const level = lines[h].match(/^#+/)![0].length;
			let end = lines.length;
			for (let i = h + 1; i < lines.length; i++) {
				const m = lines[i].match(/^(#{1,6})\s/);
				if (m && m[1].length <= level) { end = i; break; }
			}
			while (end > h + 1 && lines[end - 1].trim() === "") end--;
			lines.splice(end, 0, text);
			return lines.join("\n");
		}
		const sep = content === "" || content.endsWith("\n") ? "" : "\n";
		return `${content}${sep}\n## ${heading}\n${text}\n`;
	}
	if (content === "") return text + "\n";
	return content + (content.endsWith("\n") ? "" : "\n") + text + "\n";
}

/** Task text without checkbox, `{{field:: value}}` blocks and block ids. */
export function cleanTaskText(line: string): string {
	const m = TASK_RE.exec(line);
	const body = m ? m[3].slice(1) : line;
	return body.replace(FIELD_RE, "").replace(/\s\^[\w-]+\s*$/, "").replace(/\s+/g, " ").trim();
}

/** Markdown note -> widget rows. Frontmatter is skipped. */
export function noteToRows(content: string, file: string): Row[] {
	const lines = content.split("\n");
	let start = 0;
	if (lines[0]?.trim() === "---") {
		const end = lines.findIndex((l, i) => i > 0 && l.trim() === "---");
		if (end > 0) start = end + 1;
	}
	const rows: Row[] = [];
	for (let i = start; i < lines.length && rows.length < MAX_ROWS; i++) {
		const line = lines[i];
		const tm = TASK_RE.exec(line);
		if (tm) rows.push({ k: "t", s: cleanTaskText(line), done: tm[2] !== " ", f: file, l: i });
		else if (/^#{1,6}\s/.test(line)) rows.push({ k: "h", s: line.replace(/^#{1,6}\s+/, "") });
		else if (line.trim() !== "") rows.push({ k: "x", s: line.replace(/^(\s*)[-*+]\s/, "$1• ") });
	}
	return rows;
}
