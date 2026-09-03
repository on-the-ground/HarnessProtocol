/** NDJSON bridge between the Kotlin adapter and the official Gemini CLI SDK. */

import { randomUUID } from "node:crypto";
import { createInterface } from "node:readline";
import { pathToFileURL } from "node:url";

const sessions = new Map();
const executions = new Map();
let sdkPromise;

export function sdkSpecifier() {
  const configured = process.env.GEMINI_CLI_SDK_MODULE ?? "@google/gemini-cli-sdk";
  return /^[A-Za-z]:[\\/]/.test(configured)
    ? pathToFileURL(configured).href
    : configured;
}

async function loadSdk() {
  sdkPromise ??= import(sdkSpecifier()).catch((error) => {
    throw new Error(
      `Unable to load Gemini CLI SDK from ${sdkSpecifier()}. ` +
        "The SDK is currently built from the Gemini CLI monorepo; set " +
        "GEMINI_CLI_SDK_MODULE to its packages/sdk/dist/index.js. " +
        `Original error: ${error.message}`,
    );
  });
  return sdkPromise;
}

function send(message) {
  process.stdout.write(`${JSON.stringify(message, jsonReplacer)}\n`);
}

function respond(id, result) {
  send({ kind: "response", id, result: result ?? {} });
}

function reject(id, error) {
  send({
    kind: "response",
    id,
    error: { type: error?.constructor?.name ?? "Error", message: String(error?.message ?? error) },
  });
}

function emit(executionId, payload) {
  send({ kind: "event", executionId, payload });
}

export function agentOptions(spec, sdk) {
  const options = {
    skills: (spec.skills ?? []).map((skill) => sdk.skillDir(skill.path)),
  };
  // `null` means "provider default" and must not become an explicit empty string.
  if (spec.instructions !== undefined && spec.instructions !== null) options.instructions = spec.instructions;
  if (spec.model) options.model = spec.model;
  if (spec.workingDirectory) options.cwd = spec.workingDirectory;
  return options;
}

async function createSession(spec) {
  const sdk = await loadSdk();
  const agent = new sdk.GeminiCliAgent(agentOptions(spec, sdk));
  const session = agent.session();
  await session.initialize();
  sessions.set(session.id, { agent, session, spec });
  return { sessionId: session.id };
}

async function resumeSession(params) {
  const sdk = await loadSdk();
  const sessionId = requiredString(params, "sessionId");
  const spec = requiredObject(params, "spec");
  const agent = new sdk.GeminiCliAgent(agentOptions(spec, sdk));
  const session = await agent.resumeSession(sessionId);
  await session.initialize();
  sessions.set(sessionId, { agent, session, spec });
  return { sessionId };
}

function startExecution(params) {
  const sessionId = requiredString(params, "sessionId");
  const holder = sessions.get(sessionId);
  if (!holder) throw new Error(`session ${JSON.stringify(sessionId)} has not been created or resumed`);
  const input = requiredObject(params, "input");
  if (input.type !== "text") throw new Error(`unsupported input type: ${JSON.stringify(input.type)}`);

  const executionId = randomUUID();
  const controller = new AbortController();
  const running = { controller, promise: undefined, sessionId };
  executions.set(executionId, running);
  const prompt = activationPrompt(holder.spec, requiredString(input, "text"));
  running.promise = streamExecution(executionId, holder.session, prompt, controller)
    .finally(() => executions.delete(executionId));
  return { executionId };
}

async function streamExecution(executionId, session, text, controller) {
  emit(executionId, { type: "execution_started" });
  let failed = false;
  let cancelled = false;
  try {
    for await (const event of session.sendStream(text, controller.signal)) {
      emit(executionId, event);
      if (event.type === "error" || event.type === "invalid_stream" || event.type === "agent_execution_blocked") {
        failed = true;
      }
      if (event.type === "user_cancelled") cancelled = true;
    }
    if (controller.signal.aborted || cancelled) {
      emit(executionId, { type: "execution_cancelled" });
    } else if (failed) {
      emit(executionId, { type: "execution_failed", value: { message: "Gemini CLI execution failed" } });
    } else {
      emit(executionId, { type: "execution_completed" });
    }
  } catch (error) {
    if (controller.signal.aborted) {
      emit(executionId, { type: "execution_cancelled" });
    } else {
      emit(executionId, {
        type: "execution_failed",
        value: { message: String(error?.message ?? error), errorType: error?.constructor?.name },
      });
    }
  }
}

async function dispatch(method, params) {
  switch (method) {
    case "create_session":
      return createSession(params);
    case "resume_session":
      return resumeSession(params);
    case "start_execution":
      return startExecution(params);
    case "release_session": {
      const sessionId = requiredString(params, "sessionId");
      for (const running of executions.values()) {
        if (running.sessionId === sessionId) running.controller.abort();
      }
      sessions.delete(sessionId);
      return {};
    }
    case "cancel_execution": {
      const executionId = requiredString(params, "executionId");
      executions.get(executionId)?.controller.abort();
      return {};
    }
    default:
      throw new Error(`unknown bridge method: ${method}`);
  }
}

function requiredString(value, key) {
  const result = value?.[key];
  if (typeof result !== "string" || result.length === 0) {
    throw new Error(`${JSON.stringify(key)} must be a non-empty string`);
  }
  return result;
}

function requiredObject(value, key) {
  const result = value?.[key];
  if (!result || typeof result !== "object" || Array.isArray(result)) {
    throw new Error(`${JSON.stringify(key)} must be an object`);
  }
  return result;
}

function jsonReplacer(_key, value) {
  if (typeof value === "bigint") return value.toString();
  if (value instanceof Error) {
    return { type: value.constructor.name, message: value.message, stack: value.stack };
  }
  return value;
}

export function activationPrompt(spec, text) {
  // Provider activation envelope: Gemini CLI activates a loaded skill through a
  // `$name` mention. The user's text itself is not altered.
  const skillMentions = (spec.skills ?? []).map((skill) => `$${skill.name}`).join(" ");
  return skillMentions ? `${skillMentions}\n\n${text}` : text;
}

if (process.env.HARNESS_GEMINI_BRIDGE_LIBRARY !== "1") {
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of lines) {
  if (!line.trim()) continue;
  let request;
  try {
    request = JSON.parse(line);
    if (request.kind !== "request") continue;
    respond(request.id, await dispatch(requiredString(request, "method"), request.params ?? {}));
  } catch (error) {
    if (request?.id !== undefined) reject(request.id, error);
    else process.stderr.write(`bridge input error: ${String(error?.message ?? error)}\n`);
  }
}

for (const execution of executions.values()) execution.controller.abort();
}
