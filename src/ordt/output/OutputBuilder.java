/*
 * Copyright (c) 2016 Juniper Networks, Inc. All rights reserved.
 */
package ordt.output;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Stack;

import ordt.extract.Ordt;
import ordt.extract.RegModelIntf;
import ordt.extract.RegNumber;
import ordt.extract.Ordt.InputType;
import ordt.extract.RegNumber.NumBase;
import ordt.extract.RegNumber.NumFormat;
import ordt.extract.model.ModInstance;
import ordt.parameters.ExtParameters;
import ordt.parameters.Utils;

/**
 *  @author snellenbach      
 *  Jan 27, 2014
 *
 */
public abstract class OutputBuilder implements OutputWriterIntf{

	protected RegModelIntf model;  // model containing rdl extract info

	protected BufferedWriter bufferedWriter;
	
	// unique instance ID
	private static int nextBuilderID = 0;
	private int builderID = 0;
	
	private RegNumber nextAddress = new RegNumber("0x0");   // initialize to address 0
	private RegNumber baseAddress = new RegNumber("0x0");   // initialize to address 0
	
	private int maxRegWidth = ExtParameters.getMinDataSize();  // maximum sized register found in this addrmap - default to min pio data width  // TODO - precalculate this and store in model?

	protected Stack<InstanceProperties> instancePropertyStack = new Stack<InstanceProperties>();  // track currently active instance path
	
	private String addressMapName = (ExtParameters.defaultBaseMapName().isEmpty())? "" : ExtParameters.defaultBaseMapName();  // name of the base address map in this builder
	private boolean firstAddressMap = true;  // indication of first address map visited
	
	// parse rules for this output
	private boolean visitEachReg = true;   // should each register in a replicated group be visited
	private boolean visitEachRegSet = true;  // should each regset in a replicated group be visited
	private boolean visitExternalRegisters = false;  // should any register group/regset in an external group be visited
	private boolean visitEachExternalRegister = false;  // should each register in an external group be visited (treated as internal)
	private boolean allowLocalMapInternals = true;  // if true, address map instances encountered in builder will have local non-external regions
	private boolean supportsOverlays = false;  // if true, builder supports processing of overlay models

	private RegNumber externalBaseAddress;  // starting address of current external reg group
		
	// active rdl component info
	protected  SignalProperties signalProperties;  // output-relevant active signal properties 
	protected PriorityQueue<FieldProperties> fieldList = new PriorityQueue<FieldProperties>(  // fields in current register ordered by idx
	    	128, new Comparator<FieldProperties>(){
            public int compare(FieldProperties a, FieldProperties b) {
                if (a.getLowIndex() > b.getLowIndex()) return 1;  // ascending list
                if (a.getLowIndex() == b.getLowIndex()) return 0;
                return -1;
            }
        });
			
	protected  FieldProperties fieldProperties;  // output-relevant active field properties  
	protected  FieldSetProperties fieldSetProperties;  // output-relevant active field properties  
	protected Stack<FieldSetProperties> fieldSetPropertyStack = new Stack<FieldSetProperties>();  // field sets are nested so store stack
	protected  RegProperties regProperties;  // output-relevant active register properties  
	protected Stack<RegSetProperties> regSetPropertyStack = new Stack<RegSetProperties>();  // reg sets are nested so store stack
	protected  RegSetProperties regSetProperties;  // output-relevant active register set properties  
	private  RegSetProperties rootMapProperties;  // properties of root address map (separate from regSetProperties since root map is handled differently wrt rs stack) 
	
	/** reset builder state */
	protected void resetBuilder() {
		nextAddress = new RegNumber("0x0");   // initialize to address 0
		baseAddress = new RegNumber("0x0");   // initialize to address 0
		firstAddressMap = true;  // indication of first address map visited
		instancePropertyStack.clear();
		fieldSetPropertyStack.clear();
		regSetPropertyStack.clear();
	}
	
	/** get builderID
	 *  @return the builderID
	 */
	public int getBuilderID() {
		return builderID; 
	}

	/** set a new builderid */
	protected void setNewBuilderID() {
		this.builderID = nextBuilderID++;   // set unique ID of this instance
	}

	/** set a new builderid of 0 for this instance */
	protected void setBaseBuilderID() {
		nextBuilderID = 0;
		this.builderID = nextBuilderID++;   // set unique ID of this instance
	}

	/** return true if this is the root VerilogBuilder
	 */
	public boolean isBaseBuilder() {
		return (this.builderID == 0);
	}
	
	/** return fieldOffsetsFromZero state from the model **/
	public boolean fieldOffsetsFromZero() {
		return model.fieldOffsetsFromZero();
	}

	/** process an overlay file */
	public void processOverlay(RegModelIntf model) {
	}
		
	//---------------------------- methods to load verilog structures ----------------------------------------
	
	/** add a signal to this output */
	public  void addSignal(SignalProperties sProperties) {  
		if (sProperties != null) {
		   signalProperties = sProperties;   
		   // set instance path and instance property assigns
		   signalProperties.updateInstanceInfo(getInstancePath());
		   addSignal();
		}
	}
	
	/** add a signal for a particular output - concrete since most builders do not use */
	protected  void addSignal() {};
		
	/** add a field to this output */
	public  void addField(FieldProperties fProperties) {
		if (fProperties != null) {
		   
		   fieldProperties = fProperties;
			
		   // set instance path and instance property assigns
		   fieldProperties.updateInstanceInfo(getInstancePath());
		   
		   if (regProperties == null ) {   //FIXME
			   //System.out.println("OutputBuilder: addField: NO REGPROPERTIES DEFINED, path=" + getInstancePath() + ", id=" + fieldProperties.getId());
               return;  // exit if no active register
		   }
		   // get index range of this field in the register
		   Integer computedLowIndex = regProperties.addField(fieldProperties); // get field array indices from reg
		   fieldProperties.setLowIndex(computedLowIndex); // save the computed index back into fieldProperties  // TODO - updateIndexInfo here or in addField?
		   
		   // set slice ref string for this field
		   String fieldArrayString = "";
		   if (fieldProperties.getFieldWidth() != regProperties.getRegWidth())  
			   fieldArrayString = genRefArrayString(computedLowIndex, fieldProperties.getFieldWidth());
		   fieldProperties.setFieldArrayString(fieldArrayString);
		   // set fieldset prefix of this field instance
		   if (!fieldSetPropertyStack.isEmpty()) {
			   String prefix = getFieldSetPrefix(); // extract from fieldset instance values
			   //System.err.println("OutputBuilder: addField, setting prefix to  " + prefix);
			   //System.out.println("  field path=" + fieldProperties.getInstancePath() + ", id=" + fieldProperties.getId());
			   //System.out.println("    reg path=" + regProperties.getInstancePath() + ", id=" + regProperties.getId());
			   fieldProperties.setFieldSetPrefixString(prefix);			   
		   }
		   
		   // check for invalid field parameters
		   if (fieldProperties.isInvalid()) Ordt.errorMessage("invalid rw access to field " + fieldProperties.getInstancePath());
		   		    
		   if (regProperties != null) {
			   // handle field output for alias vs non-alias regs
			   if (visitEachReg() || (regProperties.isFirstRep() && firstRegSetRep())) { // qualify add calls by valid register options	
					fieldList.add(fieldProperties);  // add field to list for current register
				   if (regProperties.isAlias()) 
					   addAliasField();
				   else
					   addField();
			   }
			   //System.out.println("path=" + getInstancePath() + ", id=" + regInst.getId());
		   }
		}
	}
	
	/** add a field for a particular output */
	abstract public  void addField();
		
	/** add an alias register field for a particular output */
	abstract public  void addAliasField();

	/** add a field setr to this output 
	 * @param rep - replication count
     */
	public void addFieldSet(FieldSetProperties fsProperties, int rep) {
		if (fsProperties != null) {
			   //System.out.println("OutputBuilder: addFieldSet, path=" + getInstancePath() + ", id=" + fsProperties.getId());

			   // extract properties from instance/component
			   fieldSetProperties = fsProperties; 
			   // set instance path and instance property assigns
			   fieldSetProperties.updateInstanceInfo(getInstancePath());  
			   // set rep number of this iteration
			   fieldSetProperties.setRepNum(rep);
			   
			   fieldSetPropertyStack.push(fieldSetProperties);  // push active fieldset onto stack
			   
			   // initialize w/ relative offset from model instance if it exists (will be overwritten w/ abs lowIndex value from setFieldSetOffset)
			   Integer modOffset = fieldSetProperties.getExtractInstance().getOffset();
			   if (modOffset!=null) fieldSetProperties.setLowIndex(modOffset);
			   
			   // update current fieldset offset in active register definition
			   Integer currentFsetOffset = getCurrentFieldSetOffset();  // get current offset in reg for this fieldset
			   Integer computedLowIndex = regProperties.setFieldSetOffsets(currentFsetOffset, currentFsetOffset); // offset and min valid are at same position
			   fieldSetProperties.setLowIndex(computedLowIndex); // save the computed index back into fieldProperties
			   
			   addFieldSet();
			}
	}

	/** add a fieldset for a particular output - concrete since most builders do not use */
	protected void addFieldSet() {}

	public void finishFieldSet(FieldSetProperties fsProperties) {
		if (fsProperties != null) {
		   finishFieldSet();
		   FieldSetProperties fset = fieldSetPropertyStack.pop();  // done, so pop from stack
			if (fieldSetPropertyStack.isEmpty()) fieldSetProperties = null;
			else fieldSetProperties = fieldSetPropertyStack.peek();  // restore parent as active fieldset
			// restore parent fieldset offset post pop
			regProperties.setFieldSetOffsets(getCurrentFieldSetOffset(), fset.getLowIndex() + fset.getFieldSetWidth()); // minValidOffset must account for width of fieldset just completed
		}
	}

	/** finish a fieldset for a particular output - concrete since most builders do not use */
	protected void finishFieldSet() {}


	/** add a register to this output.
	 *  called for internal regs and externals where visitEachExternalRegister is set.
	 * @param regAddress - address of this register
	 * @param rep - replication count
      */
	public  void addRegister(RegProperties rProperties, int rep) {  
		if (rProperties != null) {
		   //System.out.println("OutputBuilder " + getBuilderID() + " addRegister, path=" + getInstancePath() + ", id=" + rProperties.getId() + ", addr=" + rProperties.getExtractInstance().getAddress());

		   // extract properties from instance/component
		   regProperties = rProperties; 
		   // set instance path and instance property assigns
		   regProperties.updateInstanceInfo(getInstancePath());
		   
		   updateMaxRegWidth(regProperties.getRegWidth());  // check for largest register width
		   
		   // set rep number of this iteration
		   regProperties.setRepNum(rep);
		   
		   // set register base address
		   updateRegBaseAddress();
		   
		   // now bump the running address count by regsize or increment value
		   RegNumber addressIncrement = regProperties.getExtractInstance().getAddressIncrement();
		   updateNextAddress(addressIncrement);  
		   
		   // only visit once if specified by this output type
		   if (visitEachReg() || (regProperties.isFirstRep() && firstRegSetRep())) {
			   fieldList.clear();  // clear fields in current register
			   addRegister();   
		   }
		}
	}

	/** add a register for a particular output */
	abstract public  void addRegister();

	/** process register info after all fields added */  
	public  void finishRegister(RegProperties rProperties) {  
		if (rProperties != null) {
			// only visit once if specified by this output type
			//System.out.println("OutputBuilder finishRegister: " + regProperties.getInstancePath() + ", visit each reg=" + visitEachReg() + ", isFirstRep=" + regProperties.isFirstRep() + ", firstRegSetRep=" + firstRegSetRep());
			if (visitEachReg() || (regProperties.isFirstRep() && firstRegSetRep())) {
			    updateFinishRegProperties(regProperties);  // update regprops post field processing
			    regSetProperties.updateChildHash(regProperties.hashCode()); // add this reg's hashcode to parent
				finishRegister();   
			}
		}
		//else System.out.println("OutputBuilder finishRegister: null regProperties");

	}

	/** finish a register for a particular output */
	abstract public  void finishRegister();
	
	/** add an external register group to this output (called if external and not visiting each reg) // TODO combine with addRegisters
	 * @param rProperties - extracted register properties */
	public  void addExternalRegisters(RegProperties rProperties) {  
		if (rProperties != null) {
		   //System.out.println("OutputBuilder: addExternalRegisters, path=" + getInstancePath() + ", id=" + rProperties.getId());

		   // extract properties from instance/component
		   regProperties = rProperties; 
		   // set instance path from instance stack, and init property info
		   regProperties.updateInstanceInfo(getInstancePath());
		   
		   updateMaxRegWidth(regProperties.getRegWidth());  // check for largest register width
		   
		   // set register base address
		   updateRegBaseAddress();
		   
		   if (regProperties.isRootExternal()) {
			   //System.out.println("OutputBuilder: addExternalRegisters: root external path=" + regProperties.getInstancePath() + ", base addr=" + regProperties.getBaseAddress()  + ", next/new ext base addr=" + nextAddress);
			   setExternalBaseAddress(nextAddress);  // save address of this reg if root external
		   }

		   // now bump the running address count by regsize or increment value times reg count
		   RegNumber addressIncrement = regProperties.getExtractInstance().getAddressIncrement();
		   updateNextAddress(addressIncrement, regProperties.getRepCount()); 
		   
		   //System.out.println("addExternalRegisters: path=" + regProperties.getInstancePath() + ", base=" + regProperties.getBaseAddress()  + ", next=" + nextAddress);

		   // if this output type will process external register output
		   if (visitExternalRegisters() && firstRegSetRep()) { // only first rset rep here (only one call for all reg reps)
			   fieldList.clear();  // clear fields in current register
			   addRegister();   
		   }
		}
	}

	/** process ext register info after all fields added */  
	public  void finishExternalRegisters(RegProperties rProperties) {
		if (rProperties != null) {
			// only visit once if specified by this output type
			if (visitExternalRegisters() && firstRegSetRep()) {
			    updateFinishRegProperties(regProperties);  // update regprops post field processing
			    regSetProperties.updateChildHash(regProperties.hashCode()); // add this reg's hashcode to parent
				finishRegister();   // only first rset rep here (only one call for all reg reps)
			}
		}
	}

	/** add a set of external registers to this output 
	 * @param newRegProperties - if non-null (is ext regset, not reg), this will be set as external and used as static regProperties for output gen */
	public void addRootExternalRegisters(RegProperties newRegProperties) { 
		int reservedRange = updateRootExternalRegProperties(newRegProperties, false);

		addRootExternalRegisters();  // note getExternalRegBytes() is usable by child

		// now bump the running address count by the reserved range
		RegNumber newNext = new RegNumber(getExternalBaseAddress());
		newNext.add(new RegNumber(reservedRange * getMinRegByteWidth()));
		setNextAddress(newNext);   
		//System.out.println("addRootExternalRegisters   base=" + getExternalBaseAddress() + ", next=" + getNextAddress() + ", delta=" + extSize);
	}

	/** add a register for a particular output  - concrete since most builders do not use. 
	 *  called when entering a root external register/registerset region */
	protected void addRootExternalRegisters() {}
	
	/** add a non-root address map to this output - used to generate verilog for non-root child maps such as ring leaf decoders
	 * @param newRegProperties - if non-null (is ext regset, not reg), this will be set as external and used as static regProperties for output gen */
	public void addNonRootExternalAddressMap(RegProperties newRegProperties) { 		
		updateRootExternalRegProperties(newRegProperties, true);

		addNonRootExternalAddressMap();  // note getExternalRegBytes() is usable by child
	}
	
	/** add a non-root addressmap - concrete since most builders do not use */
	protected void addNonRootExternalAddressMap() {}

	/** update static regProperties for child builder processing and return address range of regset
	 * @param newRegProperties - properties that will be updated
	 * @param isNonRootExternal - if true, base address will be set to that of current regset else builder ext base addr will be used
	 */
	private int updateRootExternalRegProperties(RegProperties newRegProperties, boolean isNonRootExternal) {
		// if new regProperties, then init 
		if (newRegProperties != null) {
			if (!newRegProperties.isExternal()) {
				newRegProperties.setExternal("DEFAULT");  // insure external is set
				//System.out.println("OutputBuilder addRootExternalRegisters: setting DEFAULT external for path=" + getInstancePath() +", orig ext=" + newRegProperties.getExternalType());
			}
			//System.out.println("OutputBuilder updateRootExternalRegProperties: updating base addr for path=" + getInstancePath() + ", old base=" + newRegProperties.getBaseAddress() + ", new base=" + getExternalBaseAddress() + ", rs base=" + regSetProperties.getBaseAddress());
			if (isNonRootExternal) newRegProperties.setBaseAddress(regSetProperties.getBaseAddress());   //  use current base to support multiple child scenarios
			else newRegProperties.setBaseAddress(getExternalBaseAddress());   //  use ext base address stored by builder
			newRegProperties.updateInstanceInfo(getInstancePath());  		   // set instance path and instance property assigns
			regProperties = newRegProperties;
		}
		//System.out.println("OutputBuilder addRootExternalRegisters: adding external reg set, path=" + getInstancePath()); // + ", reps=" + repCount);

		// compute size of the external group from next and stored base address
		RegNumber extSize = getExternalRegBytes();  
		//System.out.println("OutputBuilder addRootExternalRegisters: base=" + getExternalBaseAddress() + ", next=" + getNextAddress() + ", delta=" + extSize);

		int lowBit = getAddressLowBit();  // same low bit as overall address range
		regProperties.setExtLowBit(lowBit);  // save the low bit in external address
		//int highBit = getAddressHighBit(repSize);
		int range = getAddressWidth(extSize);
		regProperties.setExtAddressWidth(range);  // set width of the external address for this group
		int reservedRange = 1 << range;  // calc 2^n
		//System.out.println("addRootExternalRegisters   ext addr range=" + range + ", lo bit=" + lowBit + ", new rep count=" + reservedRange);
		return reservedRange;
	}

	/** add a register set to this output 
	 * @param rep - replication count */
	public  void addRegSet(RegSetProperties rsProperties, int rep) {  
		
		if (rsProperties != null) {
		   //System.out.println("OutputBuilder addRegSet: path=" + getInstancePath() + ", builder=" + builderID); // + ", id=" + regSetInst.getId());

		   regSetProperties = rsProperties; 
		   
		   // set instance path and instance property assigns
		   regSetProperties.updateInstanceInfo(getInstancePath());
		   
			// get address info from instance
		   RegNumber regSetAddress = regSetProperties.getExtractInstance().getAddress(); 
		   RegNumber addressIncrement = regSetProperties.getExtractInstance().getAddressIncrement();
		   RegNumber addressModulus = regSetProperties.getExtractInstance().getAddressModulus();     
		   RegNumber addressShift = regSetProperties.getExtractInstance().getAddressShift(); 
		   
		   // set replication number
		   regSetProperties.setRepNum(rep);
		   //System.out.println("OutputBuilder addRegSet: --- rep=" + rep + ", regSetAddress=" + regSetAddress + ", incAddress=" + addressIncrement  + ", nextAddress=" + nextAddress);
 
		   // if an explicit address is set for this regset, then use it  
		   if (regSetAddress != null) {
			   //System.out.println("OutputBuilder addRegSet: --- rep=" + rep + ", regSetAddress=" + regSetAddress + ", incAddress=" + addressIncrement  + ", nextAddress=" + nextAddress);
			   
			   // compute relative address by adding to parent base
			   RegNumber newBaseAddr = new RegNumber(regSetAddress);  
			   newBaseAddr.add(getRegSetParentAddress());
			   //System.out.println("OutputBuilder addRegSet:   parent=" + getRegSetParentAddress() + ", abs=" + newBaseAddr);
			   
               // compute next address if increment specified
			   RegNumber newAddr = new RegNumber(newBaseAddr);  
			   if (addressIncrement != null) {
				   RegNumber incrOffset = new RegNumber(addressIncrement);
				   incrOffset.multiply(rep);  // compute address increment based on rep number
				   newAddr.add(incrOffset);
				   //System.out.println("OutputBuilder addRegSet:   nextAddr=" + nextAddress + ", incr added=" + newAddr);
			   }
			   // only update if an increment or first iteration
			   if ((addressIncrement != null) || regSetProperties.isFirstRep()) {
				   if (newAddr.isLessThan(nextAddress)) {  // check for bad address here
					   Ordt.errorExit("out of order register set address specified in " + getInstancePath() );
				   }
				   setNextAddress(newAddr);  //  save computed next address   
			   }
		    }
		   
		   // else if base address shift is specified
		   else if ((addressShift != null) && (regSetProperties.isFirstRep())) {
			   updateNextAddress(addressShift);
		   }

		   // otherwise adjust if a modulus is specified
		   else {
			   if ((rep>0) && (addressIncrement != null)) updateNextAddress(addressIncrement);  // bump the running address count by increment value if specified
			   if (rep == 0) {
				   updateNextAddressModulus(addressModulus);  // adjust the base address if a modulus is defined
				   // get estimated size from model and check that base addr is aligned with size
				   if (ExtParameters.useJsAddressAlignment()) {
					   RegNumber alignBytes = regSetProperties.getExtractInstance().getRegComp().getAlignedSize();
					   alignBytes.setNumBase(NumBase.Hex);
					   alignBytes.setNumFormat(NumFormat.Address);
					   if ((alignBytes != null) && alignBytes.isNonZero()) { 

						   //System.out.println("OutputBuilder addRegSet: regset=" + this.getInstancePath() + ", align==" + alignBytes + ", addr=" + nextAddress + ", rep=" + regProperties.getRepCount());
						   // if this reg isn't in an external decoder and misaligned, then display message and/or align it
						   if (!nextAddress.isModulus(alignBytes) && !regSetProperties.isExternalDecode()) {
							   // if an external and not root external, just display a warn message
							   if (regSetProperties.isExternal() && !regSetProperties.isRootExternal()) {
								   if  (!ExtParameters.suppressAlignmentWarnings()) 
									   Ordt.warnMessage("base address for external register set " + regSetProperties.getInstancePath() + " is not " + alignBytes + "B aligned.");
							   }
							   else {
								   if  (!ExtParameters.suppressAlignmentWarnings()) 
									   Ordt.warnMessage("base address for register set " + regSetProperties.getInstancePath() + " shifted to be " + alignBytes + "B aligned.");
								   updateNextAddressModulus(new RegNumber(alignBytes));  // adjust the address to align register set					   
								   
							   }
						   }
					   }
				   }
			   }
		   }
		   
		   // save the base address of this reg set
		   regSetProperties.setBaseAddress(nextAddress); 
		   // save address of this reg set if root external 
		   if (regSetProperties.isRootExternal() && regSetProperties.isFirstRep()) setExternalBaseAddress(nextAddress);  
		   // compute the relative base address (vs parent) and save it
		   RegNumber relAddress = new RegNumber(regSetProperties.getBaseAddress());  // start with current
		   relAddress.subtract(getRegSetParentAddress());  // subtract parent base from current
		   regSetProperties.setRelativeBaseAddress(relAddress);  // store in regset
		   //System.out.println("OutputBuilder addRegSet:   saved  base=" + regSetProperties.getBaseAddress() + ", saved rel=" + regSetProperties.getRelativeBaseAddress() + ", saved ext base=" + getExternalBaseAddress());
		   // push onto regset properties stack
		   regSetPropertyStack.push(regSetProperties);    

		   // determine if this rep should be visited 
		   boolean visitThisRep = visitEachRegSet() || (regSetProperties.isFirstRep() && firstRegSetRep());
		   // if valid visit and specified reg set type then call finishRgSet 
		   if (visitThisRep && (!regSetProperties.isExternal() || 
				   (regSetProperties.isExternal() && visitExternalRegisters()))) {  
			   //System.out.println("addRegSet: rep=" + rep + ", vistEachRegSet=" + visitEachRegSet() + ", isFirstRep=" + regSetProperties.isFirstRep()  + ", firstRegSetRep=" + firstRegSetRep());
			   addRegSet();
		   }
		}
	}

	/** add a register set for a particular output */
	abstract public  void addRegSet();
	
	/** update next address after last regset/regmap rep if an increment operator is specified (called by ModRegSet generate on last rep)
	 * @param rsProperties - register set properties for active instance
	 */
	public  void updateLastRegSetAddress(RegSetProperties rsProperties) {   
		// if a replicated regset with increment, then set nextAddress to end of range when done
		RegNumber regSetAddress = regSetProperties.getBaseAddress();  // base address of last regset rep (rs stack is not yet popped)

		RegNumber addressIncrement = regSetProperties.getExtractInstance().getAddressIncrement();
		if (regSetAddress != null) {
			RegNumber newAddr = new RegNumber(regSetAddress);
			if (regSetProperties.isLastRep() && (addressIncrement != null)) {  // non-null repCount indicated final iteration
			   //RegNumber incrOffset = new RegNumber(addressIncrement);
			   //incrOffset.multiply(regSetProperties.getRepCount());  // compute address increment based on replication count				
			   newAddr.add(addressIncrement);
			   setNextAddress(newAddr);
			}
		}		
	}

	/** process regset/regmap info after all subsets,regs added 
	 * @param rsProperties - register set properties for active instance
	 */
	public  void finishRegSet(RegSetProperties rsProperties) {  
		// save the highest address of this reg set
		RegNumber highAddress = new RegNumber(nextAddress);
		highAddress.subtract(1);
		regSetProperties.setHighAddress(highAddress); 
		//System.out.println("OutputBuilder finishRegSet: id=" + rsProperties.getId() + ", next=" + nextAddress + ", highAddress=" + highAddress );		

		// determine if this rep should be visited 
		boolean visitThisRep = visitEachRegSet() || (rsProperties.isFirstRep() && firstRegSetRep());
		// if valid visit and specified reg set type then call finishRgSet 
		if (visitThisRep && (!rsProperties.isExternal() || 
				(rsProperties.isExternal() && visitExternalRegisters()))) {  
			finishRegSet();
			//System.out.println("finishRegSet: external=" + rsProperties.isExternal() + ", visitThisRep=" + visitThisRep + ", vistEachRegSet=" + visitEachRegSet() + ", isFirstRep=" + regSetProperties.isFirstRep()  + ", firstRegSetRep=" + firstRegSetRep());		
		}

		// done with this regset, so pop stack to restore parent regset properties
		regSetPropertyStack.pop();

		if (regSetPropertyStack.isEmpty()) { regSetProperties = rootMapProperties;} 
		else {
			regSetPropertyStack.peek().updateChildHash(regSetProperties.hashCode()); // add popped regset's hashcode to parent
			regSetProperties = regSetPropertyStack.peek();
		}
	}
	
	/** finish a register set for a particular output */
	abstract public  void finishRegSet();
	
	/** add the root address map to this output - addRegMap is only called on root addrmap in Builder */
	public  void addRegMap(ModInstance regMapInst) {  
		if (regMapInst != null) {
		   regSetProperties = new RegSetProperties(regMapInst);  // extract basic properties  (maxregwidth, id, external, default properties)
		   regSetProperties.setAddressMap(true);
		   regSetProperties.setInternal();   // first map in a new builder is treated as internal (overrides RegSetProperties constructor)
		   rootMapProperties = regSetProperties;  // save these so can restore if empty rs stack
		   //System.out.println("OutputBuilder addRegMap: adding regmap,  path=" + getInstancePath() + ", id=" + regMapInst.getId()+ ", comp=" + regMapInst.getRegComp().getId());
           //regSetProperties.display();
			// regmap is special case, so only allow name/desc property assign, not full extract
			if (regMapInst.hasProperty("name")) regSetProperties.setTextName(regMapInst.getProperty("name")); 
			if (regMapInst.hasProperty("desc")) regSetProperties.setTextDescription(regMapInst.getProperty("desc")); 
		   // if instance has an id then use it for modulename
		   if (regMapInst.getId() != null) setAddressMapName(regMapInst.getId());  
		   // only base addrmap instance is first
		   setFirstAddressMap(false);
		   addRegMap();
		}
	}
	
	/** add root address map for a particular output */
	abstract public  void addRegMap();
	
	/** process root address map info after all sub components added */
	public  void finishRegMap(ModInstance regMapInst) {
		if (regMapInst != null) {
			// save the highest address 
			RegNumber highAddress = new RegNumber(nextAddress);
			highAddress.subtract(1);
			regSetProperties.setHighAddress(highAddress); 			
		}
		finishRegMap();
	}
	
	/** finish root address map for a particular output */
	abstract public  void finishRegMap();


	//---------------------------- end of add/finish methods  ----------------------------------------
	
	/** update reg properties now that fields are captured
	 * 
	 * @param rProperties - properties object that will be updated
	 */
	private void updateFinishRegProperties(RegProperties rProperties) {
        // update reg info now that field processing is complete  
		boolean regIsSwReadable = false, regIsSwWriteable = false, allFieldsSwReadable = true, allFieldsSwWriteable = true; 
		boolean regIsHwReadable = false, regIsHwWriteable = false;
		boolean regHasCounter = false, regHasInterrupt = false;
		for (FieldProperties field : fieldList) {
			   if (field.isSwReadable())  regIsSwReadable = true; 
			   else allFieldsSwReadable = false;
			   if (field.isSwWriteable()) regIsSwWriteable = true;
			   else allFieldsSwWriteable = false;
			   if (field.isHwReadable()) regIsHwReadable = true;  
			   if (field.isHwWriteable()) regIsHwWriteable = true;				
			   if (field.isCounter()) regHasCounter = true;  
			   if (field.isInterrupt()) regHasInterrupt = true;				
		}
		//System.out.println("OutputBuilder finishRegister: " + regProperties.getInstancePath() + ", sw r=" + regIsSwReadable+ ", sw w=" + regIsSwWriteable);
		// set reg sw access
		rProperties.setSwReadable(regIsSwReadable);  
		rProperties.setSwWriteable(regIsSwWriteable);
		rProperties.setFieldHash(fieldList.hashCode()); // set field hash for this reg 
		// set reg category if input is rdl
		if (ExtParameters.rdlResolveRegCategory() && Ordt.hasInputType(InputType.RDL) && !rProperties.hasCategory()) {
			boolean isStatus = allFieldsSwReadable && !regIsHwReadable && regIsHwWriteable && !regHasInterrupt;
			boolean isConfig = allFieldsSwWriteable && regIsHwReadable && !regIsHwWriteable && !regHasCounter && !regHasInterrupt;
			if (isStatus) rProperties.setCategory("STATE");
			else if (isConfig) rProperties.setCategory("DYNAMIC_CONFIG");
		}
	}

	/** set the base address of a register group (internal or external) */
	private void updateRegBaseAddress() {
		   RegNumber regAddress = regProperties.getExtractInstance().getAddress();
		   RegNumber addressModulus = regProperties.getExtractInstance().getAddressModulus(); 
		   RegNumber addressShift = regProperties.getExtractInstance().getAddressShift(); 
		   
		   // save register address info - if explicit address then use it 
		   if (regAddress != null) {
			   // if a relative address is used, compute by adding to parent base
			   RegNumber newBaseAddress = new RegNumber(regAddress);
			   newBaseAddress.add(getRegSetParentAddress());
			   /*if (regProperties.getInstancePath().startsWith("msg")) {  
				   System.out.println("OutputBuilder updateRegBaseAddress:   path=" + regProperties.getInstancePath() + 
					   	      ", regAddress=" + regAddress  + ", parent addr=" + getRegSetParentAddress() + ", nextAddress=" + nextAddress + ", newBaseAddress=" + newBaseAddress);
                   regProperties.getExtractInstance().display(true);
			   }*/
			   
			   if (regProperties.isFirstRep()) {
				   if (newBaseAddress.isLessThan(nextAddress)) {  // check for bad address here
					   Ordt.errorMessage("out of order register address specified in " + regProperties.getInstancePath());
					   System.out.println("OutputBuilder updateRegBaseAddress:   path=" + regProperties.getInstancePath() + 
					   	      ", regAddress=" + regAddress  + ", parent addr=" + getRegSetParentAddress() + ", nextAddress=" + nextAddress + ", newBaseAddress=" + newBaseAddress);
					   //System.exit(0);
				   }
				   setNextAddress(newBaseAddress);  // explicit start address for this group of regs, so save it   
			   }
		   }
		   // else adjust base if address shift or modulus is specified
		   else if (regProperties.isFirstRep()) {
			   if (addressShift != null) updateNextAddress(addressShift);
			   else updateNextAddressModulus(addressModulus);  // adjust the address if a modulus is defined
		   }
		   
		   // verify that address is aligned correctly   
		   int regBytes = regProperties.getRegByteWidth();
		   int alignBytes = !Utils.isPowerOf2(regProperties.getRegWidth()) ? (Utils.getNextHighestPowerOf2(regProperties.getRegWidth())/8) : regBytes; 
		   // if an external reg then need to align based on total size
		   // else if a replicated internal using jspec alignment need to align on total size
		   boolean isFirstReplicatedExternal = regProperties.isRootExternal() && regProperties.isReplicated() && regProperties.isFirstRep();
		   boolean isFirstReplicatedInternal = ExtParameters.useJsAddressAlignment() && regProperties.isReplicated() && regProperties.isFirstRep();
		   boolean doNotShift = regProperties.isExternal() && !regProperties.isRootExternal() && !ExtParameters.useJsAddressAlignment();
		   if (isFirstReplicatedExternal || isFirstReplicatedInternal) alignBytes = alignBytes * Utils.getNextHighestPowerOf2(regProperties.getRepCount());

		   //System.out.println("OutputBuilder updateRegBaseAddress: wide reg=" + this.getInstancePath() + " with width=" + regBytes + " at addr=" + nextAddress + ", ext=" + regProperties.isExternal()+ ", rep=" + regProperties.getRepCount());
		   // if this reg isn't in an external decoder and misaligned, then align it
		   if (!nextAddress.isModulus(alignBytes) && !regProperties.isExternalDecode()) {
			   if (!ExtParameters.suppressAlignmentWarnings()) {
				   if (isFirstReplicatedExternal) 
					   Ordt.warnMessage("base address for external " + regBytes + "B register group " + regProperties.getInstancePath() + " shifted to be " + alignBytes + "B aligned.");
				   else if (isFirstReplicatedInternal) 
					   Ordt.warnMessage("base address for " + regBytes + "B register array " + regProperties.getInstancePath() + " shifted to be " + alignBytes + "B aligned.");
				   else if (doNotShift)   
					   Ordt.warnMessage("base address for " + regBytes + "B external register " + regProperties.getInstancePath() + " is not " + alignBytes + "B aligned.");
				   else 
					   Ordt.warnMessage("base address for " + regBytes + "B register " + regProperties.getInstancePath() + " shifted to be " + alignBytes + "B aligned.");
			   }
			   // if an external and not root external, just display a warn message
			   if (!doNotShift) updateNextAddressModulus(new RegNumber(alignBytes));  // adjust the address to align register					   
		   }

		   // ----
		   regProperties.setBaseAddress(nextAddress);  // save the address of this reg
		   // compute the relative base address (vs parent regset base address)
		   RegNumber relAddress = new RegNumber(regProperties.getBaseAddress());  // start with current
		   relAddress.subtract(getRegSetParentAddress());  // subtract parent base from current
		   regProperties.setRelativeBaseAddress(relAddress);  // store in reg properties		   
	}

	/** get the base address of the top regset on the stack */
	private RegNumber getRegSetParentAddress() {
		RegNumber retVal = new RegNumber(0);  // default to 0
		if (!regSetPropertyStack.isEmpty()) {
			if (regSetPropertyStack.peek().getBaseAddress().isDefined())
				retVal = regSetPropertyStack.peek().getBaseAddress();
		}
		return retVal;
	}

	// ----------- public methods
	
	/** get name of root map in this builder
	 *  @return the addressMapName
	 */
	public  String getAddressMapName() {
		return addressMapName;
	}

	/** set name of root map in this builder
	 *  @param addressMapName the addressMapName to set
	 */
	public  void setAddressMapName(String moduleName) {
		this.addressMapName = moduleName;
	}

	/** get firstAddressMap - indication of first addrmap visited in this builder
	 *  @return the firstAddressMap
	 */
	public boolean isFirstAddressMap() {
		return firstAddressMap;
	}

	/** set firstAddressMap
	 *  @param firstAddressMap the firstAddressMap to set
	 */
	public void setFirstAddressMap(boolean firstAddressMap) {
		this.firstAddressMap = firstAddressMap;
	}

	// ----------------- instance stack methods
	
	/** push an instance onto instanceStack
	 */
	public  void pushInstance(InstanceProperties inst) {
		/*boolean l3_bregs = false;
		int targetBuilder = 2;
		if (getBuilderID() == targetBuilder) {  // 0=base 1=l3child 2=l2_r16_child
			if (inst.isExternal()) 
				System.out.println("OutputBuilder " + getBuilderID() + ": pushInstance, external inst " + inst.getId() + " found, stack depth=" + instancePropertyStack.size());  
			else 
				System.out.println("OutputBuilder " + getBuilderID() + ": pushInstance, internal inst " + inst.getId() + " found, stack depth=" + instancePropertyStack.size());
			l3_bregs = "base_regs".equals(inst.getId()) && (!inst.isExternal());
		}*/

		// need to set external early so rootExternal can be determined
		if (inst.hasDefaultProperty("external")) {
			inst.setExternal(inst.getDefaultProperty("external"));
			//System.out.println("OutputBuilder " + getBuilderID() + ": pushInstance, setting external type for inst=" + inst.getId() + " via default to " + inst.getExternalType());
		}
		
		// if added instance is external and no others on stack then mark as root
		if ((instancePropertyStack.isEmpty() || !instancePropertyStack.peek().isExternal()) && inst.isExternal()) {
			inst.setRootExternal(true);
			//System.out.println("OutputBuilder " + getBuilderID() + ": pushInstance, setting external root for inst=" + inst.getId() + " and pushing onto stack");
			//instancePropertyStack.peek().display();  
		}
		// if parent is external, this instance is external (regs are already set, but not regsets)
		if (!instancePropertyStack.isEmpty()) {
			if (instancePropertyStack.peek().isLocalMapExternal(allowLocalMapInternals())) {  // FIXME - never fires after addrmap since these are not ext in child builders!
				inst.setExternalType(instancePropertyStack.peek().getExternalType());
				//if (getBuilderID() == targetBuilder) System.out.println("OutputBuilder " + getBuilderID() + ": pushInstance, setting external type for inst=" + inst.getId() + " to " + inst.getExternalType() + " based on parent");
			}
			//if (instancePropertyStack.peek().isExternalDecode()) inst.setExternal(true);
		}
		// if stack isn't empty get parent instance default properties
		if (!instancePropertyStack.isEmpty()) inst.updateDefaultProperties(instancePropertyStack.peek().getInstDefaultProperties());
		// push this instance onto the stack
		instancePropertyStack.push(inst);
	}
	
	/** pop an instance from instanceStack
	 */
	public  InstanceProperties popInstance() {
		InstanceProperties inst = instancePropertyStack.pop();
		//System.out.println("popped inst=" + inst + " from stack");
		return inst;
	}

	/** peek top instance from instanceStack
	 */
	public  InstanceProperties peekInstance() {
		if ((instancePropertyStack == null) || instancePropertyStack.isEmpty()) return null;
		InstanceProperties inst = instancePropertyStack.peek();
		//System.out.println("peeked inst=" + inst + " from stack");
		return inst;
	}
	
	/** generate current instance path string
	 */
	public  String getInstancePath() {
		String retStr = "";
		for (InstanceProperties inst: instancePropertyStack) {
			if (inst != null) retStr += "." + inst.getId(); 
		}
		if (retStr.length()<2) return "";
		else return retStr.substring(1);
	}
		
	/** generate current instance path string with indexed rep suffixes
	 */
	protected String getIndexedInstancePath() {
		String retStr = "";
		for (InstanceProperties inst: instancePropertyStack) {
			if (inst != null) retStr += "." + inst.getIndexedId(); 
		}
		if (retStr.length()<2) return "";
		else return retStr.substring(1);
	}
	
	/** generate instance path string for parent of stack top
	 */
	protected String getParentInstancePath() {
		String retStr = "";
		Iterator<InstanceProperties> iter = instancePropertyStack.iterator();
		while (iter.hasNext()) {
			InstanceProperties inst = iter.next();
			if ((inst !=null) && (iter.hasNext())) retStr += "." + inst.getId();  // skip top elem
		}
		if (retStr.length()<2) return "";
		else return retStr.substring(1);
	}

	/** get baseName with indexed reps */
	protected String getIndexedBaseName() {
		return getIndexedInstancePath().replace('.', '_');
	}

	// ----------------- regset stack methods
	
	/** return number of addressmaps in current hierarchy
	 */
	protected int currentMapLevel() {
		int mlevel = 0;
		Iterator<RegSetProperties> iter = regSetPropertyStack.iterator();
		while (iter.hasNext()) {
			RegSetProperties inst = iter.next();
			if (inst.isAddressMap()) {
				mlevel++;
				//System.out.println("OutputBuilder currentMapLevel: level=" + mlevel + ", inst=" + inst.getInstancePath());
			}
		}
		return (builderID == 0)? mlevel + 1: mlevel;  // root isn't included in stack, so add 1
	}
	
	/** return true if all reg sets on stack are first rep
	 */
	protected boolean firstRegSetRep() {
		Iterator<RegSetProperties> iter = regSetPropertyStack.iterator();
		while (iter.hasNext()) {
			RegSetProperties inst = iter.next();
			if (!inst.isFirstRep()) return false;
		}
		return true;
	}
	
	/** return RegSetProperties instance of current active address map from the regset stack
	 */
	protected RegSetProperties getParentAddressMap() {
		Iterator<RegSetProperties> iter = regSetPropertyStack.iterator();
		while (iter.hasNext()) {
			RegSetProperties inst = iter.next();
			if (inst.isAddressMap()) return inst;
		}
		return null;
	}
	
	/** return name of current active address map from the regset stack
	 */
	protected String getParentAddressMapName() {
		RegSetProperties addrMapInst = getParentAddressMap();  // get first addrmap on the stack
		if (addrMapInst == null) return getAddressMapName();  // return the root name
		return getAddressMapName() + "_" + addrMapInst.getBaseName();  // return the catenated name
	}
	
	/** update max reg width in all regSet instances on the stack
	 */
	private void updateAllRegSetMaxRegWidths(int regWidth) {
		Iterator<RegSetProperties> iter = regSetPropertyStack.iterator();
		while (iter.hasNext()) {
			RegSetProperties inst = iter.next();
			if (!inst.updateMaxRegWidth(regWidth)) return;  // regset properties will be propagate fwd to saved props for output
		}
	}

	// ----------------- fieldset stack methods

	/** get the field offset for the current fieldset by adding offsets properties found on the stack 
	 * this is used to update the offset used in regProperties when addField is called */
	private Integer getCurrentFieldSetOffset() {
		Integer offset = 0;
		//System.out.println("-- OutputBuilder getCurrentFieldSetOffset: getting fs offset");
		Iterator<FieldSetProperties> iter = fieldSetPropertyStack.iterator();
		while (iter.hasNext()) {
			FieldSetProperties inst = iter.next();
			// if this fieldset has an offset then add it
			Integer fsetLowIdx = inst.getLowIndex();
			//System.out.println("   id=" + inst.getId() + ", offset=" + inst.getExtractInstance().getOffset() + ", lowIdx=" + fsetLowIdx);
			//if ( ((fsetOffset==null) && (fsetLowIdx!=null)) || ((fsetOffset!=null) && (fsetOffset!=fsetLowIdx))) System.out.println("   *** offset!=lowIdx");
			if (fsetLowIdx==null) return null;  //Ordt.errorExit("null fs offset found");
			offset += fsetLowIdx;  // changed to use lowIdx
		}
		return offset;
	}

	/** get the fieldset prefix to be appended to field names in some outputs (rdl eg) */
	private String getFieldSetPrefix() {
		String prefix = "";
		Iterator<FieldSetProperties> iter = fieldSetPropertyStack.iterator();
		while (iter.hasNext()) {
			InstanceProperties inst = iter.next();
			prefix += inst.getId() + "_";
		}
		return prefix;
	}
	
	// ------------
	
	/** get signalProperties
	 *  @return the signalProperties
	 */
	public  SignalProperties getSignalProperties() {
		return signalProperties;
	}

	/** get fieldProperties
	 *  @return the fieldProperties
	 */
	public  FieldProperties getFieldProperties() {
		return fieldProperties;
	}

	/** get regProperties
	 *  @return the regProperties
	 */
	public  RegProperties getRegProperties() {
		return regProperties;
	}

	/** get regSetProperties
	 *  @return the regSetProperties
	 */
	public  RegSetProperties getRegSetProperties() {
		return regSetProperties;
	}

	// -------------------------------- address mod methods ----------------------------------
	
	/** get visitEachReg
	 *  @return the visitEachReg
	 */
	public boolean visitEachReg() {
		return visitEachReg;
	}

	/** set visitEachReg
	 *  @param visitEachReg the visitEachReg to set
	 */
	public void setVisitEachReg(boolean visitEachReg) {
		this.visitEachReg = visitEachReg;
	}

	/** get visitEachRegSet
	 *  @return the visitEachRegSet
	 */
	public boolean visitEachRegSet() {
		return visitEachRegSet;
	}

	/** set visitEachRegSet
	 *  @param visitEachRegSet the visitEachRegSet to set
	 */
	public void setVisitEachRegSet(boolean visitEachRegSet) {
		this.visitEachRegSet = visitEachRegSet;
	}

	/** get visitExternalRegisters
	 *  @return the visitExternalRegisters
	 */
	public boolean visitExternalRegisters() {
		return visitExternalRegisters;
	}

	/** set visitExternalRegisters
	 *  @param visitExternalRegisters the visitExternalRegisters to set
	 */
	public void setVisitExternalRegisters(boolean visitExternalRegisters) {
		this.visitExternalRegisters = visitExternalRegisters;
	}

	/** get visitEachExternalRegister
	 *  @return the visitEachExternalRegister
	 */
	public boolean visitEachExternalRegister() {
		return visitEachExternalRegister;
	}

	/** set visitEachExternalRegister
	 *  @param visitEachExternalRegister the visitEachExternalRegister to set
	 */
	public void setVisitEachExternalRegister(boolean visitEachExternalRegister) {
		this.visitEachExternalRegister = visitEachExternalRegister;
	}

	/** get allowLocalMapExternals
	 */
	public boolean allowLocalMapInternals() {
		return allowLocalMapInternals;
	}

	/** set allowLocalMapExternals
	 */
	public void setAllowLocalMapInternals(boolean allowLocalMapInternals) {
		this.allowLocalMapInternals = allowLocalMapInternals;
	}

	public boolean supportsOverlays() {
		return supportsOverlays;
	}

	public void setSupportsOverlays(boolean supportsOverlays) {
		this.supportsOverlays = supportsOverlays;
	}

	/** get next address  */
	protected  RegNumber getNextAddress() {
		return nextAddress;
	}
	
	/** set next address value */
	protected  void setNextAddress(RegNumber regAddress) {
		nextAddress.setValue(regAddress.getValue());  // update value but keep format
	}

	/** get base address of root in this builder  */
	protected  RegNumber getBaseAddress() {
		return baseAddress;
	}
	
	/** set base address of root in this builder */
	protected  void setBaseAddress(RegNumber regAddress) {
		baseAddress.setValue(regAddress.getValue());  // update value but keep format
	}
	
	/** get externalBaseAddress
	 *  @return the externalBaseAddress
	 */
	public RegNumber getExternalBaseAddress() {
		return externalBaseAddress;
	}

	/** set externalBaseAddress
	 *  @param externalBaseAddress the externalBaseAddress to set
	 */
	public void setExternalBaseAddress(RegNumber externalBaseAddress) {
		//System.out.println("Setting ext base address to " + externalBaseAddress);
		this.externalBaseAddress = new RegNumber(externalBaseAddress);
	}
	
	/** compute size of external register group */
	public RegNumber getExternalRegBytes() {
		RegNumber extSize = new RegNumber(getNextAddress());  
		extSize.subtract(getExternalBaseAddress());
	    return extSize;
	}
		
	/** get RegWidth in bytes
	 *  @return the getRegByteWidth
	 */
	public  int getMinRegByteWidth() {
		return ExtParameters.getMinDataSize()/8;
	}
	
	/** get maxRegWidth
	 *  @return the maxRegWidth
	 */
	public int getMaxRegWidth() {
		return maxRegWidth;
	}
	
	/** get maxRegWidth in bytes
	 */
	public int getMaxRegByteWidth() {
		return getMaxRegWidth() / 8;
	}
	
	/** get maxRegWidth in words
	 */
	public int getMaxRegWordWidth() {
		return getMaxRegWidth() / ExtParameters.getMinDataSize();
	}

	/** set maxRegWidth
	 *  @param maxRegWidth the maxRegWidth to set
	 */
	private void setMaxRegWidth(int maxRegWidth) {
		this.maxRegWidth = maxRegWidth;
	}
	
	/** update maxRegWidth if new value is higher
	 *  @param maxRegWidth the maxRegWidth to set
	 */
	private void updateMaxRegWidth(int maxRegWidth) {
		if (maxRegWidth > getMaxRegWidth()) setMaxRegWidth(maxRegWidth);  // update max value for this addrmap
		updateAllRegSetMaxRegWidths(maxRegWidth);  // update max value in all parent regsets
	}

	/** get high index of pio address based on max address in map 
	 *  @return the getAddressHighBit
	 */
	public  int getAddressHighBit(RegNumber regNum) {   
		return regNum.getMinusOneHighestBit();
	}
	
	/** get low index of pio address based on register size 
	 *  @return the getAddressLowBit
	 */
	public  int getAddressLowBit() {   
		return new RegNumber(getMinRegByteWidth()).getHighestBit();
	}
	
	/** get bit width of pio address  
	 *  @return the getAddressHighBit
	 */
	public  int getAddressWidth(RegNumber regNum) {   
		return getAddressHighBit(regNum) - getAddressLowBit() + 1;
	}
	
	/** generate a (little endian) array reference string given a starting bit and size */
	public static String genRefArrayString(int lowIndex, int size) {
		if (size < 1) return " ";
		if (size == 1) return " [" + lowIndex + "] ";
	   	return " [" + (size + lowIndex - 1) + ":" + lowIndex + "] ";
	}

	/** return the current accumulated size of the register map in bytes  **/
	public RegNumber getCurrentMapSize() {
		RegNumber mapSize = new RegNumber(getNextAddress());
		mapSize.subtract(getBaseAddress());
		//System.out.println("OutputBuilder getCurrentMapSize: " + builderID + ", size=" + mapSize + " next=" + getNextAddress()+ " base=" + getBaseAddress());
		return mapSize;
	}

	// ------------- protected regset size accumulation methods

	/** get the increment stride for this regset - must be run in finishRegSet after sub components for non-aligned case 
	 * 
	 * @param allowPrecomputedSize - if true and useJsAddressAlignment parameter is true, then precomputed size is returned
	 *         when no increment value is specified.  if false, the accumulated offset from regset baseAddress is returned.
	 */
	protected RegNumber getRegSetAddressStride(boolean allowPrecomputedSize) {
		// if address increment is specified, use it
		RegNumber incr = regSetProperties.getExtractInstance().getAddressIncrement();
		// otherwise use computed regset size
		if (incr == null) incr = getRegSetSize(allowPrecomputedSize);
		else {
			incr.setNumFormat(NumFormat.Address);
			incr.setNumBase(NumBase.Hex);
		}
		return incr;
	}

	/** get the size of this regset - must be run in finishRegSet after sub components for non-aligned case
	 * 
	 * @param allowPrecomputedSize - if true and useJsAddressAlignment parameter is true, then precomputed size is returned.
	 *         if false, the accumulated offset from regset baseAddress is returned.
	 */
	protected RegNumber getRegSetSize(boolean allowPrecomputedSize) {
		// if aligned then output the precomputed size
		if (allowPrecomputedSize && ExtParameters.useJsAddressAlignment())
			return regSetProperties.getAlignedSize();
		// otherwise use computed regset size
		RegNumber incr = new RegNumber(getNextAddress());
		incr.subtract(regSetProperties.getBaseAddress());
		//System.out.println("OutputBuilder getRegSetSize: computed size = " + incr);
		incr.setNumFormat(NumFormat.Address);
		incr.setNumBase(NumBase.Hex);
		return incr;
	}

	// ------------------------ next address calc methods -----------------
	
	/** set next address based on size of current register */
	private  void updateNextAddress() {
		int regBytes = regProperties.getRegByteWidth();
		int incBytes = !Utils.isPowerOf2(regProperties.getRegWidth()) ? (Utils.getNextHighestPowerOf2(regProperties.getRegWidth())/8) : regBytes; 
		//RegNumber inc = new RegNumber(regProperties.getRegByteWidth()); //register bytes
		//System.out.println("updating address, inc=" + incBytes);
		nextAddress.add(incBytes);
	}

	/** bump the next address by specified number of register width words */
	private  void updateNextAddress(int reps) {
		int regBytes = regProperties.getRegByteWidth();
		int incBytes = !Utils.isPowerOf2(regProperties.getRegWidth()) ? (Utils.getNextHighestPowerOf2(regProperties.getRegWidth())/8) : regBytes; 
		RegNumber inc = new RegNumber(reps * incBytes); //register bytes
		//System.out.println("updating address, inc=" + inc);
		nextAddress.add(inc);		
	}

	/**  bump the running address count by an increment value **/
	private void updateNextAddress(RegNumber addressIncrement) {
		// if no increment specified increase by reg width
		if (addressIncrement == null) updateNextAddress();  
		else {
			nextAddress.add(addressIncrement);
		}
	}

	/**  bump the running address count by an increment value for multiple registers **/
	private void updateNextAddress(RegNumber addressIncrement, int repCount) {
		// if no increment specified increase by reg width
		if (addressIncrement == null) updateNextAddress(repCount);  
		else {
			RegNumber totalIncrement = new RegNumber(addressIncrement);
			totalIncrement.multiply(repCount);
			nextAddress.add(totalIncrement);
		}
	}
	
	/** adjust the next address if a modulus is defined **/
	private void updateNextAddressModulus(RegNumber addressModulus) {
		if (addressModulus == null) return;
		nextAddress.roundUpToModulus(addressModulus);
	}

	//---------------------------- message/output stmt generation  ----------------------------------------

	public String getWriterName() {
		return getAddressMapName();  // use address map as name of this writer
	}

	/** write a stmt to the specified BufferedWriter */
	public  void writeStmt(BufferedWriter bw, int indentLevel, String stmt) {
		   //System.out.println("OutputBuilder: bufnull=" + (bufferedWriter == null) + ", indent=" + ",Stmt=" + stmt);
		   try {
			bw.write(Utils.repeat(' ', indentLevel*2) + stmt +"\n");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/** write a stmt to the default BufferedWriter */
	public  void writeStmt(int indentLevel, String stmt) {
		writeStmt(bufferedWriter, indentLevel, stmt);
	}
	
	/** write multiple stmts to the specified BufferedWriter */
	public void writeStmts(BufferedWriter bw, int indentLevel, List<String> outputLines) {
		Iterator<String> iter = outputLines.iterator();
		while (iter.hasNext()) writeStmt(bw, indentLevel, iter.next());	
	}
	
	/** write a multiple stmts to the default BufferedWriter */
	public  void writeStmts(int indentLevel, List<String> outputLines) {
		writeStmts(bufferedWriter, indentLevel, outputLines);
	}

	/** write an OutputLine to the specified BufferedWriter */
	public  void writeStmt(BufferedWriter bw, OutputLine outputLine) {
		writeStmt(bw, outputLine.getIndent(), outputLine.getLine());  
	}
	
	/** write an OutputLine to the default BufferedWriter */
	public  void writeStmt(OutputLine outputLine) {
		writeStmt(bufferedWriter, outputLine);
	}

	/** write a list of OutputLines to the specified BufferedWriter */
	protected void writeStmts(BufferedWriter bw, List<OutputLine> outputList) {
		for (OutputLine rLine: outputList) {
			writeStmt(bw, rLine.getIndent(), rLine.getLine());  
		}
	}

	/** write a list of OutputLines to the default BufferedWriter */
	protected void writeStmts(List<OutputLine> outputList) {
		for (OutputLine rLine: outputList) {
			writeStmt(bufferedWriter, rLine.getIndent(), rLine.getLine());  
		}
	}

	//---------------------------- methods to write output ----------------------------------------

	/** write output to an already open bufferedWriter 
	 * @param bw */
	abstract protected void write(BufferedWriter bw);
	
	/** write output to specified output file - this is called by ordt main and can be
	 *  overridden by child builders if multiple file outputs are needed.
	 *  sets the default bufferdWriter for this OutputBuilder if file is opened successfully. 
	 * @param outName - output file or directory
	 * @param description - text description of file generated
	 * @param commentPrefix - comment chars for this file type */
	public void write(String outName, String description, String commentPrefix) {
    	BufferedWriter bw = openBufferedWriter(outName, description);
    	if (bw != null) {
    		// set bw as default
    		bufferedWriter = bw;

    		// write the file header
    		writeHeader(commentPrefix);
    		
    		// now write the output
	    	write(bw);
    		closeBufferedWriter(bw);
    	}
	}
	
	/** write specified output list to specified output file.
	 *  The default bufferedWriter for this OutputBuilder is not affected. 
	 * @param outName - output file or directory
	 * @param description - text description of file generated
	 * @param commentPrefix - comment chars for this file type 
	 * @param simple list of lines to be written */
	public void simple_write(String outName, String description, String commentPrefix, List<String> stmts) {
    	BufferedWriter bw = openBufferedWriter(outName, description);
    	if (bw != null) {
    		// write the file header
    		writeHeader(bw, commentPrefix); 		
    		// now write the output
    		for (String stmt:stmts) writeStmt(bw, 0, stmt);	
    		// close buffer
    		closeBufferedWriter(bw);
    	}
	}
	
	/** write a file header to specified BufferedWriter
	 * @param commentPrefix
	 */
	protected void writeHeader(BufferedWriter bw, String commentPrefix) {
		if (commentPrefix == null) return;  // no header if commentPrefix is null (eg json)
		boolean isXml = commentPrefix.equals("<!--");
		String midCommentChar = (commentPrefix.equals("/*")) ? " *" : 
			                    isXml ? "    " : commentPrefix;
		String lastCommentChar = (commentPrefix.equals("/*")) ? " */" : 
			                     isXml ? " -->" : commentPrefix;
		if (isXml) writeStmt(bw, 0, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"); // prefix line for xml
		writeStmt(bw, 0, commentPrefix + "   Ordt " + Ordt.getVersion() + " autogenerated file ");
		writeStmt(bw, 0, midCommentChar + "   Input: " + model.getOrdtInputFile());
		for (String parmFile: ExtParameters.getParmFiles()) {
			writeStmt(bw, 0, midCommentChar + "   Parms: " + parmFile);
		}
		writeStmt(bw, 0, midCommentChar + "   Date: " + new Date());
		writeStmt(bw, 0, lastCommentChar);
		writeStmt(bw, 0, "");
	}
	
	/** write a file header to teh default BufferedWriter
	 * @param commentPrefix
	 */
	protected void writeHeader(String commentPrefix) {
		writeHeader(bufferedWriter, commentPrefix);
	}
	
	/** set buffered writer directly
     */
    public void setBufferedWriter(BufferedWriter bw) {
    	bufferedWriter = bw;
    }

	/** validate output file and create buffered writer
     */
    protected static BufferedWriter openBufferedWriter(String outName, String description) {
    	File outFile = null;
    	try {	  			
    		outFile = new File(outName); 

    		System.out.println("Ordt: writing " + description + " file " + outFile + "...");

    		// if file doesnt exists, then create it
    		if (!outFile.exists()) {
    			outFile.createNewFile();
    		}

    		FileWriter fw = new FileWriter(outFile.getAbsoluteFile());
    		BufferedWriter bw = new BufferedWriter(fw);
    		return bw;

    	} catch (IOException e) {
    		Ordt.errorMessage("Create of " + description + " file " + outFile.getAbsoluteFile() + " failed.");
    		return null;
    	}
    }
    
    /** validate output file and create buffered writer
     */
    protected static void closeBufferedWriter(BufferedWriter bw) {
    	try {	  
    		bw.close();

    	} catch (IOException e) {
    		Ordt.errorMessage("File close failed.");
    	}
    }

		
}