.PHONY: build test

build:
	./gradlew clean build shadowJar

test:
	./gradlew clean test shadowJar
