#define _POSIX_C_SOURCE 199309L
/* CI test (Linux, Mesa lavapipe): the Vulkan shader against a CPU port of ScanMask.classify on
   synthetic subtitle-like frames of awkward sizes. Exit code 1 on any differing pixel. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "hsocr_vk.h"

static int ridge(const int *v, int w, int h, int i, int limit, int r) {
    int x = i % w, y = i / w, a = 0, b = 0;
    for (int k = 1; k <= r; k++) {
        if (!a && x - k >= 0 && v[i - k] <= limit) a = 1;
        if (!b && x + k < w && v[i + k] <= limit) b = 1;
        if (a && b) return 1;
    }
    a = 0; b = 0;
    for (int k = 1; k <= r; k++) {
        if (!a && y - k >= 0 && v[i - k * w] <= limit) a = 1;
        if (!b && y + k < h && v[i + k * w] <= limit) b = 1;
        if (a && b) return 1;
    }
    return 0;
}

static void cpu_classify(const unsigned char *g, int w, int h, const int *P, unsigned char *cls) {
    int n = w * h;
    memset(cls, 0, (size_t) n);
    if (w < 3 || h < 3) return;
    int r = P[9] < 1 ? 1 : P[9];
    int *v = malloc(sizeof(int) * n), *tmp = malloc(sizeof(int) * n), *mn = malloc(sizeof(int) * n);
    for (int i = 0; i < n; i++) v[i] = P[8] ? 255 - g[i] : g[i];
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            int m = 255, a = x - r < 0 ? 0 : x - r, b = x + r > w - 1 ? w - 1 : x + r;
            for (int k = a; k <= b; k++) if (v[y * w + k] < m) m = v[y * w + k];
            tmp[y * w + x] = m;
        }
    for (int y = 0; y < h; y++) {
        int a = y - r < 0 ? 0 : y - r, b = y + r > h - 1 ? h - 1 : y + r;
        for (int x = 0; x < w; x++) {
            int m = 255;
            for (int k = a; k <= b; k++) if (tmp[k * w + x] < m) m = tmp[k * w + x];
            mn[y * w + x] = m;
        }
    }
    for (int i = 0; i < n; i++) {
        int c = v[i];
        if (c < P[6]) continue;
        int d = c - mn[i];
        if (c >= P[4] && d >= P[5] && ridge(v, w, h, i, c - P[5], P[3])) cls[i] = 2;
        else if (d >= P[7]) cls[i] = 1;
    }
    free(v); free(tmp); free(mn);
}

static unsigned rnd = 12345;
static unsigned nextr(void) { rnd = rnd * 1103515245u + 12345u; return (rnd >> 8) & 0xFFFFFF; }

static void frame(unsigned char *g, int w, int h, int k) {
    for (int i = 0; i < w * h; i++) g[i] = (unsigned char) (40 + (nextr() % 120) + (k * 7) % 30);
    /* subtitle-like bright strokes with dark outlines + a dark box + noise */
    for (int s = 0; s < 60; s++) {
        int x = nextr() % w, y = nextr() % h, len = 3 + nextr() % 25, vert = nextr() & 1;
        for (int t = -1; t <= len + 1; t++) {
            for (int q = -2; q <= 2; q++) {
                int xx = vert ? x + q : x + t, yy = vert ? y + t : y + q;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                g[yy * w + xx] = (unsigned char) ((q == 0 || q == 1) && t >= 0 && t <= len ? 230 + nextr() % 26 : 10 + nextr() % 20);
            }
        }
    }
    for (int y = h / 3; y < h * 2 / 3; y++) for (int x = w / 4; x < w / 2; x++) if (nextr() % 9) g[y * w + x] = 16;
}

int main(void) {
    HsocrVk *vk = hsocr_vk_create();
    if (!vk) { printf("VULKAN: not available: %s\n", hsocr_vk_error()); return 2; }
    printf("VULKAN DEVICE\n%s\n", hsocr_vk_info(vk));
    const int sizes[][2] = {{640, 108}, {1230, 201}, {1919, 301}, {3, 3}, {7, 5}, {641, 107}};
    const int presets[][12] = {
        {0, 0, 0, 4, 150, 60, 110, 38, 0, 1, 0, 0},
        {0, 0, 0, 4, 175, 60, 135, 38, 0, 1, 0, 0},
        {0, 0, 0, 3, 140, 50, 100, 31, 1, 1, 0, 0},
        {0, 0, 0, 4, 150, 60, 110, 38, 0, 2, 0, 0},
    };
    long total = 0, diff = 0;
    double tg = 0, tc = 0;
    for (int s = 0; s < 6; s++)
        for (int p = 0; p < 4; p++)
            for (int k = 0; k < 4; k++) {
                int w = sizes[s][0], h = sizes[s][1], n = w * h;
                unsigned char *g = malloc((size_t) n), *a = malloc((size_t) n), *b = malloc((size_t) n);
                frame(g, w, h, k);
                int P[12];
                memcpy(P, presets[p], sizeof P);
                P[0] = w; P[1] = h; P[2] = n;
                struct timespec t0, t1, t2;
                clock_gettime(CLOCK_MONOTONIC, &t0);
                if (!hsocr_vk_classify(vk, g, w, h, P, a)) { printf("VULKAN: classify failed: %s\n", hsocr_vk_error()); return 1; }
                clock_gettime(CLOCK_MONOTONIC, &t1);
                cpu_classify(g, w, h, P, b);
                clock_gettime(CLOCK_MONOTONIC, &t2);
                tg += (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
                tc += (t2.tv_sec - t1.tv_sec) * 1e3 + (t2.tv_nsec - t1.tv_nsec) / 1e6;
                long d = 0, strong = 0;
                for (int i = 0; i < n; i++) { if (a[i] != b[i]) d++; if (b[i] == 2) strong++; }
                total += n; diff += d;
                if (d) printf("MISMATCH %dx%d preset %d frame %d: %ld of %d pixels\n", w, h, p, k, d, n);
                if (k == 0 && p == 0) printf("  %dx%d: strong pixels %ld\n", w, h, strong);
                free(g); free(a); free(b);
            }
    printf("VULKAN TEST: %ld pixels compared, %ld different, GPU %.1f ms total, CPU %.1f ms total\n", total, diff, tg, tc);
    hsocr_vk_destroy(vk);
    return diff == 0 ? 0 : 1;
}
