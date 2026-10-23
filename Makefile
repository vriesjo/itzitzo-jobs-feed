.PHONY: build test tf-apply workflow-deploy

build:
	./gradlew clean build shadowJar

test:
	./gradlew clean test shadowJar

workflow-deploy:
	bash scripts/terraform-apply.sh

tf-check:
	terraform fmt -check -recursive infra && terraform -chdir=infra test -no-color
