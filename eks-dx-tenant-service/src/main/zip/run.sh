#!/bin/bash
# Starts Quarkus as HTTP server — Web Adapter (layer) handles Lambda Runtime API
exec java -jar /var/task/eks-dx-tenant-service-runner.jar
