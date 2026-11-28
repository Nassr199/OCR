/*
 * HardSub OCR — Vulkan compute backend for the per-pixel stage of the subtitle detector
 * (ScanMask.classify in core/SubScan.kt). Runs on the phone's own GPU driver (Adreno / Mali /
 * Xclipse / PowerVR) through the system Vulkan loader; the device and driver are reported back.
 *
 * Plain C (no C++ runtime). The core (hsocr_vk_*) is platform independent so the same code is
 * tested on Linux in CI (lavapipe) against a CPU reference; the JNI layer is Android only.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include "hsocr_vk.h"

static const uint32_t kSpv[] = {
#include "classify.spv.inc"
};

struct HsocrVk {
    VkInstance instance;
    VkPhysicalDevice phys;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    VkShaderModule shader;
    VkDescriptorSetLayout dsl;
    VkPipelineLayout layout;
    VkPipeline pipeline;
    VkDescriptorPool dpool;
    VkDescriptorSet dset;
    VkCommandPool cpool;
    VkCommandBuffer cmd;
    VkFence fence;
    VkBuffer bufIn, bufOut;
    VkDeviceMemory memIn, memOut;
    void *mapIn, *mapOut;
    VkDeviceSize capacity;
    int outCoherent;
    VkPhysicalDeviceMemoryProperties mem;
    char info[1024];
};

static char g_err[512] = "";

const char *hsocr_vk_error(void) { return g_err; }

static void fail(const char *what, VkResult r) {
    snprintf(g_err, sizeof g_err, "%s (VkResult %d)", what, (int) r);
}

static const char *vendor_name(uint32_t id) {
    switch (id) {
        case 0x5143: return "Qualcomm";
        case 0x13B5: return "ARM";
        case 0x1010: return "Imagination";
        case 0x144D: return "Samsung";
        case 0x10DE: return "NVIDIA";
        case 0x1002: return "AMD";
        case 0x8086: return "Intel";
        case 0x1AE0: return "Google";
        case 0x10005: return "Mesa";
        default: return "?";
    }
}

static const char *type_name(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated GPU";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete GPU";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual GPU";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "CPU (software)";
        default: return "other";
    }
}

static int type_score(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return 4;
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return 3;
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return 2;
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return 1;
        default: return 0;
    }
}

static int has_device_ext(VkPhysicalDevice pd, const char *name) {
    uint32_t n = 0;
    if (vkEnumerateDeviceExtensionProperties(pd, NULL, &n, NULL) != VK_SUCCESS || n == 0) return 0;
    VkExtensionProperties *e = (VkExtensionProperties *) calloc(n, sizeof *e);
    if (!e) return 0;
    int found = 0;
    if (vkEnumerateDeviceExtensionProperties(pd, NULL, &n, e) == VK_SUCCESS)
        for (uint32_t i = 0; i < n && !found; i++) found = strcmp(e[i].extensionName, name) == 0;
    free(e);
    return found;
}

/* Device + driver description: what actually runs the shader. */
static void describe(HsocrVk *c, uint32_t instanceApi) {
    VkPhysicalDeviceProperties p;
    vkGetPhysicalDeviceProperties(c->phys, &p);
    uint32_t dv = p.driverVersion;
    char drv[160] = "";
    char driverName[VK_MAX_DRIVER_NAME_SIZE] = "";
    char driverInfo[VK_MAX_DRIVER_INFO_SIZE] = "";
    /* driver name / build (VK_KHR_driver_properties, core in 1.2); the 1.1 entry point is loaded
       dynamically so the library still loads on Android 8 (Vulkan 1.0 loader) */
    if (instanceApi >= VK_API_VERSION_1_1 && p.apiVersion >= VK_API_VERSION_1_1 &&
        (p.apiVersion >= VK_API_VERSION_1_2 || has_device_ext(c->phys, "VK_KHR_driver_properties"))) {
        PFN_vkGetPhysicalDeviceProperties2 get2 =
            (PFN_vkGetPhysicalDeviceProperties2) vkGetInstanceProcAddr(c->instance, "vkGetPhysicalDeviceProperties2");
        if (get2) {
            VkPhysicalDeviceDriverProperties dp;
            memset(&dp, 0, sizeof dp);
            dp.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
            VkPhysicalDeviceProperties2 p2;
            memset(&p2, 0, sizeof p2);
            p2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            p2.pNext = &dp;
            get2(c->phys, &p2);
            snprintf(driverName, sizeof driverName, "%s", dp.driverName);
            snprintf(driverInfo, sizeof driverInfo, "%s", dp.driverInfo);
        }
    }
    if (p.vendorID == 0x5143) /* Qualcomm encodes major.minor.patch like VK_MAKE_VERSION */
        snprintf(drv, sizeof drv, "%u.%u.%u", dv >> 22, (dv >> 12) & 0x3ff, dv & 0xfff);
    else if (p.vendorID == 0x10DE)
        snprintf(drv, sizeof drv, "%u.%u", (dv >> 22) & 0x3ff, (dv >> 14) & 0xff);
    else
        snprintf(drv, sizeof drv, "%u.%u.%u", VK_VERSION_MAJOR(dv), VK_VERSION_MINOR(dv), VK_VERSION_PATCH(dv));
    snprintf(c->info, sizeof c->info,
             "device=%s\nvendor=%s (0x%04X)\ntype=%s\nvulkan=%u.%u.%u\ndriverVersion=%s\ndriverName=%s\ndriverInfo=%s\nqueueFamily=%u",
             p.deviceName, vendor_name(p.vendorID), p.vendorID, type_name(p.deviceType),
             VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion), VK_VERSION_PATCH(p.apiVersion),
             drv, driverName, driverInfo, c->family);
}

static int mem_type(HsocrVk *c, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < c->mem.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (c->mem.memoryTypes[i].propertyFlags & want) == want) return (int) i;
    return -1;
}

static void free_buffers(HsocrVk *c) {
    if (c->mapIn) vkUnmapMemory(c->device, c->memIn);
    if (c->mapOut) vkUnmapMemory(c->device, c->memOut);
    if (c->bufIn) vkDestroyBuffer(c->device, c->bufIn, NULL);
    if (c->bufOut) vkDestroyBuffer(c->device, c->bufOut, NULL);
    if (c->memIn) vkFreeMemory(c->device, c->memIn, NULL);
    if (c->memOut) vkFreeMemory(c->device, c->memOut, NULL);
    c->mapIn = c->mapOut = NULL;
    c->bufIn = c->bufOut = VK_NULL_HANDLE;
    c->memIn = c->memOut = VK_NULL_HANDLE;
    c->capacity = 0;
}

static int make_buffer(HsocrVk *c, VkDeviceSize size, int forRead, VkBuffer *buf, VkDeviceMemory *mem, void **map, int *coherent) {
    VkBufferCreateInfo bi;
    memset(&bi, 0, sizeof bi);
    bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bi.size = size;
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult r = vkCreateBuffer(c->device, &bi, NULL, buf);
    if (r != VK_SUCCESS) { fail("vkCreateBuffer", r); return 0; }
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(c->device, *buf, &req);
    int t = -1;
    *coherent = 1;
    if (forRead) {
        /* reading back: cached memory is much faster for the CPU */
        t = mem_type(c, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        if (t < 0) {
            t = mem_type(c, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
            if (t >= 0) *coherent = 0;
        }
    }
    if (t < 0) t = mem_type(c, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (t < 0) { fail("no host-visible coherent memory", VK_ERROR_FEATURE_NOT_PRESENT); return 0; }
    VkMemoryAllocateInfo ai;
    memset(&ai, 0, sizeof ai);
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = (uint32_t) t;
    r = vkAllocateMemory(c->device, &ai, NULL, mem);
    if (r != VK_SUCCESS) { fail("vkAllocateMemory", r); return 0; }
    r = vkBindBufferMemory(c->device, *buf, *mem, 0);
    if (r != VK_SUCCESS) { fail("vkBindBufferMemory", r); return 0; }
    r = vkMapMemory(c->device, *mem, 0, VK_WHOLE_SIZE, 0, map);
    if (r != VK_SUCCESS) { fail("vkMapMemory", r); return 0; }
    return 1;
}

static int ensure(HsocrVk *c, VkDeviceSize bytes) {
    if (bytes <= c->capacity) return 1;
    vkDeviceWaitIdle(c->device);
    free_buffers(c);
    VkDeviceSize cap = bytes + 65536;
    int coherentIn = 1;
    if (!make_buffer(c, cap, 0, &c->bufIn, &c->memIn, &c->mapIn, &coherentIn)) return 0;
    if (!make_buffer(c, cap, 1, &c->bufOut, &c->memOut, &c->mapOut, &c->outCoherent)) return 0;
    VkDescriptorBufferInfo bin = {c->bufIn, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo bout = {c->bufOut, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet w[2];
    memset(w, 0, sizeof w);
    for (int i = 0; i < 2; i++) {
        w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[i].dstSet = c->dset;
        w[i].dstBinding = (uint32_t) i;
        w[i].descriptorCount = 1;
        w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w[i].pBufferInfo = i == 0 ? &bin : &bout;
    }
    vkUpdateDescriptorSets(c->device, 2, w, 0, NULL);
    c->capacity = cap;
    return 1;
}

HsocrVk *hsocr_vk_create(void) {
    g_err[0] = 0;
    HsocrVk *c = (HsocrVk *) calloc(1, sizeof *c);
    if (!c) { fail("out of memory", VK_ERROR_OUT_OF_HOST_MEMORY); return NULL; }

    uint32_t instanceApi = VK_API_VERSION_1_0;
    PFN_vkEnumerateInstanceVersion enumVersion =
        (PFN_vkEnumerateInstanceVersion) vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (enumVersion) enumVersion(&instanceApi);

    VkApplicationInfo app;
    memset(&app, 0, sizeof app);
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "HardSub OCR";
    app.applicationVersion = 1;
    app.pEngineName = "SubScan";
    app.apiVersion = instanceApi >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici;
    memset(&ici, 0, sizeof ici);
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;
    VkResult r = vkCreateInstance(&ici, NULL, &c->instance);
    if (r != VK_SUCCESS) { fail("vkCreateInstance", r); free(c); return NULL; }

    uint32_t n = 0;
    r = vkEnumeratePhysicalDevices(c->instance, &n, NULL);
    if (r != VK_SUCCESS || n == 0) { fail("no Vulkan device", r); hsocr_vk_destroy(c); return NULL; }
    VkPhysicalDevice *devs = (VkPhysicalDevice *) calloc(n, sizeof *devs);
    vkEnumeratePhysicalDevices(c->instance, &n, devs);
    int best = -1;
    for (uint32_t i = 0; i < n; i++) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(devs[i], &p);
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devs[i], &qn, NULL);
        VkQueueFamilyProperties *q = (VkQueueFamilyProperties *) calloc(qn ? qn : 1, sizeof *q);
        vkGetPhysicalDeviceQueueFamilyProperties(devs[i], &qn, q);
        for (uint32_t f = 0; f < qn; f++) {
            if (!(q[f].queueFlags & VK_QUEUE_COMPUTE_BIT)) continue;
            if (type_score(p.deviceType) > best) {
                best = type_score(p.deviceType);
                c->phys = devs[i];
                c->family = f;
            }
            break;
        }
        free(q);
    }
    free(devs);
    if (best < 0) { fail("no Vulkan compute queue", VK_ERROR_FEATURE_NOT_PRESENT); hsocr_vk_destroy(c); return NULL; }
    vkGetPhysicalDeviceMemoryProperties(c->phys, &c->mem);

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci;
    memset(&qci, 0, sizeof qci);
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = c->family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci;
    memset(&dci, 0, sizeof dci);
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    r = vkCreateDevice(c->phys, &dci, NULL, &c->device);
    if (r != VK_SUCCESS) { fail("vkCreateDevice", r); hsocr_vk_destroy(c); return NULL; }
    vkGetDeviceQueue(c->device, c->family, 0, &c->queue);
    describe(c, instanceApi);

    VkShaderModuleCreateInfo smci;
    memset(&smci, 0, sizeof smci);
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = sizeof kSpv;
    smci.pCode = kSpv;
    r = vkCreateShaderModule(c->device, &smci, NULL, &c->shader);
    if (r != VK_SUCCESS) { fail("vkCreateShaderModule", r); hsocr_vk_destroy(c); return NULL; }

    VkDescriptorSetLayoutBinding b[2];
    memset(b, 0, sizeof b);
    for (int i = 0; i < 2; i++) {
        b[i].binding = (uint32_t) i;
        b[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        b[i].descriptorCount = 1;
        b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo dslci;
    memset(&dslci, 0, sizeof dslci);
    dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslci.bindingCount = 2;
    dslci.pBindings = b;
    r = vkCreateDescriptorSetLayout(c->device, &dslci, NULL, &c->dsl);
    if (r != VK_SUCCESS) { fail("vkCreateDescriptorSetLayout", r); hsocr_vk_destroy(c); return NULL; }

    VkPushConstantRange pcr = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 12 * sizeof(int32_t)};
    VkPipelineLayoutCreateInfo plci;
    memset(&plci, 0, sizeof plci);
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &c->dsl;
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr;
    r = vkCreatePipelineLayout(c->device, &plci, NULL, &c->layout);
    if (r != VK_SUCCESS) { fail("vkCreatePipelineLayout", r); hsocr_vk_destroy(c); return NULL; }

    VkComputePipelineCreateInfo cpci;
    memset(&cpci, 0, sizeof cpci);
    cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = c->shader;
    cpci.stage.pName = "main";
    cpci.layout = c->layout;
    cpci.basePipelineIndex = -1;
    r = vkCreateComputePipelines(c->device, VK_NULL_HANDLE, 1, &cpci, NULL, &c->pipeline);
    if (r != VK_SUCCESS) { fail("vkCreateComputePipelines", r); hsocr_vk_destroy(c); return NULL; }

    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2};
    VkDescriptorPoolCreateInfo dpci;
    memset(&dpci, 0, sizeof dpci);
    dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpci.maxSets = 1;
    dpci.poolSizeCount = 1;
    dpci.pPoolSizes = &ps;
    r = vkCreateDescriptorPool(c->device, &dpci, NULL, &c->dpool);
    if (r != VK_SUCCESS) { fail("vkCreateDescriptorPool", r); hsocr_vk_destroy(c); return NULL; }
    VkDescriptorSetAllocateInfo dsai;
    memset(&dsai, 0, sizeof dsai);
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = c->dpool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &c->dsl;
    r = vkAllocateDescriptorSets(c->device, &dsai, &c->dset);
    if (r != VK_SUCCESS) { fail("vkAllocateDescriptorSets", r); hsocr_vk_destroy(c); return NULL; }

    VkCommandPoolCreateInfo cpi;
    memset(&cpi, 0, sizeof cpi);
    cpi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpi.queueFamilyIndex = c->family;
    r = vkCreateCommandPool(c->device, &cpi, NULL, &c->cpool);
    if (r != VK_SUCCESS) { fail("vkCreateCommandPool", r); hsocr_vk_destroy(c); return NULL; }
    VkCommandBufferAllocateInfo cbai;
    memset(&cbai, 0, sizeof cbai);
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = c->cpool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    r = vkAllocateCommandBuffers(c->device, &cbai, &c->cmd);
    if (r != VK_SUCCESS) { fail("vkAllocateCommandBuffers", r); hsocr_vk_destroy(c); return NULL; }
    VkFenceCreateInfo fci;
    memset(&fci, 0, sizeof fci);
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    r = vkCreateFence(c->device, &fci, NULL, &c->fence);
    if (r != VK_SUCCESS) { fail("vkCreateFence", r); hsocr_vk_destroy(c); return NULL; }
    return c;
}

const char *hsocr_vk_info(HsocrVk *c) { return c ? c->info : ""; }

int hsocr_vk_classify(HsocrVk *c, const uint8_t *luma, int w, int h, const int32_t params[12], uint8_t *out) {
    if (!c || w <= 0 || h <= 0) { fail("bad arguments", VK_ERROR_INITIALIZATION_FAILED); return 0; }
    const int n = w * h;
    const int words = (n + 3) / 4;
    const VkDeviceSize bytes = (VkDeviceSize) words * 4;
    if (!ensure(c, bytes)) return 0;
    memcpy(c->mapIn, luma, (size_t) n);
    memset((uint8_t *) c->mapIn + n, 0, (size_t) (bytes - n));

    VkResult r = vkResetCommandBuffer(c->cmd, 0);
    if (r != VK_SUCCESS) { fail("vkResetCommandBuffer", r); return 0; }
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof bi);
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    r = vkBeginCommandBuffer(c->cmd, &bi);
    if (r != VK_SUCCESS) { fail("vkBeginCommandBuffer", r); return 0; }
    vkCmdBindPipeline(c->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, c->pipeline);
    vkCmdBindDescriptorSets(c->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, c->layout, 0, 1, &c->dset, 0, NULL);
    vkCmdPushConstants(c->cmd, c->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 12 * sizeof(int32_t), params);
    vkCmdDispatch(c->cmd, (uint32_t) ((words + 63) / 64), 1, 1);
    VkMemoryBarrier mb;
    memset(&mb, 0, sizeof mb);
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(c->cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    r = vkEndCommandBuffer(c->cmd);
    if (r != VK_SUCCESS) { fail("vkEndCommandBuffer", r); return 0; }

    VkSubmitInfo si;
    memset(&si, 0, sizeof si);
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &c->cmd;
    r = vkQueueSubmit(c->queue, 1, &si, c->fence);
    if (r != VK_SUCCESS) { fail("vkQueueSubmit", r); return 0; }
    r = vkWaitForFences(c->device, 1, &c->fence, VK_TRUE, 5000000000ull);
    vkResetFences(c->device, 1, &c->fence);
    if (r != VK_SUCCESS) { fail("vkWaitForFences", r); return 0; }
    if (!c->outCoherent) {
        VkMappedMemoryRange range;
        memset(&range, 0, sizeof range);
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = c->memOut;
        range.size = VK_WHOLE_SIZE;
        vkInvalidateMappedMemoryRanges(c->device, 1, &range);
    }
    memcpy(out, c->mapOut, (size_t) n);
    return 1;
}

void hsocr_vk_destroy(HsocrVk *c) {
    if (!c) return;
    if (c->device) {
        vkDeviceWaitIdle(c->device);
        free_buffers(c);
        if (c->fence) vkDestroyFence(c->device, c->fence, NULL);
        if (c->cpool) vkDestroyCommandPool(c->device, c->cpool, NULL);
        if (c->dpool) vkDestroyDescriptorPool(c->device, c->dpool, NULL);
        if (c->pipeline) vkDestroyPipeline(c->device, c->pipeline, NULL);
        if (c->layout) vkDestroyPipelineLayout(c->device, c->layout, NULL);
        if (c->dsl) vkDestroyDescriptorSetLayout(c->device, c->dsl, NULL);
        if (c->shader) vkDestroyShaderModule(c->device, c->shader, NULL);
        vkDestroyDevice(c->device, NULL);
    }
    if (c->instance) vkDestroyInstance(c->instance, NULL);
    free(c);
}

/* ------------------------------------------------------------------------------------ JNI */
#ifdef __ANDROID__
#include <jni.h>
#include <stdint.h>

#define JNI_FN(name) Java_com_abdulrahman_hardsubocr_video_VulkanNative_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (jlong) (intptr_t) hsocr_vk_create();
}

JNIEXPORT jstring JNICALL JNI_FN(nativeInfo)(JNIEnv *env, jclass cls, jlong h) {
    (void) cls;
    return (*env)->NewStringUTF(env, hsocr_vk_info((HsocrVk *) (intptr_t) h));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeError)(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, hsocr_vk_error());
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeClassify)(JNIEnv *env, jclass cls, jlong h, jbyteArray luma, jint w, jint hgt,
                                                  jintArray params, jbyteArray out) {
    (void) cls;
    HsocrVk *c = (HsocrVk *) (intptr_t) h;
    const int n = w * hgt;
    if (!c || n <= 0 || (*env)->GetArrayLength(env, luma) < n || (*env)->GetArrayLength(env, out) < n ||
        (*env)->GetArrayLength(env, params) < 12)
        return JNI_FALSE;
    jint prm[12];
    (*env)->GetIntArrayRegion(env, params, 0, 12, prm);
    int32_t p32[12];
    for (int i = 0; i < 12; i++) p32[i] = (int32_t) prm[i];
    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, luma, NULL);
    if (!in) return JNI_FALSE;
    uint8_t *tmp = (uint8_t *) malloc((size_t) n);
    if (!tmp) { (*env)->ReleasePrimitiveArrayCritical(env, luma, in, JNI_ABORT); return JNI_FALSE; }
    memcpy(tmp, in, (size_t) n);
    (*env)->ReleasePrimitiveArrayCritical(env, luma, in, JNI_ABORT);
    uint8_t *res = (uint8_t *) malloc((size_t) n);
    int ok = res && hsocr_vk_classify(c, tmp, w, hgt, p32, res);
    free(tmp);
    if (ok) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) res);
    free(res);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    hsocr_vk_destroy((HsocrVk *) (intptr_t) h);
}
#endif
