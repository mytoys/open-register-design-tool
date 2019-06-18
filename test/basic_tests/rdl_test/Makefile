BLOCK    = abc
ORDT_JAR = ../../../build/libs/Ordt-20190618.01.jar


all:
	@java -jar $(ORDT_JAR) \
    -parms $(BLOCK).parms \
    -verilog $(BLOCK)_regfile.v \
    -xml $(BLOCK)_regfile.xml \
    -reglist $(BLOCK).reglist \
    -vbench $(BLOCK)_test.v \
    $(BLOCK).rdl

clean:
	@rm -rf *.v *.xml *.reglist

