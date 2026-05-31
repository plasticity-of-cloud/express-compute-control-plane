#!/bin/bash
java -jar ./eks-dx-cli/target/eks-dx-cli-1.0.0-SNAPSHOT-runner.jar  create tenant $1 --arch=arm64 --pricing=ondemand  --ssh-cidr "$(curl -s https://checkip.amazonaws.com/ | tr -d '\n')/32"  --wait
