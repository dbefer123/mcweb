package dev.mcwebclient.web.gpu.spirv;

/** SPIR-V enumerant values used by the parser, reflection and WGSL translator. */
public final class Spirv {
    private Spirv() {
    }

    public static final int MAGIC = 0x07230203;

    // Opcodes
    public static final int OpNop = 0, OpUndef = 1, OpSourceContinued = 2, OpSource = 3, OpSourceExtension = 4,
            OpName = 5, OpMemberName = 6, OpString = 7, OpLine = 8, OpExtension = 10, OpExtInstImport = 11,
            OpExtInst = 12, OpMemoryModel = 14, OpEntryPoint = 15, OpExecutionMode = 16, OpCapability = 17,
            OpTypeVoid = 19, OpTypeBool = 20, OpTypeInt = 21, OpTypeFloat = 22, OpTypeVector = 23,
            OpTypeMatrix = 24, OpTypeImage = 25, OpTypeSampler = 26, OpTypeSampledImage = 27, OpTypeArray = 28,
            OpTypeRuntimeArray = 29, OpTypeStruct = 30, OpTypeOpaque = 31, OpTypePointer = 32,
            OpTypeFunction = 33, OpConstantTrue = 41, OpConstantFalse = 42, OpConstant = 43,
            OpConstantComposite = 44, OpConstantSampler = 45, OpConstantNull = 46, OpSpecConstantTrue = 48,
            OpSpecConstantFalse = 49, OpSpecConstant = 50, OpSpecConstantComposite = 51, OpSpecConstantOp = 52,
            OpFunction = 54, OpFunctionParameter = 55, OpFunctionEnd = 56, OpFunctionCall = 57, OpVariable = 59,
            OpImageTexelPointer = 60, OpLoad = 61, OpStore = 62, OpCopyMemory = 63, OpAccessChain = 65,
            OpInBoundsAccessChain = 66, OpPtrAccessChain = 67, OpArrayLength = 68, OpDecorate = 71,
            OpMemberDecorate = 72, OpDecorationGroup = 73, OpGroupDecorate = 74, OpGroupMemberDecorate = 75,
            OpVectorExtractDynamic = 77, OpVectorInsertDynamic = 78, OpVectorShuffle = 79,
            OpCompositeConstruct = 80, OpCompositeExtract = 81, OpCompositeInsert = 82, OpCopyObject = 83,
            OpTranspose = 84, OpSampledImage = 86, OpImageSampleImplicitLod = 87, OpImageSampleExplicitLod = 88,
            OpImageSampleDrefImplicitLod = 89, OpImageSampleDrefExplicitLod = 90,
            OpImageSampleProjImplicitLod = 91, OpImageSampleProjExplicitLod = 92,
            OpImageSampleProjDrefImplicitLod = 93, OpImageSampleProjDrefExplicitLod = 94, OpImageFetch = 95,
            OpImageGather = 96, OpImageDrefGather = 97, OpImageRead = 98, OpImageWrite = 99, OpImage = 100,
            OpImageQuerySizeLod = 103, OpImageQuerySize = 104, OpImageQueryLod = 105, OpImageQueryLevels = 106,
            OpImageQuerySamples = 107, OpConvertFToU = 109, OpConvertFToS = 110, OpConvertSToF = 111,
            OpConvertUToF = 112, OpUConvert = 113, OpSConvert = 114, OpFConvert = 115, OpQuantizeToF16 = 116,
            OpBitcast = 124, OpSNegate = 126, OpFNegate = 127, OpIAdd = 128, OpFAdd = 129, OpISub = 130,
            OpFSub = 131, OpIMul = 132, OpFMul = 133, OpUDiv = 134, OpSDiv = 135, OpFDiv = 136, OpUMod = 137,
            OpSRem = 138, OpSMod = 139, OpFRem = 140, OpFMod = 141, OpVectorTimesScalar = 142,
            OpMatrixTimesScalar = 143, OpVectorTimesMatrix = 144, OpMatrixTimesVector = 145,
            OpMatrixTimesMatrix = 146, OpOuterProduct = 147, OpDot = 148, OpAny = 154, OpAll = 155,
            OpIsNan = 156, OpIsInf = 157, OpLogicalEqual = 164, OpLogicalNotEqual = 165, OpLogicalOr = 166,
            OpLogicalAnd = 167, OpLogicalNot = 168, OpSelect = 169, OpIEqual = 170, OpINotEqual = 171,
            OpUGreaterThan = 172, OpSGreaterThan = 173, OpUGreaterThanEqual = 174, OpSGreaterThanEqual = 175,
            OpULessThan = 176, OpSLessThan = 177, OpULessThanEqual = 178, OpSLessThanEqual = 179,
            OpFOrdEqual = 180, OpFUnordEqual = 181, OpFOrdNotEqual = 182, OpFUnordNotEqual = 183,
            OpFOrdLessThan = 184, OpFUnordLessThan = 185, OpFOrdGreaterThan = 186, OpFUnordGreaterThan = 187,
            OpFOrdLessThanEqual = 188, OpFUnordLessThanEqual = 189, OpFOrdGreaterThanEqual = 190,
            OpFUnordGreaterThanEqual = 191, OpShiftRightLogical = 194, OpShiftRightArithmetic = 195,
            OpShiftLeftLogical = 196, OpBitwiseOr = 197, OpBitwiseXor = 198, OpBitwiseAnd = 199, OpNot = 200,
            OpBitFieldInsert = 201, OpBitFieldSExtract = 202, OpBitFieldUExtract = 203, OpBitReverse = 204,
            OpBitCount = 205, OpDPdx = 207, OpDPdy = 208, OpFwidth = 209, OpDPdxFine = 210, OpDPdyFine = 211,
            OpFwidthFine = 212, OpDPdxCoarse = 213, OpDPdyCoarse = 214, OpFwidthCoarse = 215, OpPhi = 245,
            OpLoopMerge = 246, OpSelectionMerge = 247, OpLabel = 248, OpBranch = 249, OpBranchConditional = 250,
            OpSwitch = 251, OpKill = 252, OpReturn = 253, OpReturnValue = 254, OpUnreachable = 255,
            OpNoLine = 317, OpModuleProcessed = 330, OpExecutionModeId = 331, OpDecorateId = 332,
            OpCopyLogical = 400, OpTerminateInvocation = 4416, OpDemoteToHelperInvocation = 5380,
            OpDecorateString = 5632, OpMemberDecorateString = 5633;

    // Decorations
    public static final int DecorationBlock = 2, DecorationBufferBlock = 3, DecorationRowMajor = 4,
            DecorationColMajor = 5, DecorationArrayStride = 6, DecorationMatrixStride = 7, DecorationBuiltIn = 11,
            DecorationNoPerspective = 13, DecorationFlat = 14, DecorationCentroid = 16, DecorationSample = 17,
            DecorationInvariant = 18, DecorationNonWritable = 24, DecorationLocation = 30,
            DecorationComponent = 31, DecorationIndex = 32, DecorationBinding = 33, DecorationDescriptorSet = 34,
            DecorationOffset = 35;

    // Storage classes
    public static final int UniformConstant = 0, Input = 1, Uniform = 2, Output = 3, Workgroup = 4, Private = 6,
            Function = 7, PushConstant = 9, StorageBuffer = 12;

    // Built-ins
    public static final int BuiltInPosition = 0, BuiltInPointSize = 1, BuiltInClipDistance = 3,
            BuiltInCullDistance = 4, BuiltInVertexId = 5, BuiltInInstanceId = 6, BuiltInFragCoord = 15,
            BuiltInPointCoord = 16, BuiltInFrontFacing = 17, BuiltInSampleId = 18, BuiltInSampleMask = 20,
            BuiltInFragDepth = 22, BuiltInHelperInvocation = 23, BuiltInVertexIndex = 42,
            BuiltInInstanceIndex = 43, BuiltInBaseVertex = 4424, BuiltInBaseInstance = 4425, BuiltInDrawIndex = 4426;

    // Image dimensions
    public static final int Dim1D = 0, Dim2D = 1, Dim3D = 2, DimCube = 3, DimRect = 4, DimBuffer = 5;

    // Execution models
    public static final int ExecutionModelVertex = 0, ExecutionModelFragment = 4, ExecutionModelGLCompute = 5;

    // Image operand mask bits
    public static final int ImageOperandsBias = 0x1, ImageOperandsLod = 0x2, ImageOperandsGrad = 0x4,
            ImageOperandsConstOffset = 0x8, ImageOperandsOffset = 0x10, ImageOperandsConstOffsets = 0x20,
            ImageOperandsSample = 0x40, ImageOperandsMinLod = 0x80;
}
