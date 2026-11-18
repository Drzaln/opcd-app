// Device pairing + bearer tokens. Tokens are random 32-byte values; only their SHA-256 is stored.
import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto"
import { mkdirSync, readFileSync, writeFileSync, chmodSync, existsSync } from "node:fs"
import { homedir } from "node:os"
import { join, dirname } from "node:path"

export interface Device {
  id: string
  name: string
  tokenSha256: string
  pairedAt: string
  lastSeen?: string
}

export const configDir = join(homedir(), ".config", "opencode-mobile-helper")
const devicesFile = join(configDir, "devices.json")

const sha = (s: string) => createHash("sha256").update(s).digest("hex")

export function loadDevices(file = devicesFile): Device[] {
  if (!existsSync(file)) return []
  try {
    const parsed = JSON.parse(readFileSync(file, "utf8"))
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function saveDevices(devices: Device[], file = devicesFile) {
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
  writeFileSync(file, JSON.stringify(devices, null, 2), { mode: 0o600 })
  chmodSync(file, 0o600)
}

/** Returns the device the bearer token belongs to, or null. */
export function authenticate(token: string | undefined, devices = loadDevices()): Device | null {
  if (!token || token.length < 20) return null
  const hash = Buffer.from(sha(token), "hex")
  for (const device of devices) {
    const stored = Buffer.from(device.tokenSha256, "hex")
    if (stored.length === hash.length && timingSafeEqual(stored, hash)) return device
  }
  return null
}

export function issueToken(name: string, file = devicesFile): { device: Device; token: string } {
  const token = randomBytes(32).toString("base64url")
  const device: Device = {
    id: randomBytes(6).toString("hex"),
    name: name.slice(0, 60).replace(/[^\p{L}\p{N} ._'()-]/gu, "") || "Android device",
    tokenSha256: sha(token),
    pairedAt: new Date().toISOString(),
  }
  const devices = loadDevices(file)
  devices.push(device)
  saveDevices(devices, file)
  return { device, token }
}

export function revoke(idOrName: string, file = devicesFile): number {
  const devices = loadDevices(file)
  const kept = devices.filter((d) => d.id !== idOrName && d.name !== idOrName)
  saveDevices(kept, file)
  return devices.length - kept.length
}

/** A 4-digit code shown on the phone and in the Mac's confirmation dialog. */
export function pairingCode(): string {
  return String(randomInt(0, 10000)).padStart(4, "0")
}
