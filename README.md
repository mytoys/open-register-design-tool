# open-register-design-tool

Ordt is a tool for automation of IC register definition and documentation.  It currently supports 2 input formats:
  1. SystemRDL - a stardard register description format released by [Accellera.org](http://accellera.org/activities/working-groups/systemrdl)
  2. JSpec - a register description format used within Juniper Networks

The tool can generate several outputs from SystemRDL or JSpec, including:
  - SystemVerilog/Verilog RTL code description of registers
  - UVM model of the registers
  - C++ and python models of the registers
  - XML and text file register descriptions
  - SystemRDL and JSpec (conversion)

Easiest way to get started with ordt is to download a runnable jar from the [release area](https://github.com/Juniper/open-register-design-tool/releases).  
Ordt documentation can be found [here](https://github.com/Juniper/open-register-design-tool/wiki).


# 2019-06-15 Notes

## rebuild Ordt.jar 

```shell
./gradlew shadowJar

```

output is `./build/libs/Ordt-190606.01.jar`


##  output/systemverilog

1. `addressmap` is the boundry of RTL module;
2. `regfile` is the boundry of group of registers to add register address gap;
3. `reg` is the  exact register
4. `field` is the minimum register-unit
5. output verilog module includes 3 modules:
    - top(xxxx_pio), 
    - read/write logic(xxxx_logic) 
    - addrss decode(xxxx_decode).
6. `sw` control interface can be `LEAF`, `PARALLEL` ... 
7. `sw=rw` means software can read and write the register field
8. `hw=r` means hardware can read and write the register field


