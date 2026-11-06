// Loads two run records and plays them side by side. Everything from a record is written with textContent,
// so nothing in a model's reply or a tool result can become markup.
(() => {
  "use strict";

  const panes = { a: pane("a"), b: pane("b") };
  let step = 0;
  let timer = null;

  function pane(side) {
    const root = document.getElementById("pane-" + side);
    return {
      side,
      root,
      runs: [],
      verifications: new Map(),
      run: null,
      filter: root.querySelector("[data-role=filter]"),
      select: root.querySelector("[data-role=run]"),
      summary: root.querySelector("[data-role=summary]"),
      turns: root.querySelector("[data-role=turns]"),
      verdict: root.querySelector("[data-role=verdict]"),
    };
  }

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
      if (key === "class") node.className = value;
      else node.setAttribute(key, value);
    }
    for (const child of children) {
      if (child == null) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function parseLines(text) {
    const trimmed = text.trim();
    if (trimmed.startsWith("[")) return JSON.parse(trimmed);
    return trimmed.split(/\r?\n/).filter((line) => line.trim()).map((line) => JSON.parse(line));
  }

  function latestByRunId(records) {
    const latest = new Map();
    for (const record of records) latest.set(record.runId, record);
    return [...latest.values()];
  }

  function load(p, runs, verifications) {
    if (runs) {
      p.runs = latestByRunId(runs).sort((x, y) => label(x).localeCompare(label(y)));
    }
    if (verifications) {
      p.verifications = new Map(verifications.map((v) => [v.runId, v]));
    }
    fillSelect(p);
  }

  function label(run) {
    return `${run.taskId} · ${run.arm} · ${run.model} · r${run.repetition}`;
  }

  function fillSelect(p, keep) {
    const words = p.filter.value.toLowerCase().split(/\s+/).filter(Boolean);
    const shown = p.runs.filter((run) => {
      const text = (label(run) + " " + run.runId).toLowerCase();
      return words.every((word) => text.includes(word));
    });
    p.select.replaceChildren(...shown.map((run) => el("option", { value: run.runId }, label(run))));
    const wanted = keep && shown.find((run) => run.runId === keep) ? keep : shown[0] && shown[0].runId;
    if (wanted) p.select.value = wanted;
    choose(p, wanted);
  }

  function choose(p, runId) {
    p.run = p.runs.find((run) => run.runId === runId) || null;
    renderPane(p);
    renderChart();
    applyStep();
  }

  function modelCalls(run) {
    return run ? run.turns.length : 0;
  }

  function maxSteps() {
    return Math.max(modelCalls(panes.a.run), modelCalls(panes.b.run));
  }

  function fmt(n) {
    return n == null ? "–" : Number(n).toLocaleString("en-US");
  }

  function inputTokens(turn) {
    const u = turn.usage || {};
    return (u.inputTokens || 0) + (u.cacheReadTokens || 0) + (u.cacheWriteTokens || 0);
  }

  function renderPane(p) {
    p.summary.replaceChildren();
    p.turns.replaceChildren();
    p.verdict.replaceChildren();
    document.getElementById("legend-" + p.side).textContent = p.run ? label(p.run) : (p.side === "a" ? "Left run" : "Right run");
    const run = p.run;
    if (!run) {
      p.summary.append(el("p", { class: "hint" }, "No run loaded."));
      return;
    }
    const v = p.verifications.get(run.runId);
    const t = run.totals || {};
    const totalIn = run.turns.reduce((sum, turn) => sum + inputTokens(turn), 0);
    p.summary.append(
      el("h3", {}, `${run.taskId} in the ${run.arm} arm`),
      el("div", { class: "hint" }, `${run.runId} · ${run.model} · stopped: ${run.stopReason}`
        + (v ? (v.valid ? (v.success ? " · succeeded" : " · failed") : " · invalid") : "")),
      el("div", { class: "facts" },
        fact(fmt(t.modelCalls), "model calls"),
        fact(fmt(t.mcpCalls), "MCP tool calls"),
        fact(fmt(t.backendCalls), "backend calls"),
        fact(fmt(totalIn), "input tokens"),
        fact(fmt(t.outputTokens), "output tokens"),
        fact(fmt(t.resultChars), "tool result characters"),
        fact(((run.durationMs || 0) / 1000).toFixed(1) + " s", "duration")));

    const messages = run.customerMessages || [];
    const backend = groupBackend(run.backendCalls || []);
    let segment = -1;
    run.turns.forEach((turn, i) => {
      if (turn.segment !== segment) {
        segment = turn.segment;
        for (const change of (run.worldChanges || []).filter((w) => w.beforeSegment === segment)) {
          p.turns.append(el("div", { class: "world turn-extra", "data-step": i }, "Before the next message: "
            + describeChange(change.change)));
        }
        p.turns.append(el("div", { class: "customer turn-extra", "data-step": i },
          el("b", {}, `Customer, message ${segment + 1}: `), messages[segment] || "(not in this record)"));
      }
      p.turns.append(renderTurn(turn, i, backend));
    });
    renderVerdict(p, run, v);
  }

  function fact(value, name) {
    return el("div", { class: "fact" }, el("b", {}, value), el("span", {}, name));
  }

  function describeChange(change) {
    if (!change) return "";
    if (change.advanceClockMinutes) return `the store's clock moves ${change.advanceClockMinutes} minutes ahead`;
    if (change.setStock) {
      const s = change.setStock;
      return `${s.sku} stock at ${s.warehouseId} is set to ${s.available}`;
    }
    return JSON.stringify(change);
  }

  function groupBackend(calls) {
    const byTool = new Map();
    for (const call of calls) {
      const list = byTool.get(call.toolCall) || [];
      list.push(call);
      byTool.set(call.toolCall, list);
    }
    return byTool;
  }

  function renderTurn(turn, i, backend) {
    const u = turn.usage || {};
    const head = el("div", { class: "turn-head" },
      el("span", { class: "n" }, `Model call ${turn.index}`),
      el("span", {}, `${fmt(inputTokens(turn))} in · ${fmt(u.outputTokens)} out`),
      el("span", {}, ((turn.latencyMs || 0) / 1000).toFixed(1) + " s"),
      turn.retries ? el("span", { class: "badge error" }, `${turn.retries} retries`) : null);
    const box = el("div", { class: "turn", "data-step": i }, head);
    if (turn.text) box.append(el("div", { class: "text" }, turn.text));
    if (turn.reminded) {
      box.append(el("div", {}, el("span", { class: "badge reminder" }, "Plain-text reply: the runner sent the reminder")));
    }
    for (const call of turn.toolCalls || []) box.append(renderCall(call, backend.get(call.index) || []));
    return box;
  }

  function renderCall(call, backendCalls) {
    const badges = [];
    if (call.kind === "final_answer") badges.push(el("span", { class: "badge answer" }, "answer card"));
    if (call.kind === "unknown_tool") badges.push(el("span", { class: "badge error" }, "no such tool"));
    if (call.kind === "bad_arguments") badges.push(el("span", { class: "badge error" }, "bad arguments"));
    if (call.isError && call.kind === "mcp") badges.push(el("span", { class: "badge error" }, "tool error"));
    const box = el("div", { class: "call" },
      el("div", {}, el("span", { class: "call-name" }, call.name), " ", ...badges,
        el("span", { class: "hint" }, ` ${fmt(call.resultChars)} chars · ${call.latencyMs || 0} ms`)),
      el("pre", {}, pretty(call.arguments)));
    if (call.kind !== "final_answer") {
      const result = el("details", {}, el("summary", {}, "Result"), el("pre", {}, pretty(call.resultText)));
      box.append(result);
    }
    if (backendCalls.length) {
      box.append(el("details", {}, el("summary", {}, `${backendCalls.length} backend call`
        + (backendCalls.length === 1 ? "" : "s")),
        el("ul", { class: "backend" }, ...backendCalls.map((b) =>
          el("li", {}, `${b.method} ${b.path}${b.query ? "?" + b.query : ""} → ${b.status}`)))));
    }
    return box;
  }

  function pretty(text) {
    if (text == null) return "";
    try {
      return JSON.stringify(JSON.parse(text), null, 2);
    } catch (e) {
      return String(text);
    }
  }

  function renderVerdict(p, run, v) {
    const cards = run.cards || [];
    cards.forEach((card, i) => {
      p.verdict.append(el("h4", {}, `Answer to message ${i + 1}`));
      if (!card) {
        p.verdict.append(el("p", { class: "no" }, "No answer card."));
        return;
      }
      p.verdict.append(el("div", { class: "card-message" }, card.message || ""),
        el("div", { class: "hint" }, `outcome: ${card.outcome || "–"}`));
    });
    if (!v) return;
    p.verdict.append(el("h4", {}, v.valid ? (v.success ? "Verifier: succeeded" : "Verifier: failed") : "Verifier: invalid run"));
    if (v.checks && v.checks.length) {
      p.verdict.append(el("ul", {}, ...v.checks.map((c) => el("li", { class: c.passed ? "ok" : "no" },
        `${c.passed ? "✓" : "✗"} ${c.name}${c.required ? "" : " (soft)"}`, c.detail ? ` — ${c.detail}` : ""))));
    }
    if (v.failures && v.failures.length) {
      p.verdict.append(el("ul", {}, ...v.failures.map((f) => el("li", { class: f.severity === "task" ? "no" : "" },
        `${f.failureClass} (${f.severity === "task" ? "fails the task" : "recorded"}): ${f.detail}`))));
    }
  }

  function renderChart() {
    const svg = document.getElementById("chart");
    svg.replaceChildren();
    const width = svg.clientWidth || 900;
    const height = svg.clientHeight || 190;
    svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
    const runs = [panes.a.run, panes.b.run];
    const steps = maxSteps();
    if (!steps) return;
    const max = Math.max(1, ...runs.flatMap((run) => (run ? run.turns.map(inputTokens) : [])));
    const left = 56;
    const bottom = height - 22;
    const top = 10;
    const slot = (width - left - 10) / steps;
    const bar = Math.max(2, Math.min(28, slot / 2 - 2));
    const ns = "http://www.w3.org/2000/svg";
    const line = document.createElementNS(ns, "line");
    line.setAttribute("x1", left); line.setAttribute("x2", width - 10);
    line.setAttribute("y1", bottom); line.setAttribute("y2", bottom);
    line.setAttribute("class", "axis");
    svg.append(line);
    for (const fraction of [0, 0.5, 1]) {
      const y = bottom - fraction * (bottom - top);
      const tick = document.createElementNS(ns, "text");
      tick.setAttribute("x", 4); tick.setAttribute("y", y + 4);
      tick.textContent = fmt(Math.round(max * fraction));
      svg.append(tick);
    }
    runs.forEach((run, r) => {
      if (!run) return;
      run.turns.forEach((turn, i) => {
        const h = (inputTokens(turn) / max) * (bottom - top);
        const rect = document.createElementNS(ns, "rect");
        rect.setAttribute("x", left + i * slot + (r === 0 ? slot / 2 - bar - 1 : slot / 2 + 1));
        rect.setAttribute("y", bottom - h);
        rect.setAttribute("width", bar);
        rect.setAttribute("height", Math.max(0, h));
        rect.setAttribute("class", "bar " + (r === 0 ? "a" : "b"));
        rect.setAttribute("data-step", i);
        const title = document.createElementNS(ns, "title");
        title.textContent = `${r === 0 ? "Left" : "Right"}, model call ${i + 1}: ${fmt(inputTokens(turn))} input tokens`;
        rect.append(title);
        svg.append(rect);
      });
    });
    for (let i = 0; i < steps; i += Math.max(1, Math.ceil(steps / 20))) {
      const label = document.createElementNS(ns, "text");
      label.setAttribute("x", left + i * slot + slot / 2 - 4);
      label.setAttribute("y", height - 6);
      label.textContent = String(i + 1);
      svg.append(label);
    }
  }

  function applyStep() {
    const steps = maxSteps();
    step = Math.min(step, steps);
    const showAll = step === 0;
    for (const p of Object.values(panes)) {
      p.root.querySelectorAll("[data-step]").forEach((node) => {
        const i = Number(node.getAttribute("data-step"));
        node.classList.toggle("hidden", !showAll && i >= step);
        node.classList.toggle("current", !showAll && i === step - 1 && node.classList.contains("turn"));
      });
      p.verdict.hidden = !showAll && step < modelCalls(p.run);
    }
    document.querySelectorAll("#chart .bar").forEach((rect) => {
      rect.classList.toggle("hidden", !showAll && Number(rect.getAttribute("data-step")) >= step);
    });
    document.getElementById("step-label").textContent = showAll
      ? `All ${steps} model calls`
      : `Model call ${step} of ${steps}`;
  }

  function setStep(next) {
    step = Math.max(0, Math.min(next, maxSteps()));
    applyStep();
    const current = document.querySelector(".turn.current");
    if (current) current.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }

  function stop() {
    clearInterval(timer);
    timer = null;
    document.getElementById("play").textContent = "▶ Play";
  }

  function play() {
    if (timer) return stop();
    if (step === 0 || step >= maxSteps()) setStep(1);
    document.getElementById("play").textContent = "❚❚ Pause";
    timer = setInterval(() => {
      if (step >= maxSteps()) return stop();
      setStep(step + 1);
    }, Number(document.getElementById("speed").value));
  }

  function pair(from) {
    const run = from.run;
    if (!run) return;
    const other = from === panes.a ? panes.b : panes.a;
    if (!other.runs.length) load(other, from.runs, [...from.verifications.values()]);
    const arm = run.arm === "endpoint" ? "workflow" : "endpoint";
    const match = other.runs.find((r) => r.taskId === run.taskId && r.arm === arm && r.model === run.model
      && r.repetition === run.repetition) || other.runs.find((r) => r.taskId === run.taskId && r.arm === arm);
    if (!match) return;
    other.filter.value = "";
    fillSelect(other, match.runId);
  }

  for (const p of Object.values(panes)) {
    p.root.querySelectorAll("input[type=file]").forEach((input) => {
      input.addEventListener("change", async () => {
        const file = input.files[0];
        if (!file) return;
        try {
          const records = parseLines(await file.text());
          if (input.dataset.kind === "runs") load(p, records, null);
          else load(p, null, records);
        } catch (e) {
          p.summary.replaceChildren(el("p", { class: "no" }, `Couldn't read ${file.name}: ${e.message}`));
        }
      });
    });
    p.filter.addEventListener("input", () => fillSelect(p, p.run && p.run.runId));
    p.select.addEventListener("change", () => choose(p, p.select.value));
    p.root.querySelector("[data-role=pair]").addEventListener("click", () => pair(p));
  }

  document.getElementById("first").addEventListener("click", () => { stop(); setStep(1); });
  document.getElementById("prev").addEventListener("click", () => { stop(); setStep(Math.max(1, step - 1)); });
  document.getElementById("next").addEventListener("click", () => { stop(); setStep(step === 0 ? 1 : step + 1); });
  document.getElementById("last").addEventListener("click", () => { stop(); setStep(0); });
  document.getElementById("play").addEventListener("click", play);
  window.addEventListener("resize", () => { renderChart(); applyStep(); });

  const examples = window.VIEWER_EXAMPLES || [];
  if (examples.length) {
    const box = document.getElementById("examples");
    const select = document.getElementById("example");
    select.replaceChildren(...examples.map((ex, i) => el("option", { value: i }, ex.name)));
    box.hidden = false;
    const loadExample = () => {
      const ex = examples[Number(select.value)];
      load(panes.a, ex.left.runs, ex.left.verifications);
      load(panes.b, ex.right.runs, ex.right.verifications);
      setStep(0);
    };
    document.getElementById("load-example").addEventListener("click", loadExample);
    loadExample();
  }
  applyStep();
})();
