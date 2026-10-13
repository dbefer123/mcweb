// WebAssembly entry points around glslang's C interface, built with wasi-sdk
// (see build.mjs). The browser host (web/dev/shaders.js) calls these to compile
// Minecraft's GLSL to SPIR-V the way renderpearl's GlslCompiler does with shaderc:
// Vulkan 1.2 / SPIR-V 1.5, Vulkan rules, automatic binding numbers.
//
// Includes are expanded and defines collected on the Java side, so a compile is
// one source string plus one preamble string (#define lines).
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "glslang/Include/glslang_c_interface.h"
#include "glslang/Public/resource_limits_c.h"

#define EXPORT(name) __attribute__((export_name(#name)))

typedef struct {
    int32_t ok;          // 1 = SPIR-V produced
    uint32_t* words;     // SPIR-V words (owned by this result)
    int32_t word_count;
    char* log;           // NUL-terminated diagnostics (owned by this result), may be empty
} gw_result;

static int initialized;

EXPORT(gw_alloc) void* gw_alloc(int32_t size) {
    return malloc((size_t) size);
}

EXPORT(gw_free) void gw_free(void* p) {
    free(p);
}

static char* dup_log(const char* a, const char* b) {
    size_t la = a ? strlen(a) : 0;
    size_t lb = b ? strlen(b) : 0;
    char* out = malloc(la + lb + 2);
    if (!out) return NULL;
    size_t n = 0;
    if (la) { memcpy(out, a, la); n += la; }
    if (la && lb) out[n++] = '\n';
    if (lb) { memcpy(out + n, b, lb); n += lb; }
    out[n] = 0;
    return out;
}

// stage: 0 = vertex, 4 = fragment, 5 = compute (glslang_stage_t).
EXPORT(gw_compile) gw_result* gw_compile(int32_t stage, const char* source, const char* preamble) {
    gw_result* r = calloc(1, sizeof(gw_result));
    if (!r) return NULL;
    if (!initialized) {
        glslang_initialize_process();
        initialized = 1;
    }
    const int messages = GLSLANG_MSG_SPV_RULES_BIT | GLSLANG_MSG_VULKAN_RULES_BIT;
    glslang_input_t input = {
        .language = GLSLANG_SOURCE_GLSL,
        .stage = (glslang_stage_t) stage,
        .client = GLSLANG_CLIENT_VULKAN,
        .client_version = GLSLANG_TARGET_VULKAN_1_2,
        .target_language = GLSLANG_TARGET_SPV,
        .target_language_version = GLSLANG_TARGET_SPV_1_5,
        .code = source,
        .default_version = 110,
        .default_profile = GLSLANG_NO_PROFILE,
        .force_default_version_and_profile = 0,
        .forward_compatible = 0,
        .messages = (glslang_messages_t) messages,
        .resource = glslang_default_resource(),
    };
    glslang_shader_t* shader = glslang_shader_create(&input);
    if (preamble && *preamble) glslang_shader_set_preamble(shader, preamble);
    glslang_shader_set_options(shader, GLSLANG_SHADER_AUTO_MAP_BINDINGS);
    if (!glslang_shader_preprocess(shader, &input) || !glslang_shader_parse(shader, &input)) {
        r->log = dup_log(glslang_shader_get_info_log(shader), glslang_shader_get_info_debug_log(shader));
        glslang_shader_delete(shader);
        return r;
    }
    glslang_program_t* program = glslang_program_create();
    glslang_program_add_shader(program, shader);
    if (!glslang_program_link(program, messages) || !glslang_program_map_io(program)) {
        r->log = dup_log(glslang_program_get_info_log(program), glslang_program_get_info_debug_log(program));
        glslang_program_delete(program);
        glslang_shader_delete(shader);
        return r;
    }
    glslang_spv_options_t options = {0};
    options.disable_optimizer = true;
    glslang_program_SPIRV_generate_with_options(program, (glslang_stage_t) stage, &options);
    size_t count = glslang_program_SPIRV_get_size(program);
    r->words = malloc(count * sizeof(uint32_t));
    if (r->words) {
        glslang_program_SPIRV_get(program, r->words);
        r->word_count = (int32_t) count;
        r->ok = count > 0;
    }
    r->log = dup_log(glslang_shader_get_info_log(shader), glslang_program_SPIRV_get_messages(program));
    glslang_program_delete(program);
    glslang_shader_delete(shader);
    return r;
}

EXPORT(gw_result_free) void gw_result_free(gw_result* r) {
    if (!r) return;
    free(r->words);
    free(r->log);
    free(r);
}
