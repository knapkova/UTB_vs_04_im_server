#!/bin/bash
./gradlew build
java -jar NATT.jar -nc https://gitlab.com/ap5vs/test-config/im-server-config/-/raw/main/test-config-4.yaml?ref_type=heads