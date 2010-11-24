package ch.ntb.inf.deep.config;

import ch.ntb.inf.deep.strings.HString;

public class Consts implements ErrorCodes {

	private static Consts constBlock;
	ValueAssignment consts;

	private Consts() {
	}

	public static Consts getInstance() {
		if (constBlock == null) {
			constBlock = new Consts();
		}
		return constBlock;
	}

	public void addConst(HString constName, int value) {
		if (consts == null) {
			consts = new ValueAssignment(constName, value);
			return;
		}
		int constHash = constName.hashCode();
		ValueAssignment current = consts;
		ValueAssignment prev = null;
		while (current != null) {
			if (current.name.hashCode() == constHash) {
				if (current.name.equals(consts.name)) {
					// TODO warn user
					current.setValue(value);
				}
			}
			prev = current;
			current = current.next;
		}
		// if no match prev shows the tail of the list
		prev.next = new ValueAssignment(constName, value);

	}

	public ValueAssignment getConst() {
		return consts;
	}

	public void println(int indentLevel) {
		if (consts != null) {
			for (int i = indentLevel; i > 0; i--) {
				System.out.print("  ");
			}
			System.out.println("constants {");
			ValueAssignment current = consts;
			while (current != null) {
				current.println(indentLevel + 1);
				current = current.next;
			}
			for (int i = indentLevel; i > 0; i--) {
				System.out.print("  ");
			}
			System.out.println("}");
		}
	}

	public int getConstByName(HString name) {
		int constHash = name.hashCode();
		ValueAssignment current = consts;
		while (current != null) {
			if (current.name.hashCode() == constHash) {
				if (current.name.equals(name)) {
					return current.getValue();
				}
			}
			current = current.next;
		}
		return Integer.MAX_VALUE;
	}
}