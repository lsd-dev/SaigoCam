/*
 * Unit test for the early NAL hand-off heuristic. Needs no FFmpeg or SDL.
 * Part of SaigoCam, licensed under the GNU General Public License v3; see ../../LICENSE.
 *
 * Packets imitate libavformat's RTP H.264 depacketizer output with
 * "fflags noparse": whole NAL units and FU-A start fragments begin with
 * 00 00 00 01, continuation fragments do not. Sizes: a full FU-A start
 * packet is 1403 bytes and a full continuation is 1398 bytes.
 */
#include "../src/early_nal.h"

#include <stdio.h>
#include <string.h>

#define MAX_EMITS 32

static size_t emits[MAX_EMITS];
static int n_emits;
static int failures;

static void record(void *opaque, const uint8_t *data, size_t size)
{
    (void)opaque;
    (void)data;
    if (n_emits < MAX_EMITS)
        emits[n_emits] = size;
    n_emits++;
}

static void push_start(EarlyNal *e, size_t size)
{
    static uint8_t buf[4096];
    memset(buf, 0x55, sizeof(buf));
    buf[0] = 0;
    buf[1] = 0;
    buf[2] = 0;
    buf[3] = 1;
    early_nal_push(e, buf, size, record, NULL);
}

static void push_cont(EarlyNal *e, size_t size)
{
    static uint8_t buf[4096];
    memset(buf, 0x55, sizeof(buf));
    early_nal_push(e, buf, size, record, NULL);
}

static void expect_emits(const char *step, int n)
{
    if (n_emits != n) {
        printf("FAIL %s: %d emits, expected %d\n", step, n_emits, n);
        failures++;
    }
}

static void expect_last(const char *step, size_t size)
{
    if (n_emits == 0 || n_emits > MAX_EMITS || emits[n_emits - 1] != size) {
        printf("FAIL %s: last emit %zu bytes, expected %zu\n", step,
               n_emits ? emits[n_emits - 1] : (size_t)0, size);
        failures++;
    }
}

int main(void)
{
    EarlyNal e;
    early_nal_init(&e);

    /* Joined mid-NAL: a stray continuation is dropped. */
    push_cont(&e, 1398);
    expect_emits("stray continuation", 0);

    /* Frame 1: SPS, PPS, IDR in 3 fragments. Nothing learned yet, so whole
     * NAL units are only flushed when the next one starts. */
    push_start(&e, 14);   /* SPS */
    expect_emits("SPS buffered", 0);
    push_start(&e, 8);    /* PPS: flushes SPS */
    expect_emits("PPS starts", 1);
    expect_last("SPS flushed", 14);
    push_start(&e, 1403); /* IDR FU-A start: flushes PPS */
    expect_last("PPS flushed", 8);
    push_cont(&e, 1398);  /* full continuation: learns both sizes */
    expect_emits("IDR middle", 2);
    push_cont(&e, 500);   /* short: last fragment, emitted at once */
    expect_emits("IDR end", 3);
    expect_last("IDR complete", 1403 + 1398 + 500);

    /* Frame 2: small P slice in one packet, emitted without waiting. */
    push_start(&e, 300);
    expect_emits("small P", 4);
    expect_last("small P complete", 300);

    /* Frame 3: last fragment is exactly full size, so it can't be told
     * apart: it waits for the next start code (same as the parser). */
    push_start(&e, 1403);
    push_cont(&e, 1398);
    push_cont(&e, 1398);
    push_cont(&e, 1398);
    expect_emits("ambiguous end waits", 4);
    push_start(&e, 200);  /* frame 4 starts: frame 3 flushed, then frame 4 */
    expect_emits("ambiguous end flushed", 6);
    if (n_emits >= 6 && emits[4] != 1403 + 3 * 1398) {
        printf("FAIL frame 3 size %zu\n", emits[4]);
        failures++;
    }
    expect_last("frame 4 complete", 200);

    /* Misfire: a short start packet that is in fact fragmented. */
    push_start(&e, 1000);
    expect_emits("guess", 7);
    push_cont(&e, 700);
    if (e.misfires != 1 || !e.disabled) {
        printf("FAIL misfire not detected (misfires=%d disabled=%d)\n", e.misfires, e.disabled);
        failures++;
    }
    push_cont(&e, 300);   /* rest of the broken NAL is dropped */
    expect_emits("tail dropped", 7);

    /* Disabled: only flush when the next NAL starts. */
    push_start(&e, 50);
    expect_emits("disabled waits", 7);
    push_start(&e, 60);
    expect_emits("disabled flushes on start", 8);
    expect_last("disabled flush size", 50);

    early_nal_free(&e);
    if (failures) {
        printf("%d check(s) failed\n", failures);
        return 1;
    }
    printf("all early_nal checks passed\n");
    return 0;
}
