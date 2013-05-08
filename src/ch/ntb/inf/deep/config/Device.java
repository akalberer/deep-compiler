/*
 * Copyright (c) 2011 NTB Interstate University of Applied Sciences of Technology Buchs.
 *
 * http://www.ntb.ch/inf
 * 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Eclipse Public License for more details.
 * 
 * Contributors:
 *     NTB - initial implementation
 * 
 */

package ch.ntb.inf.deep.config;

import ch.ntb.inf.deep.classItems.Item;
import ch.ntb.inf.deep.host.ErrorReporter;
import ch.ntb.inf.deep.host.StdStreams;
import ch.ntb.inf.deep.linker.TargetMemorySegment;
import ch.ntb.inf.deep.strings.HString;

public class Device extends Item {
	public Segment segments;
	public MemSector sector;

	public HString memorytype;
	public int technology = -1; // 0 = RAM, 1 = FLASH
	public int attributes = 0;
	public int size = 0;
	public int width = 0;

	public Device(String name, int baseAddress, int size, int width, int attributes, int technology, String memType) {
		this.name = HString.getRegisteredHString(name);
		this.address = baseAddress;
		this.size = size;
		this.width = width;
		this.attributes = attributes;
		this.technology = technology;
		this.memorytype = HString.getRegisteredHString(memType);
	}

	public void addSegment(Segment s) {
		if (Configuration.dbg) StdStreams.vrb.println("[CONF] Device: adding new segment " + s.name + " to device " + name);
		if (s.width == this.width) {
			if (segments == null) segments = s;
			else segments.appendTail(s);
		} else ErrorReporter.reporter.error(223, "width of device " + name + " is not equal with the width of the segment" + s.name + "\n");
	}
	
	public void addSector(MemSector newSec) {	// add to list, list is sorted by increasing addresses
		if (sector == null) sector = newSec;
		else {
			MemSector s = sector, prev = null;
			while (newSec.address >= s.address && s.next != null) {prev = s; s = (MemSector) s.next;}
			if (prev == null) {
				if (newSec.address >= s.address) {s.next = newSec;} // last item
				else {sector = newSec; newSec.next = s;}	// first item
			} else {
				if (newSec.address >= s.address) {s.next = newSec;} // last item
				else {prev.next = newSec; newSec.next = s;}	// insert
			}		
		}
	}

	public Segment getSegmentByName(String jname) {
		return (Segment)segments.getItemByName(jname);
	}
	
	public void markUsedSectors(TargetMemorySegment tms) {
		if (tms == null) return;
		int tmsEnd = tms.startAddress + tms.data.length * 4;
		boolean marked = false; //only for mark time optimization
		if (tms != null) {
			MemSector current = sector;
			while(current != null){
				if((current.address < tms.startAddress && tms.startAddress < (current.address + size)) || (tms.startAddress < current.address && (current.address + current.size) < tmsEnd) || (current.address < tmsEnd && tmsEnd <(current.address + current.size))){
					current.used = true;
					marked = true;
				} else if(marked) return;
				current = (MemSector)current.next;
			}	
		}		
	}

	public int nofMarkedSectors() {
		int count = 0;
		MemSector current = sector;
		while(current != null){
			if(current.used){
				count++;
			}
			current = (MemSector)current.next;
		}
		return count;
	}

	public void print(int indentLevel) {
		indent(indentLevel);
		vrb.println("device = " + name.toString() + " {");
		indent(indentLevel+1);
		vrb.print("technology = ");
		if (technology == 0) vrb.print("Ram, ");
		else if (technology == 1) vrb.print("Flash, ");
		else vrb.print("Unkown, ");
		vrb.print("attributes = 0x" + Integer.toHexString(attributes) + ", ");
		vrb.print("width = " + width + ", ");
		vrb.print("base = 0x" + Integer.toHexString(address) + ", ");
		vrb.println("size = 0x" + Integer.toHexString(size));
		Item curr = sector;
		while (curr != null) {
			curr.print(indentLevel+1);
			curr = (MemSector)curr.next;
		}	
		curr = segments;
		while (curr != null) {
			curr.print(indentLevel+1);
			curr = (Segment)curr.next;
		}
		indent(indentLevel);
		vrb.println("}");
	}
}
