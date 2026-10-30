/*
 * SaigoCam receiver - early NAL unit hand-off (experimental "--early" mode).
 * Part of SaigoCam, licensed under the GNU General Public License v3; see ../../LICENSE.
 *
 * Why this exists: libavformat's RTSP demuxer runs the H.264 parser on the
 * stream, and that parser only knows a frame is finished when the first bytes
 * of the NEXT frame arrive. That holds every frame back by up to one frame
 * interval (about 33 ms at 30 fps). With "fflags noparse" we get the raw
 * depacketized RTP payloads instead, but the FU-A end bit and the RTP marker
 * bit are not exposed, so we cannot know for sure when a fragmented NAL unit
 * ends.
 *
 * Heuristic: an RTP packetizer splits a large NAL unit into fragments of a
 * fixed maximum size, and only the last fragment is shorter. We learn that
 * maximum size from the stream and treat a shorter fragment as "NAL complete",
 * handing it to the decoder at once. If a guess turns out wrong (another
 * fragment follows), the heuristic switches itself off and we fall back to
 * flushing at the next start code. That is the same timing as the parser,
 * so the worst case is no worse than the default mode.
 *
 * The decoder must be opened with AV_CODEC_FLAG2_CHUNKS, because chunks are
 * NAL units, not whole frames.
 */
#ifndef SAIGOCAM_EARLY_NAL_H
#define SAIGOCAM_EARLY_NAL_H

#include <stddef.h>
#include <stdint.h>

typedef void (*EarlyNalEmit)(void *opaque, const uint8_t *data, size_t size);

typedef struct EarlyNal {
    uint8_t *buf;
    size_t len;
    size_t cap;
    size_t last_start_size;  /* size of the latest packet that began with a start code */
    size_t max_start;        /* learned size of a full-size FU-A start packet */
    size_t max_cont;         /* learned size of a full-size FU-A continuation packet */
    int prev_was_start;      /* the previous packet began with a start code */
    int flushed_early;       /* the buffer was emitted on a "complete" guess */
    int skip_until_start;    /* resync: drop continuations until a start code */
    int disabled;            /* a guess was wrong: only flush at start codes */
    int misfires;            /* number of wrong guesses (0 or 1 in practice) */
} EarlyNal;

void early_nal_init(EarlyNal *e);
void early_nal_free(EarlyNal *e);

/* Push one demuxed packet (Annex B, as produced by libavformat's RTP H.264
 * depacketizer with "fflags noparse"). Calls emit() zero or more times with
 * buffers that contain only complete NAL units. */
void early_nal_push(EarlyNal *e, const uint8_t *data, size_t size,
                    EarlyNalEmit emit, void *opaque);

#endif
