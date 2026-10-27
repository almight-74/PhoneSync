import { test } from "node:test";
import assert from "node:assert/strict";
import {
	appendToContent, cleanTaskText, hashText, noteToRows, replaceIfUnchanged, prependToContent, safeFileName, toggleInContent, toggleTaskLine, uniquePath,
} from "../src/core.ts";

test("toggleTaskLine flips both ways and ignores non-tasks", () => {
	assert.equal(toggleTaskLine("- [ ] a"), "- [x] a");
	assert.equal(toggleTaskLine("  * [x] b"), "  * [ ] b");
	assert.equal(toggleTaskLine("1. [ ] c"), "1. [x] c");
	assert.equal(toggleTaskLine("- plain"), null);
});

test("toggleInContent uses line, then relocates by text if the file shifted", () => {
	const doc = "# T\n- [ ] one\n- [ ] two";
	assert.equal(toggleInContent(doc, 2, "two"), "# T\n- [ ] one\n- [x] two");
	const shifted = "# T\nnew line\n- [ ] one\n- [ ] two";
	assert.equal(toggleInContent(shifted, 2, "two"), "# T\nnew line\n- [ ] one\n- [x] two");
	assert.equal(toggleInContent(doc, 1, "missing"), null);
});

test("cleanTaskText strips inline fields and block ids", () => {
	assert.equal(cleanTaskText("- [ ] Call Sara {{dateDue:: 2026-10-23}} ^abc1"), "Call Sara");
});

test("appendToContent: end of file and under a heading", () => {
	assert.equal(appendToContent("", "x"), "x\n");
	assert.equal(appendToContent("a", "x"), "a\nx\n");
	const doc = "# Inbox\n- a\n\n# Done\n- b\n";
	assert.equal(appendToContent(doc, "- new", "Inbox"), "# Inbox\n- a\n- new\n\n# Done\n- b\n");
	assert.equal(appendToContent("a\n", "- new", "Later"), "a\n\n## Later\n- new\n");
});

test("noteToRows skips frontmatter and classifies lines", () => {
	const rows = noteToRows("---\ntag: x\n---\n# Head\n- [x] done\n- item\n\ntext", "n.md");
	assert.deepEqual(rows, [
		{ k: "h", s: "Head" },
		{ k: "t", s: "done", done: true, f: "n.md", l: 4 },
		{ k: "x", s: "• item" },
		{ k: "x", s: "text" },
	]);
});

test("prependToContent: after frontmatter, under a heading, or new heading on top", () => {
	assert.equal(prependToContent("", "x"), "x\n");
	assert.equal(prependToContent("a\nb", "x"), "x\na\nb");
	assert.equal(prependToContent("---\nt: 1\n---\na", "x"), "---\nt: 1\n---\nx\na");
	assert.equal(prependToContent("# Log\n- old", "- new", "Log"), "# Log\n- new\n- old");
	assert.equal(prependToContent("body", "- new", "Log"), "## Log\n- new\n\nbody");
});

test("safeFileName strips forbidden characters", () => {
	assert.equal(safeFileName("a/b: c?"), "a-b- c-");
	assert.equal(safeFileName("   "), "Untitled");
});

test("uniquePath adds a counter when the name is taken", () => {
	const taken = new Set(["Inbox/Idea.md", "Inbox/Idea 1.md"]);
	assert.equal(uniquePath("Inbox/", "Idea", (p) => taken.has(p)), "Inbox/Idea 2.md");
	assert.equal(uniquePath("", "New", () => false), "New.md");
});

test("replaceIfUnchanged only overwrites the version the editor started from", () => {
	const base = hashText("a\nb");
	assert.equal(replaceIfUnchanged("a\nb", base, "a\nb\nc"), "a\nb\nc");
	assert.equal(replaceIfUnchanged("a\nb\nchanged elsewhere", base, "a\nb\nc"), null);
	assert.notEqual(hashText("x"), hashText("y"));
});
