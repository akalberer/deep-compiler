package ch.ntb.inf.junitTarget.comp.arrays;

import ch.ntb.inf.junitTarget.Assert;
import ch.ntb.inf.junitTarget.CmdTransmitter;
import ch.ntb.inf.junitTarget.MaxErrors;
import ch.ntb.inf.junitTarget.Test;

/**
 * NTB 14.11.2011
 * 
 * @author Urs Graf
 * 
 */
@MaxErrors(100)
public class ArrayThreeDimTest {
	
	@Test
	// Main test method for array length testing
	public static void testArrayLength() {
		int res;
		byte[][][] a1 = new byte[3][4][2];
		res = a1.length;
		Assert.assertEquals("byteArrayLength1", 3, res);
		res = a1[2].length;
		Assert.assertEquals("byteArrayLength2", 4, res);
		res = a1[0][1].length;
		Assert.assertEquals("byteArrayLength3", 2, res);
		
		CmdTransmitter.sendDone();
	}


	@Test
	// Main test method for array value test
	public static void testArrayValue() {
		int res;
		byte[][][] b = {{{0, 1, 2}, {3, 4, 5}},{{10, 11, 12}, {13, 14, 15}}};
		res = b[0][1][2];
		Assert.assertEquals("byteArrayValue", 5, res);
		
			
		CmdTransmitter.sendDone();
	}
	
	@Test
	// Main test method for array value test
	public static void testUnbalancedArray() {
			
		CmdTransmitter.sendDone();
	}
	
	@Test
	
	public static void testArrayVarious(){
		
		CmdTransmitter.sendDone();
	}

	@Test
	// test multiarray instruction
	public static void testMultiarray() {
		int res;
		byte[][][] a1 = new byte[3][4][2];
		a1[1][1][1] = 100;
		res = a1[1][1][1];
		Assert.assertEquals("byteArrayValue", 100, res);
		if (a1[0] instanceof byte[][]) res = 2; else res = 1;
		Assert.assertEquals("checkType", 2, res);
		
		long[][][] a2 = new long[3][4][2];
		a2[1][1][1] = -3456789;
		long res1 = a2[1][1][1];
		Assert.assertEquals("byteArrayValue", -3456789, res1);
		if (a2[0] instanceof long[][]) res = 2; else res = 1;
		Assert.assertEquals("checkType", 2, res);
		
		CmdTransmitter.sendDone();
	}
}
