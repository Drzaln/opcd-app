// scrcpy protocol pieces: control messages and the video packet splitter.
import { test } from "node:test"
import assert from "node:assert/strict"
import { touchMessage, keyMessage, PacketParser, framePacket, KIND_CONFIG, KIND_FRAME, KIND_KEYFRAME } from "../dist/test/screen.js"

test("touch and key control messages match scrcpy's layout", () => {
  const t = touchMessage(0, 7, 100, 200, 720, 1600)
  assert.equal(t.length, 32)
  assert.equal(t[0], 2) // INJECT_TOUCH_EVENT
  assert.equal(t[1], 0) // down
  assert.equal(t.readBigUInt64BE(2), 7n)
  assert.equal(t.readInt32BE(10), 100)
  assert.equal(t.readInt32BE(14), 200)
  assert.equal(t.readUInt16BE(18), 720)
  assert.equal(t.readUInt16BE(20), 1600)
  assert.equal(t.readUInt16BE(22), 0xffff)
  assert.equal(touchMessage(1, 7, 1, 1, 720, 1600).readUInt16BE(22), 0, "no pressure on up")
  const k = keyMessage(0, 4)
  assert.deepEqual([k.length, k[0], k[1], k.readInt32BE(2)], [14, 0, 0, 4])
})

test("packet parser (scrcpy 5): session, config, key and plain frames across chunk boundaries", () => {
  const packet = (flags, pts, data) => {
    const h = Buffer.alloc(12)
    h.writeBigUInt64BE((flags << 61n) | BigInt(pts), 0)
    h.writeUInt32BE(data.length, 8)
    return Buffer.concat([h, data])
  }
  const session = (w, h) => { const b = Buffer.alloc(12); b.writeUInt32BE(0x80000000, 0); b.writeUInt32BE(w, 4); b.writeUInt32BE(h, 8); return b }
  // The real first packets seen from scrcpy 5.0.1 on an emulator: session 358x800, then 32-byte SPS/PPS.
  const stream = Buffer.concat([
    session(358, 800),
    packet(2n, 0, Buffer.from([0, 0, 0, 1, 0x67])), // config (bit 62)
    packet(1n, 1000, Buffer.from([0, 0, 0, 1, 0x65, 1, 2])), // key frame (bit 61)
    packet(0n, 2000, Buffer.from([0, 0, 0, 1, 0x41])),
    session(800, 358), // rotated
  ])
  const got = []
  const parser = new PacketParser((kind, pts, data) => got.push([kind, pts, data.length]), (w, h) => got.push(["session", w, h]))
  for (let i = 0; i < stream.length; i += 5) parser.push(stream.subarray(i, i + 5))
  assert.deepEqual(got, [["session", 358, 800], [KIND_CONFIG, 0n, 5], [KIND_KEYFRAME, 1000n, 7], [KIND_FRAME, 2000n, 5], ["session", 800, 358]])
  const f = framePacket(KIND_KEYFRAME, 1000n, Buffer.from([9, 9]))
  assert.deepEqual([f[0], f.readBigUInt64BE(1), f.length], [KIND_KEYFRAME, 1000n, 11])
})

test("iOS simulator stream (idb): an access unit → config once, then key frame / frame", async () => {
  const { nalUnits, AccessUnitPacker } = await import("../dist/test/simscreen.js")
  const sc4 = Buffer.from([0, 0, 0, 1]), sc3 = Buffer.from([0, 0, 1])
  const sps = Buffer.concat([sc4, Buffer.from([0x27, 0x64, 0x00, 0x32])])
  const pps = Buffer.concat([sc4, Buffer.from([0x28, 0xee, 0x3c, 0x80])])
  const idr = Buffer.concat([sc3, Buffer.from([0x25, 0x88, 0x84, 0x00, 0x11])])
  const slice = Buffer.concat([sc4, Buffer.from([0x21, 0x9a, 0x00, 0x22])])
  assert.equal(nalUnits(Buffer.concat([sps, pps, idr])).length, 3)
  const out = []
  const packer = new AccessUnitPacker((kind, data) => out.push([kind, data.toString("hex")]))
  packer.push(Buffer.concat([sps, pps, idr]))
  packer.push(slice)
  packer.push(Buffer.concat([sps, pps, idr])) // periodic key frame repeats SPS/PPS: no new config
  assert.deepEqual(out.map(([k]) => k), [1, 3, 2, 3])
  assert.equal(out[0][1], Buffer.concat([sps, pps]).toString("hex"))
  assert.equal(out[1][1], idr.toString("hex"))
  assert.equal(out[2][1], slice.toString("hex"))
})
