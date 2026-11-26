package com.github.nyabanban.fuji.emulator.desktop

/** One bounded before-instruction episode. This stores observations; it does not qualify execution or supply guest values. */
internal class KernelLateCallObservation(
    private val cutoffAttempt:Long,
    private val cutoffTick:Long,
    entryRegisters:LongArray,
    private val tailHookLimit:Int=1040,
    private val mmioLimit:Int=1024,
    private val predicateReturn:Boolean=false,
    private val outputReturn:Boolean=false,
    private val outputFollowup:Boolean=false,
    private val extendedFollowup:Boolean=false,
) {
    companion object {
        const val KEY="kernelLateCallObservation"
        const val PROFILE="kernel-current-call-late-v1"
        const val PREDICATE_PROFILE="kernel-predicate-return-late-v1"
        const val OUTPUT_PROFILE="kernel-output-return-late-v1"
        const val FOLLOWUP_PROFILE="kernel-output-followup-late-v1"
        const val EXTENDED_FOLLOWUP_PROFILE="kernel-output-followup-extended-late-v1"
        const val EXTENDED_FOLLOWUP_CAPACITY=8192
        const val CAPACITY=32
        const val OUTPUT_CAPACITY=1024
        private const val STRIDE=20
        private val sites=longArrayOf(0x51f34,0x48e14,0x48e18,0x48e1c,0x48e20,0x48e24,0x48e28,0x48e2c,
            0x48e30,0x48e34,0x48e38,0x51f38,0x51f3c,0x51f40,0x51f44)
        private val words=longArrayOf(0xebffdbb6,0xe10f3000,0xf10c0080,0xee102fb0,0xe2022003,0xe1a02102,
            0xe59f100c,0xe0811002,0xe5910000,0xe121f003,0xe1a0f00e,0xe30b3c20,0xe3403005,0xe1530000,0x0a000003)
        private val secondWords=longArrayOf(0xe10f3000,0xf10c0080,0xee102fb0,0xe2022003,0xe1a02102,
            0xe59f100c,0xe0811002,0xe5910000,0xe121f003,0xe1a0f00e)
        private val tailWords=longArrayOf(0xe2900000,0x13a00001,0xe8bd8800)
        fun select(raw:Any?):KernelLateCallObservation? {
            if(raw==null)return null
            require(raw==PROFILE||raw==PREDICATE_PROFILE||raw==OUTPUT_PROFILE||raw==FOLLOWUP_PROFILE||raw==EXTENDED_FOLLOWUP_PROFILE){"unknown kernel late-call observation profile"}
            return KernelLateCallObservation(136887635,162001107,longArrayOf(
                0x3e,0x3e,0x1bf6977,0x1edc,0,8,0x1dd4,0x1c733a4,1,0,0x1a674d8,0x1a682dc,0x1bf6977,
                0x1a682d8,0x57df0,0x51f34,0x80000053),
                tailHookLimit=when(raw){EXTENDED_FOLLOWUP_PROFILE->16384;OUTPUT_PROFILE,FOLLOWUP_PROFILE->4096;PREDICATE_PROFILE->1054;else->1040},
                predicateReturn=raw!=PROFILE,outputReturn=raw==OUTPUT_PROFILE||raw==FOLLOWUP_PROFILE||raw==EXTENDED_FOLLOWUP_PROFILE,
                outputFollowup=raw==FOLLOWUP_PROFILE||raw==EXTENDED_FOLLOWUP_PROFILE,extendedFollowup=raw==EXTENDED_FOLLOWUP_PROFILE)
        }
    }
    /** R0..R12, SP, LR, PC, CPSR. No write, memory, translation, context-switch or time capability. */
    fun interface Registers { fun read(index:Int):Long }
    enum class Reason {
        CUTOFF_MISMATCH, HOOK_ACCOUNTING, ENTRY_MISMATCH, PC_REGISTER_MISMATCH, UNEXPECTED_PC,
        RUNTIME_WORD_MISMATCH, LINK_OR_STACK_MISMATCH, IRQ, UNDEFINED, EXECUTION_STOP,
        TAIL_HOOK_LIMIT, MMIO_LIMIT, CAPACITY, CALLBACK_FAILURE, NATIVE_ERROR, NATIVE_RUN_FAILURE, POST_TERMINAL_MMIO,
        INSTRUCTION_STATE_MISMATCH
    }
    class MmioStop:IllegalStateException("kernel-late-call observer MMIO limit; no router transaction")
    private val entry=entryRegisters.copyOf()
    private val capacity=if(extendedFollowup)EXTENDED_FOLLOWUP_CAPACITY else if(outputReturn)OUTPUT_CAPACITY else CAPACITY
    private val events=LongArray(capacity*STRIDE)
    private enum class OutputPhase { PREDICATE, CALLER_PREFIX, CALLEE_PROLOGUE, CALLEE_BODY, CALLEE_RETURN, CALLER_TAIL, CALLER_RETURN, RAW_TAIL }
    private var outputPhase=OutputPhase.PREDICATE
    private var outputStep=0
    private var callerFp=-1L
    private var callerSp=-1L
    private val outputEntry=LongArray(6) // R0,R4,R5,FP,SP,LR, copied only from the already retained entry sample.
    private var outputEntryIndex=-1
    private var outputPopIndex=-1
    private var outputReturnIndex=-1
    private var callerLoadIndex=-1
    private var callerLoadContinuationIndex=-1
    private var callerValueIndex=-1
    private var callerPopIndex=-1
    private var callerReturnIndex=-1
    private var differentFramePops=0L
    private var outputStatus=-1L
    private var callerValue=-1L
    private var cutoffSeen=false
    private var lastTick=-1L
    private var hooks=0
    private var observed=0L
    private var retained=0
    private var mmioAttempts=0L
    private var mmioAllowedToRouter=0L
    private var terminalMmioRejections=0L
    private val firstPostTerminalMmio=LongArray(7)
    private var branchDestination=-1L
    private var complete=false
    private var completionCandidate=false
    private var completionCandidateObserved=false
    private var completionNativeChecked=false
    private var completionBackendReturned=false
    private var completionCallbackFailed:Boolean?=null
    private var completionNativeError:Int?=null
    private var completionRunThrew:Boolean?=null
    private var reason:Reason?=null
    private var stopTick=-1L
    private var stopCore=-1
    private var stopPc=-1L
    private var rejectedMmio=false
    private var rejectedWrite=false
    private var rejectedAddress=-1L
    private var rejectedWidth=-1
    private var rejectedValue=0L
    init {
        require(cutoffAttempt>0&&cutoffTick>=cutoffAttempt&&entry.size==17&&entry.all{it in 0..0xffff_ffffL})
        require(!outputReturn||predicateReturn)
        require(!outputFollowup||outputReturn)
        require(!extendedFollowup||outputFollowup)
        require(entry[15]==sites[0]&&tailHookLimit in (if(extendedFollowup)30..16384 else if(outputReturn)30..4096 else if(predicateReturn)30..1054 else 16..1040)&&mmioLimit in 1..1024)
    }
    val requiresNativeFinalization:Boolean get()=predicateReturn
    private val profile:String get()=if(extendedFollowup)EXTENDED_FOLLOWUP_PROFILE else if(outputFollowup)FOLLOWUP_PROFILE else if(outputReturn)OUTPUT_PROFILE else if(predicateReturn)PREDICATE_PROFILE else PROFILE
    val terminal:Boolean get()=complete||completionCandidate||reason!=null
    val status:String get()=if(complete)"COMPLETE_SEQUENCE" else if(reason!=null)"PARTIAL" else if(completionCandidate)"RETURN_PENDING_NATIVE" else if(cutoffSeen)"AWAITING_OR_CAPTURING" else "BEFORE_CUTOFF"
    private fun stop(why:Reason,tick:Long,core:Int,pc:Long):Boolean {
        if(reason==null&&!complete){completionCandidate=false;reason=why;stopTick=tick;stopCore=core;stopPc=pc}
        return true
    }

    /** Called exactly once for each already-counted hook, using the word already fetched by the normal CPU path. */
    fun instruction(attempt:Long,tick:Long,core:Int,pc:Long,word:Long,registers:Registers):Boolean {
        if(terminal)return true
        if(!cutoffSeen) {
            if(attempt<cutoffAttempt&&tick<cutoffTick)return false
            if(attempt!=cutoffAttempt||tick!=cutoffTick||core!=3||pc!=0x5afe0L)
                return stop(Reason.CUTOFF_MISMATCH,tick,core,pc)
            cutoffSeen=true;lastTick=tick;return false
        }
        hooks++
        if(attempt!=cutoffAttempt+hooks||tick<=lastTick)return stop(Reason.HOOK_ACCOUNTING,tick,core,pc)
        lastTick=tick
        if(core==0) {
            observed++
            if(retained==capacity)return stop(Reason.CAPACITY,tick,core,pc)
            val start=retained*STRIDE
            events[start]=tick;events[start+1]=core.toLong();events[start+2]=word
            for(i in 0..16)events[start+3+i]=registers.read(i)
            retained++
            if(events[start+18]!=pc)return stop(Reason.PC_REGISTER_MISMATCH,tick,core,pc)
            if(retained==1&&(pc!=sites[0]||(0..16).any{events[start+3+it]!=entry[it]}))
                return stop(Reason.ENTRY_MISMATCH,tick,core,pc)
        }
        // The deadline arrival is counted and stopped; no later hook is required.
        if(hooks>=tailHookLimit)return stop(Reason.TAIL_HOOK_LIMIT,tick,core,pc)
        if(core!=0)return false
        val index=retained-1;val start=index*STRIDE
        if(outputReturn&&outputPhase==OutputPhase.PREDICATE&&(pc%4!=0L||events[start+19]and 0x20L!=0L))
            return stop(Reason.INSTRUCTION_STATE_MISMATCH,tick,core,pc)
        if(outputReturn&&outputPhase!=OutputPhase.PREDICATE)return outputInstruction(index,tick,pc,word)
        val equal=branchDestination==0x51f58L
        val returned=predicateReturn&&index==(if(equal)17 else 29)
        val expectedPc=when {
            index<sites.size->sites[index]
            !predicateReturn->branchDestination
            returned->pc // Stack-loaded return destination is observed, never supplied from an earlier LR.
            equal->0x51f58L+(index-15)*4
            index==15->0x51f48L
            index<=25->0x48de8L+(index-16)*4
            else->0x51f4cL+(index-26)*4
        }
        if(pc!=expectedPc)return stop(Reason.UNEXPECTED_PC,tick,core,pc)
        val expectedWord=when {
            index<words.size->words[index]
            !predicateReturn||returned->word
            equal->if(index==15)0xe3a00001L else 0xe8bd8800L
            index==15->0xebffdba6L
            index<=25->secondWords[index-16]
            else->tailWords[index-26]
        }
        if(word!=expectedWord)return stop(Reason.RUNTIME_WORD_MISMATCH,tick,core,pc)
        val expectedLink=if(predicateReturn&&!equal&&index>=16)0x51f4cL else 0x51f38L
        if(index>0&&((!returned&&(events[start+16]!=entry[13]||events[start+14]!=entry[11]))||events[start+17]!=expectedLink))
            return stop(Reason.LINK_OR_STACK_MISMATCH,tick,core,pc)
        if(index==14)branchDestination=if(events[start+19]and 0x4000_0000L!=0L)0x51f58 else 0x51f48
        if(!predicateReturn&&index==15){complete=true;stopTick=tick;stopCore=core;stopPc=pc;return true}
        if(returned){
            if(outputReturn) {
                if(equal||pc!=0x57df0L)return stop(Reason.UNEXPECTED_PC,tick,core,pc)
                if(word!=0xe3500001L)return stop(Reason.RUNTIME_WORD_MISMATCH,tick,core,pc)
                callerFp=events[start+14];callerSp=events[start+16]
                if(events[start+3]!=0L||callerFp<12||callerFp%4!=0L||callerSp!=callerFp-12||callerSp!=entry[13]+8)
                    return stop(Reason.LINK_OR_STACK_MISMATCH,tick,core,pc)
                outputPhase=OutputPhase.CALLER_PREFIX;return false
            }
            return candidate(tick,core,pc)
        }
        return false
    }

    private fun candidate(tick:Long,core:Int,pc:Long):Boolean {
        completionCandidate=true;completionCandidateObserved=true;stopTick=tick;stopCore=core;stopPc=pc;return true
    }
    /** Raw interior is deliberately opaque. Only selected entry/return and the closed caller tail are structural gates. */
    private fun outputInstruction(index:Int,tick:Long,pc:Long,word:Long):Boolean {
        val start=index*STRIDE
        fun reg(i:Int)=events[start+3+i]
        fun mismatch(why:Reason)=stop(why,tick,0,pc)
        if(outputPhase!=OutputPhase.RAW_TAIL&&(outputPhase!=OutputPhase.CALLEE_BODY||pc==0x4ea88L)&&(pc%4!=0L||reg(16)and 0x20L!=0L))
            return mismatch(Reason.INSTRUCTION_STATE_MISMATCH)
        when(outputPhase) {
            OutputPhase.CALLER_PREFIX -> {
                val expectedPc=when(outputStep){0->0x57df4L;1->0x57df8L;2->0x57dfcL;3->0x57e0cL;4->0x57e10L;else->0x4ea28L}
                val expectedWord=when(outputStep){0->0x03a03000L;1->0x050b3008L;2->0x1a000002L;3->0xe24b0008L;4->0xebffdb04L;else->0xe92d4830L}
                if(pc!=expectedPc)return mismatch(Reason.UNEXPECTED_PC)
                if(word!=expectedWord)return mismatch(Reason.RUNTIME_WORD_MISMATCH)
                if(reg(11)!=callerFp||reg(13)!=callerSp||reg(14)!=(if(outputStep==5)0x57e14L else 0x51f4cL)||
                    reg(0)!=(if(outputStep>=4)callerFp-8 else 0L)||reg(16)and 0x40000000L!=0L)
                    return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                if(outputStep++==5) {
                    if(callerSp<16)return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                    outputEntry[0]=reg(0);outputEntry[1]=reg(4);outputEntry[2]=reg(5)
                    outputEntry[3]=reg(11);outputEntry[4]=reg(13);outputEntry[5]=reg(14)
                    outputEntryIndex=index;outputStep=0;outputPhase=OutputPhase.CALLEE_PROLOGUE
                }
            }
            OutputPhase.CALLEE_PROLOGUE -> {
                if(pc!=(if(outputStep==0)0x4ea2cL else 0x4ea30L))return mismatch(Reason.UNEXPECTED_PC)
                if(word!=(if(outputStep==0)0xe28db00cL else 0xe2504000L))return mismatch(Reason.RUNTIME_WORD_MISMATCH)
                if(reg(13)!=outputEntry[4]-16||reg(11)!=(if(outputStep==0)outputEntry[3] else outputEntry[4]-4)||
                    reg(0)!=outputEntry[0]||reg(4)!=outputEntry[1]||reg(5)!=outputEntry[2]||reg(14)!=outputEntry[5])
                    return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                if(outputStep++==1)outputPhase=OutputPhase.CALLEE_BODY
            }
            OutputPhase.CALLEE_BODY -> {
                if(pc==outputEntry[5])return mismatch(Reason.UNEXPECTED_PC) // No matching selected POP was observed.
                if(pc==0x4ea88L) {
                    if(word!=0xe8bd8830L)return mismatch(Reason.RUNTIME_WORD_MISMATCH)
                    if(reg(13)!=outputEntry[4]-16){differentFramePops++;return false}
                    if(reg(11)!=outputEntry[4]-4)return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                    outputPopIndex=index;outputPhase=OutputPhase.CALLEE_RETURN
                }
            }
            OutputPhase.CALLEE_RETURN -> {
                if(pc!=outputEntry[5])return mismatch(Reason.UNEXPECTED_PC)
                if(word!=0xe3500000L)return mismatch(Reason.RUNTIME_WORD_MISMATCH)
                if(reg(4)!=outputEntry[1]||reg(5)!=outputEntry[2]||reg(11)!=outputEntry[3]||reg(13)!=outputEntry[4])
                    return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                if(reg(0)!=events[outputPopIndex*STRIDE+3])return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                outputStatus=reg(0);outputReturnIndex=index;outputStep=0;outputPhase=OutputPhase.CALLER_TAIL
            }
            OutputPhase.CALLER_TAIL -> {
                val zero=outputStatus==0L
                val expectedPc=when(outputStep){0->0x57e18L;1->0x57e1cL;2->if(zero)0x57e00L else 0x57e20L;3->0x57e04L;else->0x57e08L}
                val expectedWord=when(outputStep){0->0x13a00000L;1->0x0afffff7L;2->if(zero)0xe51b0008L else 0xeafffff7L;3->0xe24bd004L;else->0xe8bd8800L}
                if(pc!=expectedPc)return mismatch(Reason.UNEXPECTED_PC)
                if(word!=expectedWord)return mismatch(Reason.RUNTIME_WORD_MISMATCH)
                if(reg(11)!=callerFp||reg(13)!=(if(outputStep==4)callerFp-4 else callerSp)||
                    reg(4)!=outputEntry[1]||reg(5)!=outputEntry[2]||((reg(16)and 0x40000000L!=0L)!=zero))
                    return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                if(outputStep==0&&reg(0)!=outputStatus||outputStep in 1..2&&reg(0)!=0L||!zero&&outputStep>=3&&reg(0)!=0L)
                    return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                if(outputStep==2&&zero)callerLoadIndex=index
                if(outputStep==3){callerValue=reg(0);callerValueIndex=index;if(zero)callerLoadContinuationIndex=index}
                if(outputStep==4) {
                    if(reg(0)!=callerValue)return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                    callerPopIndex=index;outputPhase=OutputPhase.CALLER_RETURN
                }
                outputStep++
            }
            OutputPhase.CALLER_RETURN -> {
                if(reg(13)!=callerFp+4||reg(0)!=callerValue||reg(4)!=outputEntry[1]||reg(5)!=outputEntry[2])
                    return mismatch(Reason.LINK_OR_STACK_MISMATCH)
                callerReturnIndex=index // Stack-loaded outer PC/FP are observed, not predicted.
                if(outputFollowup)outputPhase=OutputPhase.RAW_TAIL else return candidate(tick,0,pc)
            }
            OutputPhase.RAW_TAIL -> Unit // Already retained raw state; no expected PC, word or instruction-state semantics.
            OutputPhase.PREDICATE -> error("output prefix dispatch")
        }
        return false
    }

    /** Global tail callbacks, including the read at the exact cutoff. A rejection never reaches the router. */
    fun beforeMmio(tick:Long,core:Int,pc:Long,write:Boolean,address:Long,width:Int,value:Long):Boolean {
        if(terminal){
            if(terminalMmioRejections==0L) {
                firstPostTerminalMmio[0]=tick;firstPostTerminalMmio[1]=core.toLong();firstPostTerminalMmio[2]=pc
                firstPostTerminalMmio[3]=if(write)1 else 0;firstPostTerminalMmio[4]=address
                firstPostTerminalMmio[5]=width.toLong();firstPostTerminalMmio[6]=value
            }
            terminalMmioRejections++
            if(predicateReturn)stop(Reason.POST_TERMINAL_MMIO,tick,core,pc)
            return false
        }
        if(tick<cutoffTick)return true
        mmioAttempts++
        if(!cutoffSeen){rejectMmio(write,address,width,value);stop(Reason.CUTOFF_MISMATCH,tick,core,pc);return false}
        if(mmioAttempts<=mmioLimit){mmioAllowedToRouter++;return true}
        rejectMmio(write,address,width,value)
        stop(Reason.MMIO_LIMIT,tick,core,pc);return false
    }
    private fun rejectMmio(write:Boolean,address:Long,width:Int,value:Long) {
        rejectedMmio=true;rejectedWrite=write;rejectedAddress=address;rejectedWidth=width;rejectedValue=value
    }
    /** A pending IRQ is a diagnostic stop, not an injected interrupt; failures keep the first partial reason. */
    fun interrupted(why:Reason,tick:Long,core:Int,pc:Long):Boolean {
        if(terminal||tick<cutoffTick)return false
        return stop(why,tick,core,pc)
    }
    /** Called after the actual native invocation, including a thrown run. No callback may finalize success. */
    fun nativeReturned(error:Int?,callbackFailed:Boolean,tick:Long,core:Int,pc:Long):Boolean {
        if(!predicateReturn)return false
        if(completionCandidateObserved&&!completionNativeChecked) {
            completionNativeChecked=true;completionBackendReturned=error!=null
            completionCallbackFailed=callbackFailed;completionNativeError=error;completionRunThrew=error==null
        }
        if(error==null||callbackFailed||error!=0) {
            if(tick>=cutoffTick)stop(when {
                callbackFailed->Reason.CALLBACK_FAILURE
                error==null->Reason.NATIVE_RUN_FAILURE
                error==10->Reason.UNDEFINED
                else->Reason.NATIVE_ERROR
            },tick,core,pc)
        } else if(completionCandidate&&reason==null) {
            completionCandidate=false;complete=true
        }
        return terminal
    }
    private fun failedInstructionEffects():Boolean=rejectedMmio||terminalMmioRejections>0||reason in listOf(
        Reason.EXECUTION_STOP,Reason.UNDEFINED,Reason.CALLBACK_FAILURE,Reason.NATIVE_ERROR,Reason.NATIVE_RUN_FAILURE,Reason.POST_TERMINAL_MMIO)
    fun stopDetail():Map<String,Any?> = mapOf("observer" to profile,"status" to status,"reason" to reason?.name,
        "tick" to stopTick,"core" to stopCore,"pcVirtual" to stopPc,
        "currentInstructionExecuted" to if(failedInstructionEffects())null else false,
        "currentInstructionExecutionScope" to if(rejectedMmio||terminalMmioRejections>0)"MMIO callback failed; native ABI zero placeholder and failed-instruction register effects are not qualified"
            else if(failedInstructionEffects())"Native or existing execution failure; failed-instruction effects are not qualified"
            else "Before-instruction diagnostic stop; this arrival's word is not executed")
    fun state():Map<String,Any?> = mapOf(
        "profile" to profile,"status" to status,"partialReason" to reason?.name,"cutoffAttempt" to cutoffAttempt,"cutoffTick" to cutoffTick,
        "cutoffSeen" to cutoffSeen,"tailHookLimit" to tailHookLimit,"afterCutoffHookCount" to hooks,"tailMmioAttemptLimit" to mmioLimit,
        "tailMmioAttemptedThroughFirstTerminal" to mmioAttempts,"tailMmioAllowedToRouter" to mmioAllowedToRouter,"terminalMmioCallbacksRejected" to terminalMmioRejections,
        "terminalCallbackCountScope" to "Only callbacks delivered to this observer; the native bridge separately short-circuits callbacks after callbackFailure",
        "observed" to observed,"retained" to retained,"capacity" to capacity,"unretained" to observed-retained,
        "sequenceComplete" to complete,"postTerminalCallbackInvalidatesExecutionQualification" to (terminalMmioRejections>0),"stop" to if(terminal)stopDetail() else null,
        "entryExpectedRegisters" to entry.toList(),"registerOrder" to (0..12).map{"r$it"}+listOf("sp","lr","pc","cpsr"),
        "events" to (0 until retained).map {i->val start=i*STRIDE
            mapOf("tick" to events[start],"core" to events[start+1],"runtimeWord" to events[start+2])+
                (0..12).associate{"r$it" to events[start+3+it]}+mapOf("sp" to events[start+16],"lr" to events[start+17],"pc" to events[start+18],"cpsr" to events[start+19])},
        "rejectedMmio" to if(!rejectedMmio)null else mapOf("direction" to if(rejectedWrite)"write" else "read","addressPhysical" to rejectedAddress,
            "width" to rejectedWidth,"value" to if(rejectedWrite)rejectedValue else null,"routerEntered" to false,"completed" to false,
            "nativeReadAbiPlaceholder" to if(rejectedWrite)null else 0,"placeholderIsGuestSample" to false,"executionMayResume" to false),
        "firstPostTerminalCallback" to if(terminalMmioRejections==0L)null else mapOf("tick" to firstPostTerminalMmio[0],"core" to firstPostTerminalMmio[1],
            "pcVirtual" to firstPostTerminalMmio[2],"direction" to if(firstPostTerminalMmio[3]==1L)"write" else "read","addressPhysical" to firstPostTerminalMmio[4],
            "width" to firstPostTerminalMmio[5],"value" to if(firstPostTerminalMmio[3]==1L)firstPostTerminalMmio[6] else null,"routerEntered" to false,"completed" to false,
            "nativeReadAbiPlaceholder" to if(firstPostTerminalMmio[3]==1L)null else 0,"placeholderIsGuestSample" to false,"executionMayResume" to false),
        "instructionExecutionQualified" to false,"calleeReturnQualified" to false,"branchExecutionQualified" to false,
        "scope" to if(outputFollowup)"The selected output return is an intermediate observation only. Subsequent core0 callbacks retain raw words, CPSR and registers without predicting PCs or interpreting instruction state or width. The existing global hook, event and MMIO bounds or execution refusal end a partial run. No completion candidate or cached completion-native fields are created at the intermediate return; actual final native closure belongs to the enclosing execution frontier. No extra memory read, context switch, guest value or semantic qualification is supplied."
            else if(outputReturn)"One bounded contiguous core0 episode through the predicate, selected output delegate and caller consumption. The selected caller/entry/prologue/POP words are checked; opaque interior words and effects remain raw. The outer stack-loaded destination is observed. Completion requires the actual native run to return without error or callback failure; no memory read, context switch, guest value or semantic qualification is supplied."
            else if(predicateReturn)"One contiguous core0 predicate episode after the exact cutoff. Seventeen EQ or twenty-nine non-EQ full runtime words are compared; the stack-loaded destination word is raw only. Completion requires the enclosing native run to return without error or callback failure. No stack read, assumed return PC, semantic qualification, context switch or guest value is supplied."
            else "One contiguous core0 before-instruction sequence after the exact cutoff. Fifteen runtime words are compared, terminal word is raw only. No retry, semantic qualification, memory read, context switch or guest value is supplied; IRQ/partial/complete deliberately truncate the run.")+
        (if(!predicateReturn)emptyMap() else mapOf("completionCandidateObserved" to completionCandidateObserved,"completionNativeChecked" to completionNativeChecked,
            "completionBackendReturned" to completionBackendReturned,"completionCallbackFailed" to completionCallbackFailed,
            "completionNativeError" to completionNativeError,"completionRunThrew" to completionRunThrew))+
        if(!outputReturn)emptyMap() else mapOf("outputContinuation" to mapOf("phase" to outputPhase.name,
            "calleeEntryEventIndex" to outputEntryIndex.takeIf{it>=0},"calleePopEventIndex" to outputPopIndex.takeIf{it>=0},
            "calleeReturnEventIndex" to outputReturnIndex.takeIf{it>=0},"callerLoadEventIndex" to callerLoadIndex.takeIf{it>=0},
            "callerLoadContinuationEventIndex" to callerLoadContinuationIndex.takeIf{it>=0},"callerPopEventIndex" to callerPopIndex.takeIf{it>=0},
            "callerValueEventIndex" to callerValueIndex.takeIf{it>=0},
            "callerReturnEventIndex" to callerReturnIndex.takeIf{it>=0},"differentFramePopCount" to differentFramePops,
            "calleeStatusObserved" to outputStatus.takeIf{outputReturnIndex>=0},"callerValueObserved" to callerValue.takeIf{callerValueIndex>=0},
            "nestedCalleeSemanticsQualified" to false,"outputMemoryContractQualified" to false,"callerConsumptionQualified" to false,
            "outerEntryFrameQualified" to false,"scope" to "Indices and scalar values describe retained observations only; matched local frame shape is not independent stack payload or complete semantic proof."))
}
