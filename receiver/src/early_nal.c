/*
 * SaigoCam receiver - early NAL unit hand-off. See early_nal.h for the idea.
 * Part of SaigoCam, licensed under the GNU General Public License v3; see ../../LICENSE.
 */
#include "early_nal.h"

#include <stdlib.h>
#include <string.h>

/* libavformat writes 00 00 00 01 before every NAL unit it outputs whole and
 * before the first FU-A fragment. FU-A continuation fragments have no start
 * code. Emulation prevention guarantees 00 00 01 never occurs inside a NAL
 * unit, so a continuation cannot be mistaken for a start. */
static int starts_with_start_code(const uint8_t *d, size_t n)
{
    if (n >= 3 && d[0] == 0 && d[1] == 0 && d[2] == 1)
        return 1;
    return n >= 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1;
}

static int append(EarlyNal *e, const uint8_t *data, size_t size)
{
    if (e->len + size > e->cap) {
        size_t cap = e->cap ? e->cap : 64 * 1024;
        uint8_t *p;
        while (cap < e->len + size)
            cap *= 2;
        p = realloc(e->buf, cap);
        if (!p) {
            /* Out of memory: drop what we have and resync at the next NAL. */
            e->len = 0;
            e->skip_until_start = 1;
            return 0;
        }
        e->buf = p;
        e->cap = cap;
    }
    memcpy(e->buf + e->len, data, size);
    e->len += size;
    return 1;
}

static void flush(EarlyNal *e, EarlyNalEmit emit, void *opaque)
{
    if (e->len) {
        emit(opaque, e->buf, e->len);
        e->len = 0;
    }
}

void early_nal_init(EarlyNal *e)
{
    memset(e, 0, sizeof(*e));
}

void early_nal_free(EarlyNal *e)
{
    free(e->buf);
    memset(e, 0, sizeof(*e));
}

void early_nal_push(EarlyNal *e, const uint8_t *data, size_t size,
                    EarlyNalEmit emit, void *opaque)
{
    if (!data || size == 0)
        return;

    if (starts_with_start_code(data, size)) {
        /* A new NAL unit begins, so everything buffered is complete. */
        flush(e, emit, opaque);
        e->flushed_early = 0;
        e->skip_until_start = 0;
        if (!append(e, data, size))
            return;
        e->prev_was_start = 1;
        e->last_start_size = size;
        /* Shorter than a full FU-A start packet: a whole NAL unit (single
         * NAL or STAP-A), so it cannot continue. */
        if (!e->disabled && e->max_start && size < e->max_start) {
            flush(e, emit, opaque);
            e->flushed_early = 1;
        }
        return;
    }

    /* FU-A continuation fragment. */
    if (e->flushed_early) {
        /* We already emitted this NAL unit as complete, but more of it
         * arrived: the size heuristic does not hold for this stream. */
        e->misfires++;
        e->disabled = 1;
        e->flushed_early = 0;
        e->skip_until_start = 1;
    }
    if (e->skip_until_start || e->len == 0) {
        /* Joined mid-NAL, recovering from a misfire, or out of memory. */
        e->skip_until_start = 1;
        e->prev_was_start = 0;
        return;
    }
    if (e->prev_was_start && e->last_start_size > e->max_start)
        e->max_start = e->last_start_size;
    if (size > e->max_cont)
        e->max_cont = size;
    e->prev_was_start = 0;
    if (!append(e, data, size))
        return;
    /* Shorter than a full fragment: this was the last one. */
    if (!e->disabled && size < e->max_cont) {
        flush(e, emit, opaque);
        e->flushed_early = 1;
    }
}
