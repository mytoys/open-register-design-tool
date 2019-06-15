JAR = ./build/libs/Ordt-190606.01.jar

all: build abc

build: $(JAR)

abc:
	make -C ./test/basic_tests/rdl_test/

${JAR}:clean
	@./gradlew shadowJar

clean:
	@rm -rf ${JAR}

veryclean:clean
	make -C ./test/basic_tests/rdl_test/ clean
