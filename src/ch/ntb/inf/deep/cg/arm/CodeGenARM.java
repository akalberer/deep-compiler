/*
 * Copyright 2011 - 2013 NTB University of Applied Sciences in Technology
 * Buchs, Switzerland, http://www.ntb.ch/inf
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 *   
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package ch.ntb.inf.deep.cg.arm;

import ch.ntb.inf.deep.cfg.CFGNode;
import ch.ntb.inf.deep.cg.Code32;
import ch.ntb.inf.deep.cg.CodeGen;
import ch.ntb.inf.deep.cg.RegAllocator;
import ch.ntb.inf.deep.cg.InstructionDecoder;
import ch.ntb.inf.deep.classItems.*;
import ch.ntb.inf.deep.classItems.Class;
import ch.ntb.inf.deep.host.ErrorReporter;
import ch.ntb.inf.deep.host.StdStreams;
import ch.ntb.inf.deep.ssa.*;
import ch.ntb.inf.deep.ssa.instruction.*;
import ch.ntb.inf.deep.strings.HString;

public class CodeGenARM extends CodeGen implements InstructionOpcs, Registers {

	private static final int arrayLenOffset = 6;	
	// used for some floating point operations and compiler specific subroutines
	private static final int tempStorageSize = 48;	// 1 FPR (temp) + 8 GPRs
	
	private static int LRoffset;	
	private static int XERoffset;	
	private static int CRoffset;	
	private static int CTRoffset;	
	private static int SRR0offset;	
	private static int SRR1offset;	
	private static int paramOffset;
	private static int GPRoffset;	
	private static int FPRoffset;	
	private static int localVarOffset;
	private static int tempStorageOffset;	
	private static int stackSize;
	static boolean tempStorage;
	static boolean enFloatsInExc;

	// information about the src registers for parameters of a call to a method within this method
	private static int[] srcGPR = new int[nofGPR];
	private static int[] srcFPR = new int[nofFPR];
	private static int[] srcGPRcount = new int[nofGPR];
	private static int[] srcFPRcount = new int[nofFPR];

	private static boolean newString;

	public CodeGenARM() {}

	public void translateMethod(Method method) {
		init(method);
		SSA ssa = method.ssa;
		Code32 code = method.machineCode;
		
		if (dbg) StdStreams.vrb.println("build intervals");

		tempStorage = false;
		enFloatsInExc = false;
		RegAllocator.regsGPR = regsGPRinitial;
		RegAllocator.regsFPR = regsFPRinitial;

		RegAllocator.buildIntervals(ssa);
		
		if (dbg) StdStreams.vrb.println("assign registers to parameters");
		SSANode b = (SSANode) ssa.cfg.rootNode;
		while (b.next != null) {
			b = (SSANode) b.next;
		}	
		SSAValue[] lastExitSet = b.exitSet;
		// determine, which parameters go into which register
		parseExitSet(lastExitSet, method.maxStackSlots);
		if (dbg) {
			StdStreams.vrb.print("parameter go into register: ");
			for (int n = 0; paramRegNr[n] != -1; n++) StdStreams.vrb.print(paramRegNr[n] + "  "); 
			StdStreams.vrb.println();
		}
//		StdStreams.vrb.print(ssa.toString());
		
		if (dbg) StdStreams.vrb.println("allocate registers");
		RegAllocatorARM.assignRegisters();
		if (!RegAllocator.fullRegSet) {	// repeat with a reduced register set
			if (dbg) StdStreams.vrb.println("register allocation for method " + method.owner.name + "." + method.name + " was not successful, run again and use stack slots");
			if (RegAllocator.useLongs) RegAllocator.regsGPR = regsGPRinitial & ~(0xff << nonVolStartGPR);
			else RegAllocator.regsGPR = regsGPRinitial & ~(0x1f << nonVolStartGPR);
			if (dbg) StdStreams.vrb.println("regsGPRinitial = 0x" + Integer.toHexString(RegAllocator.regsGPR));
			RegAllocator.regsFPR = regsFPRinitial& ~(0x7 << nonVolStartFPR);
			if (dbg) StdStreams.vrb.println("regsFPRinitial = 0x" + Integer.toHexString(RegAllocator.regsFPR));
			RegAllocator.stackSlotSpilledRegs = -1;
			parseExitSet(lastExitSet, method.maxStackSlots);
			if (dbg) {
				StdStreams.vrb.print("parameter go into register: ");
				for (int n = 0; paramRegNr[n] != -1; n++) StdStreams.vrb.print(paramRegNr[n] + "  "); 
				StdStreams.vrb.println();
			}
			RegAllocatorARM.resetRegisters();
			RegAllocatorARM.assignRegisters();
		}
//		StdStreams.vrb.print(ssa.toString());

		if (dbg) {
			StdStreams.vrb.println(RegAllocatorARM.joinsToString());
		}
		if (dbg) {
			StdStreams.vrb.print("register usage in method: nofNonVolGPR = " + nofNonVolGPR + ", nofVolGPR = " + nofVolGPR);
			StdStreams.vrb.println(", nofNonVolFPR = " + nofNonVolFPR + ", nofVolFPR = " + nofVolFPR);
			StdStreams.vrb.print("register usage for parameters: nofParamGPR = " + nofParamGPR + ", nofParamFPR = " + nofParamFPR);
			StdStreams.vrb.println(", receive parameters slots on stack = " + recParamSlotsOnStack);
			StdStreams.vrb.println("max. parameter slots for any call in this method = " + callParamSlotsOnStack);
			StdStreams.vrb.print("parameter end at instr no: ");
			for (int n = 0; n < nofParam; n++) 
				if (paramRegEnd[n] != -1) StdStreams.vrb.print(paramRegEnd[n] + "  "); 
			StdStreams.vrb.println();
		}
		if ((method.accAndPropFlags & (1 << dpfExcHnd)) != 0) {	// exception
			if (method.name == HString.getRegisteredHString("reset")) {	// reset has no prolog
			} else if (method.name == HString.getRegisteredHString("programExc")) {	// special treatment for exception handling
				code.iCount = 0;
//				createIrSrAsimm(ppcStwu, stackPtr, stackPtr, -24);
//				createIrSspr(ppcMtspr, EID, 0);	// must be set for further debugger exceptions
//				createIrSrAd(ppcStmw, 28, stackPtr, 4);
//				createIrArSrB(ppcOr, 31, paramStartGPR, paramStartGPR);	// copy exception into nonvolatile
			} else {
				stackSize = calcStackSizeException();
				insertPrologException();
			}
		} else {
			stackSize = calcStackSize();
			insertProlog(code);	// builds stack frame and copies parameters
		}
		
		SSANode node = (SSANode)ssa.cfg.rootNode;
		while (node != null) {
			node.codeStartIndex = code.iCount;
			translateSSA(node, method);
			node.codeEndIndex = code.iCount-1;
			node = (SSANode) node.next;
		}
		node = (SSANode)ssa.cfg.rootNode;
		while (node != null) {	// resolve local branch targets
			if (node.nofInstr > 0) {
				if ((node.instructions[node.nofInstr-1].ssaOpcode == sCbranch) || (node.instructions[node.nofInstr-1].ssaOpcode == sCswitch)) {
					int instr = code.instructions[node.codeEndIndex];
					CFGNode[] successors = node.successors;
					if ((instr & 0x0f000000) == armB) {		
						if ((instr & 0xffff) != 0) {	// switch
							int nofCases = (instr & 0xffff) >> 2;
							int k;
//							for (k = 0; k < nofCases; k++) {
//								int branchOffset = ((SSANode)successors[k]).codeStartIndex - (node.codeEndIndex+1-(nofCases-k)*2);
//								code.instructions[node.codeEndIndex+1-(nofCases-k)*2] |= (branchOffset << 2) & 0x3ffffff;
//							}
//							int branchOffset = ((SSANode)successors[k]).codeStartIndex - node.codeEndIndex;
//							code.instructions[node.codeEndIndex] &= 0xfc000000;
//							code.instructions[node.codeEndIndex] |= (branchOffset << 2) & 0x3ffffff;
						} else {
							int branchOffset;
							if ((instr & (condAlways << 28)) == condAlways << 28)
								branchOffset = ((SSANode)successors[0]).codeStartIndex - node.codeEndIndex;
							else 
								branchOffset = ((SSANode)successors[1]).codeStartIndex - node.codeEndIndex;
							code.instructions[node.codeEndIndex] |= (branchOffset - 2) & 0xffffff;	// account for pipeline
						}
					}
				} else if (node.instructions[node.nofInstr-1].ssaOpcode == sCreturn) {
					if (node.next != null) {
						int branchOffset = code.iCount - node.codeEndIndex;
						code.instructions[node.codeEndIndex] |= (branchOffset - 2) & 0xffffff;	// account for pipeline
					}
				}
			}
			node = (SSANode) node.next;
		}
		if ((method.accAndPropFlags & (1 << dpfExcHnd)) != 0) {	// exception
			if (method.name == HString.getRegisteredHString("reset")) {	// reset needs no epilog
			} else if (method.name == HString.getRegisteredHString("programExc")) {	// special treatment for exception handling
				Method m = Method.getCompSpecSubroutine("handleException");
				assert m != null;
//				loadConstantAndFixup(code, 31, m);
//				createIrSspr(ppcMtspr, LR, 31);
//				createIrDrAd(ppcLmw, 28, stackPtr, 4);
//				createIrDrAsimm(ppcAddi, stackPtr, stackPtr, 24);
//				createIBOBILK(ppcBclr, BOalways, 0, false);
			} else {
				insertEpilogException(stackSize);
			}
		} else {
			insertEpilog(code, stackSize);
		}
		if (dbg) {StdStreams.vrb.print(ssa.toString()); StdStreams.vrb.print(code.toString());}
	}

	private static void parseExitSet(SSAValue[] exitSet, int maxStackSlots) {
		nofParamGPR = 0; nofParamFPR = 0;
		nofMoveGPR = 0; nofMoveFPR = 0;
		if(dbg) StdStreams.vrb.print("[");
		for (int i = 0; i < nofParam; i++) {
			int type = paramType[i];
			if(dbg) StdStreams.vrb.print("(" + svNames[type] + ")");
			if (type == tLong) {
				if (exitSet[i+maxStackSlots] != null) {	// if null -> parameter is never used
					RegAllocator.useLongs = true;
					if(dbg) StdStreams.vrb.print("r");
					if (paramHasNonVolReg[i]) {
						int reg = RegAllocatorARM.reserveReg(gpr, true);
						int regLong = RegAllocatorARM.reserveReg(gpr, true);
						moveGPRsrc[nofMoveGPR] = nofParamGPR;
						moveGPRsrc[nofMoveGPR+1] = nofParamGPR+1;
						moveGPRdst[nofMoveGPR++] = reg;
						moveGPRdst[nofMoveGPR++] = regLong;
						paramRegNr[i] = reg;
						paramRegNr[i+1] = regLong;
						if(dbg) StdStreams.vrb.print(reg + ",r" + regLong);
					} else {
						int reg = paramStartGPR + nofParamGPR;
						if (reg <= paramEndGPR) RegAllocatorARM.reserveReg(gpr, reg);
						else {
							reg = RegAllocatorARM.reserveReg(gpr, false);
							moveGPRsrc[nofMoveGPR] = nofParamGPR;
							moveGPRdst[nofMoveGPR++] = reg;
						}
						int regLong = paramStartGPR + nofParamGPR + 1;
						if (regLong <= paramEndGPR) RegAllocatorARM.reserveReg(gpr, regLong);
						else {
							regLong = RegAllocatorARM.reserveReg(gpr, false);
							moveGPRsrc[nofMoveGPR] = nofParamGPR + 1;
							moveGPRdst[nofMoveGPR++] = regLong;
						}
						paramRegNr[i] = reg;
						paramRegNr[i+1] = regLong;
						if(dbg) StdStreams.vrb.print(reg + ",r" + regLong);
					}
				}
				nofParamGPR += 2;	// see comment below for else type 
				i++;
			} else if (type == tFloat || type == tDouble) {
				if (exitSet[i+maxStackSlots] != null) {	// if null -> parameter is never used
					if(dbg) StdStreams.vrb.print("fr");
					if (paramHasNonVolReg[i]) {
						int reg = RegAllocatorARM.reserveReg(fpr, true);
						moveFPRsrc[nofMoveFPR] = nofParamFPR;
						moveFPRdst[nofMoveFPR++] = reg;
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					} else {
						int reg = paramStartFPR + nofParamFPR;
						if (reg <= paramEndFPR) RegAllocatorARM.reserveReg(fpr, reg);
						else {
							reg = RegAllocatorARM.reserveReg(fpr, false);
							moveFPRsrc[nofMoveFPR] = nofParamFPR;
							moveFPRdst[nofMoveFPR++] = reg;
						}
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					}
				}
				nofParamFPR++;	// see comment below for else type 
				if (type == tDouble) {i++; paramRegNr[i] = paramRegNr[i-1];}
			} else {
				if (exitSet[i+maxStackSlots] != null) {	// if null -> parameter is never used
					if(dbg) StdStreams.vrb.print("r");
					if (paramHasNonVolReg[i]) {
						int reg = RegAllocatorARM.reserveReg(gpr, true);	// nonvolatile or stack slot
						moveGPRsrc[nofMoveGPR] = nofParamGPR;
						moveGPRdst[nofMoveGPR++] = reg;
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					} else {
						int reg = paramStartGPR + nofParamGPR;
						if (reg <= paramEndGPR) RegAllocatorARM.reserveReg(gpr, reg); // mark as reserved
						else {
							reg = RegAllocatorARM.reserveReg(gpr, false);	// volatile, nonvolatile or stack slot
							moveGPRsrc[nofMoveGPR] = nofParamGPR;
							moveGPRdst[nofMoveGPR++] = reg;
						}
						paramRegNr[i] = reg;
						if(dbg) StdStreams.vrb.print(reg);
					}
				}
				nofParamGPR++;	// even if the parameter is not used, the calling method
				// assigns a register and we have to account for this here
			}
			if (i < nofParam - 1) if(dbg) StdStreams.vrb.print(", ");
		}
		int nof = nofParamGPR - (paramEndGPR - paramStartGPR + 1);
		if (nof > 0) recParamSlotsOnStack = nof;
		nof = nofParamFPR - (paramEndFPR - paramStartFPR + 1);
		if (nof > 0) recParamSlotsOnStack += nof*2;
		
		if(dbg) StdStreams.vrb.println("]");
	}

	private static int calcStackSize() {
		int size = 8 + callParamSlotsOnStack * 4 + nofNonVolGPR * 4 + nofNonVolFPR * 8 + (tempStorage? tempStorageSize : 0);
		if (enFloatsInExc) size += nonVolStartFPR * 8 + 8;	// save volatile FPR's and FPSCR
		int padding = (16 - (size % 16)) % 16;
		size = size + padding;
		LRoffset = size - 4;
		GPRoffset = LRoffset - nofNonVolGPR * 4;
		FPRoffset = GPRoffset - nofNonVolFPR * 8;
		if (enFloatsInExc) FPRoffset -= nonVolStartFPR * 8 + 8;
		localVarOffset = FPRoffset - RegAllocator.maxLocVarStackSlots * 4;
		tempStorageOffset = FPRoffset - tempStorageSize;
		paramOffset = 4;
		return size;
	}

	private static int calcStackSizeException() {
		int size = 28 + nofGPR * 4 + (tempStorage? tempStorageSize : 0);
		if (enFloatsInExc) {
			size += nofNonVolFPR * 8;	// save used nonvolatile FPR's
			size += nonVolStartFPR * 8 + 8;	// save all volatile FPR's and FPSCR
		}
		int padding = (16 - (size % 16)) % 16;
		size = size + padding;
		LRoffset = size - 4;
		XERoffset = LRoffset - 4;
		CRoffset = XERoffset - 4;
		CTRoffset = CRoffset - 4;
		SRR1offset = CTRoffset - 4;
		SRR0offset = SRR1offset - 4;
		GPRoffset = SRR0offset - nofGPR * 4;
		FPRoffset = GPRoffset - nofNonVolFPR * 8;
		if (enFloatsInExc) FPRoffset -= nonVolStartFPR * 8 + 8;
		localVarOffset = FPRoffset - RegAllocator.maxLocVarStackSlots * 4;
		tempStorageOffset = FPRoffset - tempStorageSize;
		paramOffset = 4;
		return size;
	}

	private void translateSSA (SSANode node, Method meth) {
		int stringReg = 0;
		Item stringCharRef = null;
		int dReg = 0, dRegLong = 0, src1Reg = 0, src1RegLong = 0, src2Reg = 0, src2RegLong = 0, src3Reg = 0, src3RegLong = 0;
		Code32 code = meth.machineCode;

		for (int i = 0; i < node.nofInstr; i++) {
			SSAInstruction instr = node.instructions[i];
			SSAValue res = instr.result;
//			instr.machineCodeOffset = iCount;
			if (node.isCatch && i == 0 && node.loadLocalExc > -1) {	
				if (dbg) StdStreams.vrb.println("enter move register intruction for local 'exception' in catch clause: from R" + paramStartGPR + " to R" + node.instructions[node.loadLocalExc].result.reg);
//				createIrArSrB(ppcOr, node.instructions[node.loadLocalExc].result.reg, paramStartGPR, paramStartGPR);
			}
			
			if (dbg) StdStreams.vrb.println("handle instruction " + instr.toString());
			if (instr.ssaOpcode == sCloadLocal) continue;	
			SSAValue[] opds = instr.getOperands();
			if (instr.ssaOpcode == sCstoreToArray) {
				src3Reg = opds[2].reg; 
				src3RegLong = opds[2].regLong;
				if (src3RegLong >= 0x100) {
					if (dbg) StdStreams.vrb.println("opd3 regLong on stack slot for instr: " + instr.toString());
					int slot = src3RegLong & 0xff;
					src3RegLong = nonVolStartGPR + 5;
//					createIrDrAd(code, ppcLwz, src3RegLong, stackPtr, localVarOffset + 4 * slot);
				}
				if (src3Reg >= 0x100) {
					if (dbg) StdStreams.vrb.println("opd3 reg on stack slot for instr: " + instr.toString());
					int slot = src3Reg & 0xff;
					if ((opds[2].type == tFloat) || (opds[2].type == tDouble)) {
						src3Reg = nonVolStartFPR + 0;
//						createIrDrAd(code, ppcLfd, src3Reg, stackPtr, localVarOffset + 4 * slot);
					} else {
						src3Reg = nonVolStartGPR + 0;
//						createIrDrAd(code, ppcLwz, src3Reg, stackPtr, localVarOffset + 4 * slot);
					}
				}			
			}
			if (opds != null && opds.length != 0) {
				if (opds.length >= 2) {
					src2Reg = opds[1].reg; 
					src2RegLong = opds[1].regLong;
					if (src2RegLong >= 0x100) {
						if (dbg) StdStreams.vrb.println("opd2 regLong on stack slot for instr: " + instr.toString());
						int slot = src2RegLong & 0xff;
						src2RegLong = nonVolStartGPR + 7;
//						createIrDrAd(code, ppcLwz, src2RegLong, stackPtr, localVarOffset + 4 * slot);
					}
					if (src2Reg >= 0x100) {
						if (dbg) StdStreams.vrb.println("opd2 reg on stack slot for instr: " + instr.toString());
						int slot = src2Reg & 0xff;
						if ((opds[1].type == tFloat) || (opds[1].type == tDouble)) {
							src2Reg = nonVolStartFPR + 2;
//							createIrDrAd(code, ppcLfd, src2Reg, stackPtr, localVarOffset + 4 * slot);
						} else {
							src2Reg = nonVolStartGPR + 2;
//							createIrDrAd(code, ppcLwz, src2Reg, stackPtr, localVarOffset + 4 * slot);
						}
					}			
				}
				src1Reg = opds[0].reg; 
				src1RegLong = opds[0].regLong;
				if (src1RegLong >= 0x100) {
					if (dbg) StdStreams.vrb.println("opd1 regLong on stack slot for instr: " + instr.toString());
					int slot = src1RegLong & 0xff;
					src1RegLong = nonVolStartGPR + 6;
//					createIrDrAd(code, ppcLwz, src1RegLong, stackPtr, localVarOffset + 4 * slot);
				}
				if (src1Reg >= 0x100) {
					if (dbg) StdStreams.vrb.println("opd1 reg on stack slot for instr: " + instr.toString());
					int slot = src1Reg & 0xff;
					if ((opds[0].type == tFloat) || (opds[0].type == tDouble)) {
						src1Reg = nonVolStartFPR + 1;
//						createIrDrAd(code, ppcLfd, src1Reg, stackPtr, localVarOffset + 4 * slot);
					} else {
						src1Reg = nonVolStartGPR + 1;
//						createIrDrAd(code, ppcLwz, src1Reg, stackPtr, localVarOffset + 4 * slot);
					}
				}			
			}
			dRegLong = res.regLong;
			int dRegLongSlot = -1;
			if (dRegLong >= 0x100) {
				if (dbg) StdStreams.vrb.println("res regLong on stack slot for instr: " + instr.toString());
				dRegLongSlot = dRegLong & 0xff;
				dRegLong = nonVolStartGPR + 5;
			}
			dReg = res.reg;
			int dRegSlot = -1;
			if (dReg >= 0x100) {
				if (dbg) StdStreams.vrb.println("res reg on stack slot for instr: " + instr.toString());
				dRegSlot = dReg & 0xff;
				if ((res.type == tFloat) || (res.type == tDouble)) dReg = nonVolStartFPR + 0;
				else dReg = nonVolStartGPR + 0;
			}

			int gAux1 = res.regGPR1;
			if (gAux1 >= 0x100) gAux1 = nonVolStartGPR + 3;
			int gAux2 = res.regGPR2;
			if (gAux2 >= 0x100) gAux2 = nonVolStartGPR + 4;
			
			switch (instr.ssaOpcode) { 
			case sCloadConst: {
				if (dReg >= 0) {	// else immediate opd, does not have to be loaded into a register
					switch (res.type & ~(1<<ssaTaFitIntoInt)) {
					case tByte: case tShort: case tInteger:
						int immVal = ((StdConstant)res.constant).valueH;
//						if (immVal >= 0 && immVal < 0x10000) // optimize later
							loadConstant(code, dReg, immVal);
//						else 
//							loadConstantFromPoolAndFixup(code, dReg, res.constant);
						break;
					case tLong:	
//						StdConstant constant = (StdConstant)res.constant;
//						long immValLong = ((long)(constant.valueH)<<32) | (constant.valueL&0xFFFFFFFFL);
//						loadConstant(res.regLong, (int)(immValLong >> 32));
//						loadConstant(dReg, (int)immValLong);
						break;	
					case tFloat:	// load from const pool
//						constant = (StdConstant)res.constant;
//						if (constant.valueH == 0) {	// 0.0 must be loaded directly as it's not in the cp
//							createIrDrAsimm(ppcAddi, res.regGPR1, 0, 0);
//							createIrSrAd(ppcStw, res.regGPR1, stackPtr, tempStorageOffset);
//							createIrDrAd(ppcLfs, res.reg, stackPtr, tempStorageOffset);
//						} else if (constant.valueH == 0x3f800000) {	// 1.0
//							createIrDrAsimm(ppcAddis, res.regGPR1, 0, 0x3f80);
//							createIrSrAd(ppcStw, res.regGPR1, stackPtr, tempStorageOffset);
//							createIrDrAd(ppcLfs, res.reg, stackPtr, tempStorageOffset);
//						} else if (constant.valueH == 0x40000000) {	// 2.0
//							createIrDrAsimm(ppcAddis, res.regGPR1, 0, 0x4000);
//							createIrSrAd(ppcStw, res.regGPR1, stackPtr, tempStorageOffset);
//							createIrDrAd(ppcLfs, res.reg, stackPtr, tempStorageOffset);
//						} else {
//							loadConstantAndFixup(res.regGPR1, constant);
//							createIrDrAd(ppcLfs, res.reg, res.regGPR1, 0);
//						}
						break;
					case tDouble:
//						constant = (StdConstant)res.constant;
//						if (constant.valueH == 0) {	// 0.0 must be loaded directly as it's not in the cp
//							createIrDrAsimm(ppcAddi, res.regGPR1, 0, 0);
//							createIrSrAd(ppcStw, res.regGPR1, stackPtr, tempStorageOffset);
//							createIrSrAd(ppcStw, res.regGPR1, stackPtr, tempStorageOffset+4);
//							createIrDrAd(ppcLfd, res.reg, stackPtr, tempStorageOffset);
//						} else if (constant.valueH == 0x3ff00000) {	// 1.0{
//							createIrDrAsimm(ppcAddis, res.regGPR1, 0, 0x3ff0);
//							createIrSrAd(ppcStw, res.regGPR1, stackPtr, tempStorageOffset);
//							createIrDrAsimm(ppcAddis, res.regGPR1, 0, 0);
//							createIrSrAd(ppcStw, res.regGPR1, stackPtr, tempStorageOffset+4);
//							createIrDrAd(ppcLfd, res.reg, stackPtr, tempStorageOffset);
//						} else {
//							loadConstantAndFixup(res.regGPR1, constant);
//							createIrDrAd(ppcLfd, res.reg, res.regGPR1, 0);
//						}
						break;
					case tRef: case tAbyte: case tAshort: case tAchar: case tAinteger:
					case tAlong: case tAfloat: case tAdouble: case tAboolean: case tAref:
//						if (res.constant == null) {// object = null
//							loadConstant(dReg, 0);
//						} else if ((ssa.cfg.method.owner.accAndPropFlags & (1<<apfEnum)) != 0 && ssa.cfg.method.name.equals(HString.getHString("valueOf"))) {	// special case 
//							loadConstantAndFixup(res.reg, res.constant); // load address of static field "ENUM$VALUES"
//							createIrDrAd(ppcLwz, res.reg, res.reg, 0);	// load reference to object on heap
//						} else {	// ref to constant string
//							loadConstantAndFixup(res.reg, res.constant);
//						}
						break;
					default:
						ErrorReporter.reporter.error(610);
						assert false : "result of SSA instruction has wrong type";
						return;
					}
				} 
				break;}	// sCloadConst
			case sCloadLocal:
				break;	// sCloadLocal
			case sCloadFromField: {
				int offset = 0, refReg;			
				Item field = null;
				if (opds == null) {	// getstatic
					refReg = res.regGPR1;
					Item field1 = ((NoOpndRef)instr).field;
//					insertloadAndFixup(code, refReg, field);
					field = field1;
				} else {	// getfield
//					refReg = opds[0].reg;
//					if ((ssa.cfg.method.owner == Type.wktString) &&	// string access needs special treatment
//							((MonadicRef)instr).item.name.equals(HString.getRegisteredHString("value"))) {
//						createIrArSrB(ppcOr, res.reg, refReg, refReg);	// result contains ref to string
//						stringCharRef = ((MonadicRef)instr).item;	// ref to "value"
//						break;	
//					} else {
//						offset = ((MonadicRef)instr).item.offset;
//						createItrap(ppcTwi, TOifequal, refReg, 0);
//					}
				}
				
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tBoolean: case tByte:
					loadStoreVarAndFixup(code, armLdrsb, dReg, field);
					break;
				case tShort: 
					loadStoreVarAndFixup(code, armLdrsh, dReg, field);
					break;
				case tInteger: case tRef: case tAref: case tAboolean:
				case tAchar: case tAfloat: case tAdouble: case tAbyte: 
				case tAshort: case tAinteger: case tAlong:
					loadStoreVarAndFixup(code, armLdr, dReg, field);
					break;
				case tChar: 
					loadStoreVarAndFixup(code, armLdrh, dReg, field);
					break;
//				case tLong:
//					createIrDrAd(ppcLwz, res.regLong, refReg, offset);
//					createIrDrAd(ppcLwz, res.reg, refReg, offset + 4);
//					break;
//				case tFloat: 
//					createIrDrAd(ppcLfs, res.reg, refReg, offset);
//					break;
//				case tDouble: 
//					createIrDrAd(ppcLfd, res.reg, refReg, offset);
//					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	// sCloadFromField
			case sCloadFromArray: {
//				opds = instr.getOperands();
//				int refReg = opds[0].reg;	// ref to array;
//				int indexReg = opds[1].reg;	// index into array;
//				if (ssa.cfg.method.owner == Type.wktString && opds[0].owner instanceof MonadicRef && ((MonadicRef)opds[0].owner).item == stringCharRef) {	// string access needs special treatment
//					createIrDrAd(ppcLwz, res.regGPR1, refReg, objectSize);	// read field "count", must be first field
//					createItrap(ppcTw, TOifgeU, indexReg, res.regGPR1);
//					switch (res.type & 0x7fffffff) {	// type to read
//					case tByte:
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, stringSize - 4);	// add index of field "value" to index
//						createIrDrArB(ppcLbzx, res.reg, res.regGPR2, indexReg);
//						createIrArS(ppcExtsb, res.reg, res.reg);
//						break;
//					case tChar: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 1, 0, 30);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, stringSize - 4);	// add index of field "value" to index
//						createIrDrArB(ppcLhzx, res.reg, res.regGPR1, res.regGPR2);
//						break;
//					default:
//						ErrorReporter.reporter.error(610);
//						assert false : "result of SSA instruction has wrong type";
//						return;
//					}
//				} else {
//					createItrap(ppcTwi, TOifequal, refReg, 0);
//					createIrDrAd(ppcLha, res.regGPR1, refReg, -arrayLenOffset);
//					createItrap(ppcTw, TOifgeU, indexReg, res.regGPR1);
//					switch (res.type & ~(1<<ssaTaFitIntoInt)) {	// type to read
//					case tByte: case tBoolean:
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrDrArB(ppcLbzx, res.reg, res.regGPR2, indexReg);
//						createIrArS(ppcExtsb, res.reg, res.reg);
//						break;
//					case tShort: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 1, 0, 30);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrDrArB(ppcLhax, res.reg, res.regGPR1, res.regGPR2);
//						break;
//					case tInteger: case tRef: case tAref: case tAchar: case tAfloat: 
//					case tAdouble: case tAbyte: case tAshort: case tAinteger: case tAlong:
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 2, 0, 29);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrDrArB(ppcLwzx, res.reg, res.regGPR1, res.regGPR2);
//						break;
//					case tLong: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 3, 0, 28);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrDrArB(ppcLwzux, res.regLong, res.regGPR1, res.regGPR2);
//						createIrDrAd(ppcLwz, res.reg, res.regGPR1, 4);
//						break;
//					case tFloat:
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 2, 0, 29);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrDrArB(ppcLfsx, res.reg, res.regGPR1, res.regGPR2);
//						break;
//					case tDouble: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 3, 0, 28);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrDrArB(ppcLfdx, res.reg, res.regGPR1, res.regGPR2);
//						break;
//					case tChar: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 1, 0, 30);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrDrArB(ppcLhzx, res.reg, res.regGPR1, res.regGPR2);
//						break;
//					default:
//						ErrorReporter.reporter.error(610);
//						assert false : "result of SSA instruction has wrong type";
//						return;
//					}
//				}
				break;}	// sCloadFromArray
			case sCstoreToField: {
				int valReg, valRegLong, refReg, offset, type = 0;
				Item item = null; // !!!!!!!!!!!!!
				if (opds.length == 1) {	// putstatic
					valReg = opds[0].reg;
					valRegLong = opds[0].regLong;
					refReg = res.regGPR1;
					item = ((MonadicRef)instr).item;
					if(((Type)item.type).category == 'P')
						type = Type.getPrimitiveTypeIndex(item.type.name.charAt(0));
					else type = tRef; //is a Array or a Object 
					offset = 0;
//					loadConstantAndFixup(res.regGPR1, item);
				} else {	// putfield
//					refReg = opds[0].reg;
//					valReg = opds[1].reg;
//					valRegLong = opds[1].regLong;
//					if(((Type)((DyadicRef)instr).field.type).category == 'P')
//						type = Type.getPrimitiveTypeIndex(((DyadicRef)instr).field.type.name.charAt(0));
//					else type = tRef;//is a Array or a Object 
//					offset = ((DyadicRef)instr).field.offset;
//					createItrap(ppcTwi, TOifequal, refReg, 0);
				}
				switch (type) {
				case tBoolean: case tByte: 
					loadStoreVarAndFixup(code, armStrb, src1Reg, item);
					break;
				case tShort: case tChar:
					loadStoreVarAndFixup(code, armStrh, src1Reg, item);
					break;
				case tInteger: case tRef: case tAref: case tAboolean:
				case tAchar: case tAfloat: case tAdouble: case tAbyte: 
				case tAshort: case tAinteger: case tAlong:
					loadStoreVarAndFixup(code, armStr, src1Reg, item);
					break;
//				case tLong:
//					createIrSrAd(ppcStw, valRegLong, refReg, offset);
//					createIrSrAd(ppcStw, valReg, refReg, offset + 4);
//					break;
//				case tFloat: 
//					createIrSrAd(ppcStfs, valReg, refReg, offset);
//					break;
//				case tDouble: 
//					createIrSrAd(ppcStfd, valReg, refReg, offset);
//					break;
				default:
					ErrorReporter.reporter.error(611);
					assert false : "operand of SSA instruction has wrong type";
					return;
				}
				break;}	// sCstoreToField
			case sCstoreToArray: {
//				opds = instr.getOperands();
//				int refReg = opds[0].reg;	// ref to array
//				int indexReg = opds[1].reg;	// index into array
//				int valReg = opds[2].reg;	// value to store
//				if (ssa.cfg.method.owner == Type.wktString && opds[0].owner instanceof MonadicRef && ((MonadicRef)opds[0].owner).item == stringCharRef) {	// string access needs special treatment
//					createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 1, 0, 30);
//					createIrDrAsimm(ppcAddi, res.regGPR2, opds[0].reg, stringSize - 4);	// add index of field "value" to index
//					createIrSrArB(ppcSthx, valReg, res.regGPR1, res.regGPR2);
//				} else {
//					createItrap(ppcTwi, TOifequal, refReg, 0);
//					createIrDrAd(ppcLha, res.regGPR1, refReg, -arrayLenOffset);
//					createItrap(ppcTw, TOifgeU, indexReg, res.regGPR1);
//					switch (opds[0].type & ~(1<<ssaTaFitIntoInt)) {
//					case tAbyte: case tAboolean:
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrSrArB(ppcStbx, valReg, indexReg, res.regGPR2);
//						break;
//					case tAshort: case tAchar: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 1, 0, 30);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrSrArB(ppcSthx, valReg, res.regGPR1, res.regGPR2);
//						break;
//					case tAref: case tRef: case tAinteger:
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 2, 0, 29);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrSrArB(ppcStwx, valReg, res.regGPR1, res.regGPR2);
//						break;
//					case tAlong: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 3, 0, 28);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrSrArB(ppcStwux, opds[2].regLong, res.regGPR1, res.regGPR2);
//						createIrSrAd(ppcStw, valReg, res.regGPR1, 4);
//						break;
//					case tAfloat:  
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 2, 0, 29);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrSrArB(ppcStfsx, valReg, res.regGPR1, res.regGPR2);
//						break;
//					case tAdouble: 
//						createIrArSSHMBME(ppcRlwinm, res.regGPR1, indexReg, 3, 0, 28);
//						createIrDrAsimm(ppcAddi, res.regGPR2, refReg, objectSize);
//						createIrSrArB(ppcStfdx, valReg, res.regGPR1, res.regGPR2);
//						break;
//					default:
//						ErrorReporter.reporter.error(611);
//						assert false : "operand of SSA instruction has wrong type";
//						return;
//					}
//				}
				break;}	// sCstoreToArray
			case sCadd: { 
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger:
					if (src1Reg < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						if (immVal >= 0)
							createDataProcImm(code, armAdd, condAlways, dReg, src2Reg, immVal);
						else
							createDataProcImm(code, armSub, condAlways, dReg, src2Reg, -immVal);
						
					} else if (src2Reg < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						if (immVal >= 0)
							createDataProcImm(code, armAdd, condAlways, dReg, src1Reg, immVal);
						else
							createDataProcImm(code, armSub, condAlways, dReg, src1Reg, -immVal);
					} else {
						createDataProcReg(code, armAdd, condAlways, dReg, src1Reg, src2Reg, noShift, 0);
					}
					break;
				case tLong:
//					if (sReg1 < 0) {
//						long immValLong = ((long)(((StdConstant)opds[0].constant).valueH)<<32) | (((StdConstant)opds[0].constant).valueL&0xFFFFFFFFL);
//						createIrDrAsimm(ppcAddic, dReg, sReg2, (int)immValLong);
//						createIrDrA(ppcAddze, res.regLong, opds[1].regLong);
//					} else if (sReg2 < 0) {
//						long immValLong = ((long)(((StdConstant)opds[1].constant).valueH)<<32) | (((StdConstant)opds[1].constant).valueL&0xFFFFFFFFL);
//						createIrDrAsimm(ppcAddic, dReg, sReg1, (int)immValLong);
//						createIrDrA(ppcAddze, res.regLong, opds[0].regLong);
//					} else {
//						createIrDrArB(ppcAddc, dReg, sReg1, sReg2);
//						createIrDrArB(ppcAdde, res.regLong, opds[0].regLong, opds[1].regLong);
//					}	
					break;
				case tFloat:
//					createIrDrArB(ppcFadds, dReg, sReg1, sReg2);
					break;
				case tDouble:
//					createIrDrArB(ppcFadd, dReg, sReg1, sReg2);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	//sCadd
			case sCsub: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger:
					if (src1Reg < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						if (immVal >= 0)
							createDataProcImm(code, armRsb, condAlways, dReg, src2Reg, immVal);
						else
							createDataProcImm(code, armAdd, condAlways, dReg, src2Reg, immVal);
					} else if (src2Reg < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						if (immVal >= 0)
							createDataProcImm(code, armSub, condAlways, dReg, src1Reg, immVal);
						else
							createDataProcImm(code, armAdd, condAlways, dReg, src1Reg, -immVal);
					} else {
						createDataProcReg(code, armSub, condAlways, dReg, src1Reg, src2Reg, noShift, 0);
					}
					break;
				case tLong:
//					if (sReg1 < 0) {
//						long immValLong = ((long)(((StdConstant)opds[0].constant).valueH)<<32) | (((StdConstant)opds[0].constant).valueL&0xFFFFFFFFL);
//						createIrDrAsimm(ppcSubfic, dReg, sReg2, (int)immValLong);
//						createIrDrA(ppcSubfze, res.regLong, opds[1].regLong);
//					} else if (sReg2 < 0) {
//						long immValLong = ((long)(((StdConstant)opds[1].constant).valueH)<<32) | (((StdConstant)opds[1].constant).valueL&0xFFFFFFFFL);
//						createIrDrAsimm(ppcAddic, dReg, sReg1, -(int)immValLong);
//						createIrDrA(ppcAddme, res.regLong, opds[0].regLong);
//					} else {
//						createIrDrArB(ppcSubfc, dReg, sReg2, sReg1);
//						createIrDrArB(ppcSubfe, res.regLong, opds[1].regLong, opds[0].regLong);
//					}
					break;
				case tFloat:
//					createIrDrArB(ppcFsubs, dReg, sReg1, sReg2);
					break;
				case tDouble:
//					createIrDrArB(ppcFsub, dReg, sReg1, sReg2);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	// sCsub
			case sCmul: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger:
					if (src2Reg < 0) {	// is power of 2
						int immVal = ((StdConstant)opds[1].constant).valueH;
						int shift = 0;
						while (immVal > 1) {shift++; immVal >>= 1;}
						if (shift == 0) createDataProcImm(code, armMov, condAlways, dReg, src1Reg, 0);
						else createRotateShiftImm(code, armLsl, condAlways, dReg, src1Reg, shift);
					} else {
						createMul(code, armMul, condAlways, dReg, src1Reg, src2Reg);
					}
					break;
				case tLong:
//					if (sReg2 < 0) {	// is power of 2
//						long immValLong = ((long)(((StdConstant)opds[1].constant).valueH)<<32) | (((StdConstant)opds[1].constant).valueL&0xFFFFFFFFL);
//						int shift = 0;
//						while (immValLong > 1) {shift++; immValLong >>= 1;}
//						if (shift == 0) {
//							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
//							createIrArSrB(ppcOr, dReg, sReg1, sReg1);
//						} else {
//							if (shift < 32) {
//								createIrArSSHMBME(ppcRlwinm, res.regLong, sReg1, shift, 32-shift, 31);
//								createIrArSSHMBME(ppcRlwimi, res.regLong, opds[0].regLong, shift, 0, 31-shift);
//								createIrArSSHMBME(ppcRlwinm, dReg, sReg1, shift, 0, 31-shift);
//							} else {
//								createIrDrAsimm(ppcAddi, dReg, 0, 0);
//								createIrArSSHMBME(ppcRlwinm, res.regLong, sReg1, shift-32, 0, 63-shift);
//							}
//						}
//					} else {
//						createIrDrArB(ppcMullw, res.regGPR1, opds[0].regLong, sReg2);
//						createIrDrArB(ppcMullw, res.regGPR2, sReg1, opds[1].regLong);
//						createIrDrArB(ppcAdd, res.regGPR1, res.regGPR1, res.regGPR2);
//						createIrDrArB(ppcMulhwu, res.regGPR2, sReg1, sReg2);
//						createIrDrArB(ppcAdd, res.regLong, res.regGPR1, res.regGPR2);
//						createIrDrArB(ppcMullw, res.reg, sReg1, sReg2);
//					}
					break;
				case tFloat:
//					createIrDrArC(ppcFmuls, dReg, sReg1, sReg2);
					break;
				case tDouble:
//					createIrDrArC(ppcFmul, dReg, sReg1, sReg2);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	//sCmul
			case sCdiv: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte: case tShort: case tInteger:
					if (src2Reg < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;	// is power of 2
						int shift = 0;
						while (immVal > 1) {shift++; immVal >>= 1;}
//						if (shift == 0) 
//							createIrArSrB(ppcOr, dReg, sReg1, sReg1);
//						else {
//							createIrArSSH(ppcSrawi, 0, sReg1, shift-1);
//							createIrArSSHMBME(ppcRlwinm, 0, 0, shift, 32 - shift, 31);	
//							createIrDrArB(ppcAdd, 0, sReg1, 0);
//							createIrArSSH(ppcSrawi, dReg, 0, shift);
//						}
					} else {
//						 CMP             R2, #0
//						 BEQ divide_end
//						 ;check for divide by zero!
//
//						 MOV      R0,#0     ;clear R0 to accumulate result
//						 MOV      R3,#1     ;set bit 0 in R3, which will be
//						                    ;shifted left then right
//						.start
//						 CMP      R2,R1
//						 MOVLS    R2,R2,LSL#1
//						 MOVLS    R3,R3,LSL#1
//						 BLS      start
//						 ;shift R2 left until it is about to
//						 ;be bigger than R1
//						 ;shift R3 left in parallel in order
//						 ;to flag how far we have to go
//
//						.next
//						 CMP       R1,R2      ;carry set if R1&gt;R2 (don't ask why)
//						 SUBCS     R1,R1,R2   ;subtract R2 from R1 if this would
//						                      ;give a positive answer
//						 ADDCS     R0,R0,R3   ;and add the current bit in R3 to
//						                      ;the accumulating answer in R0
//
//						 MOVS      R3,R3,LSR#1     ;Shift R3 right into carry flag
//						 MOVCC     R2,R2,LSR#1     ;and if bit 0 of R3 was zero, also
//						                           ;shift R2 right
//						 BCC       next            ;If carry not clear, R3 has shifted
//						                           ;back to where it started, and we
//						                           ;can end					
						}
					break;
				case tLong:
					break;
				case tFloat:
//					createIrDrArB(ppcFdivs, dReg, sReg1, sReg2);
					break;
				case tDouble:
//					createIrDrArB(ppcFdiv, dReg, sReg1, sReg2);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	// sCdiv
			case sCrem: {
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int sReg2 = opds[1].reg;
//				int dReg = res.reg;
//				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
//				case tByte: case tShort: case tInteger:
//					if (sReg2 < 0) {
//						int immVal = ((StdConstant)opds[1].constant).valueH;	// is power of 2
//						int shift = 0;
//						while (immVal > 1) {shift++; immVal >>= 1;}
//						if (shift == 0) 
//							loadConstant(dReg, 0);
//						else {
//							createIrArSSH(ppcSrawi, 0, sReg1, shift-1);
//							createIrArSSHMBME(ppcRlwinm, 0, 0, shift, 32 - shift, 31);	
//							createIrDrArB(ppcAdd, 0, sReg1, 0);
//							createIrArSSH(ppcSrawi, dReg, 0, shift);
//							createIrArSSHMBME(ppcRlwinm, 0, dReg, shift, 0, 31-shift);
//							createIrDrArB(ppcSubf, dReg, 0, sReg1);
//						}
//					} else {
//						createItrap(ppcTwi, TOifequal, sReg2, 0);
//						createIrDrArB(ppcDivw, 0, sReg1, sReg2);
//						createIrDrArB(ppcMullw, 0, 0, sReg2);
//						createIrDrArB(ppcSubf, dReg, 0 ,sReg1);
//					}
//					break;
//				case tLong:
//					int sReg1Long = opds[0].regLong;
//					int sReg2Long = opds[1].regLong;
//					if (sReg2 < 0) {	// is power of 2
//						long immValLong = ((long)(((StdConstant)opds[1].constant).valueH)<<32) | (((StdConstant)opds[1].constant).valueL&0xFFFFFFFFL);
//						int shift = 0;
//						while (immValLong > 1) {shift++; immValLong >>= 1;}
//						if (shift == 0) {
//							loadConstant(res.regLong, 0);
//							loadConstant(dReg, 0);
//						} else if (shift < 32) {
//							int sh1 = shift - 1;																// shift right arithmetic immediate by shift-1
//							createIrArSSHMBME(ppcRlwinm, res.regGPR1, sReg1, (32-sh1)%32, sh1, 31);					
//							createIrArSSHMBME(ppcRlwimi, res.regGPR1, sReg1Long, (32-sh1)%32, 0, (sh1-1+32)%32);			
//							createIrArSSH(ppcSrawi, res.regGPR2, sReg1Long, sh1);																																				
//							createIrArSSHMBME(ppcRlwinm, res.regGPR1, res.regGPR2, shift, 32 - shift, 31);		// shift right immediate by 64-shift	
//							createIrDrAsimm(ppcAddi, res.regGPR2, 0, 0);
//							createIrDrArB(ppcAddc, res.regGPR1, res.regGPR1, sReg1);							// add
//							createIrDrArB(ppcAdde, res.regGPR2, res.regGPR2, sReg1Long);					 
//							createIrArSSHMBME(ppcRlwinm, res.regGPR1, res.regGPR1, 32-shift, shift, 31);		// shift right arithmetic immediate by shift
//							createIrArSSHMBME(ppcRlwimi, res.regGPR1, res.regGPR2, 32-shift, 0, shift-1);				
//							createIrArSSH(ppcSrawi, res.regGPR2, res.regGPR2, shift);															
//							
//							createIrArSSHMBME(ppcRlwinm, 0, res.regGPR1, shift, 32-shift, 31);					// multiply
//							createIrArSSHMBME(ppcRlwimi, 0, res.regGPR2, shift, 0, 31-shift);
//							createIrArSSHMBME(ppcRlwinm, res.regGPR1, res.regGPR1, shift, 0, 31-shift);
//							
//							createIrDrArB(ppcSubfc, dReg, res.regGPR1, sReg1);									// subtract
//							createIrDrArB(ppcSubfe, res.regLong, 0, sReg1Long);
//						} else {
//							int sh1 = shift % 32;
//							createIrArSSH(ppcSrawi, res.regGPR1, sReg1Long, (sh1-1+32)%32);				// shift right arithmetic immediate by shift-1
//							createIrArSSH(ppcSrawi, res.regGPR2, sReg1Long, 31);															
//							sh1 = (64 - shift) % 32;															// shift right immediate by 64-shift
//							createIrArSSHMBME(ppcRlwinm, res.regGPR1, res.regGPR1, (32-sh1)%32, sh1, 31);						
//							createIrArSSHMBME(ppcRlwimi, res.regGPR1, res.regGPR2, (32-sh1)%32, 0, (sh1-1)&0x1f);					
//							createIrArSSHMBME(ppcRlwinm, res.regGPR2, res.regGPR2, (32-sh1)%32, sh1, 31);		
//							createIrDrArB(ppcAddc, res.regGPR1, res.regGPR1, sReg1);							// add
//							createIrDrArB(ppcAdde, res.regGPR2, res.regGPR2, sReg1Long);					 
//							sh1 = shift % 32;																	// shift right arithmetic immediate by shift
//							createIrArSSH(ppcSrawi, res.regGPR1, res.regGPR2, sh1);									
//							createIrArSSH(ppcSrawi, res.regGPR2, res.regGPR2, 31);									
//							
//							createIrArSSHMBME(ppcRlwinm, res.regGPR2, res.regGPR1, shift-32, 0, 63-shift);		// multiply
//							createIrDrAsimm(ppcAddi, res.regGPR1, 0, 0);									
//							
//							createIrDrArB(ppcSubfc, dReg, res.regGPR1, sReg1);									// subtract
//							createIrDrArB(ppcSubfe, res.regLong, res.regGPR2, sReg1Long);
//						}
//					} else { // not a power of 2
//						createICRFrAsimm(ppcCmpi, CRF1, sReg2Long, -1); // is divisor negative?
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF1+GT, 4);	
//						createIrDrAsimm(ppcSubfic, res.regGPR2, sReg2, 0);	// negate divisor
//						createIrDrA(ppcSubfze, res.regGPR1, sReg2Long);
//						createIBOBIBD(ppcBc, BOalways, 0, 3);
//						createIrArSrB(ppcOr, res.regGPR1, sReg2Long, sReg2Long); // copy if not negative
//						createIrArSrB(ppcOr, res.regGPR2, sReg2, sReg2);
//						// test, if divisor = 0, if so, throw exception
//						createICRFrAsimm(ppcCmpi, CRF1, sReg2, 0);
//						createIBOBIBD(ppcBc, BOfalse, 4*CRF1+EQ, 3);	
//						createItrap(ppcTwi, TOifequal, sReg2Long, 0);
//						createIrDrArB(ppcDivw, sReg2, sReg2, sReg2);	// this instruction solely serves the trap handler to
//						// identify that it's a arithmetic exception
//
//						createIrSrAd(ppcStmw, 24, stackPtr, tempStorageOffset + 8);
//						copyParametersSubroutine(sReg1Long, sReg1, res.regGPR1, res.regGPR2);
//						Method m = Method.getCompSpecSubroutine("remLong");
//						loadConstantAndFixup(24, m);	// use a register which contains no operand 
//						createIrSspr(ppcMtspr, LR, 24);
//						createIBOBILK(ppcBclr, BOalways, 0, true);
//
//						createIrDrAd(ppcLmw, 25, stackPtr, tempStorageOffset + 8 + 4); // restore
//						createIrArSrB(ppcOr, dReg, 24, 24);
//						if (dReg != 24) // restore last register if not destination register
//							createIrDrAd(ppcLwz, 24, stackPtr, tempStorageOffset + 8);
//						createIrArSrB(ppcOr, res.regLong, 0, 0);
//					}
//					break;
//				case tFloat:	// correct if a / b < 32 bit
//					createIrDrArB(ppcFdiv, dReg, sReg1, sReg2);
//					createIrDrB(ppcFctiwz, 0, dReg);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, res.regGPR1, stackPtr, tempStorageOffset + 4);
//					Item item = int2floatConst1;	// ref to 2^52+2^31;					
//					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
//					createIrArSuimm(ppcXoris, 0, res.regGPR1, 0x8000);
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
//					loadConstantAndFixup(res.regGPR1, item);
//					createIrDrAd(ppcLfd, dReg, res.regGPR1, 0);
//					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
//					createIrDrArB(ppcFsub, dReg, 0, dReg);
//					createIrDrArC(ppcFmul, dReg, dReg, sReg2);
//					createIrDrArB(ppcFsub, dReg, sReg1, dReg);
//					break;
//				case tDouble:	// correct if a / b < 32 bit
//					createIrDrArB(ppcFdiv, dReg, sReg1, sReg2);
//					createIrDrB(ppcFctiwz, 0, dReg);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, res.regGPR1, stackPtr, tempStorageOffset + 4);
//					item = int2floatConst1;	// ref to 2^52+2^31;					
//					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
//					createIrArSuimm(ppcXoris, 0, res.regGPR1, 0x8000);
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
//					loadConstantAndFixup(res.regGPR1, item);
//					createIrDrAd(ppcLfd, dReg, res.regGPR1, 0);
//					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
//					createIrDrArB(ppcFsub, dReg, 0, dReg);
//					createIrDrArC(ppcFmul, dReg, dReg, sReg2);
//					createIrDrArB(ppcFsub, dReg, sReg1, dReg);
//					break;
//				default:
//					ErrorReporter.reporter.error(610);
//					assert false : "result of SSA instruction has wrong type";
//					return;
//				}
				break;}	// sCrem
			case sCneg: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger:
					createDataProcImm(code, armRsb, condAlways, dReg, src1Reg, 0);
					break;
				case tLong:
//					createIrDrAsimm(ppcSubfic, res.reg, opds[0].reg, 0);
//					createIrDrA(ppcSubfze, res.regLong, opds[0].regLong);
					break;
				case tFloat: case tDouble:
//					createIrDrB(ppcFneg, res.reg, opds[0].reg);
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	// sCneg
			case sCshl: {
				int type = res.type & ~(1<<ssaTaFitIntoInt);
				if (type == tInteger) {
					if (src2Reg < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH % 32;
						createRotateShiftImm(code, armLsl, condAlways, dReg, src1Reg, immVal);
					} else {
						createRotateShiftReg(code, armLsl, condAlways, dReg, src1Reg, src2Reg);
					}
				} else if (type == tLong) {
//					if (sReg2 < 0) {	
//						int immVal = ((StdConstant)opds[1].constant).valueH % 64;
//						if (immVal < 32) {
//							createIrArSSHMBME(ppcRlwinm, 0, sReg1, immVal, 32-immVal, 31);
//							createIrArSSHMBME(ppcRlwimi, 0, opds[0].regLong, immVal, 0, 31-immVal);
//							createIrArSSHMBME(ppcRlwinm, dReg, sReg1, immVal, 0, 31-immVal);
//							createIrArSrB(ppcOr, res.regLong, 0, 0);
//						} else {
//							createIrArSSHMBME(ppcRlwinm, res.regLong, sReg1, immVal-32, 0, 63-immVal);
//							createIrDrAsimm(ppcAddi, dReg, 0, 0);
//						}
//					} else { 
//						createIrDrAsimm(ppcSubfic, res.regGPR1, sReg2, 32);
//						createIrArSrB(ppcSlw, res.regLong, opds[0].regLong, sReg2);
//						createIrArSrB(ppcSrw, 0, sReg1, res.regGPR1);
//						createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
//						createIrDrAsimm(ppcAddi, res.regGPR1, sReg2, -32);
//						createIrArSrB(ppcSlw, 0, sReg1, res.regGPR1);
//						createIrArSrB(ppcOr, res.regLong, res.regLong, 0);
//						createIrArSrB(ppcSlw, dReg, sReg1, sReg2);
//					}
				} else {
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	// sCshl
			case sCshr: {
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int sReg2 = opds[1].reg;
//				int dReg = res.reg;
//				int type = res.type & ~(1<<ssaTaFitIntoInt);
//				if (type == tInteger) {
//					if (sReg2 < 0) {
//						int immVal = ((StdConstant)opds[1].constant).valueH % 32;
//						createIrArSSH(ppcSrawi, dReg, sReg1, immVal);
//					} else {
//						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 27, 31);
//						createIrArSrB(ppcSraw, dReg, sReg1, 0);
//					}
//				} else if (type == tLong) {
//					if (sReg2 < 0) {
//						int immVal = ((StdConstant)opds[1].constant).valueH % 64;
//						if (immVal == 0) {
//							createIrArSrB(ppcOr, dReg, sReg1, sReg1);
//							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
//						} else if (immVal < 32) {
//							createIrArSSHMBME(ppcRlwinm, dReg, sReg1, 32-immVal, immVal, 31);
//							createIrArSSHMBME(ppcRlwimi, dReg, opds[0].regLong, 32-immVal, 0, immVal-1);
//							createIrArSSH(ppcSrawi, res.regLong, opds[0].regLong, immVal);
//						} else {
//							immVal %= 32;
//							createIrArSSH(ppcSrawi, res.reg, opds[0].regLong, immVal);
//							createIrArSSH(ppcSrawi, res.regLong, opds[0].regLong, 31);
//						}
//					} else {
//						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 26, 31);
//						createIrDrAsimm(ppcSubfic, res.regGPR1, 0, 32);
//						createIrArSrB(ppcSrw, dReg, sReg1, sReg2);
//						createIrArSrB(ppcSlw, 0, opds[0].regLong, res.regGPR1);
//						createIrArSrB(ppcOr, dReg, dReg, 0);
//						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 26, 31);
//						createIrDrAsimm(ppcAddicp, res.regGPR1, 0, -32);
//						createIrArSrB(ppcSraw, 0, opds[0].regLong, res.regGPR1);
//						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, 2);
//						createIrArSuimm(ppcOri, dReg, 0, 0);
//						createIrArSrB(ppcSraw, res.regLong, opds[0].regLong, sReg2);
//					}
//				} else {
//					ErrorReporter.reporter.error(610);
//					assert false : "result of SSA instruction has wrong type";
//					return;
//				}
				break;}	// sCshr
			case sCushr: {
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int sReg2 = opds[1].reg;
//				int dReg = res.reg;
//				int type = res.type & ~(1<<ssaTaFitIntoInt);
//				if (type == tInteger) {
//					if (sReg2 < 0) {
//						int immVal = ((StdConstant)opds[1].constant).valueH % 32;
//						createIrArSSHMBME(ppcRlwinm, dReg, sReg1, (32-immVal)%32, immVal, 31);
//					} else {
//						createIrArSSHMBME(ppcRlwinm, 0, sReg2, 0, 27, 31);
//						createIrArSrB(ppcSrw, dReg, sReg1, 0);
//					}
//				} else if (type == tLong) {
//					if (sReg2 < 0) {	
//						int immVal = ((StdConstant)opds[1].constant).valueH % 64;
//						if (immVal == 0) {
//							createIrArSrB(ppcOr, dReg, sReg1, sReg1);
//							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
//						} else if (immVal < 32) {
//							createIrArSSHMBME(ppcRlwinm, dReg, sReg1, (32-immVal)%32, immVal, 31);
//							createIrArSSHMBME(ppcRlwimi, dReg, opds[0].regLong, (32-immVal)%32, 0, (immVal-1)&0x1f);
//							createIrArSSHMBME(ppcRlwinm, res.regLong, opds[0].regLong, (32-immVal)%32, immVal, 31);
//						} else {
//							createIrArSSHMBME(ppcRlwinm, dReg, opds[0].regLong, (64-immVal)%32, immVal-32, 31);
//							createIrDrAsimm(ppcAddi, res.regLong, 0, 0);
//						}
//					} else {
//						createIrDrAsimm(ppcSubfic, res.regGPR1, sReg2, 32);
//						createIrArSrB(ppcSrw, dReg, sReg1, sReg2);
//						createIrArSrB(ppcSlw, 0, opds[0].regLong, res.regGPR1);
//						createIrArSrB(ppcOr, dReg, dReg, 0);
//						createIrDrAsimm(ppcAddi, res.regGPR1, sReg2, -32);
//						createIrArSrB(ppcSrw, 0, opds[0].regLong, res.regGPR1);
//						createIrArSrB(ppcOr, dReg, dReg, 0);
//						createIrArSrB(ppcSrw, res.regLong, opds[0].regLong, sReg2);
//					}
//				} else {
//					ErrorReporter.reporter.error(610);
//					assert false : "result of SSA instruction has wrong type";
//					return;
//				}
				break;}	// sCushr
			case sCand: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte: case tShort: case tInteger:
					if (src1Reg < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createDataProcImm(code, armAnd, condAlways, dReg, src2Reg, immVal);
					} else if (src2Reg < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createDataProcImm(code, armAnd, condAlways, dReg, src1Reg, immVal);
					} else
						createDataProcReg(code, armAnd, condAlways, dReg, src1Reg, src2Reg, noShift, 0);
					break;
				case tLong:
//					if (sReg1 < 0) {
//						int immVal = ((StdConstant)opds[0].constant).valueL;
//						if (immVal >= 0) {
//							createIrArSuimm(ppcAndi, res.regLong, opds[1].regLong, 0);
//							createIrArSuimm(ppcAndi, dReg, sReg2, (int)immVal);
//						} else {
//							createIrDrAsimm(ppcAddi, 0, 0, immVal);
//							createIrArSrB(ppcOr, res.regLong, opds[1].regLong, opds[1].regLong);
//							createIrArSrB(ppcAnd, dReg, sReg2, 0);
//						}
//					} else if (sReg2 < 0) {
//						int immVal = ((StdConstant)opds[1].constant).valueL;
//						if (immVal >= 0) {
//							createIrArSuimm(ppcAndi, res.regLong, opds[0].regLong, 0);
//							createIrArSuimm(ppcAndi, dReg, sReg1, (int)immVal);
//						} else {
//							createIrDrAsimm(ppcAddi, 0, 0, immVal);
//							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
					//							createIrArSrB(ppcAnd, dReg, sReg1, 0);
					//						}
					//					} else {
					//						createIrArSrB(ppcAnd, res.regLong, opds[0].regLong, opds[1].regLong);
					//						createIrArSrB(ppcAnd, dReg, sReg1, sReg2);
					//					}
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	// sCand
			case sCor: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte: case tShort: case tInteger:
					if (src1Reg < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createDataProcImm(code, armOrr, condAlways, dReg, src2Reg, immVal);
					} else if (src2Reg < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createDataProcImm(code, armOrr, condAlways, dReg, src1Reg, immVal);
					} else
						createDataProcReg(code, armOrr, condAlways, dReg, src1Reg, src2Reg, noShift, 0);
					break;
				case tLong:
//					if (sReg1 < 0) {
//						int immVal = ((StdConstant)opds[0].constant).valueL;
//						createIrArSrB(ppcOr, res.regLong, opds[1].regLong, opds[1].regLong);
//						createIrArSuimm(ppcOri, dReg, sReg2, (int)immVal);
//						if (immVal < 0) {
//							createIrArSuimm(ppcOris, dReg, dReg, 0xffff);	
//							createIrDrAsimm(ppcAddi, res.regLong, 0, -1);	
//						}
//					} else if (sReg2 < 0) {
//						int immVal = ((StdConstant)opds[1].constant).valueL;
//						createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
//						createIrArSuimm(ppcOri, dReg, sReg1, (int)immVal);
//						if (immVal < 0) {
//							createIrArSuimm(ppcOris, dReg, dReg, 0xffff);					
//							createIrDrAsimm(ppcAddi, res.regLong, 0, -1);	
//						}
//					} else {
//						createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[1].regLong);
//						createIrArSrB(ppcOr, dReg, sReg1, sReg2);
//					}
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	//sCor
			case sCxor: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tByte: case tShort: case tInteger:
					if (src1Reg < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createDataProcImm(code, armEor, condAlways, dReg, src2Reg, immVal);
					} else if (src2Reg < 0) {
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createDataProcImm(code, armEor, condAlways, dReg, src1Reg, immVal);
					} else
						createDataProcReg(code, armEor, condAlways, dReg, src1Reg, src2Reg, noShift, 0);
					break;
				case tLong:
//					if (sReg1 < 0) {
//						int immVal = ((StdConstant)opds[0].constant).valueL;
//						createIrArSuimm(ppcXori, dReg, sReg2, (int)immVal);
//						if (immVal < 0) {
//							createIrArSuimm(ppcXoris, dReg, dReg, 0xffff);
//							createIrArSuimm(ppcXori, res.regLong, opds[1].regLong, 0xffff);
//							createIrArSuimm(ppcXoris, res.regLong, res.regLong, 0xffff);
//						} else {
//							createIrArSrB(ppcOr, res.regLong, opds[1].regLong, opds[1].regLong);
//							createIrArSuimm(ppcXoris, dReg, dReg, 0);
//						}
//					} else if (sReg2 < 0) {
//						int immVal = ((StdConstant)opds[1].constant).valueL;
//						createIrArSuimm(ppcXori, dReg, sReg1, (int)immVal);
//						if (immVal < 0) {
//							createIrArSuimm(ppcXoris, dReg, dReg, 0xffff);
//							createIrArSuimm(ppcXori, res.regLong, opds[0].regLong, 0xffff);
//							createIrArSuimm(ppcXoris, res.regLong, res.regLong, 0xffff);
//						} else {
//							createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
//							createIrArSuimm(ppcXoris, dReg, dReg, 0);
//						}
//					} else {
//						createIrArSrB(ppcXor, res.regLong, opds[0].regLong, opds[1].regLong);
//						createIrArSrB(ppcXor, dReg, sReg1, sReg2);
//					}
					break;
				default:
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}	// sCxor
			case sCconvInt:	{// int -> other type
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int dReg = res.reg;
//				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
//				case tByte:
//					createIrArS(ppcExtsb, dReg, sReg1);
//					break;
//				case tChar: 
//					createIrArSSHMBME(ppcRlwinm, dReg, sReg1, 0, 16, 31);
//					break;
//				case tShort: 
//					createIrArS(ppcExtsh, dReg, sReg1);
//					break;
//				case tLong:
//					createIrArSrB(ppcOr, dReg, sReg1, sReg1);
//					createIrArSSH(ppcSrawi, res.regLong, sReg1, 31);
//					break;
//				case tFloat:
//					Item item = int2floatConst1;	// ref to 2^52+2^31;					
//					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
//					createIrArSuimm(ppcXoris, 0, sReg1, 0x8000);
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
//					loadConstantAndFixup(res.regGPR1, item);
//					createIrDrAd(ppcLfd, dReg, res.regGPR1, 0);
//					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
//					createIrDrArB(ppcFsub, dReg, 0, dReg);
//					createIrDrB(ppcFrsp, dReg, dReg);
//					break;
//				case tDouble:
////					instructions[iCount] = ppcMtfsfi | (7 << 23) | (4  << 12);
////					incInstructionNum();
//					item = int2floatConst1;	// ref to 2^52+2^31;					
//					createIrDrAsimm(ppcAddis, 0, 0, 0x4330);	// preload 2^52
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset);
//					createIrArSuimm(ppcXoris, 0, sReg1, 0x8000);
//					createIrSrAd(ppcStw, 0, stackPtr, tempStorageOffset+4);
//					loadConstantAndFixup(res.regGPR1, item);
//					createIrDrAd(ppcLfd, dReg, res.regGPR1, 0);
//					createIrDrAd(ppcLfd, 0, stackPtr, tempStorageOffset);
//					createIrDrArB(ppcFsub, dReg, 0, dReg);
//					break;
//				default:
//					ErrorReporter.reporter.error(610);
//					assert false : "result of SSA instruction has wrong type";
//					return;
//				}
				break;}	// sCconvInt
			case sCconvLong: {	// long -> other type
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int dReg = res.reg;
//				switch (res.type & ~(1<<ssaTaFitIntoInt)){
//				case tByte:
//					createIrArS(ppcExtsb, dReg, sReg1);
//					break;
//				case tChar: 
//					createIrArSSHMBME(ppcRlwinm, dReg, sReg1, 0, 16, 31);
//					break;
//				case tShort: 
//					createIrArS(ppcExtsh, dReg, sReg1);
//					break;
//				case tInteger:
//					createIrArSrB(ppcOr, dReg, sReg1, sReg1);
//					break;
//				case tFloat:
//					createIrSrAd(ppcStmw, 29, stackPtr, tempStorageOffset + 8);
//					Method m = Method.getCompSpecSubroutine("longToDouble");
//					copyParametersSubroutine(opds[0].regLong, sReg1, 0, 0);
//					loadConstantAndFixup(29, m);	// use a register which contains no operand 
//					createIrSspr(ppcMtspr, LR, 29);
//					createIBOBILK(ppcBclr, BOalways, 0, true);
//					createIrDrAd(ppcLmw, 29, stackPtr, tempStorageOffset + 8);
//					createIrDrB(ppcFmr, dReg, 0);	// get result
//					createIrDrB(ppcFrsp, dReg, dReg);
//					break;
//				case tDouble:
//					createIrSrAd(ppcStmw, 29, stackPtr, tempStorageOffset + 8);
//					m = Method.getCompSpecSubroutine("longToDouble");
//					copyParametersSubroutine(opds[0].regLong, sReg1, 0, 0);
//					loadConstantAndFixup(29, m);	// use a register which contains no operand 
//					createIrSspr(ppcMtspr, LR, 29);
//					createIBOBILK(ppcBclr, BOalways, 0, true);
//					createIrDrAd(ppcLmw, 29, stackPtr, tempStorageOffset + 8);
//					createIrDrB(ppcFmr, dReg, 0);	// get result
//					break;
//				default:
//					ErrorReporter.reporter.error(610);
//					assert false : "result of SSA instruction has wrong type";
//					return;
//				}
				break;}
			case sCconvFloat: {	// float -> other type
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int dReg = res.reg;
//				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
//				case tByte:
//					createIrDrB(ppcFctiw, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
//					createIrArS(ppcExtsb, dReg, 0);
//					break;
//				case tChar: 
//					createIrDrB(ppcFctiw, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
//					createIrArSSHMBME(ppcRlwinm, dReg, 0, 0, 16, 31);
//					break;
//				case tShort: 
//					createIrDrB(ppcFctiw, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
//					createIrArS(ppcExtsh, dReg, 0);
//					break;
//				case tInteger:
//					createIrDrB(ppcFctiwz, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, dReg, stackPtr, tempStorageOffset + 4);
//					break;
//				case tLong:	
//					createIrSrAd(ppcStmw, 28, stackPtr, tempStorageOffset + 8);
//					Method m = Method.getCompSpecSubroutine("doubleToLong");
//					createIrDrB(ppcFmr, 0, sReg1);
//					loadConstantAndFixup(29, m);	// use a register which contains no operand 
//					createIrSspr(ppcMtspr, LR, 29);
//					createIBOBILK(ppcBclr, BOalways, 0, true);
//					createIrDrAd(ppcLmw, 29, stackPtr, tempStorageOffset + 8 + 4); // restore
//					createIrArSrB(ppcOr, dReg, 28, 28);
//					if (dReg != 28) // restore last register if not destination register
//						createIrDrAd(ppcLwz, 28, stackPtr, tempStorageOffset + 8);
//					createIrArSrB(ppcOr, res.regLong, 0, 0);
//					break;
//				case tDouble:
//					createIrDrB(ppcFmr, dReg, sReg1);
//					break;
//				default:
//					ErrorReporter.reporter.error(610);
//					assert false : "result of SSA instruction has wrong type";
//					return;
//				}
				break;}
			case sCconvDouble: {	// double -> other type
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int dReg = res.reg;
//				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
//				case tByte:
//					createIrDrB(ppcFctiw, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
//					createIrArS(ppcExtsb, dReg, 0);
//					break;
//				case tChar: 
//					createIrDrB(ppcFctiw, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
//					createIrArSSHMBME(ppcRlwinm, dReg, 0, 0, 16, 31);
//					break;
//				case tShort: 
//					createIrDrB(ppcFctiw, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, 0, stackPtr, tempStorageOffset + 4);
//					createIrArS(ppcExtsh, dReg, 0);
//					break;
//				case tInteger:
//					createIrDrB(ppcFctiwz, 0, sReg1);
//					createIrSrAd(ppcStfd, 0, stackPtr, tempStorageOffset);
//					createIrDrAd(ppcLwz, dReg, stackPtr, tempStorageOffset + 4);
//					break;
//				case tLong:	
//					createIrSrAd(ppcStmw, 28, stackPtr, tempStorageOffset + 8);
//					Method m = Method.getCompSpecSubroutine("doubleToLong");
//					createIrDrB(ppcFmr, 0, sReg1);
//					loadConstantAndFixup(29, m);	// use a register which contains no operand 
//					createIrSspr(ppcMtspr, LR, 29);
//					createIBOBILK(ppcBclr, BOalways, 0, true);
//					createIrDrAd(ppcLmw, 29, stackPtr, tempStorageOffset + 8 + 4); // restore
//					createIrArSrB(ppcOr, dReg, 28, 28);
//					if (dReg != 28) // restore last register if not destination register
//						createIrDrAd(ppcLwz, 28, stackPtr, tempStorageOffset + 8);
//					createIrArSrB(ppcOr, res.regLong, 0, 0);
//					break;
//				case tFloat:
//					createIrDrB(ppcFrsp, dReg, sReg1);
//					break;
//				default:
//					ErrorReporter.reporter.error(610);
//					assert false : "result of SSA instruction has wrong type";
//					return;
//				}
				break;}
			case sCcmpl: case sCcmpg: {
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				int sReg2 = opds[1].reg;
//				int type = opds[0].type & ~(1<<ssaTaFitIntoInt);
//				if (type == tLong) {
//					int sReg1L = opds[0].regLong;
//					int sReg2L = opds[1].regLong;
//					createICRFrArB(ppcCmp, CRF0, sReg1L, sReg2L);
//					createICRFrArB(ppcCmpl, CRF1, sReg1, sReg2);
//					instr = node.instructions[i+1];
//					if (instr.ssaOpcode == sCregMove) {i++; instr = node.instructions[i+1]; assert false;}
//					assert instr.ssaOpcode == sCbranch : "sCcompl or sCcompg is not followed by branch instruction";
//					int bci = ssa.cfg.code[node.lastBCA] & 0xff;
//					if (bci == bCifeq) {
//						createIcrbDcrbAcrbB(ppcCrand, CRF0EQ, CRF0EQ, CRF1EQ);
//						createIBOBIBD(ppcBc, BOtrue, CRF0EQ, 0);
//					} else if (bci == bCifne) {
//						createIcrbDcrbAcrbB(ppcCrand, CRF0EQ, CRF0EQ, CRF1EQ);
//						createIBOBIBD(ppcBc, BOfalse, CRF0EQ, 0);
//					} else if (bci == bCiflt) {
//						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1LT);
//						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0LT);
//						createIBOBIBD(ppcBc, BOtrue, CRF0LT, 0);
//					} else if (bci == bCifge) {
//						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1LT);
//						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0LT);
//						createIBOBIBD(ppcBc, BOfalse, CRF0LT, 0);
//					} else if (bci == bCifgt) {
//						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1GT);
//						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0GT);
//						createIBOBIBD(ppcBc, BOtrue, CRF0LT, 0);
//					} else if (bci == bCifle) {
//						createIcrbDcrbAcrbB(ppcCrand, CRF1LT, CRF0EQ, CRF1GT);
//						createIcrbDcrbAcrbB(ppcCror, CRF0LT, CRF1LT, CRF0GT);
//						createIBOBIBD(ppcBc, BOfalse, CRF0LT, 0);
//					} else {
//						ErrorReporter.reporter.error(623);
//						assert false : "sCcompl or sCcompg is not followed by branch instruction";
//						return;
//					}
//				} else if (type == tFloat  || type == tDouble) {
//					createICRFrArB(ppcFcmpu, CRF1, sReg1, sReg2);
//					instr = node.instructions[i+1];
//					assert instr.ssaOpcode == sCbranch : "sCcompl or sCcompg is not followed by branch instruction";
//					int bci = ssa.cfg.code[node.lastBCA] & 0xff;
//					if (bci == bCifeq) 
//						createIBOBIBD(ppcBc, BOtrue, CRF1EQ, 0);
//					else if (bci == bCifne)
//						createIBOBIBD(ppcBc, BOfalse, CRF1EQ, 0);
//					else if (bci == bCiflt)
//						createIBOBIBD(ppcBc, BOtrue, CRF1LT, 0);
//					else if (bci == bCifge)
//						createIBOBIBD(ppcBc, BOfalse, CRF1LT, 0);
//					else if (bci == bCifgt)
//						createIBOBIBD(ppcBc, BOtrue, CRF1GT, 0);
//					else if (bci == bCifle)
//						createIBOBIBD(ppcBc, BOfalse, CRF1GT, 0);
//					else {
//						ErrorReporter.reporter.error(623);
//						assert false : "sCcompl or sCcompg is not followed by branch instruction";
//						return;
//					}
//				} else {
//					ErrorReporter.reporter.error(611);
//					assert false : "operand of SSA instruction has wrong type";
//					return;
//				}
//				i++;
				break;}
			case sCinstanceof: {
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//
//				MonadicRef ref = (MonadicRef)instr;
//				Type t = (Type)ref.item;
//				if (t.category == tcRef) {	// object (to test for) is regular class or interface
//					if ((t.accAndPropFlags & (1<<apfInterface)) != 0) {	// object is interface
//						createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 2
//						// label 1
//						createIrDrAsimm(ppcAddi, res.reg, 0, 0);
//						createIBOBIBD(ppcBc, BOalways, 4*CRF0, 13);	// jump to end
//						// label 2
//						createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//						createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is array?
//						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, -4);	// jump to label 1
//						createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//						createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdIntfTypeChkTableOffset);
//						createIrDrArB(ppcAdd, res.regGPR1, res.regGPR1, 0);
//						// label 3
//						createIrDrAd(ppcLhzu, 0, res.regGPR1, 0);
//						createICRFrAsimm(ppcCmpi, CRF0, 0, ((Class)t).chkId);	// is interface chkId
//						createIrDrAsimm(ppcAddi, res.regGPR1, res.regGPR1, 2);
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, -3);	// jump to label 3			
//						createIrD(ppcMfcr, res.reg);
//						createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 3, 31, 31);
//					} else {	// regular class
//						int offset = ((Class)t).extensionLevel;
//						if (t.name.equals(HString.getHString("java/lang/Object"))) {
//							createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 1
//							createIrDrAsimm(ppcAddi, res.reg, 0, 0);
//							createIBOBIBD(ppcBc, BOalways, 4*CRF0, 2);	// jump to end
//							// label 1
//							createIrDrAsimm(ppcAddi, res.reg, 0, 1);
//						} else { // regular class but not java/lang/Object
//							createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 2
//							// label 1
//							createIrDrAsimm(ppcAddi, res.reg, 0, 0);
//							createIBOBIBD(ppcBc, BOalways, 4*CRF0, 11);	// jump to end
//							// label 2
//							createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//							createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is array?
//							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, -4);	// jump to label 1
//							createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//							createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdBaseClass0Offset + offset * 4);
//							loadConstantAndFixup(res.regGPR1, t);	// addr of type
//							createICRFrArB(ppcCmpl, CRF0, 0, res.regGPR1);
//							createIrD(ppcMfcr, res.reg);
//							createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 3, 31, 31);
//						}
//					}
//				} else {	// object is an array
//					if (((Array)t).componentType.category == tcPrimitive) {  // array of base type
//						// test if not null
//						createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//						createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 2
//						// label 1
//						createIrDrAsimm(ppcAddi, res.reg, 0, 0);
//						createIBOBIBD(ppcBc, BOalways, 4*CRF0, 10);	// jump to end
//						// label 2
//						createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//						createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is not array?
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, -4);	// jump to label 1
//						createIrDrAd(ppcLwz, 0, sReg1, -4);	// get tag
//						loadConstantAndFixup(res.regGPR1, t);	// addr of type
//						createICRFrArB(ppcCmpl, CRF0, 0, res.regGPR1);
//						createIrD(ppcMfcr, res.reg);
//						createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 3, 31, 31);
//					} else {	// array of regular classes or interfaces
//						int nofDim = ((Array)t).dimension;
//						Item compType = RefType.refTypeList.getItemByName(((Array)t).componentType.name.toString());
//						int offset = ((Class)(((Array)t).componentType)).extensionLevel;
//						if (((Array)t).componentType.name.equals(HString.getHString("java/lang/Object"))) {
//							// test if not null
//							createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 2
//							// label 1
//							createIrDrAsimm(ppcAddi, res.reg, 0, 0);
//							createIBOBIBD(ppcBc, BOalways, 4*CRF0, 16);	// jump to end
//							// label 2
//							createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//							createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is not array?
//							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, -4);	// jump to label 1
//
//							createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//							createIrDrAd(ppcLwz, 0, res.regGPR1, 0);	
//							createIrArSSHMBME(ppcRlwinm, res.regGPR1, 0, 16, 17, 31);	// get dim
//							createICRFrAsimm(ppcCmpi, CRF0, 0, 0);	// check if array of primitive type
//							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 5);	// jump to label 3					
//							createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, nofDim);
//							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, -11);	// jump to label 1	
//							createIrDrAsimm(ppcAddi, res.reg, 0, 1);
//							createIBOBIBD(ppcBc, BOalways, 4*CRF0, 4);	// jump to end
//							// label 3, is array of primitive type
//							createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, nofDim);
//							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+GT, -15);	// jump to label 1	
//							createIrDrAsimm(ppcAddi, res.reg, 0, 1);
//						} else {	// array of regular classes or interfaces but not java/lang/Object
//							if ((compType.accAndPropFlags & (1<<apfInterface)) != 0) {	// array of interfaces
//								createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//								createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 2
//								// label 1
//								createIrDrAsimm(ppcAddi, res.reg, 0, 0);
//								createIBOBIBD(ppcBc, BOalways, 4*CRF0, 20);	// jump to end
//								// label 2
//								createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//								createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is not array?
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, -4);	// jump to label 1
//
//								createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//								createIrDrAd(ppcLwz, 0, res.regGPR1, 0);			
//								createIrArSSHMBME(ppcRlwinm, 0, 0, 16, 17, 31);	// get dim
//								createICRFrAsimm(ppcCmpi, CRF0, 0, nofDim);
//								createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, -9);	// jump to label 1					
//
//								createIrDrAd(ppcLwz, res.regGPR1, res.regGPR1, 8 + nofDim * 4);	// get component type
//								createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is 0?
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, -12);	// jump to label 1					
//
//								createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdIntfTypeChkTableOffset);
//								createIrDrArB(ppcAdd, res.regGPR1, res.regGPR1, 0);
//								// label 3
//								createIrDrAd(ppcLhzu, 0, res.regGPR1, 0);
//								createICRFrAsimm(ppcCmpi, CRF0, 0, ((Class)compType).chkId);	// is interface chkId
//								createIrDrAsimm(ppcAddi, res.regGPR1, res.regGPR1, 2);
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, -3);	// jump to label 3			
//								createIrD(ppcMfcr, res.reg);	
//								createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 3, 31, 31);			
//							} else {	// array of regular classes
//								// test if not null
//								createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//								createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 3);	// jump to label 2
//								// label 1
//								createIrDrAsimm(ppcAddi, res.reg, 0, 0);
//								createIBOBIBD(ppcBc, BOalways, 4*CRF0, 18);	// jump to end
//								// label 2
//								createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//								createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is not array?
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, -4);	// jump to label 1
//
//								createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//								createIrDrAd(ppcLwz, 0, res.regGPR1, 0);	
//								createIrArSSHMBME(ppcRlwinm, 0, 0, 16, 17, 31);	// get dim
//								createICRFrAsimm(ppcCmpi, CRF0, 0, nofDim);
//								createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, -9);	// jump to label 1					
//
//								createIrDrAd(ppcLwz, res.regGPR1, res.regGPR1, 8 + nofDim * 4);	// get component type
//								createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, 0);	// is 0?
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, -12);	// jump to label 1					
//
//								createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdBaseClass0Offset + offset * 4);
//								loadConstantAndFixup(res.regGPR1, compType);	// addr of component type
//								createICRFrArB(ppcCmpl, CRF0, 0, res.regGPR1);
//								createIrD(ppcMfcr, res.reg);
//								createIrArSSHMBME(ppcRlwinm, res.reg, res.reg, 3, 31, 31);
//							}
//						}
//					}
//				}
				break;}
			case sCcheckcast: {
				// this ssa instruction must be translated, so that only "twi, TOifnequal" is used
				// this enables the trap handler to throw a ClassCastException
//				opds = instr.getOperands();
//				int sReg1 = opds[0].reg;
//				MonadicRef ref = (MonadicRef)instr;
//				Type t = (Type)ref.item;
//				if (t.category == tcRef) {	// object (to test for) is regular class or interface
//					if ((t.accAndPropFlags & (1<<apfInterface)) != 0) {	// object is interface
//						createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 11);	// jump to end
//						createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//						createItrapSimm(ppcTwi, TOifnequal, res.regGPR1, 0);	// is not array?
//						createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//						createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdIntfTypeChkTableOffset);
//						createIrDrArB(ppcAdd, res.regGPR1, res.regGPR1, 0);
//						// label 1
//						createIrDrAd(ppcLhzu, 0, res.regGPR1, 0);
//						createICRFrAsimm(ppcCmpi, CRF0, 0, ((Class)t).chkId);	// is interface chkId
//						createIrDrAsimm(ppcAddi, res.regGPR1, res.regGPR1, 2);
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, -3);	// jump to label 1			
//						createItrapSimm(ppcTwi, TOifnequal, 0, ((Class)t).chkId);	// chkId is not equal
//					} else {	// object is regular class
//						int offset = ((Class)t).extensionLevel;
//						createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 8);	// jump to end
//						createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//						createItrapSimm(ppcTwi, TOifnequal, res.regGPR1, 0);	// is not array?
//						createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//						createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdBaseClass0Offset + offset * 4);
//						loadConstantAndFixup(res.regGPR1, t);	// addr of type
//						createItrap(ppcTw, TOifnequal, res.regGPR1, 0);
//					}
//				} else {	// object (to test for) is an array
//					if (((Array)t).componentType.category == tcPrimitive) {  // array of base type
//						createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 8);	// jump to end
//						createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//						createIrArSuimm(ppcAndi, res.regGPR1, res.regGPR1, 0x80);
//						createItrapSimm(ppcTwi, TOifnequal, res.regGPR1, 0x80);	// is array?
//						createIrDrAd(ppcLwz, 0, sReg1, -4);	// get tag
//						loadConstantAndFixup(res.regGPR1, t);	// addr of type
//						createItrap(ppcTw, TOifnequal, res.regGPR1, 0);
//					} else {	// array of regular classes or interfaces
//						int nofDim = ((Array)t).dimension;
//						Item compType = RefType.refTypeList.getItemByName(((Array)t).componentType.name.toString());
//						if (((Array)t).componentType.name.equals(HString.getHString("java/lang/Object"))) {
//							createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 15);	// jump to end
//							createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//							createIrArSuimm(ppcAndi, res.regGPR1, res.regGPR1, 0x80);
//							createItrapSimm(ppcTwi, TOifnequal, res.regGPR1, 0x80);	// is array?
//
//							createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//							createIrDrAd(ppcLwz, 0, res.regGPR1, 0);	
//							createIrArSSHMBME(ppcRlwinm, res.regGPR1, 0, 16, 17, 31);	// get dim
//							createICRFrAsimm(ppcCmpi, CRF0, 0, 0);	// check if array of primitive type
//							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+LT, 4);	// jump to label 3	
//							
//							createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, nofDim);
//							createIBOBIBD(ppcBc, BOfalse, 4*CRF0+LT, 5);	// jump to end	
//							createItrap(ppcTwi, TOifnequal, res.regGPR1, -1);	// trap always
//							// label 3, is array of primitive type
//							createICRFrAsimm(ppcCmpi, CRF0, res.regGPR1, nofDim);
//							createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, 2);	// jump to end	
//							createItrap(ppcTwi, TOifnequal, res.regGPR1, -1);	// trap always
//						} else {	// array of regular classes or interfaces but not java/lang/Object
//							if ((compType.accAndPropFlags & (1<<apfInterface)) != 0) {	// array of interfaces
//								createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 19);	// jump to end
//								createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//								createIrArSuimm(ppcAndi, res.regGPR1, res.regGPR1, 0x80);
//								createItrapSimm(ppcTwi, TOifnequal, res.regGPR1, 0x80);	// is array?
//
//								createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//								createIrDrAd(ppcLwz, 0, res.regGPR1, 0);	
//								createIrArSSHMBME(ppcRlwinm, 0, 0, 16, 17, 31);	// get dim
//								createItrapSimm(ppcTwi, TOifnequal, 0, nofDim);	// check dim
//
//								createIrDrAd(ppcLwz, res.regGPR1, res.regGPR1, 8 + nofDim * 4);	// get component type
//								createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//								createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 2);
//								createItrapSimm(ppcTwi, TOifnequal, sReg1, -1);	// is 0?
//								createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdIntfTypeChkTableOffset);
//								createIrDrArB(ppcAdd, res.regGPR1, res.regGPR1, 0);
//								// label 1
//								createIrDrAd(ppcLhzu, 0, res.regGPR1, 0);
//								createICRFrAsimm(ppcCmpi, CRF0, 0, ((Class)compType).chkId);	// is interface chkId
//								createIrDrAsimm(ppcAddi, res.regGPR1, res.regGPR1, 2);
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+GT, -3);	// jump to label 1			
//								createItrapSimm(ppcTwi, TOifnequal, 0, ((Class)compType).chkId);	// chkId is not equal
//							} else {	// array of regular classes
//								int offset = ((Class)(((Array)t).componentType)).extensionLevel;
//								createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//								createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 16);	// jump to end
//								createIrDrAd(ppcLbz, res.regGPR1, sReg1, -7);	// get array bit
//								createIrArSuimm(ppcAndi, res.regGPR1, res.regGPR1, 0x80);
//								createItrapSimm(ppcTwi, TOifnequal, res.regGPR1, 0x80);	// is array?
//
//								createIrDrAd(ppcLwz, res.regGPR1, sReg1, -4);	// get tag
//								createIrDrAd(ppcLwz, 0, res.regGPR1, 0);	
//								createIrArSSHMBME(ppcRlwinm, 0, 0, 16, 17, 31);	// get dim
//								createItrapSimm(ppcTwi, TOifnequal, 0, nofDim);	// check dim
//
//								createIrDrAd(ppcLwz, res.regGPR1, res.regGPR1, 8 + nofDim * 4);	// get component type
//								createICRFrAsimm(ppcCmpi, CRF0, sReg1, 0);	// is null?
//								createIBOBIBD(ppcBc, BOfalse, 4*CRF0+EQ, 2);
//								createItrapSimm(ppcTwi, TOifnequal, sReg1, -1);	// is 0?
//
//								createIrDrAd(ppcLwz, 0, res.regGPR1, Linker32.tdBaseClass0Offset + offset * 4);
//								loadConstantAndFixup(res.regGPR1, compType);	// addr of component type
//								createItrap(ppcTw, TOifnequal, res.regGPR1, 0);
//							}
//						}
//					}
//				}
				break;}
			case sCthrow: {
//				opds = instr.getOperands();
//				createIrArSrB(ppcOr, paramStartGPR, opds[0].reg, opds[0].reg);	// put exception into parameter register
//				createItrap(ppcTw, TOalways, 0, 0);
				break;}
			case sCalength: {
//				opds = instr.getOperands();
//				int refReg = opds[0].reg;
//				createItrap(ppcTwi, TOifequal, refReg, 0);
//				createIrDrAd(ppcLha, res.reg , refReg, -arrayLenOffset);
				break;}
			case sCcall: {
				Call call = (Call)instr;
				Method m = (Method)call.item;
				if ((m.accAndPropFlags & (1 << dpfSynthetic)) != 0) {
					if (m.id == idGET1) {	// GET1
						createLSWordImm(code,armLdrsb, condAlways, dReg, src1Reg, 0, 0, 0, 0);
					} else if (m.id == idGET2) { // GET2
						createLSWordImm(code,armLdrsh, condAlways, dReg, src1Reg, 0, 0, 0, 0);
					} else if (m.id == idGET4) { // GET4
						createLSWordImm(code,armLdr, condAlways, dReg, src1Reg, 0, 0, 0, 0);
					} else if (m.id == idGET8) { // GET8
						createLSWordImm(code,armLdr, condAlways, dRegLong, src1Reg, 0, 0, 0, 0);
						createLSWordImm(code,armLdr, condAlways, dReg, src1Reg, 4, 1, 1, 0);
					} else if (m.id == idPUT1) { // PUT1
						createLSWordImm(code,armStrb, condAlways, src2Reg, src1Reg, 0, 0, 0, 0);
					} else if (m.id == idPUT2) { // PUT2
						createLSWordImm(code,armStrh, condAlways, src2Reg, src1Reg, 0, 0, 0, 0);
					} else if (m.id == idPUT4) { // PUT4
						createLSWordImm(code,armStr, condAlways, src2Reg, src1Reg, 0, 0, 0, 0);
					} else if (m.id == idPUT8) { // PUT8
						createLSWordImm(code,armStr, condAlways, src2RegLong, src1Reg, 0, 0, 0, 0);
						createLSWordImm(code,armStr, condAlways, src2Reg, src1Reg, 4, 1, 1, 0);
//					} else if (m.id == idBIT) { // BIT
//						createIrDrAd(ppcLbz, res.reg, opds[0].reg, 0);
//						createIrDrAsimm(ppcSubfic, 0, opds[1].reg, 32);
//						createIrArSrBMBME(ppcRlwnm, res.reg, res.reg, 0, 31, 31);
					} else if (m.id == idGETGPR) { // GETGPR
						int gpr = ((StdConstant)opds[0].constant).valueH;
//						createIrArSrB(ppcOr, res.reg, gpr, gpr);
						createDataProcReg(code, armMov, condAlways, dReg, gpr);
//					} else if (m.id == idGETFPR) { // GETFPR
//						int fpr = ((StdConstant)opds[0].constant).valueH;
//						createIrDrB(ppcFmr, res.reg, fpr);
//					} else if (m.id == idGETSPR) { // GETSPR
//						int spr = ((StdConstant)opds[0].constant).valueH;
//						createIrSspr(ppcMfspr, spr, res.reg);
					} else if (m.id == idPUTGPR) { // PUTGPR
						int gpr = ((StdConstant)opds[0].constant).valueH;
//						createIrArSrB(ppcOr, gpr, opds[1].reg, opds[1].reg);
						createDataProcReg(code, armMov, condAlways, gpr, src2Reg);
//					} else if (m.id == idPUTFPR) { // PUTFPR
//						int fpr = ((StdConstant)opds[0].constant).valueH;
//						createIrDrB(ppcFmr, fpr, opds[1].reg);
//					} else if (m.id == idPUTSPR) { // PUTSPR
//						createIrArSrB(ppcOr, 0, opds[1].reg, opds[1].reg);
//						int spr = ((StdConstant)opds[0].constant).valueH;
//						createIrSspr(ppcMtspr, spr, 0);
//					} else if (m.id == idHALT) { // HALT	// TODO
//						createItrap(ppcTw, TOalways, 0, 0);
					} else if (m.id == idASM) { // ASM
						code.instructions[code.iCount] = InstructionDecoder.dec.getCode(((StringLiteral)opds[0].constant).string.toString());
						code.iCount++;
						int len = code.instructions.length;
						if (code.iCount == len) {
							int[] newInstructions = new int[2 * len];
							for (int k = 0; k < len; k++)
								newInstructions[k] = code.instructions[k];
							code.instructions = newInstructions;
						}
//					} else if (m.id == idADR_OF_METHOD) { // ADR_OF_METHOD
//						HString name = ((StringLiteral)opds[0].constant).string;
//						int last = name.lastIndexOf('/');
//						HString className = name.substring(0, last);
//						HString methName = name.substring(last + 1);
//						Class clazz = (Class)(RefType.refTypeList.getItemByName(className.toString()));
//						if(clazz == null){
//							ErrorReporter.reporter.error(634, className.toString());
//							assert false : "class not found" + className.toString();
//						}
//						else{
//							Item method = clazz.methods.getItemByName(methName.toString());
//							loadConstantAndFixup(res.reg, method);	// addr of method
//						}
//					} else if (m.id == idREF) { // REF
//						createIrArSrB(ppcOr, res.reg, opds[0].reg, opds[0].reg);
//					} else if (m.id == idDoubleToBits) { // DoubleToBits
//						createIrSrAd(ppcStfd, opds[0].reg, stackPtr, tempStorageOffset);
//						createIrDrAd(ppcLwz, res.regLong, stackPtr, tempStorageOffset);
//						createIrDrAd(ppcLwz, res.reg, stackPtr, tempStorageOffset + 4);
//					} else if (m.id == idBitsToDouble) { // BitsToDouble
//						createIrSrAd(ppcStw, opds[0].regLong, stackPtr, tempStorageOffset);
//						createIrSrAd(ppcStw, opds[0].reg, stackPtr, tempStorageOffset+4);
//						createIrDrAd(ppcLfd, res.reg, stackPtr, tempStorageOffset);
//					} else if (m.id == idFloatToBits) { // FloatToBits
//						createIrSrAd(ppcStfs, opds[0].reg, stackPtr, tempStorageOffset);
//						createIrDrAd(ppcLwz, res.reg, stackPtr, tempStorageOffset);
//					} else if (m.id == idBitsToFloat) { // BitsToFloat
//						createIrSrAd(ppcStw, opds[0].reg, stackPtr, tempStorageOffset);
//						createIrDrAd(ppcLfs, 0, stackPtr, tempStorageOffset);
//						createIrDrB(ppcFmr, res.reg, 0);
					}
				} else {	// real method (not synthetic)
					if ((m.accAndPropFlags & (1<<apfStatic)) != 0 ||
							m.name.equals(HString.getHString("newPrimTypeArray")) ||
							m.name.equals(HString.getHString("newRefArray"))
							) {	// invokestatic
						if (m == stringNewstringMethod) {	// replace newstring stub with Heap.newstring
							m = heapNewstringMethod;
//							loadConstantAndFixup(res.regGPR1, m);	
//							createIrSspr(ppcMtspr, LR, res.regGPR1); 
						} else {
							insertBLAndFixup(code, 12, m);	// addr of method
//							createIrSspr(ppcMtspr, LR, res.regGPR1);
						}
//					} else if ((m.accAndPropFlags & (1<<dpfInterfCall)) != 0) {	// invokeinterface
//						int refReg = opds[0].reg;
//						int offset = (Class.maxExtensionLevelStdClasses + 1) * Linker32.slotSize + Linker32.tdBaseClass0Offset;
//						createItrap(ppcTwi, TOifequal, refReg, 0);
//						createIrDrAd(ppcLwz, res.regGPR1, refReg, -4);
//						createIrDrAd(ppcLwz, res.regGPR1, res.regGPR1, offset);	// delegate method
//						createIrSspr(ppcMtspr, LR, res.regGPR1);
//					} else if (call.invokespecial) {	// invokespecial
//						if (newString) {	// special treatment for strings
//							if (m == strInitC) m = strAllocC;
//							else if (m == strInitCII) m = strAllocCII;	// addr of corresponding allocate method
//							else if (m == strInitCII) m = strAllocCII;
//							loadConstantAndFixup(res.regGPR1, m);	
//							createIrSspr(ppcMtspr, LR, res.regGPR1);
//						} else {
//							int refReg = opds[0].reg;
//							createItrap(ppcTwi, TOifequal, refReg, 0);
//							loadConstantAndFixup(res.regGPR1, m);	// addr of init method
//							createIrSspr(ppcMtspr, LR, res.regGPR1);
//						}
//					} else {	// invokevirtual 
//						int refReg = opds[0].reg;
//						int offset = Linker32.tdMethTabOffset;
//						offset -= m.index * Linker32.slotSize; 
//						createItrap(ppcTwi, TOifequal, refReg, 0);
//						createIrDrAd(ppcLwz, res.regGPR1, refReg, -4);
//						createIrDrAd(ppcLwz, res.regGPR1, res.regGPR1, offset);
//						createIrSspr(ppcMtspr, LR, res.regGPR1);
					}
//					
//					// copy parameters into registers and to stack if not enough registers
//					if (dbg) StdStreams.vrb.println("call to " + m.name + ": copy parameters");
//					copyParameters(opds);
//					
//					if ((m.accAndPropFlags & (1<<dpfInterfCall)) != 0) {	// invokeinterface
//						// interface info goes into last parameter register
//						loadConstant(paramEndGPR, m.owner.index << 16 | m.index * 4);	// interface id and method offset						// check if param = maxParam in reg -2
//					}
//					
//					if (newString) {
//						int sizeOfObject = Type.wktObject.objectSize;
//						createIrDrAsimm(ppcAddi, paramStartGPR+opds.length, 0, sizeOfObject); // reg after last parameter
//					}
//					createIBOBILK(ppcBclr, BOalways, 0, true);
//					
					// get result
					int type = res.type & ~(1<<ssaTaFitIntoInt);
					if (type == tLong) {
//						if (res.regLong == returnGPR2) {
//							if (res.reg == returnGPR1) {	// returnGPR2 -> r0, returnGPR1 -> r3, r0 -> r2
//								createIrArSrB(ppcOr, 0, returnGPR2, returnGPR2);
//								createIrArSrB(ppcOr, res.regLong, returnGPR1, returnGPR1);
//								createIrArSrB(ppcOr, res.reg, 0, 0);
//							} else {	// returnGPR2 -> reg, returnGPR1 -> r3
//								createIrArSrB(ppcOr, res.reg, returnGPR2, returnGPR2);
//								createIrArSrB(ppcOr, res.regLong, returnGPR1, returnGPR1);
//							}
//						} else { // returnGPR1 -> regLong, returnGPR2 -> reg
//							createIrArSrB(ppcOr, res.regLong, returnGPR1, returnGPR1);
//							createIrArSrB(ppcOr, res.reg, returnGPR2, returnGPR2);
//						}
					} else if (type == tFloat || type == tDouble) {
//						createIrDrB(ppcFmr, res.reg, returnFPR);
					} else if (type == tVoid) {
//						if (newString) {
//							newString = false;
//							createIrArSrB(ppcOr, stringReg, returnGPR1, returnGPR1); // stringReg was set by preceding sCnew
//						}
					} else
						createDataProcReg(code, armMov, condAlways, dReg, returnGPR1);
					
				}
				break;}	//sCcall
			case sCnew: {
//				opds = instr.getOperands();
//				Item item = ((Call)instr).item;	// item = ref
//				Item method;
//				if (opds == null) {	// bCnew
//					if (item == Type.wktString) {
//						newString = true;	// allocation of strings is postponed
//						stringReg = res.reg;
//						loadConstantAndFixup(res.reg, item);	// ref to string
//					} else {
//						method = CFR.getNewMemoryMethod(bCnew);
//						loadConstantAndFixup(paramStartGPR, method);	// addr of new
//						createIrSspr(ppcMtspr, LR, paramStartGPR);
//						loadConstantAndFixup(paramStartGPR, item);	// ref
//						createIBOBILK(ppcBclr, BOalways, 0, true);
//						createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
//					}
//				} else if (opds.length == 1) {
//					switch (res.type  & ~(1<<ssaTaFitIntoInt)) {
//					case tAboolean: case tAchar: case tAfloat: case tAdouble:
//					case tAbyte: case tAshort: case tAinteger: case tAlong:	// bCnewarray
//						method = CFR.getNewMemoryMethod(bCnewarray);
//						loadConstantAndFixup(res.regGPR1, method);	// addr of newarray
//						createIrSspr(ppcMtspr, LR, res.regGPR1);
//						createIrArSrB(ppcOr, paramStartGPR, opds[0].reg, opds[0].reg);	// nof elems
//						createIrDrAsimm(ppcAddi, paramStartGPR + 1, 0, (instr.result.type & 0x7fffffff) - 10);	// type
//						loadConstantAndFixup(paramStartGPR + 2, item);	// ref to type descriptor
//						createIBOBILK(ppcBclr, BOalways, 0, true);
//						createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
//						break;
//					case tAref:	// bCanewarray
//						method = CFR.getNewMemoryMethod(bCanewarray);
//						loadConstantAndFixup(res.regGPR1, method);	// addr of anewarray
//						createIrSspr(ppcMtspr, LR, res.regGPR1);
//						createIrArSrB(ppcOr, paramStartGPR, opds[0].reg, opds[0].reg);	// nof elems
//						loadConstantAndFixup(paramStartGPR + 1, item);	// ref to type descriptor
//						createIBOBILK(ppcBclr, BOalways, 0, true);
//						createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
//						break;
//					default:
//						ErrorReporter.reporter.error(612);
//						assert false : "operand of new instruction has wrong type";
//						return;
//					}
//				} else { // bCmultianewarray:
//					method = CFR.getNewMemoryMethod(bCmultianewarray);
//					loadConstantAndFixup(res.regGPR1, method);	// addr of multianewarray
//					createIrSspr(ppcMtspr, LR, res.regGPR1);
//					// copy dimensions
//					for (int k = 0; k < nofGPR; k++) {srcGPR[k] = 0; srcGPRcount[k] = 0;}
//
//					// get info about in which register parameters are located
//					// the first two parameter registers are used for nofDim and ref
//					// therefore start is at paramStartGPR + 2
//					for (int k = 0, kGPR = 0; k < opds.length; k++) {
//						int type = opds[k].type & ~(1<<ssaTaFitIntoInt);
//						if (type == tLong) {
//							srcGPR[kGPR + paramStartGPR + 2] = opds[k].regLong;	
//							srcGPR[kGPR + 1 + paramStartGPR + 2] = opds[k].reg;
//							kGPR += 2;
//						} else {
//							srcGPR[kGPR + paramStartGPR + 2] = opds[k].reg;
//							kGPR++;
//						}
//					}
//					
//					// count register usage
//					int cnt = paramStartGPR + 2;
//					while (srcGPR[cnt] != 0) srcGPRcount[srcGPR[cnt++]]++;
//					
//					// handle move to itself
//					cnt = paramStartGPR + 2;
//					while (srcGPR[cnt] != 0) {
//						if (srcGPR[cnt] == cnt) srcGPRcount[cnt]--;
//						cnt++;
//					}
//
//					// move registers 
//					boolean done = false;
//					while (!done) {
//						cnt = paramStartGPR + 2; done = true;
//						while (srcGPR[cnt] != 0) {
//							if (srcGPRcount[cnt] == 0) { // check if register no longer used for parameter
//								if (dbg) StdStreams.vrb.println("\tGPR: parameter " + (cnt-paramStartGPR) + " from register " + srcGPR[cnt] + " to " + cnt);
//								createIrArSrB(ppcOr, cnt, srcGPR[cnt], srcGPR[cnt]);
//								srcGPRcount[cnt]--; srcGPRcount[srcGPR[cnt]]--; 
//								done = false;
//							}
//							cnt++; 
//						}
//					}
//					if (dbg) StdStreams.vrb.println();
//
//					// resolve cycles
//					done = false;
//					while (!done) {
//						cnt = paramStartGPR + 2; done = true;
//						while (srcGPR[cnt] != 0) {
//							int src = 0;
//							if (srcGPRcount[cnt] == 1) {
//								src = cnt;
//								createIrArSrB(ppcOr, 0, srcGPR[cnt], srcGPR[cnt]);
//								srcGPRcount[srcGPR[cnt]]--;
//								done = false;
//							}
//							boolean done1 = false;
//							while (!done1) {
//								int k = paramStartGPR + 2; done1 = true;
//								while (srcGPR[k] != 0) {
//									if (srcGPRcount[k] == 0 && k != src) {
//										createIrArSrB(ppcOr, k, srcGPR[k], srcGPR[k]);
//										srcGPRcount[k]--; srcGPRcount[srcGPR[k]]--; 
//										done1 = false;
//									}
//									k++; 
//								}
//							}
//							if (src != 0) {
//								createIrArSrB(ppcOr, src, 0, 0);
//								srcGPRcount[src]--;
//							}
//							cnt++;
//						}
//					}
//					loadConstantAndFixup(paramStartGPR, item);	// ref to type descriptor
//					createIrDrAsimm(ppcAddi, paramStartGPR+1, 0, opds.length);	// nofDimensions
//					createIBOBILK(ppcBclr, BOalways, 0, true);
//					createIrArSrB(ppcOr, res.reg, returnGPR1, returnGPR1);
//				}
				break;}
			case sCreturn: {
				int bci = meth.ssa.cfg.code[node.lastBCA] & 0xff;
				switch (bci) {
				case bCreturn:
					break;
				case bCireturn:
				case bCareturn:
					createDataProcReg(code, armMov, condAlways, returnGPR1, src1Reg);
					break;
				case bClreturn:
//					createIrArSrB(ppcOr, returnGPR1, opds[0].regLong, opds[0].regLong);
//					createIrArSrB(ppcOr, returnGPR2, opds[0].reg, opds[0].reg);
					break;
				case bCfreturn:
				case bCdreturn:
//					createIrDrB(ppcFmr, returnFPR, opds[0].reg);
					break;
				default:
					ErrorReporter.reporter.error(620);
					assert false : "return instruction not implemented";
					return;
				}
				if (node.next != null)	// last node needs no branch, other nodes branch to epilogue 
					createBranchImm(code, armB, condAlways, 0);
				break;}
			case sCbranch:
			case sCswitch: {
				int bci = meth.cfg.code[node.lastBCA] & 0xff;
				switch (bci) {
				case bCgoto:
					createBranchImm(code, armB, condAlways, 0);
					break;
				case bCif_acmpeq:
				case bCif_acmpne:
					createDataProcReg(code, armCmp, condAlways, 0, src2Reg, src1Reg, noShift, 0);
					if (bci == bCif_acmpeq)
						createBranchImm(code, armB, condEQ, 0);
					else
						createBranchImm(code, armB, condNOTEQ, 0);
					break;
				case bCif_icmpeq:
				case bCif_icmpne:
				case bCif_icmplt:
				case bCif_icmpge:
				case bCif_icmpgt:
				case bCif_icmple:
					boolean inverted = false;
					if (src1Reg < 0) {
						int immVal = ((StdConstant)opds[0].constant).valueH;
						createDataProcImm(code, armCmp, condAlways, src2Reg, immVal);
					} else if (src2Reg < 0) {
						inverted = true;
						int immVal = ((StdConstant)opds[1].constant).valueH;
						createDataProcImm(code, armCmp, condAlways, src1Reg, immVal);
					} else {
						createDataProcReg(code, armCmp, condAlways, 0, src2Reg, src1Reg, noShift, 0);
					}
					if (!inverted) {
						if (bci == bCif_icmpeq) 
							createBranchImm(code, armB, condEQ, 0);
						else if (bci == bCif_icmpne)
							createBranchImm(code, armB, condNOTEQ, 0);
						else if (bci == bCif_icmplt)
							createBranchImm(code, armB, condLT, 0);
						else if (bci == bCif_icmpge)
							createBranchImm(code, armB, condGE, 0);
						else if (bci == bCif_icmpgt)
							createBranchImm(code, armB, condGT, 0);
						else if (bci == bCif_icmple)
							createBranchImm(code, armB, condLE, 0);
					} else {
						if (bci == bCif_icmpeq) 
							createBranchImm(code, armB, condEQ, 0);
						else if (bci == bCif_icmpne)
							createBranchImm(code, armB, condNOTEQ, 0);
						else if (bci == bCif_icmplt)
							createBranchImm(code, armB, condGE, 0);
						else if (bci == bCif_icmpge)
							createBranchImm(code, armB, condLT, 0);
						else if (bci == bCif_icmpgt)
							createBranchImm(code, armB, condLE, 0);
						else if (bci == bCif_icmple)
							createBranchImm(code, armB, condGT, 0);
					}
					break; 
				case bCifeq:
				case bCifne:
				case bCiflt:
				case bCifge:
				case bCifgt:
				case bCifle: 
					createDataProcImm(code, armCmp, condAlways, src1Reg, 0);
					if (bci == bCifeq) 
						createBranchImm(code, armB, condEQ, 0);
					else if (bci == bCifne)
						createBranchImm(code, armB, condNOTEQ, 0);
					else if (bci == bCiflt)
						createBranchImm(code, armB, condLT, 0);
					else if (bci == bCifge)
						createBranchImm(code, armB, condGE, 0);
					else if (bci == bCifgt)
						createBranchImm(code, armB, condGT, 0);
					else if (bci == bCifle)
						createBranchImm(code, armB, condLE, 0);
					break;
				case bCifnonnull:
				case bCifnull: 
					createDataProcImm(code, armCmp, condAlways, src1Reg, 0);
					if (bci == bCifnonnull)
						createBranchImm(code, armB, condNOTEQ, 0);
					else
						createBranchImm(code, armB, condEQ, 0);
					break;
				case bCtableswitch:
//					int addr = node.lastBCA + 1;
//					addr = (addr + 3) & -4; // round to the next multiple of 4
//					addr += 4; // skip default offset
//					int low = getInt(ssa.cfg.code, addr);
//					int high = getInt(ssa.cfg.code, addr + 4);
//					int nofCases = high - low + 1;
//					for (int k = 0; k < nofCases; k++) {
//						createICRFrAsimm(ppcCmpi, CRF0, sReg1, low + k);
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
//					}
//					createIli(ppcB, nofCases, false);
					break;
				case bClookupswitch:
//					sReg1 = opds[0].reg;
//					addr = node.lastBCA + 1;
//					addr = (addr + 3) & -4; // round to the next multiple of 4
//					addr += 4; // skip default offset
//					int nofPairs = getInt(ssa.cfg.code, addr);
//					for (int k = 0; k < nofPairs; k++) {
//						int key = getInt(ssa.cfg.code, addr + 4 + k * 8);
//						createICRFrAsimm(ppcCmpi, CRF0, sReg1, key);
//						createIBOBIBD(ppcBc, BOtrue, 4*CRF0+EQ, 0);
//					}
//					createIli(ppcB, nofPairs, true);
					break;
				default:
					ErrorReporter.reporter.error(621);
					assert false : "branch instruction not implemented";
					return;
				}
				break;}
			case sCregMove: {
				switch (res.type & ~(1<<ssaTaFitIntoInt)) {
				case tInteger: case tChar: case tShort: case tByte: 
				case tBoolean: case tRef: case tAref: case tAboolean:
				case tAchar: case tAfloat: case tAdouble: case tAbyte: 
				case tAshort: case tAinteger: case tAlong:
					createDataProcReg(code, armMov, condAlways, dReg, src1Reg);
					break;
				case tLong:
//					createIrArSrB(ppcOr, res.regLong, opds[0].regLong, opds[0].regLong);
//					createIrArSrB(ppcOr, res.reg, opds[0].reg, opds[0].reg);
					break;
				case tFloat: case tDouble:
//					createIrDrB(ppcFmr, res.reg, opds[0].reg);
					break;
				default:
					if (dbg) StdStreams.vrb.println("type = " + (res.type & 0x7fffffff));
					ErrorReporter.reporter.error(610);
					assert false : "result of SSA instruction has wrong type";
					return;
				}
				break;}
			default:
				ErrorReporter.reporter.error(625);
				assert false : "SSA instruction not implemented: " + SSAInstructionMnemonics.scMnemonics[instr.ssaOpcode] + " function";
				return;
			}
//			if (dRegLongSlot >= 0) createIrSrAd(code, ppcStw, dRegLong, stackPtr, localVarOffset + 4 * dRegLongSlot);
			if (dRegSlot >= 0) {
				if ((res.type == tFloat) || (res.type == tDouble)); // createIrSrAd(code, ppcStfd, dReg, stackPtr, localVarOffset + 4 * dRegSlot);
//				else createIrSrAd(code, ppcStw, dReg, stackPtr, localVarOffset + 4 * dRegSlot);
			}
		}
	}

	private static void correctJmpAddr(int[] instructions, int count1, int count2) {
		instructions[count1] |= ((count2 - count1) << 2) & 0xffff;
	}

	// copy parameters for methods into parameter registers or onto stack
	private void copyParameters(Code32 code, SSAValue[] opds) {
		int offset = 0;
		for (int k = 0; k < nofGPR; k++) {srcGPR[k] = 0; srcGPRcount[k] = 0;}
		for (int k = 0; k < nofFPR; k++) {srcFPR[k] = 0; srcFPRcount[k] = 0;}

		// get info about in which register parameters are located
		// parameters which go onto the stack are treated equally
		for (int k = 0, kGPR = 0, kFPR = 0; k < opds.length; k++) {
			int type = opds[k].type & ~(1<<ssaTaFitIntoInt);
			if (type == tLong) {
				srcGPR[kGPR + paramStartGPR] = opds[k].regLong;
				srcGPR[kGPR + 1 + paramStartGPR] = opds[k].reg;
				kGPR += 2;
			} else if (type == tFloat || type == tDouble) {
				srcFPR[kFPR + paramStartFPR] = opds[k].reg;
				kFPR++;
			} else {
				srcGPR[kGPR + paramStartGPR] = opds[k].reg;
				kGPR++;
			}
		}

		if (dbg) {
			StdStreams.vrb.print("srcGPR = ");
			for (int k = paramStartGPR; srcGPR[k] != 0; k++) StdStreams.vrb.print(srcGPR[k] + ","); 
			StdStreams.vrb.println();
			StdStreams.vrb.print("srcGPRcount = ");
			for (int n = paramStartGPR; srcGPR[n] != 0; n++) StdStreams.vrb.print(srcGPRcount[n] + ","); 
			StdStreams.vrb.println();
		}

		// count register usage
		int i = paramStartGPR;
		while (srcGPR[i] != 0) {
			if (srcGPR[i] <= topGPR) srcGPRcount[srcGPR[i]]++;
			i++;
		}
		i = paramStartFPR;
		while (srcFPR[i] != 0) {
			if (srcFPR[i] <= topFPR) srcFPRcount[srcFPR[i]]++;
			i++;
		}
//		if (dbg) {
//			StdStreams.vrb.print("srcGPR = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPR[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("srcGPRcount = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPRcount[i] + ","); 
//			StdStreams.vrb.println();
//		}
		
		// handle move to itself
		i = paramStartGPR;
		while (srcGPR[i] != 0) {
			if (srcGPR[i] == i) srcGPRcount[i]--;
			i++;
		}
		i = paramStartFPR;
		while (srcFPR[i] != 0) {
			if (srcFPR[i] == i) srcFPRcount[i]--;
			i++;
		}
//		if (dbg) {
//			StdStreams.vrb.print("srcGPR = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPR[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("srcGPRcount = ");
//			for (i = paramStartGPR; srcGPR[i] != 0; i++) StdStreams.vrb.print(srcGPRcount[i] + ","); 
//			StdStreams.vrb.println();
//		}

		// move registers 
		boolean done = false;
		while (!done) {
			i = paramStartGPR; done = true;
			while (srcGPR[i] != 0) {
				if (i > paramEndGPR) {	// copy to stack
					if (srcGPRcount[i] >= 0) { // check if not done yet
						if (dbg) StdStreams.vrb.println("\tGPR: parameter " + (i-paramStartGPR) + " from register " + srcGPR[i] + " to stack slot");
						if (srcGPR[i] >= 0x100) {	// copy from stack slot to stack (into parameter area)
//							createIrDrAd(code, ppcLwz, 0, stackPtr, localVarOffset + 4 * (srcGPR[i] - 0x100));
//							createIrSrAsimm(code, ppcStw, 0, stackPtr, paramOffset + offset);
						} else {
//							createIrSrAsimm(code, ppcStw, srcGPR[i], stackPtr, paramOffset + offset);
							srcGPRcount[srcGPR[i]]--; 
						}
						offset += 4;
						srcGPRcount[i]--; 
						done = false;
					}
				} else {	// copy to register
					if (srcGPRcount[i] == 0) { // check if register no longer used for parameter
						if (dbg) StdStreams.vrb.println("\tGPR: parameter " + (i-paramStartGPR) + " from register " + srcGPR[i] + " to " + i);
						if (srcGPR[i] >= 0x100) {	// copy from stack
//							createIrDrAd(code, ppcLwz, i, stackPtr, localVarOffset + 4 * (srcGPR[i] - 0x100));
						} else {
//							createIrArSrB(code, ppcOr, i, srcGPR[i], srcGPR[i]);
							srcGPRcount[srcGPR[i]]--; 
						}
						srcGPRcount[i]--; 
						done = false;
					}
				}
				i++; 
			}
		}
//		if (dbg) StdStreams.vrb.println();
		done = false;
		while (!done) {
			i = paramStartFPR; done = true;
			while (srcFPR[i] != 0) {
				if (i > paramEndFPR) {	// copy to stack
					if (srcFPRcount[i] >= 0) { // check if not done yet
						if (dbg) StdStreams.vrb.println("\tFPR: parameter " + (i-paramStartFPR) + " from register " + srcFPR[i] + " to stack slot");
						if (srcFPR[i] >= 0x100) {	// copy from stack slot to stack (into parameter area)
//							createIrDrAd(code, ppcLfd, 0, stackPtr, localVarOffset + 4 * (srcFPR[i] - 0x100));
//							createIrSrAd(code, ppcStfd, 0, stackPtr, paramOffset + offset);
						} else {
//							createIrSrAd(code, ppcStfd, srcFPR[i], stackPtr, paramOffset + offset);
							srcFPRcount[srcFPR[i]]--;
						}
						offset += 8;
						srcFPRcount[i]--;  
						done = false;
					}
				} else {	// copy to register
					if (srcFPRcount[i] == 0) { // check if register no longer used for parameter
						if (dbg) StdStreams.vrb.println("\tFPR: parameter " + (i-paramStartFPR) + " from register " + srcFPR[i] + " to " + i);
						if (srcFPR[i] >= 0x100) {	// copy from stack
//							createIrDrAd(code, ppcLfd, i, stackPtr, localVarOffset + 4 * (srcFPR[i] - 0x100));
						} else {
//							createIrDrB(code, ppcFmr, i, srcFPR[i]);
							srcFPRcount[srcFPR[i]]--; 
						}
						srcFPRcount[i]--;  
						done = false;
					}
				}
				i++; 
			}
		}

		// resolve cycles
		done = false;
		while (!done) {
			i = paramStartGPR; done = true;
			while (srcGPR[i] != 0) {
				int src = 0;
				if (srcGPRcount[i] == 1) {
					src = i;
//					createIrArSrB(code, ppcOr, 0, srcGPR[i], srcGPR[i]);
					srcGPRcount[srcGPR[i]]--;
					done = false;
				}
				boolean done1 = false;
				while (!done1) {
					int k = paramStartGPR; done1 = true;
					while (srcGPR[k] != 0) {
						if (srcGPRcount[k] == 0 && k != src) {
//							createIrArSrB(code, ppcOr, k, srcGPR[k], srcGPR[k]);
							srcGPRcount[k]--; srcGPRcount[srcGPR[k]]--; 
							done1 = false;
						}
						k++; 
					}
				}
				if (src != 0) {
//					createIrArSrB(code, ppcOr, src, 0, 0);
					srcGPRcount[src]--;
				}
				i++;
			}
		}
		done = false;
		while (!done) {
			i = paramStartFPR; done = true;
			while (srcFPR[i] != 0) {
				int src = 0;
				if (srcFPRcount[i] == 1) {
					src = i;
//					createIrDrB(code, ppcFmr, 0, srcFPR[i]);
					srcFPRcount[srcFPR[i]]--;
					done = false;
				}
				boolean done1 = false;
				while (!done1) {
					int k = paramStartFPR; done1 = true;
					while (srcFPR[k] != 0) {
						if (srcFPRcount[k] == 0 && k != src) {
//							createIrDrB(code, ppcFmr, k, srcFPR[k]);
							srcFPRcount[k]--; srcFPRcount[srcFPR[k]]--; 
							done1 = false;
						}
						k++; 
					}
				}
				if (src != 0) {
//					createIrDrB(code, ppcFmr, src, 0);
					srcFPRcount[src]--;
				}
				i++;
			}
		}
	}

	// copy parameters for subroutines into registers r30/r31, r28/r29
	private void copyParametersSubroutine(int op0regLong, int op0reg, int op1regLong, int op1reg) {
		for (int k = 0; k < nofGPR; k++) {srcGPR[k] = 0; srcGPRcount[k] = 0;}

		// get info about in which register parameters are located
		srcGPR[topGPR] = op0reg;
		srcGPR[topGPR-1] = op0regLong;
		if (op1regLong != 0 && op1reg != 0) {srcGPR[topGPR-2] = op1reg; srcGPR[topGPR-3] = op1regLong;}
		
		// count register usage
		int i = topGPR;
		while (srcGPR[i] != 0) srcGPRcount[srcGPR[i--]]++;
		
		// handle move to itself
		i = topGPR;
		while (srcGPR[i] != 0) {
			if (srcGPR[i] == i) srcGPRcount[i]--;
			i--;
		}

		// move registers 
		boolean done = false;
		while (!done) {
			i = topGPR; done = true;
			while (srcGPR[i] != 0) {
				if (srcGPRcount[i] == 0) { // check if register no longer used for parameter
//					createIrArSrB(ppcOr, i, srcGPR[i], srcGPR[i]);
					srcGPRcount[i]--; srcGPRcount[srcGPR[i]]--; 
					done = false;
				}
				i--; 
			}
		}

		// resolve cycles
		done = false;
		while (!done) {
			i = topGPR; done = true;
			while (srcGPR[i] != 0) {
				int src = 0;
				if (srcGPRcount[i] == 1) {
					src = i;
//					createIrArSrB(ppcOr, 0, srcGPR[i], srcGPR[i]);
					srcGPRcount[srcGPR[i]]--;
					done = false;
				}
				boolean done1 = false;
				while (!done1) {
					int k = topGPR; done1 = true;
					while (srcGPR[k] != 0) {
						if (srcGPRcount[k] == 0 && k != src) {
//							createIrArSrB(ppcOr, k, srcGPR[k], srcGPR[k]);
							srcGPRcount[k]--; srcGPRcount[srcGPR[k]]--; 
							done1 = false;
						}
						k--; 
					}
				}
				if (src != 0) {
//					createIrArSrB(ppcOr, src, 0, 0);
					srcGPRcount[src]--;
				}
				i--;
			}
		}
	}	

	// Data-processing
	private void createDataProcImm(Code32 code, int opCode, int cond, int Rd, int Rn, int imm12) {
		code.instructions[code.iCount] = (cond << 28)| opCode | (Rd << 12) | (Rn << 16) | (imm12 << 0) | (1 << 25) ;
		code.incInstructionNum();
	}

	private void createDataProcImm(Code32 code, int opCode, int cond, int Rn, int imm12) {
		code.instructions[code.iCount] = (cond << 28)| opCode | (Rn << 16) | (imm12 << 0) | (1 << 25) ;
		code.incInstructionNum();
	}

	private void createDataProcReg(Code32 code, int opCode, int cond, int Rd, int Rn, int Rm, int shiftType, int shiftAmount) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | (Rn << 16) | (Rm << 0) | (shiftType << 5) | (shiftAmount << 7);
		code.incInstructionNum();
	}

	private void createDataProcReg(Code32 code, int opCode, int cond, int Rd, int Rm) {
		if ((opCode == armMov) && (Rd == Rm)) return;	// mov Rx, Rx makes no sense	
		createDataProcReg(code, opCode, cond, Rd, 0, Rm, noShift, 0);
	}

	private void createDataProcRegShiftedReg(Code32 code, int opCode, int cond, int Rd, int Rn, int Rm, int shiftType, int Rs) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | (Rn << 16) | (Rm << 0) | (shiftType << 5) | (Rs << 8) | (1 << 4);
		code.incInstructionNum();
	}

	// multiply
	private void createMul(Code32 code, int opCode, int cond, int Rd, int Rn, int Rm) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 16) | (Rm << 8) | Rn;
		code.incInstructionNum();
	}
	
	// rotate and shift (immediate)
	private void createRotateShiftImm(Code32 code, int opCode, int cond, int Rd, int Rm, int imm5) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | (Rm << 0) | (imm5 << 7);
		code.incInstructionNum();
	}

	private void createRotateShiftReg(Code32 code, int opCode, int cond, int Rd, int Rn, int Rm) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | (Rn << 0) | (Rm << 8) | (1 << 4);
		code.incInstructionNum();
	}
	

	// RRX
	private void createRrx(Code32 code, int opCode, int cond, int Rd, int Rm) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | (Rm << 0);
		code.incInstructionNum();
	}
	
	
	// MOVW, MOVT
	private void createMovw(Code32 code, int opCode, int cond, int Rd, int imm16) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | ((imm16 & 0xf000) << 4) | (imm16 & 0xfff);
		code.incInstructionNum();
	}
	
	// Synchronization primitives
	private void createSynchPrimLoad(Code32 code, int opCode, int cond, int Rt, int Rn) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rt << 12) | (Rn << 16);
		code.incInstructionNum();
	}

	private void createSynchPrimStore(Code32 code, int opCode, int cond, int Rd, int Rt, int Rn) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | (Rt << 0) | (Rn << 16);
		code.incInstructionNum();
	}
	
	
	// SWP / SWPB
	private void createSwp(Code32 code, int opCode, int cond, int Rt, int Rt2, int Rn) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rt << 12) | (Rt2 << 0) | (Rn << 16);
		code.incInstructionNum();
	}
	
	
	// DBG
	private void createDbg(Code32 code, int opCode, int cond, int option) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (option << 0);
		code.incInstructionNum();
	}
	
	// Hints (NOP / SEV / WFE / WFI / YIELD)
	private void createHint(Code32 code, int opCode, int cond) {
		code.instructions[code.iCount] = (cond << 28) | opCode;
		code.incInstructionNum();
	}
		
	// branch (immediate) (B, BL) 
	private void createBranchImm(Code32 code, int opCode, int cond, int imm24) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (imm24 & 0xffffff);
		code.incInstructionNum();
	}
	
	// branch (register) (including BX BXJ)
	private void createBranchReg(Code32 code, int opCode, int cond, int Rm) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rm << 0);
		code.incInstructionNum();
	}
	
	// BKPT / HVC
	private void createBkptHvc(Code32 code, int opCode, int cond, int imm16) {
		code.instructions[code.iCount] = (cond << 28) | opCode | ((imm16 & 0xfff0) << 4) | (imm16 & 0x000f);
		code.incInstructionNum();
	}
	
	
	// CLZ
	private void createClz(Code32 code, int opCode, int cond, int Rd, int Rm) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rd << 12) | (Rm << 0);
		code.incInstructionNum();
	}
	
	
	// ERET
	private void createEret(Code32 code, int opCode, int cond) {
		code.instructions[code.iCount] = (cond << 28) | opCode;
		code.incInstructionNum();
	}
	
	
	// SMC
	private void createSmc(Code32 code, int opCode, int cond, int imm4) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (imm4 << 0);
		code.incInstructionNum();
	}
	
	// Load/store word and unsigned byte	(LDR, LDRB, STR, STRB)	(LDRT / LDRBT / STRT / STRBT)
		// (LDR, LDRB, STR, STRB	:	(immediate))		(LDRT / LDRBT / STRT / STRBT	:	A1)
	private void createLSWordImm(Code32 code, int opCode, int cond, int Rt, int Rn, int imm12, int P, int U, int W) {
		if (opCode == armLdr || opCode == armLdrb || opCode == armStr || opCode == armStrb)
			code.instructions[code.iCount] = (cond << 28) | opCode | (Rt << 12) | (Rn << 16) | (imm12 << 0) | (P << 24) | (U << 23) | (W << 21);
		else	// extra load / store
			code.instructions[code.iCount] = (cond << 28) | opCode | (1 << 22) | (Rt << 12) | (Rn << 16) | (imm12 << 0) | (P << 24) | (U << 23) | (W << 21);	
		code.incInstructionNum();
	}

	// ...(LDR, LDRB	:	(literal))
	private void createLSWordLit(Code32 code, int opCode, int cond, int Rt, int imm12, int P, int U, int W) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rt << 12) | (0xf << 16) | (imm12 << 0) | (P << 24) | (U << 23) | (W << 21);
		code.incInstructionNum();
	}
	// ...(LDR, LDRB, STR, STRB	:	(register))		(LDRT / LDRBT / STRT / STRBT	:	A2)
	private void createLSWordReg(Code32 code, int opCode, int cond, int Rt, int Rn, int Rm, int shiftType, int shiftAmount, int P, int U, int W) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rt << 12) | (Rn << 16) | (Rm << 0) | (shiftType << 5) | (shiftAmount << 7) | (P << 24) | (U << 23) | (W << 21) | (1 << 25);
		code.incInstructionNum();
	}
	
	// block data transfer	(LDMxx, STMxx)
	private void createBlockDataTransfer(Code32 code, int opCode, int cond, int Rn, int regList, int W) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rn << 16) | regList | (W << 21);
		code.incInstructionNum();
	}
	
	// block data transfer	(POP, PUSH)
	private void createBlockDataTransfer(Code32 code, int opCode, int cond, int regList) {
		createBlockDataTransfer(code, opCode, cond, stackPtr, regList, 1);
	}
	
	
	// Unconditional instructions
	// ...RFE
	private void createRfe(Code32 code, int opCode, int cond, int Rn, int P, int U, int W) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (Rn << 16) | (P << 24) | (U << 23) | (W << 21);
		code.incInstructionNum();
	}
	// ...SRS
	private void createSrs(Code32 code, int opCode, int cond, int mode, int P, int U, int W) {
		code.instructions[code.iCount] = (cond << 28) | opCode | (mode << 0) | (P << 24) | (U << 23) | (W << 21);
		code.incInstructionNum();
	}
	
	private void createIpat(Code32 code, int pat) {
		code.instructions[code.iCount] = pat;
		code.incInstructionNum();
	}

	/*
	 * loads a constant (up to 32 Bit) with immediate instructions
	 */
	private void loadConstant(Code32 code, int reg, int val) {
		int low = val & 0xffff;
		int high = (val >> 16) & 0xffff;
		if (low != 0 && high != 0) {
			createMovw(code, armMovw, condAlways, reg, low);
			createMovw(code, armMovt, condAlways, reg, high);
		} else if (low == 0 && high != 0) {
			createMovw(code, armMovw, condAlways, reg, 0);
			createMovw(code, armMovt, condAlways, reg, high);
		} else if (low != 0 && high == 0) {
			createMovw(code, armMovw, condAlways, reg, low);
		} else createMovw(code, armMovw, condAlways, reg, 0);
	}
	
	/*
	 * inserts a load register literal instruction
	 * the address of a class constant is taken relative to the PC and will later be fixed
	 */
	private void loadConstantFromPoolAndFixup(Code32 code, int reg, Item item) {
		if (code.lastFixup < 0 || code.lastFixup > 4096) {ErrorReporter.reporter.error(602); return;}
		createLSWordLit(code, armLdr, condAlways, reg, code.lastFixup, 1, 1, 0);
		code.lastFixup = code.iCount - 1;
		code.fixups[code.fCount] = item;
		code.fCount++;
		int len = code.fixups.length;
		if (code.fCount == len) {
			Item[] newFixups = new Item[2 * len];
			for (int k = 0; k < len; k++)
				newFixups[k] = code.fixups[k];
			code.fixups = newFixups;
		}		
	}

	/*
	 * reads or writes the content of a class variable
	 * both instructions will later be fixed
	 */
	private void loadStoreVarAndFixup(Code32 code, int opCode, int reg, Item item) {
		if (code.lastFixup < 0 || code.lastFixup > 4096) {ErrorReporter.reporter.error(602); return;}
//		createLSWordLit(code, armLdr, condAlways, LR, code.lastFixup, 1, 1, 0);
		createMovw(code, armMovw, condAlways, LR, code.lastFixup);
		createMovw(code, armMovt, condAlways, LR, 0);
		createLSWordImm(code, opCode, condAlways, reg, LR, 0, 1, 1, 0);
		code.lastFixup = code.iCount - 3;
		code.fixups[code.fCount] = item;
		code.fCount++;
		int len = code.fixups.length;
		if (code.fCount == len) {
			Item[] newFixups = new Item[2 * len];
			for (int k = 0; k < len; k++)
				newFixups[k] = code.fixups[k];
			code.fixups = newFixups;
		}		
	}
	
//	/*
//	 * writes the content of a class variable
//	 * both instructions will later be fixed
//	 */
//	private void storeVarAndFixup(Code32 code, int opCode, int srcReg, Item item) {
//		if (code.lastFixup < 0 || code.lastFixup > 4096) {ErrorReporter.reporter.error(602); return;}
//		createLSWordLit(code, armLdr, condAlways, LR, code.lastFixup, 1, 1, 0);
//		createLSWordImm(code, opCode, condAlways, srcReg, LR, 0, 1, 1, 0);
//		code.lastFixup = code.iCount - 2;
//		code.fixups[code.fCount] = item;
//		code.fCount++;
//		int len = code.fixups.length;
//		if (code.fCount == len) {
//			Item[] newFixups = new Item[2 * len];
//			for (int k = 0; k < len; k++)
//				newFixups[k] = code.fixups[k];
//			code.fixups = newFixups;
//		}		
//	}
	
	/*
	 * inserts a BL instruction
	 * it's offset will later be fixed
	 */
	private void insertBLAndFixup(Code32 code, int reg, Item item) {
		if (code.lastFixup < 0 || code.lastFixup >= 4096) {ErrorReporter.reporter.error(602); return;}
		createBranchImm(code, armBl, condAlways, code.lastFixup);
		code.lastFixup = code.iCount - 1;
		code.fixups[code.fCount] = item;
		code.fCount++;
		int len = code.fixups.length;
		if (code.fCount == len) {
			Item[] newFixups = new Item[2 * len];
			for (int k = 0; k < len; k++)
				newFixups[k] = code.fixups[k];
			code.fixups = newFixups;
		}		
	}
	
	public void doFixups(Code32 code) {
		int currInstr = code.lastFixup;
		int currFixup = code.fCount - 1;
		while (currFixup >= 0) {
			Item item = code.fixups[currFixup];
			int addr;
			if (item == null) // item is null, if constant null is loaded (aconst_null) 
				addr = 0;
			else 
				addr = item.address;
			if (dbg) { 
				if (item == null) StdStreams.vrb.print("\tnull"); 
				else StdStreams.vrb.println("\t" + item.name + " at 0x" + Integer.toHexString(addr) + " currInstr=" + currInstr);
			}
			int[] instrs = code.instructions;
			int nextInstr = instrs[currInstr] & 0xfff;
			if (item instanceof Method) {	// must be a branch to a method
				int branchOffset = ((addr - code.ssa.cfg.method.address) >> 2) - currInstr - 2;	// -2: account for pipelining
				assert (branchOffset < 0x1000000) && (branchOffset > 0xff000000);
				if ((branchOffset >= 0x1000000) || (branchOffset <= 0xff000000)) {ErrorReporter.reporter.error(650); return;}
				instrs[currInstr] = (instrs[currInstr] & 0xff000000) | (branchOffset & 0xffffff);
			} else {	// must be a load / store instruction
				int val = item.address & 0xffff;
				instrs[currInstr] = (instrs[currInstr] & 0xfff0f000) | ((val & 0xf000) << 4) | (val & 0xfff);
				val = (item.address >> 16) & 0xffff;
				instrs[currInstr+1] = (instrs[currInstr+1] & 0xfff0f000) | ((val & 0xf000) << 4) | (val & 0xfff);
			}
			currInstr = nextInstr;
			currFixup--;
		}
		// fix addresses of exception information
		if (code.ssa == null) return;	// compiler specific subroutines have no unwinding or exception table
		if ((code.ssa.cfg.method.accAndPropFlags & (1 << dpfExcHnd)) != 0) return;	// exception methods have no unwinding or exception table
		if (dbg) StdStreams.vrb.print("\n\tFixup of exception table for method: " + code.ssa.cfg.method.owner.name + "." + code.ssa.cfg.method.name +  code.ssa.cfg.method.methDescriptor + "\n");		
		currInstr = code.excTabCount;
		int count = 0;
		while (code.instructions[currInstr] != 0xffffffff) {
//			SSAInstruction ssaInstr = code.ssa.searchBca(code.instructions[currInstr]);	
//			assert ssaInstr != null;
//			code.instructions[currInstr++] = code.ssa.cfg.method.address + ssaInstr.machineCodeOffset * 4;	// start
//			
//			ssaInstr = code.ssa.searchBca(code.instructions[currInstr]);	
//			assert ssaInstr != null;
//			code.instructions[currInstr++] = code.ssa.cfg.method.address + ssaInstr.machineCodeOffset * 4;	// end
//			
//			ExceptionTabEntry[] tab = code.ssa.cfg.method.exceptionTab;
//			assert tab != null;
//			ExceptionTabEntry entry = tab[count];
//			assert entry != null;
//			if (entry.catchType != null) code.instructions[currInstr++] = entry.catchType.address;	// type 
//			else code.instructions[currInstr++] = 0;	// finally 
//			
//			ssaInstr = code.ssa.searchBca(code.instructions[currInstr] + 1);	// add 1, as first store is ommitted	
//			assert ssaInstr != null;
//			code.instructions[currInstr++] = code.ssa.cfg.method.address + ssaInstr.machineCodeOffset * 4;	// handler
			currInstr++; //muss wieder weg!!!!!!!!!!!!!!!!!!!!!
			count++;
		}
		// fix addresses of variable and constant segment
		currInstr++;
		Class clazz = code.ssa.cfg.method.owner;
		code.instructions[currInstr] = clazz.varSegment.address + clazz.varOffset;
	}

	private void insertProlog(Code32 code) {
		code.iCount = 0;
		
		int regList = 1 << LR;
		if (nofNonVolGPR > 0) {
//			createIrSrAd(code, ppcStmw, nofGPR-nofNonVolGPR, stackPtr, GPRoffset);
		}
		createBlockDataTransfer(code, armStmdb, condAlways, regList);
		// enFloatsInExc could be true, even if this is no exception method
		// such a case arises when this method is called from within an exception method
//		if (enFloatsInExc) {
//			createIrD(code, ppcMfmsr, 0);
//			createIrArSuimm(code, ppcOri, 0, 0, 0x2000);
//			createIrS(code, ppcMtmsr, 0);
//			createIrS(code, ppcIsync, 0);	// must context synchronize after setting of FP bit
//		}
		int offset = FPRoffset;
//		if (nofNonVolFPR > 0) {
//			for (int i = 0; i < nofNonVolFPR; i++) {
//				createIrSrAd(code, ppcStfd, topFPR-i, stackPtr, offset);
//				offset += 8;
//			}
//		}
//		if (enFloatsInExc) {
//			for (int i = 0; i < nonVolStartFPR; i++) {	// save volatiles
//				createIrSrAd(code, ppcStfd, i, stackPtr, offset);
//				offset += 8;
//			}
//			createIrD(code, ppcMffs, 0);
//			createIrSrAd(code, ppcStfd, 0, stackPtr, offset);
//		}
//		if (dbg) {
//			StdStreams.vrb.print("moveGPRsrc = ");
//			for (int i = 0; moveGPRsrc[i] != 0; i++) StdStreams.vrb.print(moveGPRsrc[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("moveGPRdst = ");
//			for (int i = 0; moveGPRdst[i] != 0; i++) StdStreams.vrb.print(moveGPRdst[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("moveFPRsrc = ");
//			for (int i = 0; moveFPRsrc[i] != 0; i++) StdStreams.vrb.print(moveFPRsrc[i] + ","); 
//			StdStreams.vrb.println();
//			StdStreams.vrb.print("moveFPRdst = ");
//			for (int i = 0; moveFPRdst[i] != 0; i++) StdStreams.vrb.print(moveFPRdst[i] + ","); 
//			StdStreams.vrb.println();
//		}
		offset = 0;
//		for (int i = 0; i < nofMoveGPR; i++) {
//			if (moveGPRsrc[i]+paramStartGPR <= paramEndGPR) {// copy from parameter register
//				if (dbg) StdStreams.vrb.println("Prolog: copy parameter " + moveGPRsrc[i] + " into GPR" + moveGPRdst[i]);
//				if (moveGPRdst[i] < 0x100)
//					createIrArSrB(code, ppcOr, moveGPRdst[i], moveGPRsrc[i]+paramStartGPR, moveGPRsrc[i]+paramStartGPR);
//				else 	// copy to stack slot (locals)
//					createIrSrAd(code, ppcStw, moveGPRsrc[i]+paramStartGPR, stackPtr, localVarOffset + 4 * (moveGPRdst[i] - 0x100));
//			} else { // copy from stack slot (parameters)
//				if (dbg) StdStreams.vrb.println("Prolog: copy parameter " + moveGPRsrc[i] + " from stack slot into GPR" + moveGPRdst[i]);
//				if (moveGPRdst[i] < 0x100)
//					createIrDrAd(code, ppcLwz, moveGPRdst[i], stackPtr, stackSize + paramOffset + offset);
//				else { 	// copy to stack slot (locals)
//					createIrDrAd(code, ppcLwz, 0, stackPtr, stackSize + paramOffset + offset);
//					createIrSrAd(code, ppcStw, 0, stackPtr, localVarOffset + 4 * (moveGPRdst[i] - 0x100));
//				}	
//				offset += 4;
//			}
//		}
//		for (int i = 0; i < nofMoveFPR; i++) {
//			if (moveFPRsrc[i]+paramStartFPR <= paramEndFPR) {// copy from parameter register
//				if (dbg) StdStreams.vrb.println("Prolog: copy parameter " + moveFPRsrc[i] + " into FPR" + moveFPRdst[i]);
//				if (moveFPRdst[i] < 0x100)
//					createIrDrB(code, ppcFmr, moveFPRdst[i], moveFPRsrc[i]+paramStartFPR);
//				else	// copy to stack slot (locals)
//					createIrSrAd(code, ppcStfd, moveFPRsrc[i]+paramStartFPR, stackPtr, localVarOffset + 4 * (moveFPRdst[i] - 0x100));
//			} else { // copy from stack slot (parameters)
//				if (dbg) StdStreams.vrb.println("Prolog: copy parameter " + moveFPRsrc[i] + " from stack slot into FPR" + moveFPRdst[i]);
//				if (moveFPRdst[i] < 0x100)
//					createIrDrAd(code, ppcLfd, moveFPRdst[i], stackPtr, stackSize + paramOffset + offset);
//				else {
//					createIrDrAd(code, ppcLfd, 0, stackPtr, stackSize + paramOffset + offset);
//					createIrSrAd(code, ppcStfd, 0, stackPtr, localVarOffset + 4 * (moveFPRdst[i] - 0x100));
//				}
//				offset += 8;
//			}
//		}
	}

	private void insertEpilog(Code32 code, int stackSize) {
		int epilogStart = code.iCount;
//		int offset = GPRoffset - 8;
//		if (enFloatsInExc) {
//			createIrDrAd(ppcLfd, 0, stackPtr, offset);
//			createIFMrB(ppcMtfsf, 0xff, 0);
//			offset -= 8;
//			for (int i = nonVolStartFPR - 1; i >= 0; i--) {
//				createIrDrAd(ppcLfd, i, stackPtr, offset);
//				offset -= 8;
//			}
//		}
//		if (nofNonVolFPR > 0) {
//			for (int i = nofNonVolFPR - 1; i >= 0; i--) {
//				createIrDrAd(ppcLfd, topFPR-i, stackPtr, offset);
//				offset -= 8;
//			}
//		}
//		if (nofNonVolGPR > 0)
//			createIrDrAd(ppcLmw, nofGPR - nofNonVolGPR, stackPtr, GPRoffset);
//		createIrDrAd(ppcLwz, 0, stackPtr, LRoffset);
//		createIrSspr(ppcMtspr, LR, 0);
////		createIrDrAsimm(ppcAddi, stackPtr, stackPtr, stackSize);
////		createIBOBILK(ppcBclr, BOalways, 0, false);
		int regList = 1 << PC;
		createBlockDataTransfer(code, armPop, condAlways, regList);
		
		createIpat(code, (-(code.iCount-epilogStart)*4) & 0xff);
		code.excTabCount = code.iCount;
		ExceptionTabEntry[] tab = code.ssa.cfg.method.exceptionTab;
		if (tab != null) {
			for (int i = 0; i < tab.length; i++) {
				ExceptionTabEntry entry = tab[i];
				createIpat(code, entry.startPc);
				createIpat(code, entry.endPc);
				if (entry.catchType != null) createIpat(code, entry.catchType.address); else createIpat(code, 0);
				createIpat(code, entry.handlerPc);
			}
		}
		createIpat(code, 0xffffffff);	// end of exception information
		createIpat(code, 0);	// address of variable segment 
//		createIpat(code, 0);	// address of constant segment 		
	}

	private void insertPrologException() {
//		iCount = 0;
//		createIrSrAsimm(ppcStwu, stackPtr, stackPtr, -stackSize);
//		createIrSrAsimm(ppcStw, 0, stackPtr, GPRoffset);
//		createIrSspr(ppcMfspr, SRR0, 0);
//		createIrSrAsimm(ppcStw, 0, stackPtr, SRR0offset);
//		createIrSspr(ppcMfspr, SRR1, 0);
//		createIrSrAsimm(ppcStw, 0, stackPtr, SRR1offset);
//		createIrSspr(ppcMtspr, EID, 0);
//		createIrSspr(ppcMfspr, LR, 0);
//		createIrSrAsimm(ppcStw, 0, stackPtr, LRoffset);
//		createIrSspr(ppcMfspr, XER, 0);
//		createIrSrAsimm(ppcStw, 0, stackPtr, XERoffset);
//		createIrSspr(ppcMfspr, CTR, 0);
//		createIrSrAsimm(ppcStw, 0, stackPtr, CTRoffset);
//		createIrD(ppcMfcr, 0);
//		createIrSrAsimm(ppcStw, 0, stackPtr, CRoffset);
//		createIrSrAd(ppcStmw, 2, stackPtr, GPRoffset + 8);
//		if (enFloatsInExc) {
//			createIrD(ppcMfmsr, 0);
//			createIrArSuimm(ppcOri, 0, 0, 0x2000);
//			createIrS(ppcMtmsr, 0);
//			createIrS(ppcIsync, 0);	// must context synchronize after setting of FP bit
//			int offset = FPRoffset;
//			if (nofNonVolFPR > 0) {
//				for (int i = 0; i < nofNonVolFPR; i++) {
//					createIrSrAd(ppcStfd, topFPR-i, stackPtr, offset);
//					offset += 8;
//				}
//			}
//			for (int i = 0; i < nonVolStartFPR; i++) {
//				createIrSrAd(ppcStfd, i, stackPtr, offset);
//				offset += 8;
//			}
//			createIrD(ppcMffs, 0);
//			createIrSrAd(ppcStfd, 0, stackPtr, offset);
//		}
	}

	private void insertEpilogException(int stackSize) {
//		int offset = GPRoffset - 8;
//		if (enFloatsInExc) {
//			createIrDrAd(ppcLfd, 0, stackPtr, offset);
//			createIFMrB(ppcMtfsf, 0xff, 0);
//			offset -= 8;
//			for (int i = nonVolStartFPR - 1; i >= 0; i--) {
//				createIrDrAd(ppcLfd, i, stackPtr, offset);
//				offset -= 8;
//			}
//		}
//		if (nofNonVolFPR > 0) {
//			for (int i = nofNonVolFPR - 1; i >= 0; i--) {
//				createIrDrAd(ppcLfd, topFPR-i, stackPtr, offset);
//				offset -= 8;
//			}
//		}
//		createIrDrAd(ppcLmw, 2, stackPtr, GPRoffset + 8);
//		createIrDrAd(ppcLwz, 0, stackPtr, CRoffset);
//		createICRMrS(ppcMtcrf, 0xff, 0);
//		createIrDrAd(ppcLwz, 0, stackPtr, CTRoffset);
//		createIrSspr(ppcMtspr, CTR, 0);
//		createIrDrAd(ppcLwz, 0, stackPtr, XERoffset);
//		createIrSspr(ppcMtspr, XER, 0);
//		createIrDrAd(ppcLwz, 0, stackPtr, LRoffset);
//		createIrSspr(ppcMtspr, LR, 0);
//		createIrDrAd(ppcLwz, 0, stackPtr, SRR1offset);
//		createIrSspr(ppcMtspr, SRR1, 0);
//		createIrDrAd(ppcLwz, 0, stackPtr, SRR0offset);
//		createIrSspr(ppcMtspr, SRR0, 0);
//		createIrDrAd(ppcLwz, 0, stackPtr, GPRoffset);
////		createIrDrAsimm(ppcAddi, stackPtr, stackPtr, stackSize);
//		createIrfi(ppcRfi);
	}

	public void generateCompSpecSubroutines() {
		Method m = Method.getCompSpecSubroutine("longToDouble");
		// long is passed in r30/r31, r29 can be used for general purposes
		// faux1 and faux2 are used as general purpose FPR's, result is passed in f0 
		if (m != null) { 
			Code32 code = new Code32(null);
			m.machineCode = code;
		}
		
		m = Method.getCompSpecSubroutine("remLong");
		if (m != null) { 
			Code32 code = new Code32(null);
			m.machineCode = code;
		}
	
		m = Method.getCompSpecSubroutine("doubleToLong");
		if (m != null) { 
			Code32 code = new Code32(null);
			m.machineCode = code;
		}
	
		m = Method.getCompSpecSubroutine("divLong");
		if (m != null) { 
			Code32 code = new Code32(null);
			m.machineCode = code;
		}
	
		m = Method.getCompSpecSubroutine("handleException");
		if (m != null) { 
			Code32 code = new Code32(null);
			m.machineCode = code;
		}
	}

}


