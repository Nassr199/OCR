/* HardSub OCR — Vulkan compute backend (see vk_classify.c). */
#ifndef HSOCR_VK_H
#define HSOCR_VK_H
#include <stdint.h>

typedef struct HsocrVk HsocrVk;

/* NULL on failure; hsocr_vk_error() says why. */
HsocrVk *hsocr_vk_create(void);
/* "device=…\nvendor=…\ntype=…\nvulkan=…\ndriverVersion=…\ndriverName=…\ndriverInfo=…" */
const char *hsocr_vk_info(HsocrVk *c);
/* params = w, h, n, ridgeRadius, brightHigh, contrastHigh, brightLow, contrastLow, invert, minRadius, 0, 0.
   out[i] = 0 / 1 (weak) / 2 (strong) for every pixel, exactly like ScanMask.classify. 1 = ok. */
int hsocr_vk_classify(HsocrVk *c, const uint8_t *luma, int w, int h, const int32_t params[12], uint8_t *out);
void hsocr_vk_destroy(HsocrVk *c);
const char *hsocr_vk_error(void);

#endif
