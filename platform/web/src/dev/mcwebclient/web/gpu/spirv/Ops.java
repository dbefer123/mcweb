package dev.mcwebclient.web.gpu.spirv;

import java.util.BitSet;

import static dev.mcwebclient.web.gpu.spirv.Spirv.*;

/** Instruction shapes: which opcodes produce a result id, and which also carry a result type. */
final class Ops {
    private Ops() {
    }

    /** Opcodes without a result id (statements, declarations, control flow). */
    private static final BitSet NO_RESULT = new BitSet();
    /** Opcodes with a result id but no result type. */
    private static final BitSet NO_RESULT_TYPE = new BitSet();

    static {
        int[] noResult = {OpNop, OpSourceContinued, OpSource, OpSourceExtension, OpName, OpMemberName, OpLine,
            OpExtension, OpMemoryModel, OpEntryPoint, OpExecutionMode, OpCapability, OpFunctionEnd, OpStore,
            OpCopyMemory, OpDecorate, OpMemberDecorate, OpGroupDecorate, OpGroupMemberDecorate, OpImageWrite,
            224 /* OpControlBarrier */, 225 /* OpMemoryBarrier */, OpLoopMerge, OpSelectionMerge, OpBranch,
            OpBranchConditional, OpSwitch, OpKill, OpReturn, OpReturnValue, OpUnreachable, OpNoLine,
            OpModuleProcessed, OpExecutionModeId, OpDecorateId, OpTerminateInvocation,
            OpDemoteToHelperInvocation, OpDecorateString, OpMemberDecorateString};
        for (int op : noResult) {
            NO_RESULT.set(op);
        }
        int[] noType = {OpString, OpExtInstImport, OpLabel, OpDecorationGroup};
        for (int op : noType) {
            NO_RESULT_TYPE.set(op);
        }
        for (int op = OpTypeVoid; op <= OpTypeFunction; op++) {
            NO_RESULT_TYPE.set(op);
        }
    }

    static boolean hasResult(int op) {
        return !NO_RESULT.get(op);
    }

    static boolean hasResultType(int op) {
        return !NO_RESULT.get(op) && !NO_RESULT_TYPE.get(op);
    }
}
