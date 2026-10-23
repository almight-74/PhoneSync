import esbuild from "esbuild";
import { copyFileSync, mkdirSync } from "fs";

mkdirSync("dist", { recursive: true });
copyFileSync("../manifest.json", "dist/manifest.json");

await esbuild.build({
	entryPoints: ["src/main.ts"],
	bundle: true,
	external: ["obsidian", "electron"],
	format: "cjs",
	target: "es2020",
	minify: true,
	outfile: "dist/main.js",
});
