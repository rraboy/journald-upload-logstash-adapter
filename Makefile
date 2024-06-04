.PHONY: container

container:
	pack build journald-upload-logstash-adapter --env BP_JVM_VERSION=11 --builder paketobuildpacks/builder-jammy-tiny
