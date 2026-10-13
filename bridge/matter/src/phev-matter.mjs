// PHEV Link bridge — the car as a Matter bridge, so Google Home (and Apple Home, Alexa, SmartThings…) can add it by
// scanning a code: no cloud, no Home Assistant. Started and driven by the bridge (Kotlin) over stdin/stdout, one JSON
// object per line:
//
//   bridge → here   {"type":"config", name, storage, passcode, discriminator, port, interface, serial, devices:{key: label|null}}
//                   {"type":"state", climate, headlights, parking, reachable}
//                   {"type":"reset"}      forget every pairing (then exit; the bridge starts us again)
//                   {"type":"stop"}
//   here → bridge   {"type":"ready", commissioned, qr, manual, qrRows, fabrics}   qrRows: the QR code as rows of 0/1
//                   {"type":"fabrics", commissioned, fabrics:[{index, label, vendorId}]}
//                   {"type":"command", what, on, by}   a controller switched something
//                   {"type":"log", level, text}
//
// matter.js' own log goes to stderr (the bridge copies warnings into its log).

import { Endpoint, Logger, ServerNode, VendorId } from "@matter/main";
import { BridgedDeviceBasicInformationServer } from "@matter/main/behaviors/bridged-device-basic-information";
import { OnOffLightDevice } from "@matter/main/devices/on-off-light";
import { OnOffPlugInUnitDevice } from "@matter/main/devices/on-off-plug-in-unit";
import { AggregatorEndpoint } from "@matter/main/endpoints/aggregator";
import { Environment } from "@matter/main";
import readline from "node:readline";
import qrcode from "qrcode-generator";

Logger.destinations.default.write = (text) => process.stderr.write(text + "\n");
Logger.format = "plain";
Logger.level = process.env.PHEV_MATTER_LOG ?? "notice";

const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");
const log = (text, level = "info") => send({ type: "log", level, text });

/** What can be shared, and as what kind of Matter device. Keys are also the endpoint ids (keep them stable). */
const KINDS = {
    climate: OnOffPlugInUnitDevice,
    headlights: OnOffLightDevice,
    parking: OnOffLightDevice,
};

let server = null;
let config = null;
const endpoints = new Map();
let lastState = {};
let applying = false;

function fabricsOf(node) {
    const fabrics = node.state.commissioning?.fabrics ?? {};
    return Object.values(fabrics).map((f) => ({ index: f.fabricIndex, label: f.label ?? "", vendorId: f.rootVendorId }));
}

function report(kind) {
    const fabrics = fabricsOf(server);
    const commissioned = server.lifecycle.isCommissioned;
    if (kind === "ready") {
        const codes = server.state.commissioning.pairingCodes;
        send({ type: "ready", commissioned, qr: codes.qrPairingCode, manual: codes.manualPairingCode, qrRows: qrRows(codes.qrPairingCode), fabrics });
    } else {
        send({ type: "fabrics", commissioned, fabrics });
    }
}

/** The QR code as rows of "0"/"1" (the app draws it; no QR library needed there). */
function qrRows(text) {
    const q = qrcode(0, "M");
    q.addData(text);
    q.make();
    const n = q.getModuleCount();
    const rows = [];
    for (let r = 0; r < n; r++) {
        let row = "";
        for (let c = 0; c < n; c++) row += q.isDark(r, c) ? "1" : "0";
        rows.push(row);
    }
    return rows;
}

async function start(cfg) {
    config = cfg;
    Environment.default.vars.set("storage.path", cfg.storage);
    if (cfg.interface) Environment.default.vars.set("mdns.networkInterface", cfg.interface);

    server = await ServerNode.create({
        id: "phev-link",
        network: { port: cfg.port ?? 5540 },
        commissioning: { passcode: cfg.passcode, discriminator: cfg.discriminator },
        productDescription: { name: cfg.name, deviceType: AggregatorEndpoint.deviceType },
        basicInformation: {
            vendorName: "PHEV Link",
            vendorId: VendorId(cfg.vendorId ?? 0xfff1),
            productName: "PHEV Link bridge",
            productLabel: "PHEV Link",
            productId: cfg.productId ?? 0x8000,
            nodeLabel: cfg.name.slice(0, 32),
            serialNumber: cfg.serial.slice(0, 32),
            uniqueId: `uid-${cfg.serial}`.slice(0, 32),
            hardwareVersion: 1,
            softwareVersion: 1,
        },
    });

    const aggregator = new Endpoint(AggregatorEndpoint, { id: "car" });
    await server.add(aggregator);

    for (const [key, label] of Object.entries(cfg.devices ?? {})) {
        const kind = KINDS[key];
        if (!kind || !label) continue;
        const ep = new Endpoint(kind.with(BridgedDeviceBasicInformationServer), {
            id: key,
            bridgedDeviceBasicInformation: {
                nodeLabel: label.slice(0, 32),
                productName: label.slice(0, 32),
                productLabel: label.slice(0, 64),
                serialNumber: `${cfg.serial}-${key}`.slice(0, 32),
                uniqueId: `uid-${cfg.serial}-${key}`.slice(0, 32),
                reachable: true,
            },
        });
        await aggregator.add(ep);
        // Only what a controller asks for: our own state updates (from the car) come in as "offline" changes.
        ep.events.onOff.onOff$Changed.on((value, _old, context) => {
            if (applying || context?.offline) return;
            let by = null, vendorId = null;
            try {
                by = context?.session?.fabric?.label || null;
                vendorId = context?.session?.fabric?.rootVendorId ?? null;
            } catch { /* no fabric on this session */ }
            send({ type: "command", what: key, on: !!value, by, vendorId });
        });
        endpoints.set(key, ep);
    }

    server.lifecycle.commissioned.on(() => { log("paired with a controller"); report("fabrics"); });
    server.lifecycle.decommissioned.on(() => { log("all pairings removed"); report("fabrics"); });
    server.events.commissioning.fabricsChanged.on(() => report("fabrics"));

    await server.start();
    log(`running on port ${cfg.port ?? 5540}` + (cfg.interface ? ` (${cfg.interface})` : "") + ` with ${endpoints.size} device(s): ${[...endpoints.keys()].join(", ")}`);
    report("ready");
    if (Object.keys(lastState).length) await applyState(lastState);
}

async function applyState(s) {
    lastState = s;
    if (!server) return;
    applying = true;
    try {
        for (const [key, ep] of endpoints) {
            const on = !!s[key];
            if (ep.state.onOff.onOff !== on) await ep.set({ onOff: { onOff: on } });
            const reachable = s.reachable !== false;
            if (ep.state.bridgedDeviceBasicInformation.reachable !== reachable) {
                await ep.set({ bridgedDeviceBasicInformation: { reachable } });
            }
        }
    } finally {
        applying = false;
    }
}

async function stop(code = 0) {
    try {
        if (server) await server.close();
    } catch (e) {
        log(`closing failed: ${e?.message ?? e}`, "warn");
    }
    process.exit(code);
}

const rl = readline.createInterface({ input: process.stdin });
rl.on("line", async (line) => {
    let msg;
    try {
        msg = JSON.parse(line);
    } catch {
        return log(`not JSON: ${line.slice(0, 80)}`, "warn");
    }
    try {
        switch (msg.type) {
            case "config":
                if (server) return log("already configured — restart to change", "warn");
                await start(msg);
                break;
            case "state":
                await applyState(msg);
                break;
            case "reset":
                log("forgetting every pairing");
                if (server) await server.erase();
                await stop(0);
                break;
            case "stop":
                await stop(0);
                break;
        }
    } catch (e) {
        const text = String(e?.message ?? e);
        const cause = String(e?.cause?.message ?? "");
        // Matter runs on IPv6: say so plainly when the computer has it switched off.
        const noIpv6 = /EAFNOSUPPORT|MdnsService unavailable/.test(text + " " + cause);
        send({ type: "error", text: noIpv6 ? "IPv6 is switched off on the bridge computer (Matter needs it)" : `${msg.type}: ${text}`, code: noIpv6 ? "no-ipv6" : undefined });
        // Nothing to tear down cleanly after a failed start; let the message out first.
        if (msg.type === "config") process.stdout.write("", () => process.exit(2));
    }
});
// The bridge went away: so do we.
rl.on("close", () => stop(0));
process.on("SIGTERM", () => stop(0));
process.on("SIGINT", () => stop(0));
