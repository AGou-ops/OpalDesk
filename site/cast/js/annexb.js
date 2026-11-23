/**
 * Pure ES Module Annex-B NAL parser and AVCC / HVCC WebCodecs configuration builder.
 * Mirrors macOS `AnnexBParser.swift` so H.264 & HEVC streams decode uniformly across
 * Chrome, Edge, Firefox, and Safari WebCodecs `VideoDecoder`.
 */

import { VIDEO_CODEC_H264, VIDEO_CODEC_HEVC } from './protocol.js';

export const H264_NAL_IDR = 5;
export const H264_NAL_SPS = 7;
export const H264_NAL_PPS = 8;
export const H264_NAL_AUD = 9;

export const HEVC_NAL_IDR_W = 19;
export const HEVC_NAL_IDR_N = 20;
export const HEVC_NAL_CRA = 21;
export const HEVC_NAL_VPS = 32;
export const HEVC_NAL_SPS = 33;
export const HEVC_NAL_PPS = 34;
export const HEVC_NAL_AUD = 35;

/**
 * Splits an Annex-B byte buffer (3-byte `00 00 01` or 4-byte `00 00 00 01` start codes)
 * into raw NAL units without copying underlying bytes.
 */
export function splitNalUnits(u8, codecId) {
  const bytes = u8 instanceof Uint8Array ? u8 : new Uint8Array(u8);
  const len = bytes.byteLength;
  const units = [];
  let cursor = 0;
  let nalStart = -1;

  while (cursor < len) {
    if (bytes[cursor] === 0) {
      let scLen = 0;
      if (cursor + 3 < len && bytes[cursor + 1] === 0 && bytes[cursor + 2] === 0 && bytes[cursor + 3] === 1) {
        scLen = 4;
      } else if (cursor + 2 < len && bytes[cursor + 1] === 0 && bytes[cursor + 2] === 1) {
        scLen = 3;
      }
      if (scLen > 0) {
        if (nalStart >= 0 && nalStart < cursor) {
          pushNal(bytes, nalStart, cursor, codecId, units);
        }
        cursor += scLen;
        nalStart = cursor;
        continue;
      }
    }
    cursor++;
  }

  if (nalStart >= 0 && nalStart < len) {
    pushNal(bytes, nalStart, len, codecId, units);
  }
  return units;
}

function pushNal(bytes, start, end, codecId, out) {
  let e = end;
  while (e > start && bytes[e - 1] === 0) e--;
  if (e <= start) return;
  const hdr = bytes[start];
  const nalType = codecId === VIDEO_CODEC_HEVC ? (hdr >> 1) & 0x3f : hdr & 0x1f;
  out.push({ nalType, data: bytes.subarray(start, e) });
}

export function extractParameterSets(units, codecId) {
  const vps = [];
  const sps = [];
  const pps = [];
  for (const u of units) {
    if (codecId === VIDEO_CODEC_HEVC) {
      if (u.nalType === HEVC_NAL_VPS) vps.push(u.data);
      else if (u.nalType === HEVC_NAL_SPS) sps.push(u.data);
      else if (u.nalType === HEVC_NAL_PPS) pps.push(u.data);
    } else {
      if (u.nalType === H264_NAL_SPS) sps.push(u.data);
      else if (u.nalType === H264_NAL_PPS) pps.push(u.data);
    }
  }
  return { vps, sps, pps };
}

function hex2(n) {
  return (n & 0xff).toString(16).padStart(2, '0');
}

/**
 * Builds ISO/IEC 14496-15 AVCDecoderConfigurationRecord (`avcC`) and RFC 6381 codec string (`avc1.PPCCLL`).
 */
export function buildAvcDecoderConfig(spsList, ppsList) {
  if (!spsList.length || !ppsList.length || spsList[0].byteLength < 4) {
    return null;
  }
  const sps0 = spsList[0];
  const profileIdc = sps0[1];
  const profileCompat = sps0[2];
  const levelIdc = sps0[3];
  const codecString = `avc1.${hex2(profileIdc)}${hex2(profileCompat)}${hex2(levelIdc)}`;

  let totalLen = 7;
  for (const s of spsList) totalLen += 2 + s.byteLength;
  for (const p of ppsList) totalLen += 2 + p.byteLength;

  const avcC = new Uint8Array(totalLen);
  let o = 0;
  avcC[o++] = 1; // configurationVersion
  avcC[o++] = profileIdc;
  avcC[o++] = profileCompat;
  avcC[o++] = levelIdc;
  avcC[o++] = 0xff; // lengthSizeMinusOne = 3 (4-byte NAL length)
  avcC[o++] = 0xe0 | (spsList.length & 0x1f);
  for (const s of spsList) {
    avcC[o++] = (s.byteLength >> 8) & 0xff;
    avcC[o++] = s.byteLength & 0xff;
    avcC.set(s, o);
    o += s.byteLength;
  }
  avcC[o++] = ppsList.length & 0xff;
  for (const p of ppsList) {
    avcC[o++] = (p.byteLength >> 8) & 0xff;
    avcC[o++] = p.byteLength & 0xff;
    avcC.set(p, o);
    o += p.byteLength;
  }
  return { codecString, description: avcC };
}

function removeEmulationPreventionBytes(nalPayload) {
  const out = [];
  let zeros = 0;
  for (let i = 0; i < nalPayload.byteLength; i++) {
    const b = nalPayload[i];
    if (zeros === 2 && b === 3) {
      zeros = 0;
      continue;
    }
    out.push(b);
    zeros = b === 0 ? zeros + 1 : 0;
  }
  return new Uint8Array(out);
}

/**
 * Extracts HEVC general_profile_tier_level (12 bytes) from SPS RBSP (starting after 2-byte NAL header + 1-byte sps_video_parameter_set_id/max_sub_layers/temporal_id_nesting).
 */
function parseHevcPtlFromSps(sps) {
  if (sps.byteLength < 15) return null;
  const rbsp = removeEmulationPreventionBytes(sps.subarray(2));
  if (rbsp.byteLength < 13) return null;
  // rbsp[0]: sps_video_parameter_set_id (4b), sps_max_sub_layers_minus1 (3b), sps_temporal_id_nesting_flag (1b)
  const ptl = rbsp.subarray(1, 13);
  const profileSpace = (ptl[0] >> 6) & 0x03;
  const tierFlag = (ptl[0] >> 5) & 0x01;
  const profileIdc = ptl[0] & 0x1f;
  const compatFlags = new DataView(ptl.buffer, ptl.byteOffset + 1, 4).getUint32(0, false);
  const constraintBytes = ptl.subarray(5, 11);
  const levelIdc = ptl[11];

  const spacePrefix = ['', 'A', 'B', 'C'][profileSpace] || '';
  // RFC 6381 / ISO 14496-15 E.3: reverse bit-order of 32-bit compatibility flags
  let reversedCompat = 0;
  for (let i = 0; i < 32; i++) {
    if ((compatFlags & (1 << i)) !== 0) reversedCompat |= 1 << (31 - i);
  }
  const compatHex = (reversedCompat >>> 0).toString(16).toUpperCase();
  const tierChar = tierFlag ? 'H' : 'L';
  let lastNonZero = constraintBytes.length - 1;
  while (lastNonZero > 0 && constraintBytes[lastNonZero] === 0) lastNonZero--;
  const constraintParts = [];
  for (let i = 0; i <= lastNonZero; i++) {
    constraintParts.push(constraintBytes[i].toString(16).toUpperCase());
  }
  const codecString = `hvc1.${spacePrefix}${profileIdc}.${compatHex}.${tierChar}${levelIdc}.${constraintParts.join('.')}`;
  return { ptl, codecString };
}

/**
 * Builds ISO/IEC 14496-15 HEVCDecoderConfigurationRecord (`hvcC`) and RFC 6381 codec string (`hvc1...`).
 */
export function buildHevcDecoderConfig(vpsList, spsList, ppsList) {
  if (!vpsList.length || !spsList.length || !ppsList.length) return null;
  const parsed = parseHevcPtlFromSps(spsList[0]);
  const ptl = parsed ? parsed.ptl : new Uint8Array([0x01, 0x60, 0x00, 0x00, 0x00, 0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x78]);
  const codecString = parsed ? parsed.codecString : 'hvc1.1.6.L120.90';

  const arrays = [
    { nalType: HEVC_NAL_VPS, list: vpsList },
    { nalType: HEVC_NAL_SPS, list: spsList },
    { nalType: HEVC_NAL_PPS, list: ppsList },
  ];
  let totalLen = 23;
  for (const a of arrays) {
    totalLen += 3;
    for (const nal of a.list) totalLen += 2 + nal.byteLength;
  }

  const hvcC = new Uint8Array(totalLen);
  let o = 0;
  hvcC[o++] = 1; // configurationVersion
  hvcC.set(ptl, o); // 12 bytes: profile_space/tier/idc (1) + compat (4) + constraint (6) + level (1)
  o += 12;
  hvcC[o++] = 0xf0; // min_spatial_segmentation_idc hi
  hvcC[o++] = 0x00; // lo
  hvcC[o++] = 0xfc; // parallelismType
  hvcC[o++] = 0xfd; // chromaFormat = 1 (4:2:0)
  hvcC[o++] = 0xf8; // bitDepthLumaMinus8 = 0
  hvcC[o++] = 0xf8; // bitDepthChromaMinus8 = 0
  hvcC[o++] = 0x00; // avgFrameRate hi
  hvcC[o++] = 0x00; // lo
  hvcC[o++] = 0x0f; // constantFrameRate=0, numTemporalLayers=1, temporalIdNested=1, lengthSizeMinusOne=3
  hvcC[o++] = arrays.length;

  for (const a of arrays) {
    hvcC[o++] = 0x80 | (a.nalType & 0x3f); // array_completeness=1
    hvcC[o++] = (a.list.length >> 8) & 0xff;
    hvcC[o++] = a.list.length & 0xff;
    for (const nal of a.list) {
      hvcC[o++] = (nal.byteLength >> 8) & 0xff;
      hvcC[o++] = nal.byteLength & 0xff;
      hvcC.set(nal, o);
      o += nal.byteLength;
    }
  }
  return { codecString, description: hvcC };
}

/**
 * Converts an Annex-B video access unit into 4-byte Big-Endian length-prefixed AVCC/HVCC sample data,
 * skipping AUD & parameter-set NAL units that belong in `description`.
 */
export function annexBToLengthPrefixed(u8, codecId) {
  const units = splitNalUnits(u8, codecId);
  const kept = [];
  let totalBytes = 0;
  let hasKeySlice = false;

  for (const u of units) {
    if (codecId === VIDEO_CODEC_HEVC) {
      if (u.nalType === HEVC_NAL_VPS || u.nalType === HEVC_NAL_SPS || u.nalType === HEVC_NAL_PPS || u.nalType === HEVC_NAL_AUD) {
        continue;
      }
      if (u.nalType === HEVC_NAL_IDR_W || u.nalType === HEVC_NAL_IDR_N || u.nalType === HEVC_NAL_CRA) {
        hasKeySlice = true;
      }
    } else {
      if (u.nalType === H264_NAL_SPS || u.nalType === H264_NAL_PPS || u.nalType === H264_NAL_AUD) {
        continue;
      }
      if (u.nalType === H264_NAL_IDR) {
        hasKeySlice = true;
      }
    }
    kept.push(u.data);
    totalBytes += 4 + u.data.byteLength;
  }

  const sample = new Uint8Array(totalBytes);
  const view = new DataView(sample.buffer);
  let off = 0;
  for (const nal of kept) {
    view.setUint32(off, nal.byteLength, false);
    sample.set(nal, off + 4);
    off += 4 + nal.byteLength;
  }
  return { sample, hasKeySlice, units };
}
