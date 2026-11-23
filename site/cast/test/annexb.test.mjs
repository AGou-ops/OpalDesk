import test from 'node:test';
import assert from 'node:assert/strict';

import {
  annexBToLengthPrefixed,
  buildAvcDecoderConfig,
  buildHevcDecoderConfig,
  extractParameterSets,
  H264_NAL_IDR,
  H264_NAL_PPS,
  H264_NAL_SPS,
  HEVC_NAL_IDR_W,
  HEVC_NAL_PPS,
  HEVC_NAL_SPS,
  HEVC_NAL_VPS,
  splitNalUnits,
} from '../js/annexb.js';
import { VIDEO_CODEC_H264, VIDEO_CODEC_HEVC } from '../js/protocol.js';

test('H.264 Annex-B splits SPS/PPS and builds valid avcC + avc1.PPCCLL codec string', () => {
  // Minimal High Profile Level 4.2 SPS (0x67 0x64 0x00 0x2A ...) + PPS (0x68 0xEE 0x3C 0x80)
  const csd = new Uint8Array([
    0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x2a, 0xac, 0xd9,
    0x00, 0x00, 0x01, 0x68, 0xee, 0x3c, 0x80,
  ]);
  const units = splitNalUnits(csd, VIDEO_CODEC_H264);
  assert.equal(units.length, 2);
  assert.equal(units[0].nalType, H264_NAL_SPS);
  assert.equal(units[1].nalType, H264_NAL_PPS);

  const ps = extractParameterSets(units, VIDEO_CODEC_H264);
  const cfg = buildAvcDecoderConfig(ps.sps, ps.pps);
  assert.ok(cfg);
  assert.equal(cfg.codecString, 'avc1.64002a');
  assert.equal(cfg.description[0], 1); // configurationVersion
  assert.equal(cfg.description[1], 0x64);
  assert.equal(cfg.description[3], 0x2a);
  assert.equal(cfg.description[4], 0xff); // 4-byte length prefix
  assert.equal(cfg.description[5], 0xe1); // 1 SPS
});

test('H.264 Annex-B frame strips inline SPS/PPS/AUD and converts IDR slice to 4-byte length-prefixed AVCC', () => {
  const frame = new Uint8Array([
    0x00, 0x00, 0x00, 0x01, 0x09, 0x10,             // AUD (stripped)
    0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x2a, // SPS (stripped)
    0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x21, // IDR slice (4 bytes)
  ]);
  const res = annexBToLengthPrefixed(frame, VIDEO_CODEC_H264);
  assert.equal(res.hasKeySlice, true);
  assert.equal(res.sample.byteLength, 8);
  assert.equal(new DataView(res.sample.buffer).getUint32(0, false), 4);
  assert.equal(res.sample[4] & 0x1f, H264_NAL_IDR);
});

test('HEVC Annex-B splits VPS/SPS/PPS and builds hvcC + hvc1 codec string', () => {
  // Synthetic HEVC VPS (type 32 -> 0x40 0x01), SPS (type 33 -> 0x42 0x01 + PTL), PPS (type 34 -> 0x44 0x01)
  const vps = [0x40, 0x01, 0x0c, 0x01];
  const sps = [
    0x42, 0x01, // NAL header (type 33)
    0x01,       // vps_id=0, max_sub_layers=0, nesting=1
    0x01,       // profile_space=0, tier=0 (L), profile_idc=1 (Main)
    0x60, 0x00, 0x00, 0x00, // compat flags -> bit-reversed 0x06 -> "6"
    0x90, 0x00, 0x00, 0x00, 0x00, 0x00, // constraint flags -> "90"
    0x78,       // level_idc=120 (L120)
    0xaa, 0xbb, // trailing RBSP bytes
  ];
  const pps = [0x44, 0x01, 0xc1, 0x72];
  const csd = new Uint8Array([
    0, 0, 0, 1, ...vps,
    0, 0, 0, 1, ...sps,
    0, 0, 0, 1, ...pps,
  ]);

  const units = splitNalUnits(csd, VIDEO_CODEC_HEVC);
  assert.deepEqual(units.map((u) => u.nalType), [HEVC_NAL_VPS, HEVC_NAL_SPS, HEVC_NAL_PPS]);

  const ps = extractParameterSets(units, VIDEO_CODEC_HEVC);
  const cfg = buildHevcDecoderConfig(ps.vps, ps.sps, ps.pps);
  assert.ok(cfg);
  assert.equal(cfg.codecString, 'hvc1.1.6.L120.90');
  assert.equal(cfg.description[0], 1); // configurationVersion
  assert.equal(cfg.description[22], 3); // 3 arrays (VPS, SPS, PPS)

  const idrFrame = new Uint8Array([0, 0, 0, 1, 0x26, 0x01, 0xaf, 0x09]); // type 19 = IDR_W_RADL
  const conv = annexBToLengthPrefixed(idrFrame, VIDEO_CODEC_HEVC);
  assert.equal(conv.hasKeySlice, true);
  assert.equal(new DataView(conv.sample.buffer).getUint32(0, false), 4);
  assert.equal((conv.sample[4] >> 1) & 0x3f, HEVC_NAL_IDR_W);
});
