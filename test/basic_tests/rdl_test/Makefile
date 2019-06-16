BLOCK    = abc
ORDT_JAR = ../../../build/libs/Ordt-190606.01.jar


all: clean
	@java -jar $(ORDT_JAR) \
    -parms $(BLOCK).parms \
    -verilog $(BLOCK)_pio.v \
    -vbench $(BLOCK)_test.v \
    -xml $(BLOCK)_pio.xml \
    -reglist $(BLOCK).reglist \
    $(BLOCK).rdl

clean:
	@rm -rf *.v *.xml *.reglist
