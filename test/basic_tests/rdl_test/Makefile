BLOCK    = abc
ORDT_JAR = ../../../build/libs/Ordt-190606.01.jar

OUTFILES = $(BLOCK)_reg.v

all: clean
	@java -jar $(ORDT_JAR) -parms $(BLOCK).parms -verilog $(BLOCK)_reg.v $(BLOCK).rdl

clean:
	@rm -rf $(OUTFILES)
