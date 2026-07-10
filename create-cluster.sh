#!/bin/bash
./eksdx-cli.sh create-cluster $1 --arch=arm64 --pricing=spot --ssh-cidr "$(curl -s https://checkip.amazonaws.com/ | tr -d '\n')/32" --wait
