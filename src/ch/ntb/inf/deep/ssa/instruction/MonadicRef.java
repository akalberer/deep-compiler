package ch.ntb.inf.deep.ssa.instruction;

import ch.ntb.inf.deep.ssa.SSAValue;
import ch.ntb.inf.deep.strings.HString;

public class MonadicRef extends Monadic {
	
	HString className;
	HString fieldName;
	
	
	public MonadicRef(int opCode) {
		super(opCode);		
	}
	
	public MonadicRef(int opCode, HString className, HString fieldName){
		super(opCode);
		this.className = className;
		this.fieldName = fieldName;
	}
	
	public MonadicRef(int opCode, SSAValue operand){
		super(opCode,operand);
	}
	
	public MonadicRef(int opCode, HString className, HString fieldName, SSAValue operand){
		super(opCode,operand);
		this.className = className;
		this.fieldName = fieldName;
	}
	
	public void setArgs(HString className, HString fieldName){
		this.className = className;
		this.fieldName = fieldName;
	}
	
	public HString[] getArgs(){
		return new HString[]{className, fieldName};
	}
	
	@Override
	public void print(int level) {
		for (int i = 0; i < level*3; i++)System.out.print(" ");
		System.out.println("MonadicRef["+ scMnemonics[ssaOpcode]+"] ( "+ operands[0].typeName() + " )");
	}
	

}
