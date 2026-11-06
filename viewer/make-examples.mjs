// Bundles pairs of runs into examples/examples.js so the viewer can open them from file:// without a server.
//
//   node viewer/make-examples.mjs --batches=results/final/final-qwen \
//     --pair="Order 1042, the waste=final-qwen-C1-endpoint-r1,final-qwen-C1-workflow-r1"
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const option = (name) => args.filter((a) => a.startsWith(`--${name}=`)).map((a) => a.slice(name.length + 3));
const batches = option("batches").flatMap((b) => b.split(",")).filter(Boolean);
const pairs = option("pair");
if (!batches.length || !pairs.length) {
  console.error('Usage: node viewer/make-examples.mjs --batches=dir[,dir] --pair="Name=leftRunId,rightRunId" ...');
  process.exit(1);
}

const lines = (file) => existsSync(file)
  ? readFileSync(file, "utf8").split(/\r?\n/).filter((l) => l.trim()).map((l) => JSON.parse(l))
  : [];
const runs = new Map();
const verifications = new Map();
for (const dir of batches) {
  for (const run of lines(join(dir, "runs.jsonl"))) runs.set(run.runId, run);
  for (const v of lines(join(dir, "verification.jsonl"))) verifications.set(v.runId, v);
}

const side = (runId) => {
  const run = runs.get(runId);
  if (!run) throw new Error(`No run ${runId} in ${batches.join(", ")}`);
  return { runs: [run], verifications: verifications.has(runId) ? [verifications.get(runId)] : [] };
};
const examples = pairs.map((pair) => {
  const at = pair.indexOf("=");
  const [left, right] = pair.slice(at + 1).split(",");
  return { name: pair.slice(0, at), left: side(left), right: side(right) };
});

const out = join(dirname(fileURLToPath(import.meta.url)), "examples", "examples.js");
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, "window.VIEWER_EXAMPLES = " + JSON.stringify(examples) + ";\n");
console.log(`Wrote ${examples.length} example${examples.length === 1 ? "" : "s"} to ${out}`);
