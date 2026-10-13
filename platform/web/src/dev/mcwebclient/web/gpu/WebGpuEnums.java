package dev.mcwebclient.web.gpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

/** renderpearl enums → the integer codes webgpu.js decodes. */
final class WebGpuEnums {
    private WebGpuEnums() {
    }

    // webgpu.js TOPOLOGY
    static final int POINT_LIST = 0, LINE_LIST = 1, LINE_STRIP = 2, TRIANGLE_LIST = 3, TRIANGLE_STRIP = 4;
    // webgpu.js VIEW_DIM
    static final int VIEW_2D = 0, VIEW_2D_ARRAY = 1, VIEW_CUBE = 2, VIEW_CUBE_ARRAY = 3, VIEW_3D = 4;
    // webgpu.js SAMPLE_TYPE
    static final int SAMPLE_FLOAT = 0, SAMPLE_UNFILTERABLE = 1, SAMPLE_DEPTH = 2, SAMPLE_SINT = 3, SAMPLE_UINT = 4;
    // webgpu.js SAMPLER_TYPE
    static final int SAMPLER_FILTERING = 0, SAMPLER_NON_FILTERING = 1, SAMPLER_COMPARISON = 2;
    // webgpu.js ASPECT
    static final int ASPECT_ALL = 0, ASPECT_DEPTH = 1;

    /** GpuFormat ordinal = index into webgpu.js TEXTURE_FORMATS / VERTEX_FORMATS. */
    static int format(GpuFormat format) {
        return format.ordinal();
    }

    static boolean textureFormatSupported(GpuFormat f) {
        return switch (f) {
            case RGB8_UNORM, RGB8_SNORM, RGB16_UNORM, RGB16_SNORM, RGB8_UINT, RGB8_SINT, RGB16_UINT, RGB16_SINT,
                    RGB32_UINT, RGB32_SINT, RGB16_FLOAT, RGB32_FLOAT -> false;
            case R16_UNORM, R16_SNORM, RG16_UNORM, RG16_SNORM, RGBA16_UNORM, RGBA16_SNORM ->
                WebGpuDevice.features().contains("texture-formats-tier1");
            case D32_FLOAT_S8_UINT -> WebGpuDevice.features().contains("depth32float-stencil8");
            default -> true;
        };
    }

    /** Whether a texture of this format can be sampled with a filtering sampler. */
    static boolean filterable(GpuFormat f) {
        if (f.hasDepthAspect() || f.hasStencilAspect()) {
            return false;
        }
        return switch (f.componentType()) {
            case FLOAT_32 -> WebGpuDevice.features().contains("float32-filterable");
            case UINT_8, SINT_8, UINT_16, SINT_16, UINT_32, SINT_32 -> false;
            default -> f != GpuFormat.RGB10A2_UINT;
        };
    }

    static boolean isInteger(GpuFormat f) {
        return switch (f.componentType()) {
            case UINT_8, SINT_8, UINT_16, SINT_16, UINT_32, SINT_32 -> true;
            default -> f == GpuFormat.RGB10A2_UINT;
        };
    }

    static int topology(PrimitiveTopology t) {
        return switch (t) {
            case POINTS -> POINT_LIST;
            case DEBUG_LINES -> LINE_LIST;
            case DEBUG_LINE_STRIP -> LINE_STRIP;
            case TRIANGLE_STRIP -> TRIANGLE_STRIP;
            // QUADS and LINES are drawn as indexed triangles; fans are expanded to triangle lists.
            case TRIANGLES, QUADS, LINES, TRIANGLE_FAN -> TRIANGLE_LIST;
        };
    }

    /** webgpu.js COMPARE: never, less, equal, less-equal, greater, not-equal, greater-equal, always. */
    static int compare(CompareOp op) {
        return switch (op) {
            case NEVER_PASS -> 0;
            case LESS_THAN -> 1;
            case EQUAL -> 2;
            case LESS_THAN_OR_EQUAL -> 3;
            case GREATER_THAN -> 4;
            case NOT_EQUAL -> 5;
            case GREATER_THAN_OR_EQUAL -> 6;
            case ALWAYS_PASS -> 7;
        };
    }

    /** webgpu.js BLEND_FACTOR. */
    static int blendFactor(BlendFactor f) {
        return switch (f) {
            case ZERO -> 0;
            case ONE -> 1;
            case SRC_COLOR -> 2;
            case ONE_MINUS_SRC_COLOR -> 3;
            case DST_COLOR -> 4;
            case ONE_MINUS_DST_COLOR -> 5;
            case SRC_ALPHA -> 6;
            case ONE_MINUS_SRC_ALPHA -> 7;
            case DST_ALPHA -> 8;
            case ONE_MINUS_DST_ALPHA -> 9;
            case CONSTANT_COLOR, CONSTANT_ALPHA -> 10;
            case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> 11;
            case SRC_ALPHA_SATURATE -> 12;
        };
    }

    static int blendOp(BlendOp op) {
        return op.ordinal(); // add, subtract, reverse-subtract, min, max
    }
}
