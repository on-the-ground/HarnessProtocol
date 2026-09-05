import { test } from "node:test";
import assert from "node:assert/strict";

process.env.HARNESS_GEMINI_BRIDGE_LIBRARY = "1";
const { agentOptions, sdkSpecifier, taskPrompt } = await import("../gemini_cli_sdk_bridge.mjs");

const sdk = { skillDir: (path) => ({ dir: path }) };

test("agentOptions omits instructions when null (provider default)", () => {
  assert.equal("instructions" in agentOptions({ instructions: null }, sdk), false);
  assert.equal("instructions" in agentOptions({}, sdk), false);
});

test("agentOptions sends an explicit empty string", () => {
  assert.equal(agentOptions({ instructions: "" }, sdk).instructions, "");
});

test("agentOptions maps model, cwd and skills", () => {
  const options = agentOptions({ model: "m", workingDirectory: "/w", skills: [{ name: "s", path: "/s" }] }, sdk);
  assert.deepEqual(options, { skills: [{ dir: "/s" }], model: "m", cwd: "/w" });
});

test("taskPrompt keeps caller input exact because active bodies are system instructions", () => {
  assert.equal(taskPrompt({ skills: [] }, "do it"), "do it");
  assert.equal(taskPrompt({ skills: [{ name: "s", activate: true }] }, "do it"), "do it");
});

test("sdkSpecifier turns a Windows path into a file URL", () => {
  process.env.GEMINI_CLI_SDK_MODULE = "C:\\src\\gemini-cli\\packages\\sdk\\dist\\index.js";
  const expected = process.platform === "win32" ? "file:///C:/" : "file://";
  assert.ok(sdkSpecifier().startsWith(expected), sdkSpecifier());
  delete process.env.GEMINI_CLI_SDK_MODULE;
  assert.equal(sdkSpecifier(), "@google/gemini-cli-sdk");
});
