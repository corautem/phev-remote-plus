// End-to-end check of phev-matter.mjs: start it like the bridge does, pair it with a Matter controller (matter.js'
// own, standing in for Google Home), switch the climate from the controller, and push the car's state back.
// Run: node test/pairing-test.mjs [path to phev-matter.mjs]   (exit code 0 = all good)

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import readline from "node:readline";
import { Environment, Logger } from "@matter/main";
import { GeneralCommissioning, OnOff } from "@matter/main/clusters";
import { CommissioningController } from "@project-chip/matter.js";

Logger.level = "error";
const script = process.argv[2] ?? new URL("../src/phev-matter.mjs", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "phev-matter-"));
const PORT = 5541, PASSCODE = 20202021, DISC = 3840;
const fail = (m) => { console.error("FAIL: " + m); cleanup(1); };
const messages = [];
const waiters = [];

const child = spawn(process.execPath, [script], { stdio: ["pipe", "pipe", "pipe"], env: { ...process.env, PHEV_MATTER_LOG: "error" } });
child.stderr.on("data", (d) => process.stderr.write("[device] " + d));
readline.createInterface({ input: child.stdout }).on("line", (line) => {
    const m = JSON.parse(line);
    messages.push(m);
    if (m.type === "log") console.log("[device] " + m.text);
    for (const w of [...waiters]) if (w.test(m)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(m); }
});
const waitFor = (test, ms = 20000, what = "message") => new Promise((resolve, reject) => {
    const found = messages.find(test);
    if (found) return resolve(found);
    const w = { test, resolve };
    waiters.push(w);
    setTimeout(() => reject(new Error("timed out waiting for " + what)), ms);
});
const tell = (m) => child.stdin.write(JSON.stringify(m) + "\n");

let controller;
async function cleanup(code) {
    try { await controller?.close(); } catch {}
    try { tell({ type: "stop" }); } catch {}
    setTimeout(() => { child.kill(); rmSync(dir, { recursive: true, force: true }); process.exit(code); }, 1500);
}

try {
    tell({
        type: "config", name: "Outlander", storage: join(dir, "device"), passcode: PASSCODE, discriminator: DISC, port: PORT,
        serial: "phev_test01", devices: { climate: "Klima", headlights: "Frontlys", parking: null },
    });
    const ready = await waitFor((m) => m.type === "ready", 20000, "ready");
    if (!ready.qr?.startsWith("MT:")) fail("no QR payload: " + JSON.stringify(ready));
    if (!/^\d{11}$/.test(ready.manual ?? "")) fail("bad manual code " + ready.manual);
    if (ready.commissioned) fail("fresh device says it is paired");
    if (!Array.isArray(ready.qrRows) || ready.qrRows.length < 21) fail("no QR rows");
    console.log(`ready: ${ready.qr} / ${ready.manual}`);

    Environment.default.vars.set("storage.path", join(dir, "controller"));
    controller = new CommissioningController({
        environment: { environment: Environment.default, id: "test-controller" },
        autoConnect: false,
        adminFabricLabel: "Test Home",
    });
    await controller.start();
    const nodeId = await controller.commissionNode({
        commissioning: { regulatoryLocation: GeneralCommissioning.RegulatoryLocationType.IndoorOutdoor, regulatoryCountryCode: "XX" },
        discovery: { knownAddress: { ip: "::1", port: PORT, type: "udp" }, identifierData: { longDiscriminator: DISC } },
        passcode: PASSCODE,
    });
    console.log("paired as node " + nodeId);
    const fabrics = await waitFor((m) => m.type === "fabrics" && m.commissioned && m.fabrics.length > 0, 20000, "fabrics");
    // The controller names its fabric a moment after commissioning; the bridge hears about it then.
    const named = await waitFor((m) => m.type === "fabrics" && m.fabrics.some((f) => f.label === "Test Home"), 10000, "fabric label").catch(() => null);
    console.log("fabrics: " + JSON.stringify((named ?? fabrics).fabrics));

    const node = await controller.getNode(nodeId);
    if (!node.isConnected) await node.events.initialized;
    const devices = node.getDevices();
    const aggregator = devices.find((d) => d.getChildEndpoints().length > 0) ?? devices[0];
    const children = aggregator.getChildEndpoints();
    console.log(`endpoints under the bridge: ${children.length}`);
    if (children.length !== 2) fail("expected climate + headlights, got " + children.length);
    const climate = children[0].getClusterClient(OnOff.Cluster);
    await climate.on();
    const cmd = await waitFor((m) => m.type === "command" && m.what === "climate", 10000, "command");
    if (cmd.on !== true) fail("command " + JSON.stringify(cmd));
    console.log("controller switched the climate on → " + JSON.stringify(cmd));

    // The bridge reports the car: climate didn't start after all → the controller sees it off again.
    tell({ type: "state", climate: false, headlights: false, parking: false, reachable: true });
    await new Promise((r) => setTimeout(r, 800));
    const now = await climate.getOnOffAttribute(false);
    if (now !== false) fail("state not pushed: " + now);
    console.log("state pushed back: climate off");
    console.log("PASS");
    await cleanup(0);
} catch (e) {
    fail(e?.stack ?? String(e));
}
