# journald-upload-logstash-adapter
A standalone java service that acts as a logstash-http-adapter for systemd-journal-upload

## Usage Diagram

any system using journald --> systemd-journald-upload --> this-service --> logstash-http-input

## How to run
java -cp journald-upload-logstash-adapter-all.jar org.journald.remote.adapter.Main


